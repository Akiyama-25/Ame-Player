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
 * 离线与在线音频综合响度测算扫描器 (ITU-R BS.1770-4 / EBU R128 + Tier 2 11×3s 稀疏抽样)
 *
 * 支持三种扫描路径：
 * 1. [scanTrackSparse11x3s]（Tier 2 主路径）：
 *    - 时长 > 33s：自 0% 起每 10% 取 3s（共 11 段、合计 33s，末段钳位至 [dur-3s, dur]），
 *      在线直链先做 HTTP Range 探测并以 3–4 并发优先拉取 20%/40%/60%/80% 中段切片，
 *      快速给出抗前奏初判，随后合并全部 11 段执行 400ms/75% 重叠双重门控 + P75 锚点 + 4x 过采样真峰值锁定；
 *    - 时长 <= 33s：直接执行全曲完整 EBU R128 扫描，无需切片。
 * 2. [scanTrackFast]：保留轻量中段跳帧初判能力（兼容预热与快速估算）。
 * 3. [scanTrack]：全曲完整 BS.1770-4（75% 重叠 + 4x 真峰值）离线扫描。
 */
object TrackLoudnessScanner {
    private const val TAG = "TrackLoudnessScanner"
    private const val TIMEOUT_US = 2000L
    private const val ABS_GATE_THRESHOLD_ENERGY = 1.172195e-7 // 10^((-70.0 + 0.691) / 10.0)

