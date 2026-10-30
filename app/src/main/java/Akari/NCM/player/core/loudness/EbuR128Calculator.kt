package Akari.NCM.player.core.loudness

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * EBU R128 / ITU-R BS.1770-4 综合响度计算与音量均衡引擎
 *
 * 核心规范依据：
 * 1. EBU R128: Loudness normalisation and permitted maximum level of audio signals
 * 2. ITU-R BS.1770-4: Algorithms to measure audio programme loudness and true-peak audio level
 */
object EbuR128Calculator {
    const val TARGET_STREAMING_LUFS = -14.0f  // 现代流媒体移动端标准（推荐，适合耳机与移动设备）
    const val TARGET_EBU_R128_LUFS = -23.0f  // EBU R128 国际广电标准（保留宽广动态范围）
    const val TARGET_STUDIO_LUFS = -16.0f    // Apple Sound Check / 录音室标准
    const val TRUE_PEAK_LIMIT_DBTP = -1.0f    // EBU R128 准许最大真峰值电平 (-1.0 dBTP)

    data class GainResult(
        val rawGainDb: Float,          // 目标响度 - 综合响度的原始增益 (dB)
        val effectiveGainDb: Float,    // 经防破音真峰值压制后的实际生效增益 (dB)
        val linearMultiplier: Float,   // 转换后的线性音量放大乘数
        val isPeakLimited: Boolean     // 是否因峰值过高触发了防过载削波压制
    )

    /**
     * 根据 EBU R128 准则与目标响度电平计算音量均衡增益
     *
     * @param loudness 当前歌曲的综合响度特征数据
     * @param targetLufs 目标综合响度（如 -14.0 LUFS 或 -23.0 LUFS）
     * @param peakLimitEnabled 是否启用 -1.0 dBTP 防过载压制保护
     */
    fun computeGain(
        loudness: LoudnessData,
        targetLufs: Float,
        peakLimitEnabled: Boolean = true
    ): GainResult {
        val rawGainDb = targetLufs - loudness.integratedLufs

        var effectiveGainDb = rawGainDb
        var isPeakLimited = false

        if (peakLimitEnabled && loudness.truePeak > 0f) {
            val peakDb = loudness.peakDb
            // 若原始正增益施加后会使峰值突破 -1.0 dBTP，则将增益限制在安全余量内
            val maxAllowedGainDb = TRUE_PEAK_LIMIT_DBTP - peakDb
            if (rawGainDb > maxAllowedGainDb) {
                effectiveGainDb = maxAllowedGainDb
                isPeakLimited = true
            }
        }

        // 设定合理的防破音与防噪声增益上下限 [-24.0 dB, +15.0 dB]
        effectiveGainDb = effectiveGainDb.coerceIn(-24.0f, 15.0f)
        val linearMultiplier = 10.0.pow(effectiveGainDb.toDouble() / 20.0).toFloat()

        return GainResult(
            rawGainDb = rawGainDb,
            effectiveGainDb = effectiveGainDb,
            linearMultiplier = linearMultiplier,
            isPeakLimited = isPeakLimited
        )
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
     * 基于 ITU-R BS.1770-4 规范计算立体声 PCM 数据的综合响度（Integrated Loudness LUFS）与峰值（Peak）
     *
     * @param leftChannel 左声道归一化浮点样本 [-1.0f, 1.0f]
     * @param rightChannel 右声道归一化浮点样本 [-1.0f, 1.0f]
     * @param sampleRate 采样率（Hz，如 44100 或 48000）
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

        // 1. 计算样本绝对峰值 (Sample Peak)
        var maxPeak = 0.0f
        for (i in 0 until length) {
            val absL = abs(leftChannel[i])
            val absR = abs(rightChannel[i])
            if (absL > maxPeak) maxPeak = absL
            if (absR > maxPeak) maxPeak = absR
        }

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
            // ITU-R BS.1770 权重：左声道 G_L = 1.0，右声道 G_R = 1.0
            val totalEnergy = meanL + meanR
            blockEnergies.add(totalEnergy)
            offset += stepSize
        }

        if (blockEnergies.isEmpty()) {
            return LoudnessData(-70.0f, maxPeak, LoudnessSource.BS1770_SCAN)
        }

        // 4. 双重门限门控（Gating）
        // 绝对门限 (Absolute Threshold): -70 LKFS
        val absThresholdEnergy = 10.0.pow((-70.0 + 0.691) / 10.0)
        val absGatedEnergies = blockEnergies.filter { it >= absThresholdEnergy }

        if (absGatedEnergies.isEmpty()) {
            return LoudnessData(-70.0f, maxPeak, LoudnessSource.BS1770_SCAN)
        }

        // 相对门限 (Relative Threshold): 绝对门控能量均值 - 10 LU
        val meanAbsGated = absGatedEnergies.average()
        val relativeLoudness = -0.691 + 10.0 * log10(meanAbsGated)
        val relThresholdLoudness = relativeLoudness - 10.0
        val relThresholdEnergy = 10.0.pow((relThresholdLoudness + 0.691) / 10.0)

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
}