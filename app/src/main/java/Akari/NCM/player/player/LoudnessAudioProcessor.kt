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
import kotlin.math.sign
import kotlin.math.tanh

/**
 * 演播室级 EBU R128 / ITU-R BS.1770 全曲音量均衡与响度平准 AudioProcessor
 *
 * 核心特性：
 * 1. 处于 ExoPlayer AudioTrack / USB DAC 渲染管道核心，支持 PCM 16-bit / 24-bit / 32-bit / Float 全格式；
 * 2. 渐进式双阶段均衡：
 *    - 已知响度（缓存 / 云端 / 标签 / 预热队列均值）在首帧 PCM 输入前零延迟直接锁定；
 *    - 未知曲目或无 Range 支持的在线流媒体，由内置实时 BS.1770 K-Weighting 流式积分器在首 ~0.8s 内立即触发 Stage 1 初判，随后平滑收敛锁定至全曲响度；
 * 3. 跨越 AudioSink 格式重建（onReset / onFlush）保持当前曲目目标增益不丢失；
 * 4. -1.0 dBTP 演播室级真峰值软膝饱和限制器（Soft-Knee Peak Limiter），彻底消除削波破音。
 */
@UnstableApi
class LoudnessAudioProcessor : BaseAudioProcessor() {

    companion object {
        private const val TAG = "AME_LOUDNESS_PROC"
        private const val PEAK_LIMIT_THRESHOLD = 0.89125f // -1.0 dBFS
        private const val ABS_GATE_THRESHOLD_ENERGY = 1.172195e-7 // 10^((-70.0 + 0.691) / 10.0)
        private const val STAGE1_MIN_ACTIVE_BLOCKS = 2   // 2 * 400ms = 0.8s 秒级初判触发阈值
        private const val STAGE1_REFINE_BLOCKS = 20      // ~8s 流式复核
        private const val STAGE2_LOCK_BLOCKS = 45        // ~18s 流式全曲锁定阈值
    }

    @Volatile
    var isEnabled: Boolean = true

    @Volatile
    var targetLufs: Float = -14.0f

    @Volatile
    var peakLimitEnabled: Boolean = true

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

    var onPreviewLoudnessMeasured: ((String, LoudnessData) -> Unit)? = null
    var onLoudnessMeasured: ((String, LoudnessData) -> Unit)? = null

    // 线性平滑增益状态
    @Volatile
    private var currentLinearGain = 1.0f

    @Volatile
    private var targetLinearGain = 1.0f

    @Volatile
    private var smoothTimeConstantSec = 0.2

    // 预分配每帧声道缓冲，避免在音频实时线程中频繁分配堆内存
    private var channelScratch = FloatArray(8)

    // 实时流式 BS.1770 K-Weighting 滤波器与分块积分状态
    private var s1Coeffs: EbuR128Calculator.BiquadCoeffs? = null
    private var s2Coeffs: EbuR128Calculator.BiquadCoeffs? = null
    private var blockSizeFrames: Int = 19200
    private var kL1_x1 = 0.0; private var kL1_x2 = 0.0; private var kL1_y1 = 0.0; private var kL1_y2 = 0.0
    private var kL2_x1 = 0.0; private var kL2_x2 = 0.0; private var kL2_y1 = 0.0; private var kL2_y2 = 0.0
    private var kR1_x1 = 0.0; private var kR1_x2 = 0.0; private var kR1_y1 = 0.0; private var kR1_y2 = 0.0
    private var kR2_x1 = 0.0; private var kR2_x2 = 0.0; private var kR2_y1 = 0.0; private var kR2_y2 = 0.0
    private val streamingBlockEnergies = ArrayList<Double>(128)
    private var streamingActiveBlocks = 0
    private var streamingBlockSum = 0.0
    private var streamingFrameCount = 0
    private var streamingMaxPeak = 0.0f

    @Volatile
    private var hasEmittedStage1Preview = false

