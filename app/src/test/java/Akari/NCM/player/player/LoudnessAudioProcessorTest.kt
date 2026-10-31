package Akari.NCM.player.player

import Akari.NCM.player.core.loudness.LoudnessData
import Akari.NCM.player.core.loudness.LoudnessSource
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class LoudnessAudioProcessorTest {

    @Test
    fun `未开启音量均衡时保持原始数据透传`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = false

        val format = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        val configured = processor.configure(format)
        assertTrue(processor.isActive)
        assertEquals(48000, configured.sampleRate)
        assertEquals(2, configured.channelCount)

        processor.flush()

        // 构造 4 帧立体声 16bit 样本
        val input = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)
        input.putShort(100); input.putShort(-200)
        input.putShort(300); input.putShort(-400)
        input.putShort(500); input.putShort(-600)
        input.putShort(700); input.putShort(-800)
        input.flip()

        processor.queueInput(input)
        val output = processor.output
        output.order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(16, output.remaining())
        assertEquals(100, output.short)
        assertEquals(-200, output.short)
        assertEquals(300, output.short)
        assertEquals(-400, output.short)
        assertEquals(500, output.short)
        assertEquals(-600, output.short)
        assertEquals(700, output.short)
        assertEquals(-800, output.short)
    }

    @Test
    fun `已知响度音轨施加精确静态EBU R128衰减增益`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = true
        processor.targetLufs = -14.0f
        processor.peakLimitEnabled = true

        val format = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        // 音轨响度为 -8.0 LUFS（非常响的 J-Pop 流行乐），目标为 -14.0 LUFS -> 增益应为 -6.0 dB (乘数 ≈ 0.501187)
        val knownLoudness = LoudnessData(
            integratedLufs = -8.0f,
            truePeak = 1.0f,
            source = LoudnessSource.REPLAYGAIN_TAG
        )
        processor.resetForTrack("song_loud", knownLoudness)

        // 连续喂入恒定幅值为 10000 的样本以完成平滑增益收敛 (约 200ms = 9600 帧)
        val frameCount = 12000
        val input = ByteBuffer.allocateDirect(frameCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            input.putShort(10000)
            input.putShort(10000)
        }
        input.flip()

        processor.queueInput(input)
        val output = processor.output
        output.order(ByteOrder.LITTLE_ENDIAN)

        // 读取末尾平稳段样本
        output.position((frameCount - 10) * 4)
        val settledSample = output.short.toInt()

        // 10000 * 0.501187 ≈ 5012
        assertTrue("收敛后的样本值应在 4900 ~ 5150 之间，实际: $settledSample", settledSample in 4900..5150)
    }

    @Test
    fun `正向增益提升且真峰值限制器防破音生效`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = true
        processor.targetLufs = -14.0f
        processor.peakLimitEnabled = true

        val format = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        // 音轨响度为 -20.0 LUFS (较安静录音)，目标为 -14.0 LUFS -> +6.0 dB 提升 (乘数 ≈ 1.995)
        val quietLoudness = LoudnessData(
            integratedLufs = -20.0f,
            truePeak = 0.8f,
            source = LoudnessSource.BS1770_SCAN
        )
        processor.resetForTrack("song_quiet", quietLoudness)

        // 喂入高幅值大动态信号 (30000 * 2 = 60000 会严重越界超限)
        val frameCount = 12000
        val input = ByteBuffer.allocateDirect(frameCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            input.putShort(30000)
            input.putShort(-30000)
        }
        input.flip()

        processor.queueInput(input)
        val output = processor.output
        output.order(ByteOrder.LITTLE_ENDIAN)

        output.position((frameCount - 10) * 4)
        val settledPositive = output.short.toInt()
        val settledNegative = output.short.toInt()

        // 防破音限制器应当将波峰平滑限制在 32767 之内且不发生下溢回绕 (无符号破音翻转)
        assertTrue("输出应在有效范围内且不破音，实际正峰值: $settledPositive", settledPositive in 28000..32767)
        assertTrue("输出应在有效范围内且不破音，实际负峰值: $settledNegative", settledNegative in -32768..-28000)
    }

    @Test
    fun `支持PCM Float浮点格式且高精度处理`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = true
        processor.targetLufs = -14.0f

        val format = AudioFormat(96000, 2, C.ENCODING_PCM_FLOAT)
        val configured = processor.configure(format)
        assertEquals(C.ENCODING_PCM_FLOAT, configured.encoding)
        assertEquals(96000, configured.sampleRate)

        processor.flush()

        val knownLoudness = LoudnessData(-8.0f, 1.0f, LoudnessSource.REPLAYGAIN_TAG)
        processor.resetForTrack("float_test", knownLoudness)

        val frameCount = 20000
        val input = ByteBuffer.allocateDirect(frameCount * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            input.putFloat(0.8f)
            input.putFloat(-0.8f)
        }
        input.flip()

        processor.queueInput(input)
        val output = processor.output
        output.order(ByteOrder.LITTLE_ENDIAN)

        output.position((frameCount - 10) * 8)
        val sampleL = output.float
        val sampleR = output.float

        // 0.8 * 0.501187 ≈ 0.4009
        assertTrue("浮点输出左声道应在 0.38 ~ 0.42，实际: $sampleL", abs(sampleL - 0.4009f) < 0.02f)
        assertTrue("浮点输出右声道应在 -0.42 ~ -0.38，实际: $sampleR", abs(sampleR - (-0.4009f)) < 0.02f)
    }

    @Test
    fun `未校准音轨在起播时动态分析并自适应平准`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = true
        processor.targetLufs = -14.0f

        val format = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        // 重置为未知响度曲目 (null)
        processor.resetForTrack("uncalibrated_song", null)
        assertEquals(null, processor.staticGainDb)

        // 喂入响度较高的连续音频流（幅值约 0.38，接近 -8 LUFS 能量）
        val frameCount = 30000 // 约 625ms
        val input = ByteBuffer.allocateDirect(frameCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            input.putShort(12500)
            input.putShort(12500)
        }
        input.flip()

        processor.queueInput(input)
        val output = processor.output
        output.order(ByteOrder.LITTLE_ENDIAN)

        output.position((frameCount - 10) * 4)
        val measuredOutput = output.short.toInt()

        // 动态自适应探测到大能量后会自动将增益拉低以匹配 -14 LUFS 目标
        assertTrue("动态自适应应调低过响曲目，实际输出样本: $measuredOutput", measuredOutput < 11000)
    }

    @Test
    fun `积累满有效音频分块后成功回调BS1770综合响度结果`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = true
        processor.targetLufs = -14.0f

        val format = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        var callbackReceived: LoudnessData? = null
        processor.onLoudnessMeasured = { id, data ->
            if (id == "song_analyze_test") {
                callbackReceived = data
            }
        }

        processor.resetForTrack("song_analyze_test", null)

        // 48000Hz 下一个分块为 0.4s = 19200 帧，25 个分块为 480,000 帧
        // 构造带有正弦波信号的连续缓冲
        val chunkSize = 48000 // 1 秒一段
        val inputBuf = ByteBuffer.allocateDirect(chunkSize * 4).order(ByteOrder.LITTLE_ENDIAN)
        val f0 = 997.0
        val fs = 48000.0

        for (chunk in 0 until 11) {
            inputBuf.clear()
            for (i in 0 until chunkSize) {
                val t = (chunk * chunkSize + i) / fs
                val s = (kotlin.math.sin(2.0 * Math.PI * f0 * t) * 16384).toInt().toShort()
                inputBuf.putShort(s)
                inputBuf.putShort(s)
            }
            inputBuf.flip()
            processor.queueInput(inputBuf)
        }

        assertNotNull("分析完成应触发回调并返回 BS1770 数据", callbackReceived)
        val data = callbackReceived!!
        assertEquals(LoudnessSource.BS1770_SCAN, data.source)
        // 正弦波幅值 0.5 (-6 dBFS) 经 K-weighting 后的 LUFS 在 -6 ~ -10 之间
        assertTrue("测得的综合响度应在 -12 ~ -6 LUFS 之间，实际: ${data.integratedLufs}", data.integratedLufs in -12.0f..-6.0f)
    }
}