    /**
     * Tier 2 核心引擎：11 段 × 3 秒等距稀疏抽样（时长 <= 33s 时自动走全曲完整扫描）
     *
     * @param onMidSegmentsReady 当 20%/40%/60%/80% 中段切片完成时立刻回调可靠初始估计（不等待首尾段）
     */
    suspend fun scanTrackSparse11x3s(
        context: Context,
        dataSource: String,
        headers: Map<String, String>? = null,
        onMidSegmentsReady: ((LoudnessData) -> Unit)? = null
    ): EbuR128Calculator.SparseLoudnessAnalysis? = withContext(Dispatchers.IO) {
        val isHttp = dataSource.startsWith("http://") || dataSource.startsWith("https://")
        var rangeSource: SparseRangeLoudnessProber.SparseHttpRangeMediaDataSource? = null
        var flacIndex: SparseRangeLoudnessProber.FlacHeaderIndex? = null
        var mp4Index: SparseRangeLoudnessProber.Mp4BoxIndex? = null
        var mp3Index: SparseRangeLoudnessProber.Mp3HeaderIndex? = null

        if (isHttp) {
            val probe = SparseRangeLoudnessProber.probeHttpRangeSupport(dataSource, headers)
                ?: return@withContext null // 服务端不支持 HTTP Range -> 交由 Tier 3 实时兜底
            rangeSource = SparseRangeLoudnessProber.SparseHttpRangeMediaDataSource(
                url = dataSource,
                headers = headers,
                totalSize = probe.totalSize,
                initialHeaderBytes = probe.initialBytes
            )
            // 使用随机偏移读取器完整解析容器索引：自动跳过 FLAC 大封面、定位 MP4 尾部 moov、跳过 MP3 大 ID3v2 与 VBRI TOC
            val headerReader = rangeSource.asRandomAccessReader()
            flacIndex = SparseRangeLoudnessProber.FlacHeaderIndex.parse(headerReader, probe.totalSize)
            if (flacIndex == null) {
                mp4Index = SparseRangeLoudnessProber.Mp4BoxIndex.parse(headerReader, probe.totalSize)
            }
            if (flacIndex == null && mp4Index == null) {
                mp3Index = SparseRangeLoudnessProber.Mp3HeaderIndex.parse(headerReader, probe.totalSize)
            }
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var localMediaDataSource: MediaDataSource? = null
        try {
            if (rangeSource != null) {
                extractor.setDataSource(rangeSource)
            } else {
                localMediaDataSource = configureExtractor(context, extractor, dataSource, headers)
            }
            val selection = selectAudioTrack(extractor) ?: return@withContext null
            val audioFormat = selection.second
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext null

            val sampleRate = getSampleRate(audioFormat)
            val channelCount = getChannelCount(audioFormat)
            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                val containerDurMs = flacIndex?.durationMs ?: mp4Index?.durationMs ?: mp3Index?.durationMs ?: 0L
                containerDurMs * 1000L
            }
            val durationMs = durationUs / 1000L

            if (durationMs <= 0L) {
                AppLogger.d(TAG, "Unknown track duration for $dataSource -> fallback to Tier 3")
                return@withContext null
            }

            // 短曲目 (<= 33s)：直接执行全曲完整 EBU R128 计算，无需切片
            if (durationMs <= EbuR128Calculator.SPARSE_FULL_TRACK_THRESHOLD_MS) {
                if (rangeSource != null) {
                    rangeSource.prefetchRangesConcurrently(
                        listOf(SparseRangeLoudnessProber.ByteRange(0L, (rangeSource.size - 1L).coerceAtLeast(0L))),
                        maxConcurrency = 1
                    )
                }
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(audioFormat, null, null, 0)
                codec.start()
                val fullSeg = decodeWindowToSegment(
                    extractor = extractor,
                    codec = codec,
                    audioFormat = audioFormat,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    window = EbuR128Calculator.SparseSampleWindow(0, 0f, 0L, durationMs, isFullTrack = true),
                    shouldSeek = false
                ) ?: return@withContext null
                val fullData = EbuR128Calculator.computeIntegratedLoudnessFromBlockEnergies(
                    fullSeg.momentaryBlockEnergies,
                    fullSeg.truePeak
                ).copy(source = LoudnessSource.BS1770_SCAN)
                return@withContext EbuR128Calculator.SparseLoudnessAnalysis(
                    loudnessData = fullData,
                    gatedIntegratedLufs = fullData.integratedLufs,
                    gatedSegmentP75Lufs = fullSeg.segmentLufs,
                    filteredSegmentIndices = emptyList(),
                    keptSegmentIndices = listOf(0),
                    estimatedLraLu = 0f
                )
            }

            // 常规曲目 (> 33s)：规划 11 段 × 3 秒等距窗口
            val windows = EbuR128Calculator.planSparseSampleWindows(durationMs)
            val priorityWindows = windows.filter { it.index in EbuR128Calculator.PRIORITY_MID_SEGMENT_INDICES }
            val remainingWindows = windows.filter { it.index !in EbuR128Calculator.PRIORITY_MID_SEGMENT_INDICES }

            fun mapWindowToRange(w: EbuR128Calculator.SparseSampleWindow): SparseRangeLoudnessProber.ByteRange? {
                return when {
                    flacIndex != null -> flacIndex.mapWindowToByteRange(w.startMs, w.endMs)
                    mp4Index != null -> mp4Index.mapWindowToByteRange(w.startMs, w.endMs)
                    mp3Index != null -> mp3Index.mapWindowToByteRange(w.startMs, w.endMs)
                    rangeSource != null && durationMs > 0L -> {
                        val size = rangeSource.size
                        val s = ((size.toDouble() * w.startMs / durationMs).toLong() - 16_384L).coerceIn(0L, size - 1L)
                        val e = ((size.toDouble() * w.endMs / durationMs).toLong() + 24_576L).coerceIn(s, size - 1L)
                        SparseRangeLoudnessProber.ByteRange(s, e)
                    }
                    else -> null
                }
            }

            // 1. 优先并发拉取 20% / 40% / 60% / 80% 四个中段切片（并发度 4）
            if (rangeSource != null) {
                rangeSource.prefetchRangesConcurrently(
                    priorityWindows.mapNotNull { mapWindowToRange(it) },
                    maxConcurrency = 4
                )
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val collectedSegments = mutableListOf<EbuR128Calculator.SparseSegmentResult>()

            for (w in priorityWindows) {
                if (!coroutineContext.isActive) return@withContext null
                decodeWindowToSegment(
                    extractor = extractor,
                    codec = codec,
                    audioFormat = audioFormat,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    window = w,
                    shouldSeek = true
                )?.let { collectedSegments.add(it) }
            }

            if (collectedSegments.isNotEmpty() && onMidSegmentsReady != null && coroutineContext.isActive) {
                val midAnalysis = EbuR128Calculator.calculateSparseSegmentsLoudness(
                    segments = collectedSegments,
                    source = LoudnessSource.FAST_PREVIEW_SCAN
                )
                onMidSegmentsReady(midAnalysis.loudnessData)
            }

            // 2. 并发拉取其余 7 段切片（0%/10%/30%/50%/70%/90%/100% 尾段钳位）
            if (rangeSource != null) {
                rangeSource.prefetchRangesConcurrently(
                    remainingWindows.mapNotNull { mapWindowToRange(it) },
                    maxConcurrency = 4
                )
            }

            for (w in remainingWindows) {
                if (!coroutineContext.isActive) return@withContext null
                decodeWindowToSegment(
                    extractor = extractor,
                    codec = codec,
                    audioFormat = audioFormat,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    window = w,
                    shouldSeek = true
                )?.let { collectedSegments.add(it) }
            }

            if (!coroutineContext.isActive || collectedSegments.isEmpty()) return@withContext null

            val sortedSegments = collectedSegments.sortedBy { it.segmentIndex }
            val analysis = EbuR128Calculator.calculateSparseSegmentsLoudness(
                segments = sortedSegments,
                source = LoudnessSource.SPARSE_RANGE_SCAN
            )
            AppLogger.i(
                TAG,
                "Tier 2 11×3s sparse scan complete (${sortedSegments.size}/11 segs, filtered=${analysis.filteredSegmentIndices}, " +
                    "I_sampled=${"%.2f".format(analysis.gatedIntegratedLufs)}, P75=${analysis.gatedSegmentP75Lufs?.let { "%.2f".format(it) }}, " +
                    "final=${"%.2f".format(analysis.loudnessData.integratedLufs)} LUFS, truePeak=${"%.2f".format(analysis.loudnessData.peakDb)} dBTP)"
            )
            analysis
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.w(TAG, "Tier 2 sparse 11×3s scan failed for $dataSource: ${e.message}")
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { localMediaDataSource?.close() }
            runCatching { rangeSource?.close() }
        }
    }

    /**
     * 解码单个时间窗口，按 400ms / 75% 重叠（100ms 步进）计算瞬时响度块能量、切片短时响度与 4x 过采样真峰值
     */
    private suspend fun decodeWindowToSegment(
        extractor: MediaExtractor,
        codec: MediaCodec,
        audioFormat: MediaFormat,
        sampleRate: Int,
        channelCount: Int,
        window: EbuR128Calculator.SparseSampleWindow,
        shouldSeek: Boolean
    ): EbuR128Calculator.SparseSegmentResult? {
        if (shouldSeek) {
            // 使用 SEEK_TO_PREVIOUS_SYNC 确保定位不晚于窗口起点（防止尾段 Seg 10 向前跳越导致不足 3s）
            extractor.seekTo(window.startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.flush()
        }

        val s1Coeffs = EbuR128Calculator.calculateStage1Coeffs(sampleRate.toDouble())
        val s2Coeffs = EbuR128Calculator.calculateStage2Coeffs(sampleRate.toDouble())
        val truePeakDetector = EbuR128Calculator.TruePeakDetector4x(2)

        val stepFrames = (0.1 * sampleRate).toInt().coerceAtLeast(1) // 100ms 子块，4 个子块组成 400ms 75% 重叠窗
        val targetFrames = if (window.isFullTrack) {
            Int.MAX_VALUE
        } else {
            ((window.durationMs.coerceAtLeast(1000L) * sampleRate) / 1000L).toInt()
        }
        val windowStartUs = if (shouldSeek && !window.isFullTrack) window.startMs * 1000L else 0L
        val maxPreSkipFrames = sampleRate * 4 // 最多跳过 4s 预同步音频，防止异常时间戳死循环
        var skippedPreFrames = 0

        var kL1_x1 = 0.0; var kL1_x2 = 0.0; var kL1_y1 = 0.0; var kL1_y2 = 0.0
        var kL2_x1 = 0.0; var kL2_x2 = 0.0; var kL2_y1 = 0.0; var kL2_y2 = 0.0
        var kR1_x1 = 0.0; var kR1_x2 = 0.0; var kR1_y1 = 0.0; var kR1_y2 = 0.0
        var kR2_x1 = 0.0; var kR2_x2 = 0.0; var kR2_y1 = 0.0; var kR2_y2 = 0.0

        val subBlockEnergies = DoubleArray(4)
        var subBlockCount = 0
        var subBlockWriteIdx = 0
        var currentSubSum = 0.0
        var framesInSub = 0

        val momentaryBlocks = mutableListOf<Double>()
        var totalEnergySum = 0.0
        var decodedFrames = 0

        var currentPcmEncoding = getPcmEncoding(audioFormat)
        val bufferInfo = MediaCodec.BufferInfo()
        var isInputEos = false
        var isOutputEos = false
        var stallLoops = 0

        while (!isOutputEos && decodedFrames < targetFrames && coroutineContext.isActive && stallLoops < 80) {
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

                    // 若 SEEK_TO_PREVIOUS_SYNC 落在窗口起点之前，跳过窗口起点前的多余同步帧，使 3s 切片与目标时间窗精准对齐
                    var startFrameInBuf = 0
                    if (windowStartUs > 0L && decodedFrames == 0 && skippedPreFrames < maxPreSkipFrames) {
                        val ptsUs = bufferInfo.presentationTimeUs
                        if (ptsUs >= 0L && ptsUs < windowStartUs) {
                            val deltaUs = windowStartUs - ptsUs
                            val skipNeeded = ((deltaUs * sampleRate) / 1_000_000L).toInt()
                            val allowedSkip = (maxPreSkipFrames - skippedPreFrames).coerceAtLeast(0)
                            startFrameInBuf = minOf(frames, skipNeeded, allowedSkip)
                            if (startFrameInBuf > 0) {
                                outputBuffer.position(outputBuffer.position() + startFrameInBuf * bytesPerFrame)
                                skippedPreFrames += startFrameInBuf
                            }
                        }
                    }

                    for (f in startFrameInBuf until frames) {
                        val sL = readNormalizedSample(outputBuffer, currentPcmEncoding)
                        val sR = if (channelCount > 1) readNormalizedSample(outputBuffer, currentPcmEncoding) else sL
                        for (c in 2 until channelCount) skipSample(outputBuffer, currentPcmEncoding)

                        truePeakDetector.processSample(0, sL)
                        truePeakDetector.processSample(1, sR)

                        val y1L = s1Coeffs.b0 * sL + s1Coeffs.b1 * kL1_x1 + s1Coeffs.b2 * kL1_x2 - s1Coeffs.a1 * kL1_y1 - s1Coeffs.a2 * kL1_y2
                        kL1_x2 = kL1_x1; kL1_x1 = sL.toDouble(); kL1_y2 = kL1_y1; kL1_y1 = y1L
                        val y2L = s2Coeffs.b0 * y1L + s2Coeffs.b1 * kL2_x1 + s2Coeffs.b2 * kL2_x2 - s2Coeffs.a1 * kL2_y1 - s2Coeffs.a2 * kL2_y2
                        kL2_x2 = kL2_x1; kL2_x1 = y1L; kL2_y2 = kL2_y1; kL2_y1 = y2L

                        val y1R = s1Coeffs.b0 * sR + s1Coeffs.b1 * kR1_x1 + s1Coeffs.b2 * kR1_x2 - s1Coeffs.a1 * kR1_y1 - s1Coeffs.a2 * kR1_y2
                        kR1_x2 = kR1_x1; kR1_x1 = sR.toDouble(); kR1_y2 = kR1_y1; kR1_y1 = y1R
                        val y2R = s2Coeffs.b0 * y1R + s2Coeffs.b1 * kR2_x1 + s2Coeffs.b2 * kR2_x2 - s2Coeffs.a1 * kR2_y1 - s2Coeffs.a2 * kR2_y2
                        kR2_x2 = kR2_x1; kR2_x1 = y1R; kR2_y2 = kR2_y1; kR2_y1 = y2R

                        val frameEnergy = y2L * y2L + y2R * y2R
                        currentSubSum += frameEnergy
                        totalEnergySum += frameEnergy
                        framesInSub++
                        decodedFrames++

                        if (framesInSub >= stepFrames) {
                            subBlockEnergies[subBlockWriteIdx] = currentSubSum / stepFrames
                            subBlockWriteIdx = (subBlockWriteIdx + 1) and 3
                            if (subBlockCount < 4) subBlockCount++
                            if (subBlockCount == 4) {
                                val blockEnergy = (subBlockEnergies[0] + subBlockEnergies[1] + subBlockEnergies[2] + subBlockEnergies[3]) * 0.25
                                momentaryBlocks.add(blockEnergy)
                            }
                            currentSubSum = 0.0
                            framesInSub = 0
                        }
                        if (decodedFrames >= targetFrames) break
                    }
                }
                codec.releaseOutputBuffer(outputIndex, false)
            } else {
                stallLoops++
            }
        }

        if (decodedFrames <= 0) return null
        val segmentEnergy = totalEnergySum / decodedFrames
        return EbuR128Calculator.SparseSegmentResult(
            segmentIndex = window.index,
            startMs = window.startMs,
            endMs = window.endMs,
            momentaryBlockEnergies = momentaryBlocks,
            segmentEnergy = segmentEnergy,
            segmentLufs = EbuR128Calculator.energyToLufs(segmentEnergy),
            truePeak = truePeakDetector.maxTruePeak
        )
    }

