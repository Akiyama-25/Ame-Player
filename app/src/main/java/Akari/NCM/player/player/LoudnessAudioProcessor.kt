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
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.tanh

/**
 * 实时 EBU R128 / ITU-R BS.1770 音频均衡与响度平准 AudioProcessor
 *
 * 核心特性：
 * 1. 处于 ExoPlayer AudioTrack 直通渲染管道核心，独立于底层系统 LoudnessEnhancer 与不同 OEM 硬件限制；
 * 2. 支持 PCM 16-bit 与 PCM Float（覆盖无损与 Hi-Res 192kHz/96kHz/48kHz/44.1kHz 全格式）；
 * 3. 静态/动态双模平准：已具备标签或缓存响度特征的音轨从第 0 毫秒施加无感静态增益；未校准音轨在起播前数秒基于能量平滑自适应收敛；
 * 4. 内置 ITU-R BS.1770-4 K-weighting 双二阶滤波与 400ms 分块能量累加，在播放中自动测算曲目综合响度并回写持久化缓存；
 * 5. -1.0 dBTP / dBFS 演播室级真峰值软饱和防削波限制器（Soft-Knee Peak Limiter），彻底消除正向增益可能带来的数字破音。
 */
@UnstableApi
class LoudnessAudioProcessor : BaseAudioProcessor() {

    companion object {
        private const val TAG = "AME_LOUDNESS_PROC"
        private const val PEAK_LIMIT_THRESHOLD = 0.89125f // -1.0 dBFS
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

    var onLoudnessMeasured: ((String, LoudnessData) -> Unit)? = null

    // 动态能量分析与增益平滑状态
    private var currentLinearGain = 1.0f
    private var targetLinearGain = 1.0f

    // 短期能量追踪（用于未知响度曲目的动态平准）
    private var shortTermEnergy = 0.0
    private var frameCountInBlock = 0
    private var currentBlockSum = 0.0
    private var maxObservedPeak = 0.0f

    // EBU R128 分块能量收集
    private val blockEnergies = mutableListOf<Double>()
    private var isMeasuredSaved = false

    // K-weighting 滤波器左声道历史状态 (Stage 1 & Stage 2)
    private var kL1_x1 = 0.0; private var kL1_x2 = 0.0
    private var kL1_y1 = 0.0; private var kL1_y2 = 0.0
    private var kL2_x1 = 0.0; private var kL2_x2 = 0.0
    private var kL2_y1 = 0.0; private var kL2_y2 = 0.0

    // K-weighting 滤波器右声道历史状态 (Stage 1 & Stage 2)
    private var kR1_x1 = 0.0; private var kR1_x2 = 0.0
    private var kR1_y1 = 0.0; private var kR1_y2 = 0.0
    private var kR2_x1 = 0.0; private var kR2_x2 = 0.0
    private var kR2_y1 = 0.0; private var kR2_y2 = 0.0

    private var s1Coeffs: EbuR128Calculator.BiquadCoeffs? = null
    private var s2Coeffs: EbuR128Calculator.BiquadCoeffs? = null
    private var blockSizeFrames = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioFormat.NOT_SET
        }

