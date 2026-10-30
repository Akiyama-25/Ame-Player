package Akari.NCM.player.core.loudness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class EbuR128CalculatorTest {

    @Test
    fun `测试ITU-R BS1770滤波器系数计算`() {
        val fs = 48000.0
        val s1 = EbuR128Calculator.calculateStage1Coeffs(fs)
        // 验证预滤波 Stage 1 (High-shelf +4dB)
        assertTrue(s1.b0 > 1.5 && s1.b0 < 1.55)
        assertTrue(s1.b1 < -2.65 && s1.b1 > -2.7)
        assertTrue(s1.b2 > 1.15 && s1.b2 < 1.25)
        assertTrue(s1.a1 < -1.65 && s1.a1 > -1.7)
        assertTrue(s1.a2 > 0.7 && s1.a2 < 0.75)

        val s2 = EbuR128Calculator.calculateStage2Coeffs(fs)
        // 验证 RLB 高通 Stage 2
        assertTrue(s2.b0 > 0.99 && s2.b0 <= 1.0)
        assertTrue(s2.b1 < -1.98 && s2.b1 >= -2.0)
        assertTrue(s2.a1 < -1.98 && s2.a1 > -2.0)
        assertTrue(s2.a2 > 0.98 && s2.a2 < 1.0)
    }

    @Test
    fun `测试标准流媒体目标响度下高电平流行音乐的衰减计算`() {
        // 现代流行音乐经过响度战争母带，综合响度常在 -9.0 LUFS，真峰值 0.988 (-0.1 dBFS)
        val loudPop = LoudnessData(
            integratedLufs = -9.0f,
            truePeak = 0.988f,
            source = LoudnessSource.NETEASE_ONLINE
        )
        val result = EbuR128Calculator.computeGain(
            loudness = loudPop,
            targetLufs = EbuR128Calculator.TARGET_STREAMING_LUFS, // -14.0 LUFS
            peakLimitEnabled = true
        )

        // 目标为 -14.0 LUFS，计算出的增益应为 -14 - (-9) = -5.0 dB
        assertEquals(-5.0f, result.rawGainDb, 0.05f)
        assertEquals(-5.0f, result.effectiveGainDb, 0.05f)
        // 衰减不会触发真峰值超标
        assertFalse(result.isPeakLimited)
        // 线性乘数应在 0.56 左右 (10^(-5/20) ≈ 0.5623)
        assertTrue(result.linearMultiplier > 0.55f && result.linearMultiplier < 0.57f)
    }

    @Test
    fun `测试EBU R128广电标准目标下的大动态衰减`() {
        // EBU R128 基准目标为 -23.0 LUFS
        val track = LoudnessData(
            integratedLufs = -15.0f,
            truePeak = 0.90f,
            source = LoudnessSource.REPLAYGAIN_TAG
        )
        val result = EbuR128Calculator.computeGain(
            loudness = track,
            targetLufs = EbuR128Calculator.TARGET_EBU_R128_LUFS, // -23.0 LUFS
            peakLimitEnabled = true
        )

        assertEquals(-8.0f, result.rawGainDb, 0.05f)
        assertEquals(-8.0f, result.effectiveGainDb, 0.05f)
        assertFalse(result.isPeakLimited)
        assertTrue(result.linearMultiplier > 0.38f && result.linearMultiplier < 0.41f)
    }

    @Test
    fun `测试安静曲目正增益提升触发防破音真峰值压制保护`() {
        // 安静的古典/录音室曲目：综合响度 -20.0 LUFS，但某处瞬态已有极大真峰值 0.95 (-0.45 dBFS)
        val quietTrackWithSpike = LoudnessData(
            integratedLufs = -20.0f,
            truePeak = 0.95f, // peakDb ≈ -0.45 dBFS
            source = LoudnessSource.BS1770_SCAN
        )
        val result = EbuR128Calculator.computeGain(
            loudness = quietTrackWithSpike,
            targetLufs = EbuR128Calculator.TARGET_STREAMING_LUFS, // -14.0 LUFS
            peakLimitEnabled = true
        )

        // 原始增益理论应为 -14 - (-20) = +6.0 dB
        assertEquals(6.0f, result.rawGainDb, 0.05f)
        // 若直接加 +6.0 dB，峰值将达到 -0.45 + 6.0 = +5.55 dBFS，造成剧烈数字削波破音！
        // 启用真峰值限制 (-1.0 dBTP) 后，允许的最大增益为 -1.0 - (-0.45) = -0.55 dB
        assertTrue(result.isPeakLimited)
        assertTrue(result.effectiveGainDb < 0.0f)
        assertTrue(result.effectiveGainDb <= -0.5f)
    }

    @Test
    fun `测试未达到真峰值上限的正增益安全提升`() {
        // 安静且动态很小的纯声乐：综合响度 -18.0 LUFS，真峰值仅 0.30 (-10.45 dBFS)
        val softVocal = LoudnessData(
            integratedLufs = -18.0f,
            truePeak = 0.30f,
            source = LoudnessSource.NETEASE_ONLINE
        )
        val result = EbuR128Calculator.computeGain(
            loudness = softVocal,
            targetLufs = EbuR128Calculator.TARGET_STREAMING_LUFS, // -14.0 LUFS
            peakLimitEnabled = true
        )

        // 原始增益为 +4.0 dB
        assertEquals(4.0f, result.rawGainDb, 0.05f)
        // +4.0 dB 施加后峰值仅 -10.45 + 4.0 = -6.45 dBFS，远低于 -1.0 dBTP，不触发压制
        assertEquals(4.0f, result.effectiveGainDb, 0.05f)
        assertFalse(result.isPeakLimited)
        assertTrue(result.linearMultiplier > 1.5f)
    }

    @Test
    fun `测试双声道PCM正弦波数据的综合响度与峰值计算`() {
        val sampleRate = 48000
        val durationSec = 1.0 // 1 秒
        val numSamples = (sampleRate * durationSec).toInt()

        val left = FloatArray(numSamples)
        val right = FloatArray(numSamples)

        // 生成 1kHz 正弦波，峰值幅值 0.5 (-6.02 dBFS)
        val freq = 1000.0
        for (i in 0 until numSamples) {
            val v = (0.5 * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
            left[i] = v
            right[i] = v
        }

        val loudness = EbuR128Calculator.calculateIntegratedLoudness(left, right, sampleRate)

        // 峰值应精确接近 0.5
        assertEquals(0.5f, loudness.truePeak, 0.01f)
        assertEquals(-6.02f, loudness.peakDb, 0.1f)
        // 1kHz 立体声正弦波经过 K-weighting 与能量积分，响度应在 -9 ~ -10 LUFS 左右
        assertTrue(loudness.integratedLufs > -12.0f && loudness.integratedLufs < -8.0f)
        assertEquals(LoudnessSource.BS1770_SCAN, loudness.source)
    }

    @Test
    fun `测试静音数据的综合响度返回最低门限`() {
        val sampleRate = 48000
        val silence = FloatArray(sampleRate) // 1 秒静音
        val loudness = EbuR128Calculator.calculateIntegratedLoudness(silence, silence, sampleRate)

        assertEquals(-70.0f, loudness.integratedLufs, 0.1f)
        assertEquals(0.0f, loudness.truePeak, 0.001f)
    }

    @Test
    fun `测试音量均衡实时状态文本格式化`() {
        val disabledState = LoudnessNormalizationState(isEnabled = false)
        assertEquals("未启用", disabledState.statusSummary)

        val activeState = LoudnessNormalizationState(
            isEnabled = true,
            sourceLoudnessLufs = -11.2f,
            appliedGainDb = -2.8f,
            isPeakLimited = false
        )
        assertTrue(activeState.statusSummary.contains("-11.2 LUFS"))
        assertTrue(activeState.statusSummary.contains("-2.8 dB"))

        val peakLimitedState = LoudnessNormalizationState(
            isEnabled = true,
            sourceLoudnessLufs = -19.0f,
            appliedGainDb = -0.5f,
            isPeakLimited = true
        )
        assertTrue(peakLimitedState.statusSummary.contains("防过载压制"))
    }
}