    /**
     * 极速多点跳帧抽样扫描（兼容预热缓存与快速中段初判：20% / 40% / 60% / 80%，在线直链优先复用 HTTP Range 并发拉取）
     */
    suspend fun scanTrackFast(
        context: Context,
        dataSource: String,
        headers: Map<String, String>? = null
    ): LoudnessData? = withContext(Dispatchers.IO) {
        val isHttp = dataSource.startsWith("http://") || dataSource.startsWith("https://")
        var rangeSource: SparseRangeLoudnessProber.SparseHttpRangeMediaDataSource? = null
        var flacIndex: SparseRangeLoudnessProber.FlacHeaderIndex? = null
        var mp4Index: SparseRangeLoudnessProber.Mp4BoxIndex? = null
        var mp3Index: SparseRangeLoudnessProber.Mp3HeaderIndex? = null

        if (isHttp) {
            val probe = SparseRangeLoudnessProber.probeHttpRangeSupport(dataSource, headers)
            if (probe != null) {
                rangeSource = SparseRangeLoudnessProber.SparseHttpRangeMediaDataSource(
                    url = dataSource,
                    headers = headers,
                    totalSize = probe.totalSize,
                    initialHeaderBytes = probe.initialBytes
                )
                val reader = rangeSource.asRandomAccessReader()
                flacIndex = SparseRangeLoudnessProber.FlacHeaderIndex.parse(reader, probe.totalSize)
                if (flacIndex == null) mp4Index = SparseRangeLoudnessProber.Mp4BoxIndex.parse(reader, probe.totalSize)
                if (flacIndex == null && mp4Index == null) mp3Index = SparseRangeLoudnessProber.Mp3HeaderIndex.parse(reader, probe.totalSize)
            }
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var mediaDataSource: MediaDataSource? = null
        try {
            if (rangeSource != null) {
                extractor.setDataSource(rangeSource)
            } else {
                mediaDataSource = configureExtractor(context, extractor, dataSource, headers)
            }
            val selection = selectAudioTrack(extractor) ?: return@withContext null
            val audioFormat = selection.second
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext null

            val sampleRate = getSampleRate(audioFormat)
            val channelCount = getChannelCount(audioFormat)
            val durationMs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION) / 1000L
            } else {
                flacIndex?.durationMs ?: mp4Index?.durationMs ?: mp3Index?.durationMs ?: 0L
            }

            val windows = if (durationMs >= 12_000L) {
                listOf(0.20f, 0.40f, 0.60f, 0.80f).mapIndexed { idx, ratio ->
                    val s = (durationMs * ratio).toLong()
                    EbuR128Calculator.SparseSampleWindow(idx, ratio, s, (s + 1500L).coerceAtMost(durationMs))
                }
            } else {
                listOf(EbuR128Calculator.SparseSampleWindow(0, 0f, 0L, durationMs.coerceAtLeast(2400L)))
            }

            val activeRangeSource = rangeSource
            val activeFlacIndex = flacIndex
            val activeMp4Index = mp4Index
            val activeMp3Index = mp3Index
            if (activeRangeSource != null && durationMs > 0L) {
                val ranges = windows.map { w ->
                    when {
                        activeFlacIndex != null -> activeFlacIndex.mapWindowToByteRange(w.startMs, w.endMs)
                        activeMp4Index != null -> activeMp4Index.mapWindowToByteRange(w.startMs, w.endMs)
                        activeMp3Index != null -> activeMp3Index.mapWindowToByteRange(w.startMs, w.endMs)
                        else -> {
                            val size = activeRangeSource.size
                            val s = ((size.toDouble() * w.startMs / durationMs).toLong() - 16_384L).coerceIn(0L, size - 1L)
                            val e = ((size.toDouble() * w.endMs / durationMs).toLong() + 24_576L).coerceIn(s, size - 1L)
                            SparseRangeLoudnessProber.ByteRange(s, e)
                        }
                    }
                }
                activeRangeSource.prefetchRangesConcurrently(ranges, maxConcurrency = 4)
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val segments = mutableListOf<EbuR128Calculator.SparseSegmentResult>()
            for ((idx, w) in windows.withIndex()) {
                if (!coroutineContext.isActive) break
                decodeWindowToSegment(
                    extractor = extractor,
                    codec = codec,
                    audioFormat = audioFormat,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    window = w,
                    shouldSeek = idx > 0 || w.startMs > 0L
                )?.let { segments.add(it) }
            }

            if (coroutineContext.isActive && segments.isNotEmpty()) {
                val previewSource = if (windows.size > 1) LoudnessSource.FAST_PREVIEW_SCAN else LoudnessSource.INTRO_PREVIEW_SCAN
                val analysis = EbuR128Calculator.calculateSparseSegmentsLoudness(segments, source = previewSource)
                AppLogger.i(TAG, "Fast multi-point scan complete (${segments.size} mid segs): ${analysis.loudnessData.integratedLufs} LUFS (${previewSource.label})")
                analysis.loudnessData
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
            runCatching { rangeSource?.close() }
        }
    }

    /**
     * 全曲完整 BS.1770-4 扫描（400ms / 75% 重叠 + 4x 过采样真峰值）
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
            val truePeakDetector = EbuR128Calculator.TruePeakDetector4x(2)
            val stepFrames = (0.1 * sampleRate).toInt().coerceAtLeast(1)

            var kL1_x1 = 0.0; var kL1_x2 = 0.0; var kL1_y1 = 0.0; var kL1_y2 = 0.0
            var kL2_x1 = 0.0; var kL2_x2 = 0.0; var kL2_y1 = 0.0; var kL2_y2 = 0.0
            var kR1_x1 = 0.0; var kR1_x2 = 0.0; var kR1_y1 = 0.0; var kR1_y2 = 0.0
            var kR2_x1 = 0.0; var kR2_x2 = 0.0; var kR2_y1 = 0.0; var kR2_y2 = 0.0

            val subBlockEnergies = DoubleArray(4)
            var subBlockCount = 0
            var subBlockWriteIdx = 0
            var currentSubSum = 0.0
            var framesInSub = 0

            val blockEnergies = mutableListOf<Double>()
            var activeNonSilentBlocks = 0
            var previewEmitted = false

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

                            truePeakDetector.processSample(0, sL)
                            truePeakDetector.processSample(1, sR)

                            val y1L = s1Coeffs.b0 * sL + s1Coeffs.b1 * kL1_x1 + s1Coeffs.b2 * kL1_x2 - s1Coeffs.a1 * kL1_y1 - s1Coeffs.a2 * kL1_y2
                            kL1_x2 = kL1_x1; kL1_x1 = sL.toDouble(); kL1_y2 = kL1_y1; kL1_y1 = y1L
                            val y2L = s2Coeffs.b0 * y1L + s2Coeffs.b1 * kL2_x1 + s2Coeffs.b2 * kL2_x2 - s2Coeffs.a1 * kL2_y1 - s2Coeffs.a2 * kL2_y2
                            kL2_x2 = kL2_x1; kL2_x1 = y1L; kL2_y2 = kL2_y1; kL2_y1 = y2L

                            val y1R = s1Coeffs.b0 * sR + s1Coeffs.b1 * kR1_x1 + s1Coeffs.b2 * kR1_x2 - s1Coeffs.a1 * kR1_y1 - s1Coeffs.a2 * kR1_y2
                            kR1_x2 = kR1_x1; kR1_x1 = sR.toDouble(); kR1_y2 = kR1_y1; kR1_y1 = y1R
                            val y2R = s2Coeffs.b0 * y1R + s2Coeffs.b1 * kR2_x1 + s2Coeffs.b2 * kR2_x2 - s2Coeffs.a1 * kR2_y1 - s2Coeffs.a2 * kR2_y2
                            kR2_x2 = kR2_x1; kR2_x1 = y1R; kR2_y2 = kR2_y1; kR2_y1 = y2R

                            currentSubSum += (y2L * y2L + y2R * y2R)
                            framesInSub++

                            if (framesInSub >= stepFrames) {
                                subBlockEnergies[subBlockWriteIdx] = currentSubSum / stepFrames
                                subBlockWriteIdx = (subBlockWriteIdx + 1) and 3
                                if (subBlockCount < 4) subBlockCount++
                                if (subBlockCount == 4) {
                                    val energy = (subBlockEnergies[0] + subBlockEnergies[1] + subBlockEnergies[2] + subBlockEnergies[3]) * 0.25
                                    blockEnergies.add(energy)
                                    if (energy >= ABS_GATE_THRESHOLD_ENERGY) {
                                        activeNonSilentBlocks++
                                    }
                                    if (!previewEmitted && onPreviewReady != null && activeNonSilentBlocks >= 10) {
                                        previewEmitted = true
                                        val preview = EbuR128Calculator.computeIntegratedLoudnessFromBlockEnergies(
                                            blockEnergies.toList(),
                                            truePeakDetector.maxTruePeak
                                        ).copy(source = LoudnessSource.INTRO_PREVIEW_SCAN)
                                        onPreviewReady(preview)
                                    }
                                }
                                currentSubSum = 0.0
                                framesInSub = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }

            if (coroutineContext.isActive && blockEnergies.isNotEmpty()) {
                val fullResult = EbuR128Calculator.computeIntegratedLoudnessFromBlockEnergies(
                    blockEnergies,
                    truePeakDetector.maxTruePeak
                )
                AppLogger.i(TAG, "Completed full-track BS.1770 scan (${blockEnergies.size} blocks, truePeak=${fullResult.peakDb} dBTP): ${fullResult.integratedLufs} LUFS")
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