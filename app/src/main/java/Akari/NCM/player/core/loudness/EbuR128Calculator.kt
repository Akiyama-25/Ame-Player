package Akari.NCM.player.core.loudness

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * EBU R128 / ITU-R BS.1770-4 综合响度计算、11×3s 稀疏抽样双门控与 4x 过采样真峰值引擎
 *
 * 核心规范依据：
 * 1. EBU R128: Loudness normalisation and permitted maximum level of audio signals
 * 2. ITU-R BS.1770-4: Algorithms to measure audio programme loudness and true-peak audio level (Annex 2 4x FIR)
 */
object EbuR128Calculator {
    const val TARGET_LOUD_LUFS = -11.0f       // 响亮模式（适合嘈杂环境或外放）
    const val TARGET_STREAMING_LUFS = -14.0f  // 标准流媒体模式（默认推荐）
    const val TARGET_STUDIO_LUFS = -16.0f     // 柔和 / Apple Sound Check 标准
    const val TARGET_EBU_R128_LUFS = -23.0f   // EBU R128 国际广电标准（保留宽广动态范围）
    const val TRUE_PEAK_LIMIT_DBTP = -1.0f    // 允许最大真峰值天花板 (-1.0 dBTP)
    const val MAX_UNVERIFIED_POSITIVE_GAIN_DB = 2.0f // Tier 3 / 未锁定时正向增益保守上限 (+2.0 dB)
    const val MAX_REALTIME_ATTENUATION_DB = -15.0f   // Tier 3 实时兜底负向衰减上限 (-15.0 dB)
    const val SPARSE_SEGMENT_DURATION_MS = 3_000L
    const val SPARSE_FULL_TRACK_THRESHOLD_MS = 33_000L
    const val SPARSE_SEGMENT_COUNT = 11

    // 20% / 40% / 60% / 80% 中段优先索引（最快获得抗前奏干扰的可靠初始估计）
    val PRIORITY_MID_SEGMENT_INDICES = listOf(2, 4, 6, 8)

    data class GainResult(
        val rawGainDb: Float,          // 目标响度 - 综合响度的原始增益 (dB)
        val effectiveGainDb: Float,    // 经防破音真峰值压制与模式约束后的实际生效增益 (dB)
        val linearMultiplier: Float,   // 转换后的线性音量放大乘数 10^(dB/20)
        val isPeakLimited: Boolean     // 是否因峰值过高触发了防过载削波压制
    )

    data class SparseSampleWindow(
        val index: Int,
        val ratio: Float,
        val startMs: Long,
        val endMs: Long,
        val isFullTrack: Boolean = false
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    }

    data class SparseSegmentResult(
        val segmentIndex: Int,
        val startMs: Long,
        val endMs: Long,
        val momentaryBlockEnergies: List<Double>, // 400ms 窗口、75% 重叠（100ms 步进）能量
        val segmentEnergy: Double,                // 3s 切片平均 K-Weighted 能量
        val segmentLufs: Float,                   // 3s 切片短时响度 (LUFS)
        val truePeak: Float                       // 4x 过采样真峰值 (线性)
    )

    data class SparseLoudnessAnalysis(
        val loudnessData: LoudnessData,
        val gatedIntegratedLufs: Float,
        val gatedSegmentP75Lufs: Float?,
        val filteredSegmentIndices: List<Int>,
        val keptSegmentIndices: List<Int>,
        val estimatedLraLu: Float
    )

