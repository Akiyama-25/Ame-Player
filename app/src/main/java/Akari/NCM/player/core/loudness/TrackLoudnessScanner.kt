package Akari.NCM.player.core.loudness

import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.data.NcmMediaDataSource
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * 离线与在线音频综合响度测算扫描器 (ITU-R BS.1770-4 / EBU R128)
 *
 * 支持双模式扫描：
 * 1. [scanTrackFast]：多点跳帧极速抽样（20% / 45% / 70% 三锚点各解码 ~1.2s，耗时 < 400ms），用于 1s 曲间等候窗口与队列预热初判；
 * 2. [scanTrack]：全曲完整门控积分扫描，可在首 ~1.2s 触发预览回调并在全曲完成时锁定精确综合响度。
 */
object TrackLoudnessScanner {
    private const val TAG = "TrackLoudnessScanner"
    private const val TIMEOUT_US = 2000L
    private const val ABS_GATE_THRESHOLD_ENERGY = 1.172195e-7 // 10^((-70.0 + 0.691) / 10.0)

    /**
     * 极速多点跳帧抽样扫描（Stage 1 初判 / 预热缓存用）
     * 直接定位曲目 20%、45%、70% 三个代表片段各解码 ~1.2 秒，总解码量约 3.6 秒音频。
     */
    suspend fun scanTrackFast(
        context: Context,
        dataSource: String,
        headers: Map<String, String>? = null
    ): LoudnessData? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var mediaDataSource: MediaDataSource? = null
        try {
            mediaDataSource = configureExtractor(context, extractor, dataSource, headers)
            val selection = selectAudioTrack(extractor) ?: return@withContext null
            val audioFormat = selection.second
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext null

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val sampleRate = getSampleRate(audioFormat)
            val channelCount = getChannelCount(audioFormat)
            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            val s1Coeffs = EbuR128Calculator.calculateStage1Coeffs(sampleRate.toDouble())
            val s2Coeffs = EbuR128Calculator.calculateStage2Coeffs(sampleRate.toDouble())
            val blockSizeFrames = (0.4 * sampleRate).toInt().coerceAtLeast(1)

            val anchors = if (durationUs >= 10_000_000L) {
                listOf(
                    (durationUs * 0.20).toLong(),
                    (durationUs * 0.45).toLong(),
                    (durationUs * 0.70).toLong()
                )
            } else {
                listOf(0L)
            }

            val allBlockEnergies = mutableListOf<Double>()
            var maxObservedPeak = 0.0f
            val framesPerAnchorTarget = if (anchors.size > 1) {
                (1.25 * sampleRate).toInt()
            } else {
                (2.4 * sampleRate).toInt()
            }

            var currentPcmEncoding = getPcmEncoding(audioFormat)
            val bufferInfo = MediaCodec.BufferInfo()

            for ((anchorIdx, targetUs) in anchors.withIndex()) {
                if (!coroutineContext.isActive) break

                if (anchorIdx > 0 || targetUs > 0L) {
                    extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                }

                // 每个锚点重置 K-Weighting 状态，防止跳帧不连续引入瞬态冲击
                var kL1_x1 = 0.0; var kL1_x2 = 0.0; var kL1_y1 = 0.0; var kL1_y2 = 0.0
                var kL2_x1 = 0.0; var kL2_x2 = 0.0; var kL2_y1 = 0.0; var kL2_y2 = 0.0
                var kR1_x1 = 0.0; var kR1_x2 = 0.0; var kR1_y1 = 0.0; var kR1_y2 = 0.0
                var kR2_x1 = 0.0; var kR2_x2 = 0.0; var kR2_y1 = 0.0; var kR2_y2 = 0.0

                var currentBlockSum = 0.0
                var frameCountInBlock = 0
                var decodedFramesThisAnchor = 0
                var isInputEos = false
                var isOutputEos = false
                var stallLoops = 0

                while (!isOutputEos && decodedFramesThisAnchor < framesPerAnchorTarget && coroutineContext.isActive && stallLoops < 60) {
                    if (!isInputEos) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                            if (inputBuffer != null) {
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    isInputEos = true
                                } else {
                                    val timeUs = extractor.sampleTime
                                    codec.queueInputBuffer(inputIndex, 0, sampleSize, timeUs, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        currentPcmEncoding = getPcmEncoding(codec.outputFormat)
                        stallLoops = 0
                    } else if (outputIndex >= 0) {
                        stallLoops = 0
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputEos = true
                        }
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

                            val bytesPerSample = bytesPerPcmSample(currentPcmEncoding)
                            val bytesPerFrame = bytesPerSample * channelCount
                            val frames = if (bytesPerFrame > 0) bufferInfo.size / bytesPerFrame else 0

                            for (f in 0 until frames) {
                                val sL = readNormalizedSample(outputBuffer, currentPcmEncoding)
                                val sR = if (channelCount > 1) readNormalizedSample(outputBuffer, currentPcmEncoding) else sL
                                for (c in 2 until channelCount) skipSample(outputBuffer, currentPcmEncoding)

                                val peak = maxOf(abs(sL), abs(sR))
                                if (peak > maxObservedPeak) maxObservedPeak = peak

                                val y1L = s1Coeffs.b0 * sL + s1Coeffs.b1 * kL1_x1 + s1Coeffs.b2 * kL1_x2 - s1Coeffs.a1 * kL1_y1 - s1Coeffs.a2 * kL1_y2
                                kL1_x2 = kL1_x1; kL1_x1 = sL.toDouble(); kL1_y2 = kL1_y1; kL1_y1 = y1L
                                val y2L = s2Coeffs.b0 * y1L + s2Coeffs.b1 * kL2_x1 + s2Coeffs.b2 * kL2_x2 - s2Coeffs.a1 * kL2_y1 - s2Coeffs.a2 * kL2_y2
                                kL2_x2 = kL2_x1; kL2_x1 = y1L; kL2_y2 = kL2_y1; kL2_y1 = y2L

                                val y1R = s1Coeffs.b0 * sR + s1Coeffs.b1 * kR1_x1 + s1Coeffs.b2 * kR1_x2 - s1Coeffs.a1 * kR1_y1 - s1Coeffs.a2 * kR1_y2
                                kR1_x2 = kR1_x1; kR1_x1 = sR.toDouble(); kR1_y2 = kR1_y1; kR1_y1 = y1R
                                val y2R = s2Coeffs.b0 * y1R + s2Coeffs.b1 * kR2_x1 + s2Coeffs.b2 * kR2_x2 - s2Coeffs.a1 * kR2_y1 - s2Coeffs.a2 * kR2_y2
                                kR2_x2 = kR2_x1; kR2_x1 = y1R; kR2_y2 = kR2_y1; kR2_y1 = y2R

                                currentBlockSum += (y2L * y2L + y2R * y2R)
                                frameCountInBlock++
                                decodedFramesThisAnchor++

                                if (frameCountInBlock >= blockSizeFrames) {
                                    allBlockEnergies.add(currentBlockSum / blockSizeFrames)
                                    currentBlockSum = 0.0
                                    frameCountInBlock = 0
                                }
                                if (decodedFramesThisAnchor >= framesPerAnchorTarget) break
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    } else {
                        stallLoops++
                    }
                }
            }

            if (coroutineContext.isActive && allBlockEnergies.isNotEmpty()) {
                val previewSource = if (anchors.size > 1) {
                    LoudnessSource.FAST_PREVIEW_SCAN
                } else {
                    LoudnessSource.INTRO_PREVIEW_SCAN
                }
                val previewResult = EbuR128Calculator.computeIntegratedLoudnessFromBlockEnergies(
                    allBlockEnergies,
                    maxObservedPeak
                ).copy(source = previewSource)
                AppLogger.i(TAG, "Fast multi-point scan complete (${allBlockEnergies.size} blocks, anchors=${anchors.size}, peak=$maxObservedPeak): ${previewResult.integratedLufs} LUFS (${previewSource.label})")
                previewResult
            } else {
                null
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.w(TAG, "Fast loudness scan failed for $dataSource: ${e.message}")
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { mediaDataSource?.close() }
        }
    }

    /**
     * 全曲完整 BS.1770 扫描（Stage 2 精确锁定）
     * 支持可选 [onPreviewReady] 在采集到首批有效响度分块 (~1.2s) 时立刻吐出 Stage 1 初判结果。
     */
    suspend fun scanTrack(
        context: Context,
        dataSource: String,
        headers: Map<String, String>? = null,
        onPreviewReady: ((LoudnessData) -> Unit)? = null
    ): LoudnessData? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var mediaDataSource: MediaDataSource? = null
        try {
            mediaDataSource = configureExtractor(context, extractor, dataSource, headers)
            val selection = selectAudioTrack(extractor) ?: return@withContext null
            val audioFormat = selection.second
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext null

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val sampleRate = getSampleRate(audioFormat)
            val channelCount = getChannelCount(audioFormat)

            val s1Coeffs = EbuR128Calculator.calculateStage1Coeffs(sampleRate.toDouble())
            val s2Coeffs = EbuR128Calculator.calculateStage2Coeffs(sampleRate.toDouble())
            val blockSizeFrames = (0.4 * sampleRate).toInt().coerceAtLeast(1)

            var kL1_x1 = 0.0; var kL1_x2 = 0.0; var kL1_y1 = 0.0; var kL1_y2 = 0.0
            var kL2_x1 = 0.0; var kL2_x2 = 0.0; var kL2_y1 = 0.0; var kL2_y2 = 0.0
            var kR1_x1 = 0.0; var kR1_x2 = 0.0; var kR1_y1 = 0.0; var kR1_y2 = 0.0
            var kR2_x1 = 0.0; var kR2_x2 = 0.0; var kR2_y1 = 0.0; var kR2_y2 = 0.0

            val blockEnergies = mutableListOf<Double>()
            var activeNonSilentBlocks = 0
            var previewEmitted = false
            var currentBlockSum = 0.0
            var frameCountInBlock = 0
            var maxObservedPeak = 0.0f

            var currentPcmEncoding = getPcmEncoding(audioFormat)
            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEos = false
            var isOutputEos = false

            while (!isOutputEos && coroutineContext.isActive) {
                if (!isInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEos = true
                            } else {
                                val timeUs = extractor.sampleTime
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, timeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    currentPcmEncoding = getPcmEncoding(codec.outputFormat)
                } else if (outputIndex >= 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEos = true
                    }
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

                        val bytesPerSample = bytesPerPcmSample(currentPcmEncoding)
                        val bytesPerFrame = bytesPerSample * channelCount
                        val frames = if (bytesPerFrame > 0) bufferInfo.size / bytesPerFrame else 0

                        for (f in 0 until frames) {
                            val sL = readNormalizedSample(outputBuffer, currentPcmEncoding)
                            val sR = if (channelCount > 1) readNormalizedSample(outputBuffer, currentPcmEncoding) else sL
                            for (c in 2 until channelCount) skipSample(outputBuffer, currentPcmEncoding)

                            val peak = maxOf(abs(sL), abs(sR))
                            if (peak > maxObservedPeak) maxObservedPeak = peak

                            val y1L = s1Coeffs.b0 * sL + s1Coeffs.b1 * kL1_x1 + s1Coeffs.b2 * kL1_x2 - s1Coeffs.a1 * kL1_y1 - s1Coeffs.a2 * kL1_y2
                            kL1_x2 = kL1_x1; kL1_x1 = sL.toDouble(); kL1_y2 = kL1_y1; kL1_y1 = y1L
                            val y2L = s2Coeffs.b0 * y1L + s2Coeffs.b1 * kL2_x1 + s2Coeffs.b2 * kL2_x2 - s2Coeffs.a1 * kL2_y1 - s2Coeffs.a2 * kL2_y2
                            kL2_x2 = kL2_x1; kL2_x1 = y1L; kL2_y2 = kL2_y1; kL2_y1 = y2L

                            val y1R = s1Coeffs.b0 * sR + s1Coeffs.b1 * kR1_x1 + s1Coeffs.b2 * kR1_x2 - s1Coeffs.a1 * kR1_y1 - s1Coeffs.a2 * kR1_y2
                            kR1_x2 = kR1_x1; kR1_x1 = sR.toDouble(); kR1_y2 = kR1_y1; kR1_y1 = y1R
                            val y2R = s2Coeffs.b0 * y1R + s2Coeffs.b1 * kR2_x1 + s2Coeffs.b2 * kR2_x2 - s2Coeffs.a1 * kR2_y1 - s2Coeffs.a2 * kR2_y2
                            kR2_x2 = kR2_x1; kR2_x1 = y1R; kR2_y2 = kR2_y1; kR2_y1 = y2R

                            currentBlockSum += (y2L * y2L + y2R * y2R)
                            frameCountInBlock++

                            if (frameCountInBlock >= blockSizeFrames) {
                                val energy = currentBlockSum / blockSizeFrames
                                blockEnergies.add(energy)
                                if (energy >= ABS_GATE_THRESHOLD_ENERGY) {
                                    activeNonSilentBlocks++
                                }
                                currentBlockSum = 0.0
                                frameCountInBlock = 0

                                if (!previewEmitted && onPreviewReady != null && activeNonSilentBlocks >= 3) {
                                    previewEmitted = true
                                    val preview = EbuR128Calculator.computeIntegratedLoudnessFromBlockEnergies(
                                        blockEnergies.toList(),
                                        maxObservedPeak
                                    ).copy(source = LoudnessSource.INTRO_PREVIEW_SCAN)
                                    onPreviewReady(preview)
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }

            if (coroutineContext.isActive && blockEnergies.isNotEmpty()) {
                val fullResult = EbuR128Calculator.computeIntegratedLoudnessFromBlockEnergies(
                    blockEnergies,
                    maxObservedPeak
                )
                AppLogger.i(TAG, "Completed full-track BS.1770 scan (${blockEnergies.size} blocks, peak=$maxObservedPeak): ${fullResult.integratedLufs} LUFS")
                fullResult
            } else {
                null
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.w(TAG, "Full-track loudness scan failed for $dataSource: ${e.message}")
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { mediaDataSource?.close() }
        }
    }

    private fun configureExtractor(
        context: Context,
        extractor: MediaExtractor,
        dataSource: String,
        headers: Map<String, String>?
    ): MediaDataSource? {
        if (isNcmDataSource(context, dataSource)) {
            val ncmDs = NcmMediaDataSource(context, Uri.parse(dataSource))
            extractor.setDataSource(ncmDs)
            return ncmDs
        } else if (dataSource.startsWith("content://")) {
            extractor.setDataSource(context, Uri.parse(dataSource), null)
        } else if (dataSource.startsWith("http://") || dataSource.startsWith("https://")) {
            val reqHeaders = headers ?: mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to "https://music.163.com/"
            )
            extractor.setDataSource(dataSource, reqHeaders)
        } else {
            extractor.setDataSource(dataSource)
        }
        return null
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                extractor.selectTrack(i)
                return i to format
            }
        }
        AppLogger.d(TAG, "No audio track found in extractor")
        return null
    }

    private fun getSampleRate(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100

    private fun getChannelCount(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

    private fun getPcmEncoding(format: MediaFormat): Int {
        return if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
    }

    private fun bytesPerPcmSample(encoding: Int): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        AudioFormat.ENCODING_PCM_32BIT -> 4
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_8BIT -> 1
        else -> 2
    }

    private fun readNormalizedSample(buf: ByteBuffer, encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> buf.float
        AudioFormat.ENCODING_PCM_32BIT -> buf.int.toFloat() / 2147483648.0f
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt()
            ((b2 shl 16) or (b1 shl 8) or b0).toFloat() / 8388608.0f
        }
        AudioFormat.ENCODING_PCM_8BIT -> ((buf.get().toInt() and 0xFF) - 128).toFloat() / 128.0f
        else -> buf.short.toFloat() / 32768.0f
    }

    private fun skipSample(buf: ByteBuffer, encoding: Int) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> buf.int
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                buf.get(); buf.get(); buf.get()
            }
            AudioFormat.ENCODING_PCM_8BIT -> buf.get()
            else -> buf.short
        }
    }

    private fun isNcmDataSource(context: Context, dataSource: String): Boolean {
        if (dataSource.contains(".ncm", ignoreCase = true)) return true
        if (dataSource.startsWith("content://") || dataSource.startsWith("file://")) {
            return runCatching {
                val uri = Uri.parse(dataSource)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val magic = ByteArray(8)
                    val read = stream.read(magic)
                    read == 8 && magic.contentEquals(byteArrayOf(67, 84, 69, 78, 70, 68, 65, 77))
                } ?: false
            }.getOrDefault(false)
        }
        return false
    }
}