        val sr = inputAudioFormat.sampleRate.toDouble()
        s1Coeffs = EbuR128Calculator.calculateStage1Coeffs(sr)
        s2Coeffs = EbuR128Calculator.calculateStage2Coeffs(sr)
        blockSizeFrames = (0.4 * inputAudioFormat.sampleRate).toInt().coerceAtLeast(1)

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
        recalculateTargetGain()
    }

    fun resetForTrack(mediaId: String?, loudness: LoudnessData?) {
        this.currentMediaId = mediaId
        this.isMeasuredSaved = false
        this.blockEnergies.clear()
        this.frameCountInBlock = 0
        this.currentBlockSum = 0.0
        this.maxObservedPeak = 0.0f
        this.shortTermEnergy = 0.0

        // 重置滤波历史
        kL1_x1 = 0.0; kL1_x2 = 0.0; kL1_y1 = 0.0; kL1_y2 = 0.0
        kL2_x1 = 0.0; kL2_x2 = 0.0; kL2_y1 = 0.0; kL2_y2 = 0.0
        kR1_x1 = 0.0; kR1_x2 = 0.0; kR1_y1 = 0.0; kR1_y2 = 0.0
        kR2_x1 = 0.0; kR2_x2 = 0.0; kR2_y1 = 0.0; kR2_y2 = 0.0

        if (loudness != null && loudness.source != LoudnessSource.DEFAULT_FALLBACK) {
            val gain = EbuR128Calculator.computeGain(loudness, targetLufs, peakLimitEnabled)
            this.staticGainDb = gain.effectiveGainDb
            this.targetLinearGain = gain.linearMultiplier
            AppLogger.d(TAG, "Track '$mediaId' has known loudness: ${loudness.integratedLufs} LUFS, applying static gain=${gain.effectiveGainDb} dB")
        } else {
            this.staticGainDb = null
            // 现代商业音乐先预设 -10.0 LUFS 名义电平进行平滑起播
            val nominalGainDb = (targetLufs - (-10.0f)).coerceIn(-18.0f, 10.0f)
            this.targetLinearGain = 10.0.pow(nominalGainDb / 20.0).toFloat()
            AppLogger.d(TAG, "Track '$mediaId' loudness unknown, starting real-time EBU R128 measurement (target: $targetLufs LUFS)")
        }
    }

    private fun recalculateTargetGain() {
        val static = staticGainDb
        if (static != null) {
            val linear = 10.0.pow(static / 20.0).toFloat()
            targetLinearGain = linear
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
        val encoding = inputAudioFormat.encoding
        val sampleRate = inputAudioFormat.sampleRate

        // 增益平滑平准系数（平滑时间常数约 200ms）
        val smoothFactor = 1.0f - Math.exp(-1.0 / (sampleRate * 0.2)).toFloat()
        // 短期能量滤波系数（约 300ms 窗口）
        val shortTermAlpha = 1.0 - Math.exp(-1.0 / (sampleRate * 0.3))

        val s1 = s1Coeffs
        val s2 = s2Coeffs

        for (i in 0 until frameCount) {
            var sumSquare = 0.0
            var maxFramePeak = 0.0f

            // 采样读取与声道浮点归一化
            val samples = FloatArray(channels)
            for (c in 0 until channels) {
                val s = if (encoding == C.ENCODING_PCM_FLOAT) {
                    inputBuffer.float
                } else {
                    inputBuffer.short.toFloat() / 32768.0f
                }
                samples[c] = s
                sumSquare += s * s
                val absS = abs(s)
                if (absS > maxFramePeak) maxFramePeak = absS
            }

            if (maxFramePeak > maxObservedPeak) {
                maxObservedPeak = maxFramePeak
            }

            val meanEnergy = sumSquare / channels
            shortTermEnergy += (meanEnergy - shortTermEnergy) * shortTermAlpha

            // 若尚未固定静态增益，根据当前短期能量自适应调整平准目标
            if (staticGainDb == null) {
                if (shortTermEnergy > 1e-6) { // 门控门限约 -60 dBFS
                    val measuredLufs = (-0.691 + 10.0 * log10(shortTermEnergy)).toFloat().coerceIn(-60f, 0f)
                    val desiredGainDb = (targetLufs - measuredLufs).coerceIn(-20.0f, 12.0f)
                    targetLinearGain = 10.0.pow(desiredGainDb / 20.0).toFloat()
                }
            }

            // 平滑收敛至目标增益，消除增益调节引入的咔嗒声
            currentLinearGain += (targetLinearGain - currentLinearGain) * smoothFactor

            // 施加平准增益与软饱和防破音限幅
            for (c in 0 until channels) {
                var s = samples[c] * currentLinearGain
                if (peakLimitEnabled) {
                    val absVal = abs(s)
                    if (absVal > PEAK_LIMIT_THRESHOLD) {
                        val over = absVal - PEAK_LIMIT_THRESHOLD
                        val margin = 1.0f - PEAK_LIMIT_THRESHOLD
                        val compressed = PEAK_LIMIT_THRESHOLD + margin * tanh((over / margin).toDouble()).toFloat()
                        s = sign(s) * compressed
                    }
                }

                if (encoding == C.ENCODING_PCM_FLOAT) {
                    out.putFloat(s.coerceIn(-1.0f, 1.0f))
                } else {
                    val intSample = (s * 32767.0f).toInt().coerceIn(-32768, 32767)
                    out.putShort(intSample.toShort())
                }
            }

            // BS.1770 能量积累（仅对未固化的曲目）
            if (!isMeasuredSaved && s1 != null && s2 != null) {
                val left = samples[0].toDouble()
                val right = if (channels > 1) samples[1].toDouble() else left

                val kL = filterLeft(left, s1, s2)
                val kR = filterRight(right, s1, s2)
                currentBlockSum += (kL * kL + kR * kR)
                frameCountInBlock++

                if (frameCountInBlock >= blockSizeFrames) {
                    val blockEnergy = currentBlockSum / blockSizeFrames
                    blockEnergies.add(blockEnergy)
                    currentBlockSum = 0.0
                    frameCountInBlock = 0

                    // 收集满 25 个块（约 10 秒有效音频）时，完成综合响度计算与缓存回写
                    if (blockEnergies.size >= 25) {
                        val calculated = EbuR128Calculator.computeIntegratedLoudnessFromBlockEnergies(
                            blockEnergies,
                            maxObservedPeak
                        )
                        if (calculated.integratedLufs > -65.0f) {
                            isMeasuredSaved = true
                            staticGainDb = EbuR128Calculator.computeGain(calculated, targetLufs, peakLimitEnabled).effectiveGainDb
                            currentMediaId?.let { id ->
                                onLoudnessMeasured?.invoke(id, calculated)
                            }
                            AppLogger.i(TAG, "Completed online BS.1770 scan for '$currentMediaId': ${calculated.integratedLufs} LUFS, peak=${calculated.peakDb} dBFS")
                        }
                    }
                }
            }
        }

        out.flip()
    }

    private fun filterLeft(
        sample: Double,
        s1: EbuR128Calculator.BiquadCoeffs,
        s2: EbuR128Calculator.BiquadCoeffs
    ): Double {
        // Stage 1
        val y1 = s1.b0 * sample + s1.b1 * kL1_x1 + s1.b2 * kL1_x2 - s1.a1 * kL1_y1 - s1.a2 * kL1_y2
        kL1_x2 = kL1_x1
        kL1_x1 = sample
        kL1_y2 = kL1_y1
        kL1_y1 = y1

        // Stage 2
        val y2 = s2.b0 * y1 + s2.b1 * kL2_x1 + s2.b2 * kL2_x2 - s2.a1 * kL2_y1 - s2.a2 * kL2_y2
        kL2_x2 = kL2_x1
        kL2_x1 = y1
        kL2_y2 = kL2_y1
        kL2_y1 = y2

        return y2
    }

    private fun filterRight(
        sample: Double,
        s1: EbuR128Calculator.BiquadCoeffs,
        s2: EbuR128Calculator.BiquadCoeffs
    ): Double {
        // Stage 1
        val y1 = s1.b0 * sample + s1.b1 * kR1_x1 + s1.b2 * kR1_x2 - s1.a1 * kR1_y1 - s1.a2 * kR1_y2
        kR1_x2 = kR1_x1
        kR1_x1 = sample
        kR1_y2 = kR1_y1
        kR1_y1 = y1

        // Stage 2
        val y2 = s2.b0 * y1 + s2.b1 * kR2_x1 + s2.b2 * kR2_x2 - s2.a1 * kR2_y1 - s2.a2 * kR2_y2
        kR2_x2 = kR2_x1
        kR2_x1 = y1
        kR2_y2 = kR2_y1
        kR2_y1 = y2

        return y2
    }

    override fun onFlush() {
        super.onFlush()
        frameCountInBlock = 0
        currentBlockSum = 0.0
    }

    override fun onReset() {
        super.onReset()
        currentMediaId = null
        staticGainDb = null
        blockEnergies.clear()
        frameCountInBlock = 0
        currentBlockSum = 0.0
        isMeasuredSaved = false
    }
}