    /**
     * 根据 EBU R128 准则与目标响度电平计算音量均衡增益
     *
     * @param loudness 当前歌曲的综合响度特征数据
     * @param targetLufs 目标综合响度（如 -14.0 / -16.0 / -11.0 / -23.0 LUFS）
     * @param peakLimitEnabled 是否启用 -1.0 dBTP 防过载压制保护
     * @param negativeGainOnly 仅降不升模式：仅压低过响曲目，禁止正向抬升轻柔曲目
     */
    fun computeGain(
        loudness: LoudnessData,
        targetLufs: Float,
        peakLimitEnabled: Boolean = true,
        negativeGainOnly: Boolean = false
    ): GainResult {
        val rawGainDb = targetLufs - loudness.integratedLufs

        var effectiveGainDb = rawGainDb
        var isPeakLimited = false

        if (peakLimitEnabled && loudness.truePeak > 0f) {
            val peakDb = loudness.peakDb
            val maxAllowedGainDb = TRUE_PEAK_LIMIT_DBTP - peakDb
            if (effectiveGainDb > maxAllowedGainDb) {
                effectiveGainDb = maxAllowedGainDb
                isPeakLimited = true
            }
        }

        // 仅降不升模式：将生效增益上限钳位至 0.0 dB
        if (negativeGainOnly && effectiveGainDb > 0.0f) {
            effectiveGainDb = 0.0f
        }

        // 未全曲/稀疏锁定前（初判 / 实时兜底 / 队列均值），正向增益安全封顶 +2.0 dB
        val maxPositiveDb = when {
            negativeGainOnly -> 0.0f
            loudness.source.isFinalLocked -> 15.0f
            else -> MAX_UNVERIFIED_POSITIVE_GAIN_DB
        }
        val minNegativeDb = if (loudness.source == LoudnessSource.REALTIME_AGC) {
            MAX_REALTIME_ATTENUATION_DB
        } else {
            -24.0f
        }
        effectiveGainDb = effectiveGainDb.coerceIn(minNegativeDb, maxPositiveDb)
        val linearMultiplier = 10.0.pow(effectiveGainDb.toDouble() / 20.0).toFloat()

        return GainResult(
            rawGainDb = rawGainDb,
            effectiveGainDb = effectiveGainDb,
            linearMultiplier = linearMultiplier,
            isPeakLimited = isPeakLimited
        )
    }

    /**
     * 规划 Tier 2 的 11 段 × 3 秒（共 33 秒）等距稀疏抽样窗口：
     * - 曲目时长 > 33s：自 0% 起每 10% 取 3s（0%, 10%, ..., 90%），第 11 段（100%）向前钳位至 [duration - 3s, duration]
     * - 曲目时长 <= 33s：直接返回全曲单窗，无需切片
     */
    fun planSparseSampleWindows(durationMs: Long): List<SparseSampleWindow> {
        if (durationMs <= 0L) return emptyList()
        if (durationMs <= SPARSE_FULL_TRACK_THRESHOLD_MS) {
            return listOf(
                SparseSampleWindow(
                    index = 0,
                    ratio = 0.0f,
                    startMs = 0L,
                    endMs = durationMs,
                    isFullTrack = true
                )
            )
        }
        val windows = ArrayList<SparseSampleWindow>(SPARSE_SEGMENT_COUNT)
        val maxStart = (durationMs - SPARSE_SEGMENT_DURATION_MS).coerceAtLeast(0L)
        for (i in 0 until SPARSE_SEGMENT_COUNT) {
            val ratio = i / 10.0f
            val startMs = if (i == SPARSE_SEGMENT_COUNT - 1) {
                maxStart
            } else {
                (durationMs * ratio).toLong().coerceIn(0L, maxStart)
            }
            val endMs = (startMs + SPARSE_SEGMENT_DURATION_MS).coerceAtMost(durationMs)
            windows.add(
                SparseSampleWindow(
                    index = i,
                    ratio = ratio,
                    startMs = startMs,
                    endMs = endMs,
                    isFullTrack = false
                )
            )
        }
        return windows
    }

