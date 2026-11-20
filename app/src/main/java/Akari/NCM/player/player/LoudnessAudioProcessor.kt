package Akari.NCM.player.player

import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.loudness.EbuR128Calculator
import Akari.NCM.player.core.loudness.LoudnessData
import Akari.NCM.player.core.loudness.LoudnessSource
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 三级渐进式音量均衡与全局 4x 过采样真峰值限幅 AudioProcessor
 *
 * 管线架构：
 * 1. USB DAC 独占 / Bit-Perfect 旁路：开启时强制零延迟逐字节透传，彻底旁路所有增益与限幅 DSP；
 * 2. Tier 1 / Tier 2 静态增益锁定：命中本地缓存、云端 gain/vd 或 11×3s 稀疏抽样后锁定静态目标增益；
 * 3. Tier 3 实时流式兜底（Look-ahead 非对称 AGC + Freeze Gate）：
 *    - 60 ms Look-ahead 环形缓冲，提前感知能量突增；
 *    - 保守正向增益上限 (+2.0 dB，仅降不升模式下为 0.0 dB)，负向衰减可达 -15.0 dB；
 *    - 快压（Attack = 300 ms）应对突发响度骤升，慢放（Release = 8.0 s）保留编曲动态；
 *    - Freeze Gate：当实时短时响度低于跟踪均值 11 LU 以上时冻结增益上调，消除底噪抬升与抽吸效应；
 *    - 无缝交接：Tier 2 后台完成后以 1.2 s 对数斜坡（dB 线性插值）平滑过渡至静态增益并锁定；
 * 4. 全局末端 4x 过采样 True Peak Limiter（天花板 -1.0 dBTP，Look-ahead 5 ms，Release 75 ms）。
 */
@UnstableApi
class LoudnessAudioProcessor : BaseAudioProcessor() {

    companion object {
        private const val TAG = "AME_LOUDNESS_PROC"
        const val TRUE_PEAK_CEILING_LINEAR = 0.8912509f // -1.0 dBTP (10^(-1/20))
        const val TIER3_LOOKAHEAD_MS = 60               // Tier 3 前瞻环形缓冲 (50–100 ms)
        const val LIMITER_LOOKAHEAD_MS = 5              // 末端 4x 真峰值限幅前瞻 (5 ms)
        const val LIMITER_RELEASE_SEC = 0.075           // 末端限幅释放时间 (50–100 ms)
        const val AGC_ATTACK_SEC = 0.30                 // Tier 3 快压时间常数 (200–500 ms)
        const val AGC_RELEASE_SEC = 8.0                 // Tier 3 慢放时间常数 (5–15 s)
        const val FREEZE_GATE_DROP_LU = 11.0f           // Freeze Gate 触发落差 (10–12 LU)
        const val HANDOFF_RAMP_SEC = 1.2                // Tier 2 -> 静态无缝交接斜坡 (1.0–1.5 s)

        private const val ABS_GATE_THRESHOLD_ENERGY = 1.172195e-7 // -70 LUFS
        private const val STAGE1_MIN_ACTIVE_BLOCKS = 5            // ~0.8s (400ms 窗 + 4*100ms 步进)
        private const val STAGE1_REFINE_BLOCKS = 80               // ~8.3s
        private const val STAGE2_LOCK_BLOCKS = 180                // ~18.3s
    }

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var targetLufs: Float = EbuR128Calculator.TARGET_STREAMING_LUFS

    @Volatile
    var peakLimitEnabled: Boolean = true

    @Volatile
    var negativeGainOnly: Boolean = false

    @Volatile
    var usbExclusiveBypass: Boolean = false

    @Volatile
    var currentMediaId: String? = null

    @Volatile
    var staticGainDb: Float? = null
        private set

    @Volatile
    var currentMultiplier: Float = 1.0f
        private set

    @Volatile
    var isFinalLocked: Boolean = false
        private set

    @Volatile
    var isFreezeGateActive: Boolean = false
        private set

    @Volatile
    var currentSource: LoudnessSource = LoudnessSource.DEFAULT_FALLBACK
        private set

    @Volatile
    private var currentLoudnessData: LoudnessData? = null

    var onPreviewLoudnessMeasured: ((String, LoudnessData) -> Unit)? = null
    var onLoudnessMeasured: ((String, LoudnessData) -> Unit)? = null

    // dB 域平滑与无缝斜坡状态
    @Volatile
    private var currentGainDb: Double = 0.0

    @Volatile
    private var targetGainDb: Double = 0.0

    @Volatile
    private var rampActive: Boolean = false
    private var rampStartDb: Double = 0.0
    private var rampEndDb: Double = 0.0
    private var rampTotalFrames: Int = 0
    private var rampElapsedFrames: Int = 0

