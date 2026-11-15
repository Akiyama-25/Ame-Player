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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
        assertEquals(100.toShort(), output.short)
        assertEquals((-200).toShort(), output.short)
        assertEquals(300.toShort(), output.short)
        assertEquals((-400).toShort(), output.short)
        assertEquals(500.toShort(), output.short)
        assertEquals((-600).toShort(), output.short)
        assertEquals(700.toShort(), output.short)
        assertEquals((-800).toShort(), output.short)
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
    fun `未校准音轨在起播时保持稳定初始电平并在全曲响度就绪后平滑应用`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = true
        processor.targetLufs = -14.0f

        val format = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        // 重置为未知响度曲目 (null)
        processor.resetForTrack("uncalibrated_song", null)
        assertEquals(null, processor.staticGainDb)
        assertEquals(1.0f, processor.currentMultiplier, 0.001f)

        // 喂入连续音频流，未获取全曲响度前保持 1.0 原始电平输出，绝不在播放中途动态漂移
        val frameCount = 1000
        val input = ByteBuffer.allocateDirect(frameCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            input.putShort(10000)
            input.putShort(10000)
        }
        input.flip()

        processor.queueInput(input)
        val output = processor.output
        output.order(ByteOrder.LITTLE_ENDIAN)

        output.position((frameCount - 10) * 4)
        val initialSample = output.short.toInt()
        assertTrue("未获取全曲响度前应保持 1.0 原始电平输出，实际: $initialSample", initialSample in 9990..10010)

        // 全曲扫描完成后，设置全曲静态增益 (-6.0 dB, 乘数 ≈ 0.501)
        processor.setStaticGain(-6.0f, 0.501187f)
        assertEquals(-6.0f, processor.staticGainDb)

        // 再次喂入音频，增益在 200ms 时间常数指数平滑下完全收敛至目标值 (约 1 秒 = 48000+ 帧)
        val nextFrames = 60000
        val inputNext = ByteBuffer.allocateDirect(nextFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until nextFrames) {
            inputNext.putShort(10000)
            inputNext.putShort(10000)
        }
        inputNext.flip()

        processor.queueInput(inputNext)
        val outputNext = processor.output
        outputNext.order(ByteOrder.LITTLE_ENDIAN)

        outputNext.position((nextFrames - 10) * 4)
        val settledSample = outputNext.short.toInt()
        assertTrue("全曲增益就绪后应平滑收敛，实际: $settledSample", settledSample in 4900..5150)
    }

    @Test
    fun `反复开关音量均衡保持稳定且不发生累积衰减`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = true
        processor.targetLufs = -14.0f

        val format = AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        val knownLoudness = LoudnessData(-8.0f, 1.0f, LoudnessSource.REPLAYGAIN_TAG)
        processor.resetForTrack("toggle_test_song", knownLoudness)

        val targetMult = processor.currentMultiplier
        assertTrue("已知 -8 LUFS 增益乘数应在 0.5 左右", targetMult in 0.49f..0.52f)

        // 反复开关 20 次
        for (i in 0 until 20) {
            processor.updateSettings(enabled = false, target = -14.0f, peakLimit = true)
            assertEquals(1.0f, processor.currentMultiplier, 0.001f)

            processor.updateSettings(enabled = true, target = -14.0f, peakLimit = true)
            assertEquals(targetMult, processor.currentMultiplier, 0.001f)
        }

        // 最终确认增益绝对未发生向下衰减漂移
        assertEquals(targetMult, processor.currentMultiplier, 0.001f)
    }

    @Test
    fun `支持PCM 24-bit打包格式读写与响度衰减`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = true
        processor.targetLufs = -14.0f

        val format = AudioFormat(48000, 2, C.ENCODING_PCM_24BIT)
        val configured = processor.configure(format)
        assertEquals(C.ENCODING_PCM_24BIT, configured.encoding)
        processor.flush()

        val knownLoudness = LoudnessData(-8.0f, 1.0f, LoudnessSource.REPLAYGAIN_TAG)
        processor.resetForTrack("pcm24_test", knownLoudness)

        // 构造 24-bit LE 样本，幅度 4_000_000 (约 0.4768)
        val rawSample = 4_000_000
        val frameCount = 12000
        val input = ByteBuffer.allocateDirect(frameCount * 6).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            for (ch in 0 until 2) {
                val v = if (ch == 0) rawSample else -rawSample
                input.put((v and 0xFF).toByte())
                input.put(((v ushr 8) and 0xFF).toByte())
                input.put(((v ushr 16) and 0xFF).toByte())
            }
        }
        input.flip()

        processor.queueInput(input)
        val output = processor.output
        output.position((frameCount - 10) * 6)

        val b0 = output.get().toInt() and 0xFF
        val b1 = output.get().toInt() and 0xFF
        val b2 = output.get().toInt()
        val outL = (b2 shl 16) or (b1 shl 8) or b0
        // 4_000_000 * 0.501187 ≈ 2_004_748
        assertTrue("24-bit 输出应精确衰减至 ~2,004,748，实际: $outL", outL in 1_950_000..2_060_000)
    }

    @Test
    fun `AudioSink采样率重建触发reset时不丢失当前曲目响度增益`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = true
        processor.targetLufs = -14.0f

        processor.configure(AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.resetForTrack("hires_song", LoudnessData(-8.0f, 1.0f, LoudnessSource.NETEASE_ONLINE))

        val expectedGain = processor.staticGainDb
        val expectedMult = processor.currentMultiplier
        assertNotNull(expectedGain)
        assertTrue(processor.isFinalLocked)

        // 模拟 ExoPlayer DefaultAudioSink 在切采样率/重配输出管线时调用 reset() + configure() + flush()
        processor.reset()
        processor.configure(AudioFormat(96000, 2, C.ENCODING_PCM_FLOAT))
        processor.flush()

        assertEquals("hires_song", processor.currentMediaId)
        assertEquals(expectedGain!!, processor.staticGainDb!!, 0.001f)
        assertEquals(expectedMult, processor.currentMultiplier, 0.001f)
        assertTrue(processor.isFinalLocked)
    }

    @Test
    fun `在线流媒体缺失增益时在播放解码流1秒内触发Stage1初判并在持续播放后锁定Stage2`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = true
        processor.targetLufs = -14.0f
        processor.peakLimitEnabled = true

        val sr = 48000
        processor.configure(AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        processor.flush()

        var previewData: LoudnessData? = null
        var finalData: LoudnessData? = null
        processor.onPreviewLoudnessMeasured = { id, data ->
            if (id == "online_missing_gain") previewData = data
        }
        processor.onLoudnessMeasured = { id, data ->
            if (id == "online_missing_gain") finalData = data
        }

        processor.resetForTrack("online_missing_gain", null)
        assertFalse(processor.isFinalLocked)

        // 喂入 1.0 秒 1kHz 正弦波 (幅值 0.5 -> ~-6.06 LUFS)，足以触发 2 个 400ms 活跃块 (0.8s)
        val amp = 16384.0
        val oneSecFrames = sr
        val chunk1 = ByteBuffer.allocateDirect(oneSecFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until oneSecFrames) {
            val s = (amp * kotlin.math.sin(2.0 * kotlin.math.PI * 1000.0 * i / sr)).toInt().toShort()
            chunk1.putShort(s)
            chunk1.putShort(s)
        }
        chunk1.flip()
        processor.queueInput(chunk1)

        assertNotNull("1 秒播放流内应触发 Stage 1 极速初判", previewData)
        assertEquals(LoudnessSource.FAST_PREVIEW_SCAN, previewData!!.source)
        assertEquals(-6.06f, previewData!!.integratedLufs, 0.6f)
        assertNotNull(processor.staticGainDb)
        assertFalse(processor.isFinalLocked)

        // 继续喂入 18 秒流式音频以触发 Stage 2 全曲锁定 (45 * 400ms = 18s)
        val batchFrames = sr * 3
        repeat(6) {
            val batch = ByteBuffer.allocateDirect(batchFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until batchFrames) {
                val s = (amp * kotlin.math.sin(2.0 * kotlin.math.PI * 1000.0 * i / sr)).toInt().toShort()
                batch.putShort(s)
                batch.putShort(s)
            }
            batch.flip()
            processor.queueInput(batch)
        }

        assertNotNull("累计达到锁定窗口后应触发 Stage 2 锁定", finalData)
        assertEquals(LoudnessSource.BS1770_SCAN, finalData!!.source)
        assertEquals(-6.06f, finalData!!.integratedLufs, 0.5f)
        assertTrue(processor.isFinalLocked)
    }
}