    /**
     * Biquad 双二阶 IIR 滤波器系数结构
     */
    class BiquadCoeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double
    )

    /**
     * 计算 ITU-R BS.1770 Stage 1 预滤波（High-Shelf 头部声学模拟滤波）系数
     */
    fun calculateStage1Coeffs(sampleRate: Double): BiquadCoeffs {
        val f0 = 1681.974450955533
        val gDb = 3.99984385397
        val k = tan(Math.PI * f0 / sampleRate)
        val v0 = 10.0.pow(gDb / 20.0)
        val den = 1.0 + sqrt(2.0) * k + k * k

        return BiquadCoeffs(
            b0 = (v0 + sqrt(2.0 * v0) * k + k * k) / den,
            b1 = 2.0 * (k * k - v0) / den,
            b2 = (v0 - sqrt(2.0 * v0) * k + k * k) / den,
            a1 = 2.0 * (k * k - 1.0) / den,
            a2 = (1.0 - sqrt(2.0) * k + k * k) / den
        )
    }

    /**
     * 计算 ITU-R BS.1770 Stage 2 RLB 高通滤波系数
     */
    fun calculateStage2Coeffs(sampleRate: Double): BiquadCoeffs {
        val f0 = 38.13547087602444
        val q = 0.5003270373238773
        val k = tan(Math.PI * f0 / sampleRate)
        val den = 1.0 + k / q + k * k

        return BiquadCoeffs(
            b0 = 1.0 / den,
            b1 = -2.0 / den,
            b2 = 1.0 / den,
            a1 = 2.0 * (k * k - 1.0) / den,
            a2 = (1.0 - k / q + k * k) / den
        )
    }

    /**
     * 对单声道浮点样本执行 Biquad IIR 滤波
     */
    fun filterChannel(samples: FloatArray, coeffs: BiquadCoeffs): FloatArray {
        val out = FloatArray(samples.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        val b0 = coeffs.b0
        val b1 = coeffs.b1
        val b2 = coeffs.b2
        val a1 = coeffs.a1
        val a2 = coeffs.a2

        for (i in samples.indices) {
            val x0 = samples[i].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            out[i] = y0.toFloat()
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        }
        return out
    }

    /**
     * ITU-R BS.1770-4 Annex 2 规范 4x 过采样真峰值检测器（4 相 × 12 抽头多相 Kaiser/Blackman 窗 Sinc FIR）
     * 零堆分配流式设计，可用于离线/稀疏扫描与实时限幅器。
     */
    class TruePeakDetector4x(channelCount: Int = 2) {
        companion object {
            private const val TAPS_PER_PHASE = 12
            private const val OVERSAMPLE_FACTOR = 4

            // 预计算 4 相 × 12 抽头多相插值系数（每个相位 DC 归一化为 1.0）
            private val POLYPHASE_COEFFS: Array<FloatArray> = buildPolyphaseCoeffs()

            private fun buildPolyphaseCoeffs(): Array<FloatArray> {
                val totalTaps = TAPS_PER_PHASE * OVERSAMPLE_FACTOR
                val center = (totalTaps - 1) / 2.0
                val fullKernel = DoubleArray(totalTaps)
                for (n in 0 until totalTaps) {
                    val x = (n - center) / OVERSAMPLE_FACTOR
                    val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
                    // Blackman-Harris 4-term 窗函数，旁瓣抑制 > 90 dB，确保真峰值测量精准无振铃偏置
                    val wPhase = 2.0 * PI * n / (totalTaps - 1)
                    val window = 0.35875 -
                        0.48829 * cos(wPhase) +
                        0.14128 * cos(2.0 * wPhase) -
                        0.01168 * cos(3.0 * wPhase)
                    fullKernel[n] = sinc * window
                }
                return Array(OVERSAMPLE_FACTOR) { phase ->
                    val phaseCoeffs = FloatArray(TAPS_PER_PHASE)
                    var sum = 0.0
                    for (t in 0 until TAPS_PER_PHASE) {
                        val v = fullKernel[t * OVERSAMPLE_FACTOR + phase]
                        phaseCoeffs[t] = v.toFloat()
                        sum += v
                    }
                    if (abs(sum) > 1e-9) {
                        val inv = (1.0 / sum).toFloat()
                        for (t in 0 until TAPS_PER_PHASE) {
                            phaseCoeffs[t] *= inv
                        }
                    }
                    phaseCoeffs
                }
            }
        }

        private var history = Array(channelCount.coerceAtLeast(1)) { FloatArray(TAPS_PER_PHASE) }
        private var writePos = IntArray(channelCount.coerceAtLeast(1))
        private var hotFrames = IntArray(channelCount.coerceAtLeast(1))
        var maxTruePeak: Float = 0.0f
            private set

        fun reset(channelCount: Int = history.size) {
            val ch = channelCount.coerceAtLeast(1)
            if (history.size != ch) {
                history = Array(ch) { FloatArray(TAPS_PER_PHASE) }
                writePos = IntArray(ch)
                hotFrames = IntArray(ch)
            } else {
                for (c in history.indices) {
                    history[c].fill(0f)
                    writePos[c] = 0
                    hotFrames[c] = 0
                }
            }
            maxTruePeak = 0.0f
        }

        /**
         * 实时限幅低功耗路径：持续维护 12 抽头环形历史缓冲，仅当 12 样本窗口内存在
         * |sample| >= threshold 时才执行 4 相 × 12 抽头多相插值；
         * 当窗口内全部样本均低于 0.55 (-5.2 dBFS) 时，数学上 4x 插值峰值必然低于 -1.0 dBTP (0.891)，
         * 可安全跳过 48 次乘加循环，大幅降低 96kHz/192kHz 播放 CPU 功耗且保持限幅精度完全一致。
         */
        fun processSampleWithThreshold(channel: Int, sample: Float, threshold: Float): Float {
            if (channel < 0 || channel >= history.size) return abs(sample)
            val buf = history[channel]
            val pos = writePos[channel]
            buf[pos] = sample
            writePos[channel] = if (pos + 1 == TAPS_PER_PHASE) 0 else pos + 1

            val absSample = abs(sample)
            if (absSample >= threshold) {
                hotFrames[channel] = TAPS_PER_PHASE
            }
            if (hotFrames[channel] <= 0) {
                if (absSample > maxTruePeak) maxTruePeak = absSample
                return absSample
            }
            hotFrames[channel]--

            var peak = absSample
            for (phase in 0 until OVERSAMPLE_FACTOR) {
                val coeffs = POLYPHASE_COEFFS[phase]
                var acc = 0.0f
                var idx = pos
                for (t in 0 until TAPS_PER_PHASE) {
                    acc += buf[idx] * coeffs[t]
                    idx = if (idx == 0) TAPS_PER_PHASE - 1 else idx - 1
                }
                val a = abs(acc)
                if (a > peak) peak = a
            }
            if (peak > maxTruePeak) maxTruePeak = peak
            return peak
        }

        /**
         * 推入单个声道样本并返回该采样周期内 4 个过采样相位（含原始样本）的最大绝对真峰值
         */
        fun processSample(channel: Int, sample: Float): Float {
            if (channel < 0 || channel >= history.size) return abs(sample)
            val buf = history[channel]
            val pos = writePos[channel]
            buf[pos] = sample
            val nextPos = if (pos + 1 == TAPS_PER_PHASE) 0 else pos + 1
            writePos[channel] = nextPos

            var peak = abs(sample)
            for (phase in 0 until OVERSAMPLE_FACTOR) {
                val coeffs = POLYPHASE_COEFFS[phase]
                var acc = 0.0f
                var idx = pos
                for (t in 0 until TAPS_PER_PHASE) {
                    acc += buf[idx] * coeffs[t]
                    idx = if (idx == 0) TAPS_PER_PHASE - 1 else idx - 1
                }
                val a = abs(acc)
                if (a > peak) peak = a
            }
            if (peak > maxTruePeak) maxTruePeak = peak
            return peak
        }
    }

    /**
     * 对立体声浮点数组计算 4x 过采样真峰值（线性值，1.0 = 0 dBTP）
     */
    fun measureTruePeak4x(leftChannel: FloatArray, rightChannel: FloatArray): Float {
        val length = min(leftChannel.size, rightChannel.size)
        if (length == 0) return 0.0f
        val detector = TruePeakDetector4x(2)
        for (i in 0 until length) {
            detector.processSample(0, leftChannel[i])
            detector.processSample(1, rightChannel[i])
        }
        return detector.maxTruePeak
    }

    /**
     * 对单段立体声 PCM（如 3 秒切片）计算 400ms / 75% 重叠瞬时块能量、切片短时响度及 4x 过采样真峰值
     */
    fun analyzeSegment(
        segmentIndex: Int,
        startMs: Long,
        endMs: Long,
        leftChannel: FloatArray,
        rightChannel: FloatArray,
        sampleRate: Int
    ): SparseSegmentResult {
        val length = min(leftChannel.size, rightChannel.size)
        if (length == 0) {
            return SparseSegmentResult(segmentIndex, startMs, endMs, emptyList(), 0.0, -70.0f, 0.0f)
        }

        val truePeak = measureTruePeak4x(leftChannel, rightChannel)
        val fs = sampleRate.toDouble()
        val s1 = calculateStage1Coeffs(fs)
        val s2 = calculateStage2Coeffs(fs)
        val kLeft = filterChannel(filterChannel(leftChannel, s1), s2)
        val kRight = filterChannel(filterChannel(rightChannel, s1), s2)

        val blockSize = (0.4 * sampleRate).toInt().coerceAtLeast(1)
        val stepSize = (0.1 * sampleRate).toInt().coerceAtLeast(1)

        val momentaryBlocks = mutableListOf<Double>()
        var offset = 0
        while (offset + blockSize <= length) {
            var sumL = 0.0
            var sumR = 0.0
            for (i in 0 until blockSize) {
                val idx = offset + i
                val sL = kLeft[idx].toDouble()
                val sR = kRight[idx].toDouble()
                sumL += sL * sL
                sumR += sR * sR
            }
            momentaryBlocks.add((sumL + sumR) / blockSize)
            offset += stepSize
        }

        var totalSum = 0.0
        for (i in 0 until length) {
            val sL = kLeft[i].toDouble()
            val sR = kRight[i].toDouble()
            totalSum += sL * sL + sR * sR
        }
        val segmentEnergy = totalSum / length
        val segmentLufs = energyToLufs(segmentEnergy)

        return SparseSegmentResult(
            segmentIndex = segmentIndex,
            startMs = startMs,
            endMs = endMs,
            momentaryBlockEnergies = momentaryBlocks,
            segmentEnergy = segmentEnergy,
            segmentLufs = segmentLufs,
            truePeak = truePeak
        )
    }

    /**
     * 抗极端响度计算（EBU R128 双重门控 + 高分位锚点 P75 融合）
     * 适用于 Tier 2 的 11×3s 稀疏抽样切片集合。
     */
    fun calculateSparseSegmentsLoudness(
        segments: List<SparseSegmentResult>,
        source: LoudnessSource = LoudnessSource.SPARSE_RANGE_SCAN
    ): SparseLoudnessAnalysis {
        val maxTruePeak = segments.maxOfOrNull { it.truePeak } ?: 0.0f
        val allBlocks = segments.flatMap { it.momentaryBlockEnergies }
        if (allBlocks.isEmpty()) {
            val fallback = LoudnessData(-70.0f, maxTruePeak, source)
            return SparseLoudnessAnalysis(fallback, -70.0f, null, segments.map { it.segmentIndex }, emptyList(), 0.0f)
        }

        // 1. 绝对门限 (-70 LUFS)
        val absThresholdEnergy = lufsToEnergy(-70.0)
        val absGatedBlocks = allBlocks.filter { it >= absThresholdEnergy }
        if (absGatedBlocks.isEmpty()) {
            val fallback = LoudnessData(-70.0f, maxTruePeak, source)
            return SparseLoudnessAnalysis(fallback, -70.0f, null, segments.map { it.segmentIndex }, emptyList(), 0.0f)
        }

        // 2. 相对门限 (I_ungated - 10 LU)
        val iUngatedLufs = -0.691 + 10.0 * log10(absGatedBlocks.average())
        val relThresholdLufs = iUngatedLufs - 10.0
        val relThresholdEnergy = lufsToEnergy(relThresholdLufs)

        val relGatedBlocks = absGatedBlocks.filter { it >= relThresholdEnergy }
        val iSampled = if (relGatedBlocks.isNotEmpty()) {
            (-0.691 + 10.0 * log10(relGatedBlocks.average())).toFloat().coerceIn(-70.0f, 0.0f)
        } else {
            iUngatedLufs.toFloat().coerceIn(-70.0f, 0.0f)
        }

        // 3. 按相对门限过滤 3 秒切片，提取有效切片的 P75 分位数 S_P75
        val keptSegments = mutableListOf<SparseSegmentResult>()
        val filteredIndices = mutableListOf<Int>()
        for (seg in segments) {
            if (seg.segmentEnergy >= absThresholdEnergy && seg.segmentEnergy >= relThresholdEnergy) {
                keptSegments.add(seg)
            } else {
                filteredIndices.add(seg.segmentIndex)
            }
        }

        val sortedSegmentLufs = keptSegments.map { it.segmentLufs.toDouble() }.sorted()
        val sP75 = if (sortedSegmentLufs.isNotEmpty()) {
            percentile(sortedSegmentLufs, 0.75).toFloat().coerceIn(-70.0f, 0.0f)
        } else null

        // 估算响度动态范围 LRA (P90 - P10 of gated momentary blocks)
        val sortedBlockLufs = relGatedBlocks.map { -0.691 + 10.0 * log10(it) }.sorted()
        val lraLu = if (sortedBlockLufs.size >= 4) {
            (percentile(sortedBlockLufs, 0.90) - percentile(sortedBlockLufs, 0.10)).toFloat().coerceAtLeast(0.0f)
        } else 0.0f

        // 4. 综合响度与高分位锚点融合：
        // 常规曲目以 I_sampled 为主，大动态曲目 (LRA >= 6 LU) 平衡融合 0.5 * I_sampled + 0.5 * S_P75
        val finalLufs = if (sP75 != null && keptSegments.size >= 3) {
            val weightP75 = if (lraLu >= 6.0f) 0.5f else 0.25f
            ((1.0f - weightP75) * iSampled + weightP75 * sP75).coerceIn(-70.0f, 0.0f)
        } else {
            iSampled
        }

        return SparseLoudnessAnalysis(
            loudnessData = LoudnessData(
                integratedLufs = finalLufs,
                truePeak = maxTruePeak,
                source = source
            ),
            gatedIntegratedLufs = iSampled,
            gatedSegmentP75Lufs = sP75,
            filteredSegmentIndices = filteredIndices,
            keptSegmentIndices = keptSegments.map { it.segmentIndex },
            estimatedLraLu = lraLu
        )
    }

    /**
     * 对完整 PCM 数组直接模拟 Tier 2 的 11×3s 等距抽样分析（用于短路径与精确度基准测试）
     */
    fun analyzePcmSparse11x3s(
        leftChannel: FloatArray,
        rightChannel: FloatArray,
        sampleRate: Int
    ): SparseLoudnessAnalysis {
        val length = min(leftChannel.size, rightChannel.size)
        val durationMs = (length * 1000L) / sampleRate.coerceAtLeast(1)
        val windows = planSparseSampleWindows(durationMs)
        if (windows.size == 1 && windows[0].isFullTrack) {
            val full = calculateIntegratedLoudness(leftChannel, rightChannel, sampleRate)
            return SparseLoudnessAnalysis(
                loudnessData = full,
                gatedIntegratedLufs = full.integratedLufs,
                gatedSegmentP75Lufs = full.integratedLufs,
                filteredSegmentIndices = emptyList(),
                keptSegmentIndices = listOf(0),
                estimatedLraLu = 0.0f
            )
        }
        val segments = windows.map { w ->
            val startSample = ((w.startMs * sampleRate) / 1000L).toInt().coerceIn(0, length)
            val endSample = ((w.endMs * sampleRate) / 1000L).toInt().coerceIn(startSample, length)
            analyzeSegment(
                segmentIndex = w.index,
                startMs = w.startMs,
                endMs = w.endMs,
                leftChannel = leftChannel.copyOfRange(startSample, endSample),
                rightChannel = rightChannel.copyOfRange(startSample, endSample),
                sampleRate = sampleRate
            )
        }
        return calculateSparseSegmentsLoudness(segments)
    }

    /**
     * 基于 ITU-R BS.1770-4 规范计算立体声 PCM 数据的综合响度（Integrated Loudness LUFS）与 4x 过采样真峰值
     */
    fun calculateIntegratedLoudness(
        leftChannel: FloatArray,
        rightChannel: FloatArray,
        sampleRate: Int
    ): LoudnessData {
        val length = min(leftChannel.size, rightChannel.size)
        if (length == 0) {
            return LoudnessData(-70.0f, 0.0f, LoudnessSource.BS1770_SCAN)
        }

        // 1. 计算 4x 过采样真峰值 (ITU-R BS.1770-4 Annex 2)
        val maxTruePeak = measureTruePeak4x(leftChannel, rightChannel)

        // 2. K-weighting 双级滤波（Stage 1 High-Shelf + Stage 2 RLB High-Pass）
        val fs = sampleRate.toDouble()
        val s1Coeffs = calculateStage1Coeffs(fs)
        val s2Coeffs = calculateStage2Coeffs(fs)

        val kLeft = filterChannel(filterChannel(leftChannel, s1Coeffs), s2Coeffs)
        val kRight = filterChannel(filterChannel(rightChannel, s1Coeffs), s2Coeffs)

        // 3. 400ms 分块计算（75% 重叠，步长 100ms）
        val blockSize = (0.4 * sampleRate).toInt().coerceAtLeast(1)
        val stepSize = (0.1 * sampleRate).toInt().coerceAtLeast(1)

        val blockEnergies = mutableListOf<Double>()
        var offset = 0
        while (offset + blockSize <= length) {
            var sumL = 0.0
            var sumR = 0.0
            for (i in 0 until blockSize) {
                val idx = offset + i
                val sL = kLeft[idx].toDouble()
                val sR = kRight[idx].toDouble()
                sumL += sL * sL
                sumR += sR * sR
            }
            val meanL = sumL / blockSize
            val meanR = sumR / blockSize
            val totalEnergy = meanL + meanR
            blockEnergies.add(totalEnergy)
            offset += stepSize
        }

        if (blockEnergies.isEmpty()) {
            return LoudnessData(-70.0f, maxTruePeak, LoudnessSource.BS1770_SCAN)
        }

        return computeIntegratedLoudnessFromBlockEnergies(blockEnergies, maxTruePeak)
    }

    /**
     * 基于已收集的 BS.1770 滤波分块能量数据执行 ITU-R BS.1770 双重门限门控并计算综合响度
     */
    fun computeIntegratedLoudnessFromBlockEnergies(
        blockEnergies: List<Double>,
        maxPeak: Float = 1.0f
    ): LoudnessData {
        if (blockEnergies.isEmpty()) {
            return LoudnessData(-70.0f, maxPeak, LoudnessSource.BS1770_SCAN)
        }

        // 绝对门限 (Absolute Threshold): -70 LKFS
        val absThresholdEnergy = lufsToEnergy(-70.0)
        val absGatedEnergies = blockEnergies.filter { it >= absThresholdEnergy }

        if (absGatedEnergies.isEmpty()) {
            return LoudnessData(-70.0f, maxPeak, LoudnessSource.BS1770_SCAN)
        }

        // 相对门限 (Relative Threshold): 绝对门控能量均值 - 10 LU
        val meanAbsGated = absGatedEnergies.average()
        val relativeLoudness = -0.691 + 10.0 * log10(meanAbsGated)
        val relThresholdLoudness = relativeLoudness - 10.0
        val relThresholdEnergy = lufsToEnergy(relThresholdLoudness)

        val finalGatedEnergies = absGatedEnergies.filter { it >= relThresholdEnergy }
        if (finalGatedEnergies.isEmpty()) {
            return LoudnessData(-70.0f, maxPeak, LoudnessSource.BS1770_SCAN)
        }

        val finalMeanEnergy = finalGatedEnergies.average()
        val integratedLufs = (-0.691 + 10.0 * log10(finalMeanEnergy)).toFloat()

        return LoudnessData(
            integratedLufs = integratedLufs.coerceIn(-70.0f, 0.0f),
            truePeak = maxPeak,
            source = LoudnessSource.BS1770_SCAN
        )
    }

    fun energyToLufs(energy: Double): Float {
        if (energy <= 1e-12) return -70.0f
        return (-0.691 + 10.0 * log10(energy)).toFloat().coerceIn(-70.0f, 0.0f)
    }

    fun lufsToEnergy(lufs: Double): Double = 10.0.pow((lufs + 0.691) / 10.0)

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return -70.0
        if (sorted.size == 1) return sorted[0]
        val pos = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = pos.toInt()
        val upper = min(lower + 1, sorted.size - 1)
        val frac = pos - lower
        return sorted[lower] * (1.0 - frac) + sorted[upper] * frac
    }
}