    // Tier 3 实时 AGC 与 Freeze Gate 状态
    private var trackedMeanLufs: Float? = null
    private var latestShortTermLufs: Float? = null
    private var surgeFastEnergy: Double = 0.0
    private var agcActive: Boolean = false

    // Look-ahead 环形缓冲 (Tier 3 60ms)
    private var tier3Ring = FloatArray(0)
    private var tier3RingFrames = 0
    private var tier3RingWriteFrame = 0
    private var tier3RingFilledFrames = 0
    private var tier3RingActiveForTrack = false

    // 全局末端 4x 过采样 True Peak Limiter (5ms look-ahead, -1.0 dBTP ceiling, 75ms release)
    private var limiterRing = FloatArray(0)
    private var limiterReqGainRing = FloatArray(0)
    private var limiterDequeIndices = IntArray(0)
    private var limiterDequeHead = 0
    private var limiterDequeTail = 0
    private var limiterRingFrames = 0
    private var limiterWriteFrame = 0
    private var limiterFilledFrames = 0
    private var limiterAbsoluteFrame = 0L
    private var limiterGain: Float = 1.0f
    private val limiterTruePeak4x = EbuR128Calculator.TruePeakDetector4x(2)

    private var channelScratch = FloatArray(8)
    private var delayedScratch = FloatArray(8)

    // 实时流式 BS.1770 K-Weighting 滤波器与 75% 重叠 400ms 分块（100ms 步进）
    private var s1Coeffs: EbuR128Calculator.BiquadCoeffs? = null
    private var s2Coeffs: EbuR128Calculator.BiquadCoeffs? = null
    private var stepSizeFrames: Int = 4800
    private var kL1_x1 = 0.0; private var kL1_x2 = 0.0; private var kL1_y1 = 0.0; private var kL1_y2 = 0.0
    private var kL2_x1 = 0.0; private var kL2_x2 = 0.0; private var kL2_y1 = 0.0; private var kL2_y2 = 0.0
    private var kR1_x1 = 0.0; private var kR1_x2 = 0.0; private var kR1_y1 = 0.0; private var kR1_y2 = 0.0
    private var kR2_x1 = 0.0; private var kR2_x2 = 0.0; private var kR2_y1 = 0.0; private var kR2_y2 = 0.0

    private val subBlockEnergies = DoubleArray(4)
    private var subBlockCount = 0
    private var subBlockWriteIdx = 0
    private val shortTermWindow = DoubleArray(30) // 30 * 100ms = 3s 短时响度窗
    private var shortTermCount = 0
    private var shortTermWriteIdx = 0

    private val streamingBlockEnergies = ArrayList<Double>(256)
    private var streamingActiveBlocks = 0
    private var streamingSubSum = 0.0
    private var streamingFrameCount = 0
    private val streamingTruePeak4x = EbuR128Calculator.TruePeakDetector4x(2)

    @Volatile
    private var hasEmittedStage1Preview = false

    @Volatile
    private var hasEmittedMidRefine = false

