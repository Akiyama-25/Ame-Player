package Akari.NCM.player.core.loudness

import Akari.NCM.player.core.AppLogger
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * 离线全曲综合响度测算扫描器 (ITU-R BS.1770-4 / EBU R128)
 *
 * 通过 MediaExtractor 与 MediaCodec 在后台极速解复用并解码音频文件全长，
 * 运用 BS.1770 K-weighting 双级滤波积累 400ms 能量分块并完成门限门控积分，
 * 计算出整首曲目的全局综合响度 (Integrated Loudness LUFS) 与真峰值 (Peak)。
 */
object TrackLoudnessScanner {
    private const val TAG = "TrackLoudnessScanner"

    suspend fun scanTrack(
        context: Context,
        dataSource: String,
        headers: Map<String, String>? = null
    ): LoudnessData? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (dataSource.startsWith("content://")) {
                extractor.setDataSource(context, Uri.parse(dataSource), null)
            } else if (dataSource.startsWith("http://") || dataSource.startsWith("https://")) {
                extractor.setDataSource(dataSource, headers)
            } else {
                extractor.setDataSource(dataSource)
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                AppLogger.d(TAG, "No audio track found in dataSource: $dataSource")
                return@withContext null
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            val s1Coeffs = EbuR128Calculator.calculateStage1Coeffs(sampleRate.toDouble())
            val s2Coeffs = EbuR128Calculator.calculateStage2Coeffs(sampleRate.toDouble())
            val blockSizeFrames = (0.4 * sampleRate).toInt().coerceAtLeast(1)

            var kL1_x1 = 0.0; var kL1_x2 = 0.0; var kL1_y1 = 0.0; var kL1_y2 = 0.0
            var kL2_x1 = 0.0; var kL2_x2 = 0.0; var kL2_y1 = 0.0; var kL2_y2 = 0.0
            var kR1_x1 = 0.0; var kR1_x2 = 0.0; var kR1_y1 = 0.0; var kR1_y2 = 0.0
            var kR2_x1 = 0.0; var kR2_x2 = 0.0; var kR2_y1 = 0.0; var kR2_y2 = 0.0

            val blockEnergies = mutableListOf<Double>()
            var currentBlockSum = 0.0
            var frameCountInBlock = 0
            var maxObservedPeak = 0.0f

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEos = false
            var isOutputEos = false
            val timeoutUs = 10000L

            while (!isOutputEos && coroutineContext.isActive) {
                if (!isInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(timeoutUs)
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

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputIndex >= 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEos = true
                    }
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

                        val bytesPerSample = 2 // standard 16-bit PCM output from MediaCodec
                        val bytesPerFrame = bytesPerSample * channelCount
                        val frames = bufferInfo.size / bytesPerFrame

                        for (f in 0 until frames) {
                            val leftShort = outputBuffer.short
                            val rightShort = if (channelCount > 1) outputBuffer.short else leftShort
                            for (c in 2 until channelCount) outputBuffer.short

                            val sL = leftShort.toFloat() / 32768.0f
                            val sR = rightShort.toFloat() / 32768.0f
                            val peak = maxOf(abs(sL), abs(sR))
                            if (peak > maxObservedPeak) maxObservedPeak = peak

                            // Stage 1 filter
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
                                blockEnergies.add(currentBlockSum / blockSizeFrames)
                                currentBlockSum = 0.0
                                frameCountInBlock = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }

            if (blockEnergies.isNotEmpty()) {
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
            AppLogger.w(TAG, "Full-track loudness scan failed for $dataSource: ${e.message}")
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}