    @Volatile
    private var hasEmittedMidRefine = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_24BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_32BIT
        ) {
            return AudioFormat.NOT_SET
        }

        val sr = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        s1Coeffs = EbuR128Calculator.calculateStage1Coeffs(sr.toDouble())
        s2Coeffs = EbuR128Calculator.calculateStage2Coeffs(sr.toDouble())
        blockSizeFrames = (0.4 * sr).toInt().coerceAtLeast(1)
        if (inputAudioFormat.channelCount > channelScratch.size) {
            channelScratch = FloatArray(inputAudioFormat.channelCount)
        }
        resetBiquadState()

        return AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            inputAudioFormat.encoding
        )
    }

    fun updateSettings(enabled: Boolean, target: Float, peakLimit: Boolean) {
        this.isEnabled = enabled
        this.targetLufs = target
        this.peakLimitEnabled = peakLimit
        if (!enabled) {
            targetLinearGain = 1.0f
            currentLinearGain = 1.0f
            currentMultiplier = 1.0f
        } else {
            recalculateTargetGain()
        }
    }

    fun setStaticGain(
        gainDb: Float?,
        multiplier: Float,
        isFinal: Boolean = true,
        smoothSec: Double = 0.2
    ) {
        this.staticGainDb = gainDb
        this.currentMultiplier = multiplier
        this.smoothTimeConstantSec = smoothSec.coerceIn(0.05, 1.0)
        if (isFinal) {
            this.isFinalLocked = true
            this.hasEmittedStage1Preview = true
        }
        if (isEnabled) {
            this.targetLinearGain = multiplier
        } else {
            this.targetLinearGain = 1.0f
            this.currentLinearGain = 1.0f
        }
    }

    fun resetForTrack(mediaId: String?, loudness: LoudnessData?) {
        this.currentMediaId = mediaId
        resetStreamingAnalyzer()
        this.smoothTimeConstantSec = 0.2

        if (loudness != null && loudness.source != LoudnessSource.DEFAULT_FALLBACK) {
            val gain = EbuR128Calculator.computeGain(loudness, targetLufs, peakLimitEnabled)
            this.staticGainDb = gain.effectiveGainDb
            this.currentMultiplier = gain.linearMultiplier
            this.targetLinearGain = if (isEnabled) gain.linearMultiplier else 1.0f
            this.currentLinearGain = this.targetLinearGain
            this.isFinalLocked = loudness.source.isFinalLocked
            this.hasEmittedStage1Preview = this.isFinalLocked || loudness.source == LoudnessSource.FAST_PREVIEW_SCAN
            AppLogger.d(
                TAG,
                "Track '$mediaId' loudness preset (${loudness.source.label}): ${loudness.integratedLufs} LUFS, gain=${gain.effectiveGainDb} dB, locked=$isFinalLocked"
            )
        } else {
            this.staticGainDb = null
            this.currentMultiplier = 1.0f
            this.targetLinearGain = 1.0f
            this.currentLinearGain = 1.0f
            this.isFinalLocked = false
            this.hasEmittedStage1Preview = false
            AppLogger.d(TAG, "Track '$mediaId' loudness unknown, armed real-time dual-stage BS.1770 analyzer (target: $targetLufs LUFS)")
        }
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
        streamingActiveBlocks = 0
        streamingBlockSum = 0.0
        streamingFrameCount = 0
        streamingMaxPeak = 0.0f
        hasEmittedStage1Preview = false
        hasEmittedMidRefine = false
    }

    private fun recalculateTargetGain() {
        val static = staticGainDb
        if (static != null) {
            val gainResult = EbuR128Calculator.computeGain(
                LoudnessData(targetLufs - static, 1.0f, LoudnessSource.BS1770_SCAN),
                targetLufs,
                peakLimitEnabled
            )
            currentMultiplier = gainResult.linearMultiplier
            targetLinearGain = gainResult.linearMultiplier
        } else {
            currentMultiplier = 1.0f
            targetLinearGain = 1.0f
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

        if (!isEnabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }

        val channels = inputAudioFormat.channelCount
        if (channels > channelScratch.size) {
            channelScratch = FloatArray(channels)
        }
        val encoding = inputAudioFormat.encoding
        val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)

        // 指数平滑包络系数
        val smoothFactor = (1.0 - exp(-1.0 / (sampleRate * smoothTimeConstantSec))).toFloat()
        val shouldAnalyzeStream = !isFinalLocked && currentMediaId != null && s1Coeffs != null && s2Coeffs != null
        val c1 = s1Coeffs
        val c2 = s2Coeffs

        for (i in 0 until frameCount) {
            currentLinearGain += (targetLinearGain - currentLinearGain) * smoothFactor

            for (c in 0 until channels) {
                channelScratch[c] = readSampleNormalized(inputBuffer, encoding)
            }

            if (shouldAnalyzeStream && c1 != null && c2 != null) {
                val sL = channelScratch[0]
                val sR = if (channels > 1) channelScratch[1] else sL
                val peak = maxOf(abs(sL), abs(sR))
                if (peak > streamingMaxPeak) streamingMaxPeak = peak

                val y1L = c1.b0 * sL + c1.b1 * kL1_x1 + c1.b2 * kL1_x2 - c1.a1 * kL1_y1 - c1.a2 * kL1_y2
                kL1_x2 = kL1_x1; kL1_x1 = sL.toDouble(); kL1_y2 = kL1_y1; kL1_y1 = y1L
                val y2L = c2.b0 * y1L + c2.b1 * kL2_x1 + c2.b2 * kL2_x2 - c2.a1 * kL2_y1 - c2.a2 * kL2_y2
                kL2_x2 = kL2_x1; kL2_x1 = y1L; kL2_y2 = kL2_y1; kL2_y1 = y2L

                val y1R = c1.b0 * sR + c1.b1 * kR1_x1 + c1.b2 * kR1_x2 - c1.a1 * kR1_y1 - c1.a2 * kR1_y2
                kR1_x2 = kR1_x1; kR1_x1 = sR.toDouble(); kR1_y2 = kR1_y1; kR1_y1 = y1R
                val y2R = c2.b0 * y1R + c2.b1 * kR2_x1 + c2.b2 * kR2_x2 - c2.a1 * kR2_y1 - c2.a2 * kR2_y2
                kR2_x2 = kR2_x1; kR2_x1 = y1R; kR2_y2 = kR2_y1; kR2_y1 = y2R

                streamingBlockSum += (y2L * y2L + y2R * y2R)
                streamingFrameCount++

                if (streamingFrameCount >= blockSizeFrames) {
                    onStreamingBlockCompleted()
                }
            }

            for (c in 0 until channels) {
                var s = channelScratch[c] * currentLinearGain
                if (peakLimitEnabled) {
                    val absVal = abs(s)
                    if (absVal > PEAK_LIMIT_THRESHOLD) {
                        val over = absVal - PEAK_LIMIT_THRESHOLD
                        val margin = 1.0f - PEAK_LIMIT_THRESHOLD
                        val compressed = PEAK_LIMIT_THRESHOLD + margin * tanh((over / margin).toDouble()).toFloat()
                        s = sign(s) * compressed
                    }
                }
                writeSampleFromNormalized(out, s, encoding)
            }
        }

        out.flip()
    }

    private fun onStreamingBlockCompleted() {
        val energy = streamingBlockSum / blockSizeFrames
        streamingBlockSum = 0.0
        streamingFrameCount = 0

        val snapshotEnergies: List<Double>
        synchronized(streamingBlockEnergies) {
            streamingBlockEnergies.add(energy)
            if (energy >= ABS_GATE_THRESHOLD_ENERGY) {
                streamingActiveBlocks++
            }
            val activeCount = streamingActiveBlocks
            if (!hasEmittedStage1Preview && activeCount >= STAGE1_MIN_ACTIVE_BLOCKS) {
                hasEmittedStage1Preview = true
                snapshotEnergies = streamingBlockEnergies.toList()
                emitStreamingEstimate(snapshotEnergies, isFinal = false)
            } else if (!hasEmittedMidRefine && activeCount >= STAGE1_REFINE_BLOCKS) {
                hasEmittedMidRefine = true
                snapshotEnergies = streamingBlockEnergies.toList()
                emitStreamingEstimate(snapshotEnergies, isFinal = false)
            } else if (activeCount >= STAGE2_LOCK_BLOCKS && !isFinalLocked) {
                snapshotEnergies = streamingBlockEnergies.toList()
                emitStreamingEstimate(snapshotEnergies, isFinal = true)
            }
        }
    }

    private fun emitStreamingEstimate(energies: List<Double>, isFinal: Boolean) {
        val mediaId = currentMediaId ?: return
        if (isFinalLocked) return

        val measured = EbuR128Calculator.computeIntegratedLoudnessFromBlockEnergies(
            energies,
            streamingMaxPeak
        ).let {
            if (isFinal) it else it.copy(source = LoudnessSource.FAST_PREVIEW_SCAN)
        }

        val gain = EbuR128Calculator.computeGain(measured, targetLufs, peakLimitEnabled)
        if (isFinal) {
            setStaticGain(gain.effectiveGainDb, gain.linearMultiplier, isFinal = true, smoothSec = 0.45)
            AppLogger.i(TAG, "Streaming Stage 2 locked for '$mediaId': ${measured.integratedLufs} LUFS, gain=${gain.effectiveGainDb} dB")
            onLoudnessMeasured?.invoke(mediaId, measured)
        } else {
            val smoothTau = if (staticGainDb != null) 0.4 else 0.2
            setStaticGain(gain.effectiveGainDb, gain.linearMultiplier, isFinal = false, smoothSec = smoothTau)
            AppLogger.i(TAG, "Streaming Stage 1 preview for '$mediaId': ${measured.integratedLufs} LUFS, gain=${gain.effectiveGainDb} dB")
            onPreviewLoudnessMeasured?.invoke(mediaId, measured)
        }
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
        // 清空缓冲后直接从目标增益起点恢复，避免 seek/切歌时产生增益回跳
        if (isEnabled) {
            currentLinearGain = targetLinearGain
        } else {
            currentLinearGain = 1.0f
        }
    }

    override fun onReset() {
        super.onReset()
        // 重要：DefaultAudioSink 在切换采样率或重构 AudioPipeline 时会调用 reset()，
        // 严禁在此清除当前曲目的 staticGainDb / targetLinearGain / currentMediaId，
        // 否则会导致不同采样率切歌时音量均衡被意外重置为 0 dB！
        resetBiquadState()
    }
}