    private var configuredChannels = 2
    private var configuredSampleRate = 48000

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_24BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_32BIT
        ) {
            return AudioFormat.NOT_SET
        }

        val sr = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        val ch = inputAudioFormat.channelCount.coerceAtLeast(1)
        configuredSampleRate = sr
        configuredChannels = ch

        s1Coeffs = EbuR128Calculator.calculateStage1Coeffs(sr.toDouble())
        s2Coeffs = EbuR128Calculator.calculateStage2Coeffs(sr.toDouble())
        stepSizeFrames = (0.1 * sr).toInt().coerceAtLeast(1)

        if (ch > channelScratch.size) {
            channelScratch = FloatArray(ch)
            delayedScratch = FloatArray(ch)
        }

        allocateBuffers(sr, ch)
        resetBiquadState()
        limiterTruePeak4x.reset(ch)
        streamingTruePeak4x.reset(min(ch, 2))

        return AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            inputAudioFormat.encoding
        )
    }

    private fun allocateBuffers(sampleRate: Int, channels: Int) {
        tier3RingFrames = ((sampleRate.toLong() * TIER3_LOOKAHEAD_MS) / 1000L).toInt().coerceAtLeast(1)
        tier3Ring = FloatArray(tier3RingFrames * channels)
        tier3RingWriteFrame = 0
        tier3RingFilledFrames = 0

        limiterRingFrames = ((sampleRate.toLong() * LIMITER_LOOKAHEAD_MS) / 1000L).toInt().coerceAtLeast(1)
        limiterRing = FloatArray(limiterRingFrames * channels)
        limiterReqGainRing = FloatArray(limiterRingFrames) { 1.0f }
        limiterDequeIndices = IntArray(limiterRingFrames + 1)
        limiterDequeHead = 0
        limiterDequeTail = 0
        limiterWriteFrame = 0
        limiterFilledFrames = 0
        limiterAbsoluteFrame = 0L
        limiterGain = 1.0f
    }

    fun updateSettings(
        enabled: Boolean,
        target: Float,
        peakLimit: Boolean,
        negativeGainOnly: Boolean = this.negativeGainOnly,
        usbExclusiveBypass: Boolean = this.usbExclusiveBypass
    ) {
        this.isEnabled = enabled
        this.targetLufs = target
        this.peakLimitEnabled = peakLimit
        this.negativeGainOnly = negativeGainOnly
        this.usbExclusiveBypass = usbExclusiveBypass
        if (!enabled || usbExclusiveBypass) {
            targetGainDb = 0.0
            currentGainDb = 0.0
            currentMultiplier = 1.0f
            rampActive = false
        } else {
            recalculateTargetGain()
        }
    }

    /**
     * Tier 2 后台 11×3s 稀疏探测完成后的无缝对数斜坡交接（默认 1.2s），随后锁定静态模式直至切歌
     */
    fun handoffToStaticGain(
        loudness: LoudnessData,
        rampDurationSec: Double = HANDOFF_RAMP_SEC
    ): EbuR128Calculator.GainResult {
        this.currentLoudnessData = loudness
        val gain = EbuR128Calculator.computeGain(
            loudness = loudness,
            targetLufs = targetLufs,
            peakLimitEnabled = peakLimitEnabled,
            negativeGainOnly = negativeGainOnly
        )
        setStaticGain(
            gainDb = gain.effectiveGainDb,
            multiplier = gain.linearMultiplier,
            isFinal = loudness.source.isFinalLocked,
            smoothSec = rampDurationSec,
            source = loudness.source
        )
        return gain
    }

    fun setStaticGain(
        gainDb: Float?,
        multiplier: Float,
        isFinal: Boolean = true,
        smoothSec: Double = 0.2,
        source: LoudnessSource = if (isFinal) LoudnessSource.BS1770_SCAN else LoudnessSource.INTRO_PREVIEW_SCAN
    ) {
        if (!isFinal && currentSource.priority > source.priority) {
            return
        }
        val clampedDb = if (gainDb != null) {
            if (negativeGainOnly && gainDb > 0.0f) 0.0f else gainDb
        } else {
            null
        }
        val effectiveMult = if (clampedDb != null) {
            10.0.pow(clampedDb.toDouble() / 20.0).toFloat()
        } else {
            if (negativeGainOnly) min(multiplier, 1.0f) else multiplier
        }

        this.currentSource = source
        this.staticGainDb = clampedDb
        this.currentMultiplier = effectiveMult

        if (isFinal) {
            this.isFinalLocked = true
            this.agcActive = false
            this.isFreezeGateActive = false
            this.hasEmittedStage1Preview = true
            this.hasEmittedMidRefine = true
        }

        if (isEnabled && !usbExclusiveBypass) {
            val destDb = clampedDb?.toDouble() ?: linearToDb(effectiveMult)
            this.targetGainDb = destDb
            val rampSec = smoothSec.coerceIn(0.05, 1.5)
            val totalRampFrames = (configuredSampleRate * rampSec).toInt().coerceAtLeast(1)
            this.rampStartDb = currentGainDb
            this.rampEndDb = destDb
            this.rampTotalFrames = totalRampFrames
            this.rampElapsedFrames = 0
            this.rampActive = true
        } else {
            this.targetGainDb = 0.0
            this.currentGainDb = 0.0
            this.rampActive = false
        }
    }

    fun resetForTrack(mediaId: String?, loudness: LoudnessData?) {
        this.currentMediaId = mediaId
        resetStreamingAnalyzer()
        resetDelayLines()
        this.rampActive = false
        this.isFreezeGateActive = false
        this.trackedMeanLufs = null
        this.latestShortTermLufs = null
        this.surgeFastEnergy = 0.0

        if (loudness != null && loudness.source != LoudnessSource.DEFAULT_FALLBACK) {
            val gain = EbuR128Calculator.computeGain(
                loudness = loudness,
                targetLufs = targetLufs,
                peakLimitEnabled = peakLimitEnabled,
                negativeGainOnly = negativeGainOnly
            )
            this.currentLoudnessData = loudness
            this.currentSource = loudness.source
            this.staticGainDb = gain.effectiveGainDb
            this.currentMultiplier = gain.linearMultiplier
            val startDb = if (isEnabled && !usbExclusiveBypass) gain.effectiveGainDb.toDouble() else 0.0
            this.targetGainDb = startDb
            this.currentGainDb = startDb
            this.isFinalLocked = loudness.source.isFinalLocked
            this.agcActive = !this.isFinalLocked && loudness.source.priority <= LoudnessSource.REALTIME_AGC.priority
            this.tier3RingActiveForTrack = this.agcActive
            this.hasEmittedStage1Preview = this.isFinalLocked || loudness.source == LoudnessSource.FAST_PREVIEW_SCAN
            this.hasEmittedMidRefine = this.isFinalLocked || loudness.source == LoudnessSource.FAST_PREVIEW_SCAN
            AppLogger.d(
                TAG,
                "Track '$mediaId' loudness preset (${loudness.source.label}): ${loudness.integratedLufs} LUFS, gain=${gain.effectiveGainDb} dB, locked=$isFinalLocked"
            )
        } else {
            this.currentLoudnessData = null
            this.currentSource = LoudnessSource.DEFAULT_FALLBACK
            this.staticGainDb = null
            this.currentMultiplier = 1.0f
            this.targetGainDb = 0.0
            this.currentGainDb = 0.0
            this.isFinalLocked = false
            this.agcActive = true
            this.tier3RingActiveForTrack = true
            this.hasEmittedStage1Preview = false
            this.hasEmittedMidRefine = false
            AppLogger.d(TAG, "Track '$mediaId' loudness unknown, armed Tier 3 Look-ahead AGC + Freeze Gate (target: $targetLufs LUFS)")
        }
    }

    private fun resetDelayLines() {
        tier3Ring.fill(0f)
        tier3RingWriteFrame = 0
        tier3RingFilledFrames = 0
        limiterRing.fill(0f)
        limiterReqGainRing.fill(1.0f)
        limiterDequeHead = 0
        limiterDequeTail = 0
        limiterWriteFrame = 0
        limiterFilledFrames = 0
        limiterAbsoluteFrame = 0L
        limiterGain = 1.0f
        limiterTruePeak4x.reset(configuredChannels)
    }

    private fun resetBiquadState() {
        kL1_x1 = 0.0; kL1_x2 = 0.0; kL1_y1 = 0.0; kL1_y2 = 0.0
        kL2_x1 = 0.0; kL2_x2 = 0.0; kL2_y1 = 0.0; kL2_y2 = 0.0
        kR1_x1 = 0.0; kR1_x2 = 0.0; kR1_y1 = 0.0; kR1_y2 = 0.0
        kR2_x1 = 0.0; kR2_x2 = 0.0; kR2_y1 = 0.0; kR2_y2 = 0.0
    }

    private fun resetStreamingAnalyzer() {
        resetBiquadState()
        synchronized(streamingBlockEnergies) {
            streamingBlockEnergies.clear()
        }
        subBlockEnergies.fill(0.0)
        subBlockCount = 0
        subBlockWriteIdx = 0
        shortTermWindow.fill(0.0)
        shortTermCount = 0
        shortTermWriteIdx = 0
        streamingActiveBlocks = 0
        streamingSubSum = 0.0
        streamingFrameCount = 0
        streamingTruePeak4x.reset(min(configuredChannels, 2))
        hasEmittedStage1Preview = false
        hasEmittedMidRefine = false
    }

    private fun recalculateTargetGain() {
        val data = currentLoudnessData
        if (data != null && data.source != LoudnessSource.DEFAULT_FALLBACK) {
            val gainResult = EbuR128Calculator.computeGain(
                loudness = data,
                targetLufs = targetLufs,
                peakLimitEnabled = peakLimitEnabled,
                negativeGainOnly = negativeGainOnly
            )
            staticGainDb = gainResult.effectiveGainDb
            currentMultiplier = gainResult.linearMultiplier
            targetGainDb = gainResult.effectiveGainDb.toDouble()
            rampStartDb = currentGainDb
            rampEndDb = targetGainDb
            rampTotalFrames = (configuredSampleRate * 0.25).toInt().coerceAtLeast(1)
            rampElapsedFrames = 0
            rampActive = true
            return
        }
        val staticDb = staticGainDb
        if (staticDb != null) {
            val gainResult = EbuR128Calculator.computeGain(
                loudness = LoudnessData(
                    targetLufs - staticDb,
                    1.0f,
                    currentSource.takeIf { it != LoudnessSource.DEFAULT_FALLBACK } ?: LoudnessSource.BS1770_SCAN
                ),
                targetLufs = targetLufs,
                peakLimitEnabled = peakLimitEnabled,
                negativeGainOnly = negativeGainOnly
            )
            currentMultiplier = gainResult.linearMultiplier
            targetGainDb = gainResult.effectiveGainDb.toDouble()
            rampActive = false
        } else {
            currentMultiplier = 1.0f
            targetGainDb = 0.0
            rampActive = false
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        if (bytesPerFrame <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val frameCount = remaining / bytesPerFrame
        if (frameCount <= 0) return

        val out = replaceOutputBuffer(remaining)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)

        // 1. 开关关闭或 USB 独占模式（Bit-Perfect）时：强制零延迟逐字节透传，彻底旁路所有均衡与限幅 DSP
        if (!isEnabled || usbExclusiveBypass) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val channels = inputAudioFormat.channelCount
        if (channels > channelScratch.size) {
            channelScratch = FloatArray(channels)
            delayedScratch = FloatArray(channels)
        }
        val encoding = inputAudioFormat.encoding
        val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)

        val attackAlpha = 1.0 - exp(-1.0 / (sampleRate * AGC_ATTACK_SEC))
        val releaseAlpha = 1.0 - exp(-1.0 / (sampleRate * AGC_RELEASE_SEC))
        val surgeAlpha = 1.0 - exp(-1.0 / (sampleRate * 0.040)) // 40ms 快速能量包络，配合 60ms Look-ahead 预判突增
        val limReleaseCoeff = exp(-1.0 / (sampleRate * LIMITER_RELEASE_SEC)).toFloat()
        val limAttackAlpha = (1.0 - exp(-1.0 / (sampleRate * 0.0012))).toFloat()

        val shouldAnalyzeStream = !isFinalLocked && currentMediaId != null && s1Coeffs != null && s2Coeffs != null
        val c1 = s1Coeffs
        val c2 = s2Coeffs
        val useTier3LookAhead = tier3RingActiveForTrack && tier3RingFrames > 0

        for (i in 0 until frameCount) {
            for (c in 0 until channels) {
                channelScratch[c] = readSampleNormalized(inputBuffer, encoding)
            }

            // A. K-Weighting 前瞻分析（在进入 Look-ahead 环形缓冲前完成，提前 60ms 感知能量突增）
            if (shouldAnalyzeStream && c1 != null && c2 != null) {
                val sL = channelScratch[0]
                val sR = if (channels > 1) channelScratch[1] else sL
                streamingTruePeak4x.processSample(0, sL)
                if (channels > 1) streamingTruePeak4x.processSample(1, sR)

                val y1L = c1.b0 * sL + c1.b1 * kL1_x1 + c1.b2 * kL1_x2 - c1.a1 * kL1_y1 - c1.a2 * kL1_y2
                kL1_x2 = kL1_x1; kL1_x1 = sL.toDouble(); kL1_y2 = kL1_y1; kL1_y1 = y1L
                val y2L = c2.b0 * y1L + c2.b1 * kL2_x1 + c2.b2 * kL2_x2 - c2.a1 * kL2_y1 - c2.a2 * kL2_y2
                kL2_x2 = kL2_x1; kL2_x1 = y1L; kL2_y2 = kL2_y1; kL2_y1 = y2L

                val y1R = c1.b0 * sR + c1.b1 * kR1_x1 + c1.b2 * kR1_x2 - c1.a1 * kR1_y1 - c1.a2 * kR1_y2
                kR1_x2 = kR1_x1; kR1_x1 = sR.toDouble(); kR1_y2 = kR1_y1; kR1_y1 = y1R
                val y2R = c2.b0 * y1R + c2.b1 * kR2_x1 + c2.b2 * kR2_x2 - c2.a1 * kR2_y1 - c2.a2 * kR2_y2
                kR2_x2 = kR2_x1; kR2_x1 = y1R; kR2_y2 = kR2_y1; kR2_y1 = y2R

                val instEnergy = y2L * y2L + y2R * y2R
                streamingSubSum += instEnergy
                streamingFrameCount++

                // 快速前瞻能量突增检测：若前方 60ms 内突发高能（如《ハテ》第 11s 骤升），立即下压目标增益
                if (agcActive) {
                    surgeFastEnergy += (instEnergy - surgeFastEnergy) * surgeAlpha
                    if (surgeFastEnergy > ABS_GATE_THRESHOLD_ENERGY * 100.0) {
                        val surgeLufs = EbuR128Calculator.energyToLufs(surgeFastEnergy)
                        val surgeRequiredDb = (targetLufs - surgeLufs).coerceIn(
                            EbuR128Calculator.MAX_REALTIME_ATTENUATION_DB,
                            if (negativeGainOnly) 0.0f else EbuR128Calculator.MAX_UNVERIFIED_POSITIVE_GAIN_DB
                        ).toDouble()
                        if (surgeRequiredDb < targetGainDb) {
                            targetGainDb = surgeRequiredDb
                            isFreezeGateActive = false
                        }
                    }
                }

                if (streamingFrameCount >= stepSizeFrames) {
                    onStreamingSubBlockCompleted()
                }
            }

            // B. Tier 3 60ms Look-ahead 环形缓冲读写
            if (useTier3LookAhead) {
                val baseIdx = tier3RingWriteFrame * channels
                for (c in 0 until channels) {
                    delayedScratch[c] = if (tier3RingFilledFrames >= tier3RingFrames) {
                        tier3Ring[baseIdx + c]
                    } else {
                        0.0f
                    }
                    tier3Ring[baseIdx + c] = channelScratch[c]
                }
                tier3RingWriteFrame++
                if (tier3RingWriteFrame >= tier3RingFrames) tier3RingWriteFrame = 0
                if (tier3RingFilledFrames < tier3RingFrames) tier3RingFilledFrames++
            } else {
                for (c in 0 until channels) {
                    delayedScratch[c] = channelScratch[c]
                }
            }

            // C. 计算当前帧增益（无缝对数斜坡 or 非对称 Attack/Release 包络）
            if (rampActive) {
                rampElapsedFrames++
                if (rampElapsedFrames >= rampTotalFrames) {
                    currentGainDb = rampEndDb
                    rampActive = false
                } else {
                    val t = rampElapsedFrames.toDouble() / rampTotalFrames.toDouble()
                    // dB 域线性插值 = 线性振幅域平滑对数斜坡，听感响度变化均匀无突跳
                    currentGainDb = rampStartDb + (rampEndDb - rampStartDb) * t
                }
            } else {
                val diff = targetGainDb - currentGainDb
                if (diff < -1e-6) {
                    // 快压 (Attack: 300ms)
                    currentGainDb += diff * attackAlpha
                } else if (diff > 1e-6) {
                    // 若未处于 Freeze Gate，执行慢放 (Release: 8s)
                    if (!isFreezeGateActive || !agcActive) {
                        currentGainDb += diff * releaseAlpha
                    }
                }
            }

            val linearGain = dbToLinear(currentGainDb)
            if (agcActive) {
                currentMultiplier = linearGain
            }

            for (c in 0 until channels) {
                delayedScratch[c] = delayedScratch[c] * linearGain
            }

            // D. 全局末端 4x 过采样 True Peak Limiter (-1.0 dBTP, Look-ahead 5ms, Release 75ms)
            if (peakLimitEnabled && limiterRingFrames > 0) {
                processTruePeakLimiterFrame(channels, limAttackAlpha, limReleaseCoeff)
            }

            for (c in 0 until channels) {
                writeSampleFromNormalized(out, delayedScratch[c], encoding)
            }
        }

        out.flip()
    }

    /**
     * 末端 4x 过采样真峰值限幅器：
     * - 测量当前帧 4x 过采样真峰值并计算所需衰减 reqGain；
     * - 经 5ms Look-ahead 环形缓冲与 O(1) 单调队列跟踪未来 5ms 最小增益；
     * - 峰值抵达输出端前平滑压低至 <= -1.0 dBTP，峰值过后按 75ms 释放恢复。
     */
    private fun processTruePeakLimiterFrame(
        channels: Int,
        attackAlpha: Float,
        releaseCoeff: Float
    ) {
        var maxTp = 0.0f
        for (c in 0 until channels) {
            val tp = limiterTruePeak4x.processSample(c, delayedScratch[c])
            if (tp > maxTp) maxTp = tp
        }
        val reqGain = if (maxTp > TRUE_PEAK_CEILING_LINEAR) {
            (TRUE_PEAK_CEILING_LINEAR / maxTp).coerceIn(0.05f, 1.0f)
        } else {
            1.0f
        }

        val slot = limiterWriteFrame
        val baseIdx = slot * channels
        val outReady = limiterFilledFrames >= limiterRingFrames
        val delayedReqGain = if (outReady) limiterReqGainRing[slot] else 1.0f

        // 取出 5ms 前暂存的音频帧，同时将当前帧写入环形缓冲
        for (c in 0 until channels) {
            val prev = if (outReady) limiterRing[baseIdx + c] else 0.0f
            limiterRing[baseIdx + c] = delayedScratch[c]
            delayedScratch[c] = prev
        }
        limiterReqGainRing[slot] = reqGain

        // 维护 5ms 窗口内的最小需求增益单调递增队列
        val dequeCap = limiterDequeIndices.size
        while (limiterDequeHead != limiterDequeTail) {
            val prevTail = if (limiterDequeTail == 0) dequeCap - 1 else limiterDequeTail - 1
            val prevSlot = limiterDequeIndices[prevTail]
            if (limiterReqGainRing[prevSlot] >= reqGain) {
                limiterDequeTail = prevTail
            } else {
                break
            }
        }
        limiterDequeIndices[limiterDequeTail] = slot
        limiterDequeTail = (limiterDequeTail + 1) % dequeCap

        limiterWriteFrame++
        if (limiterWriteFrame >= limiterRingFrames) limiterWriteFrame = 0
        if (limiterFilledFrames < limiterRingFrames) limiterFilledFrames++
        limiterAbsoluteFrame++

        // 剔除已滑出 5ms 窗口的旧索引（刚好等于当前刚被覆写的下一个 slot，或超过窗长）
        val oldestValidSlot = limiterWriteFrame
        if (outReady && limiterDequeHead != limiterDequeTail && limiterDequeIndices[limiterDequeHead] == oldestValidSlot) {
            limiterDequeHead = (limiterDequeHead + 1) % dequeCap
        }

        val windowMinGain = if (limiterDequeHead != limiterDequeTail) {
            limiterReqGainRing[limiterDequeIndices[limiterDequeHead]]
        } else {
            reqGain
        }

        // 前瞻快压 + 75ms 慢放 + 输出端硬兜底（确保输出真峰值严格 <= -1.0 dBTP）
        val targetLimGain = min(windowMinGain, delayedReqGain)
        limiterGain = if (targetLimGain < limiterGain) {
            val stepped = limiterGain + (targetLimGain - limiterGain) * attackAlpha
            min(stepped, delayedReqGain)
        } else {
            val released = targetLimGain - (targetLimGain - limiterGain) * releaseCoeff
            min(released, delayedReqGain)
        }.coerceIn(0.05f, 1.0f)

        for (c in 0 until channels) {
            var limited = delayedScratch[c] * limiterGain
            if (limited > TRUE_PEAK_CEILING_LINEAR) limited = TRUE_PEAK_CEILING_LINEAR
            else if (limited < -TRUE_PEAK_CEILING_LINEAR) limited = -TRUE_PEAK_CEILING_LINEAR
            delayedScratch[c] = limited
        }
    }

    /**
     * 每完成 100ms 子块：更新 400ms/75% 重叠瞬时块、3s 短时响度、Tier 3 Freeze Gate 与在线流式测算
     */
    private fun onStreamingSubBlockCompleted() {
        val subEnergy = streamingSubSum / stepSizeFrames
        streamingSubSum = 0.0
        streamingFrameCount = 0

        subBlockEnergies[subBlockWriteIdx] = subEnergy
        subBlockWriteIdx = (subBlockWriteIdx + 1) and 3
        if (subBlockCount < 4) subBlockCount++

        shortTermWindow[shortTermWriteIdx] = subEnergy
        shortTermWriteIdx = (shortTermWriteIdx + 1) % shortTermWindow.size
        if (shortTermCount < shortTermWindow.size) shortTermCount++

        if (subBlockCount < 4) return
        val blockEnergy = (subBlockEnergies[0] + subBlockEnergies[1] + subBlockEnergies[2] + subBlockEnergies[3]) * 0.25

        // 更新 Tier 3 实时短时响度与 Freeze Gate
        if (agcActive && !isFinalLocked) {
            var stSum = 0.0
            for (k in 0 until shortTermCount) stSum += shortTermWindow[k]
            val stMeanEnergy = stSum / shortTermCount
            val stLufs = EbuR128Calculator.energyToLufs(stMeanEnergy)
            latestShortTermLufs = stLufs

            val prevMean = trackedMeanLufs
            if (blockEnergy >= ABS_GATE_THRESHOLD_ENERGY) {
                if (prevMean == null) {
                    trackedMeanLufs = stLufs
                    isFreezeGateActive = false
                } else if (stLufs < prevMean - FREEZE_GATE_DROP_LU) {
                    // Freeze Gate 触发：短时响度跌落超过 11 LU（静音/人声独白/Breakdown/尾奏），冻结当前增益不再上调
                    isFreezeGateActive = true
                } else {
                    isFreezeGateActive = false
                    // 仅用非低谷有效块缓慢更新跟踪均值
                    trackedMeanLufs = prevMean * 0.90f + stLufs * 0.10f
                }

                if (!isFreezeGateActive) {
                    val refLufs = max(stLufs, trackedMeanLufs ?: stLufs)
                    val maxPosDb = if (negativeGainOnly) 0.0f else EbuR128Calculator.MAX_UNVERIFIED_POSITIVE_GAIN_DB
                    val desiredDb = (targetLufs - refLufs).coerceIn(
                        EbuR128Calculator.MAX_REALTIME_ATTENUATION_DB,
                        maxPosDb
                    ).toDouble()
                    targetGainDb = desiredDb
                    staticGainDb = desiredDb.toFloat()
                    if (currentSource == LoudnessSource.DEFAULT_FALLBACK) {
                        currentSource = LoudnessSource.REALTIME_AGC
                    }
                }
            } else {
                // 绝对静音段直接冻结
                isFreezeGateActive = true
            }
        }

        synchronized(streamingBlockEnergies) {
            streamingBlockEnergies.add(blockEnergy)
            if (blockEnergy >= ABS_GATE_THRESHOLD_ENERGY) {
                streamingActiveBlocks++
            }
            val activeCount = streamingActiveBlocks
            if (!hasEmittedStage1Preview && activeCount >= STAGE1_MIN_ACTIVE_BLOCKS) {
                hasEmittedStage1Preview = true
                emitStreamingEstimate(streamingBlockEnergies.toList(), isFinal = false)
            } else if (!hasEmittedMidRefine && activeCount >= STAGE1_REFINE_BLOCKS) {
                hasEmittedMidRefine = true
                emitStreamingEstimate(streamingBlockEnergies.toList(), isFinal = false)
            } else if (activeCount >= STAGE2_LOCK_BLOCKS && !isFinalLocked) {
                emitStreamingEstimate(streamingBlockEnergies.toList(), isFinal = true)
            }
        }
    }

    private fun emitStreamingEstimate(energies: List<Double>, isFinal: Boolean) {
        val mediaId = currentMediaId ?: return
        if (isFinalLocked) return
        val targetSource = if (isFinal) LoudnessSource.BS1770_SCAN else LoudnessSource.INTRO_PREVIEW_SCAN
        if (!isFinal && currentSource.priority > targetSource.priority) {
            return
        }

        val measured = EbuR128Calculator.computeIntegratedLoudnessFromBlockEnergies(
            energies,
            streamingTruePeak4x.maxTruePeak
        ).let {
            if (isFinal) it else it.copy(source = targetSource)
        }

        currentLoudnessData = measured
        val gain = EbuR128Calculator.computeGain(
            loudness = measured,
            targetLufs = targetLufs,
            peakLimitEnabled = peakLimitEnabled,
            negativeGainOnly = negativeGainOnly
        )
        if (isFinal) {
            setStaticGain(
                gainDb = gain.effectiveGainDb,
                multiplier = gain.linearMultiplier,
                isFinal = true,
                smoothSec = HANDOFF_RAMP_SEC,
                source = targetSource
            )
            AppLogger.i(TAG, "Streaming Stage 2 locked for '$mediaId': ${measured.integratedLufs} LUFS, gain=${gain.effectiveGainDb} dB")
            onLoudnessMeasured?.invoke(mediaId, measured)
        } else {
            this.currentSource = targetSource
            this.staticGainDb = gain.effectiveGainDb
            if (agcActive) {
                val cappedDb = gain.effectiveGainDb.toDouble()
                if (cappedDb < targetGainDb || !isFreezeGateActive) {
                    targetGainDb = cappedDb
                }
            } else {
                setStaticGain(
                    gainDb = gain.effectiveGainDb,
                    multiplier = gain.linearMultiplier,
                    isFinal = false,
                    smoothSec = 0.35,
                    source = targetSource
                )
            }
            AppLogger.i(TAG, "Streaming Tier 3 preview for '$mediaId': ${measured.integratedLufs} LUFS, gain=${gain.effectiveGainDb} dB")
            onPreviewLoudnessMeasured?.invoke(mediaId, measured)
        }
    }

    private fun linearToDb(linear: Float): Double {
        if (linear <= 1e-6f) return -120.0
        return 20.0 * log10(linear.toDouble())
    }

    private fun dbToLinear(db: Double): Float {
        return 10.0.pow(db / 20.0).toFloat()
    }

    private fun readSampleNormalized(buf: ByteBuffer, encoding: Int): Float = when (encoding) {
        C.ENCODING_PCM_FLOAT -> buf.float
        C.ENCODING_PCM_32BIT -> buf.int.toFloat() / 2147483648.0f
        C.ENCODING_PCM_24BIT -> {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt()
            ((b2 shl 16) or (b1 shl 8) or b0).toFloat() / 8388608.0f
        }
        else -> buf.short.toFloat() / 32768.0f
    }

    private fun writeSampleFromNormalized(out: ByteBuffer, sample: Float, encoding: Int) {
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> out.putFloat(sample.coerceIn(-1.0f, 1.0f))
            C.ENCODING_PCM_32BIT -> {
                val clamped = sample.coerceIn(-1.0f, 1.0f)
                val v = (clamped * 2147483647.0).toLong().coerceIn(-2147483648L, 2147483647L).toInt()
                out.putInt(v)
            }
            C.ENCODING_PCM_24BIT -> {
                val clamped = sample.coerceIn(-1.0f, 1.0f)
                val v = (clamped * 8388607.0f).toInt().coerceIn(-8388608, 8388607)
                out.put((v and 0xFF).toByte())
                out.put(((v ushr 8) and 0xFF).toByte())
                out.put(((v ushr 16) and 0xFF).toByte())
            }
            else -> {
                val intSample = (sample * 32767.0f).toInt().coerceIn(-32768, 32767)
                out.putShort(intSample.toShort())
            }
        }
    }

    override fun onFlush() {
        super.onFlush()
        resetBiquadState()
        resetDelayLines()
        rampActive = false
        if (isEnabled && !usbExclusiveBypass) {
            currentGainDb = targetGainDb
        } else {
            currentGainDb = 0.0
        }
    }

    override fun onReset() {
        super.onReset()
        // 重要：DefaultAudioSink 切换采样率重配管线时会调用 reset()，
        // 保留当前曲目目标增益与锁定状态，仅重置滤波器与延迟线。
        resetBiquadState()
        resetDelayLines()
    }
}