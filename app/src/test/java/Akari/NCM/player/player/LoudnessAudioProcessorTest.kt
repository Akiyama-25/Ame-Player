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

        // 喂入超过 60ms Look-ahead 环形缓冲 (2880 帧) 的平稳直流信号，验证起播平滑无突兀漂移
        val frameCount = 6000
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
        assertTrue("未获取全曲响度前平稳信号应保持接近 1.0 原始电平输出，实际: $initialSample", initialSample in 9950..10050)

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
        assertEquals(LoudnessSource.INTRO_PREVIEW_SCAN, previewData!!.source)
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

    @Test
    fun `多点极速初判预设后流式前奏测算不得覆盖已有初判增益`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = true
        processor.targetLufs = -14.0f
        processor.peakLimitEnabled = true

        val sr = 48000
        processor.configure(AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        processor.flush()

        var previewCalled = 0
        processor.onPreviewLoudnessMeasured = { _, _ -> previewCalled++ }

        // 先通过 3 锚点极速抽样预设 -6.9 LUFS -> 应施加 -7.1 dB 衰减
        val fastPreview = LoudnessData(-6.9f, 0.95f, LoudnessSource.FAST_PREVIEW_SCAN)
        processor.resetForTrack("dahlia_track", fastPreview)
        assertEquals(-7.1f, processor.staticGainDb!!, 0.05f)
        assertEquals(LoudnessSource.FAST_PREVIEW_SCAN, processor.currentSource)

        // 播放极轻前奏（约 -26 LUFS）：不得回调覆盖 FAST_PREVIEW_SCAN
        val amp = 1600.0
        val chunk = ByteBuffer.allocateDirect(sr * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sr * 2) {
            val s = (amp * kotlin.math.sin(2.0 * kotlin.math.PI * 1000.0 * i / sr)).toInt().toShort()
            chunk.putShort(s)
            chunk.putShort(s)
        }
        chunk.flip()
        processor.queueInput(chunk)

        assertEquals(0, previewCalled)
        assertEquals(-7.1f, processor.staticGainDb!!, 0.05f)
        assertEquals(LoudnessSource.FAST_PREVIEW_SCAN, processor.currentSource)

        // 尝试用低优先级 INTRO_PREVIEW_SCAN 调用 setStaticGain 也应被拒绝
        processor.setStaticGain(7.3f, 2.31f, isFinal = false, smoothSec = 0.25, source = LoudnessSource.INTRO_PREVIEW_SCAN)
        assertEquals(-7.1f, processor.staticGainDb!!, 0.05f)
        assertEquals(LoudnessSource.FAST_PREVIEW_SCAN, processor.currentSource)
    }

    @Test
    fun `修改目标响度重算时保留未锁定初判的2dB正增益上限`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = true
        processor.targetLufs = -16.0f
        processor.peakLimitEnabled = true

        processor.configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()

        val unverifiedQuiet = LoudnessData(-21.3f, 0.2f, LoudnessSource.FAST_PREVIEW_SCAN)
        processor.resetForTrack("cap_preserve_track", unverifiedQuiet)
        assertEquals(2.0f, processor.staticGainDb!!, 0.01f)

        // 将目标响度进一步调高到 -11.0 LUFS 时，未锁定源的正向增益仍被控制在 +2.0 dB 以内
        processor.updateSettings(enabled = true, target = -11.0f, peakLimit = true)
        assertEquals(2.0f, processor.staticGainDb!!, 0.01f)
    }

    @Test
    fun `USB独占BitPerfect模式强制逐字节旁路所有响度与限幅DSP`() {
        val processor = LoudnessAudioProcessor()
        processor.updateSettings(
            enabled = true,
            target = -14.0f,
            peakLimit = true,
            negativeGainOnly = false,
            usbExclusiveBypass = true
        )
        processor.configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.resetForTrack("usb_bp_track", LoudnessData(-6.0f, 1.0f, LoudnessSource.NETEASE_ONLINE))

        val input = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)
        val samples = shortArrayOf(12345, -23456, 32767, -32768, 1000, -1000, 42, -42)
        samples.forEach { input.putShort(it) }
        input.flip()

        processor.queueInput(input)
        val output = processor.output
        output.order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(16, output.remaining())
        for (expected in samples) {
            assertEquals("USB 独占模式必须零修改逐样本透传", expected, output.short)
        }
    }

    @Test
    fun `Tier2完成后通过1点2秒对数斜坡无缝交接并锁定静态增益`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = true
        processor.targetLufs = -14.0f
        processor.peakLimitEnabled = true

        val sr = 48000
        processor.configure(AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.resetForTrack("handoff_track", null)
        assertFalse(processor.isFinalLocked)

        // 先走过 100ms Tier 3 Look-ahead 缓冲
        val initFrames = sr / 10
        val initBuf = ByteBuffer.allocateDirect(initFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(initFrames) {
            initBuf.putShort(10000)
            initBuf.putShort(10000)
        }
        initBuf.flip()
        processor.queueInput(initBuf)

        // Tier 2 11x3s 探测返回 -8.0 LUFS -> -6.0 dB 衰减，执行 1.2s 对数斜坡交接
        val sparseData = LoudnessData(-8.0f, 0.98f, LoudnessSource.SPARSE_RANGE_SCAN)
        val handoffGain = processor.handoffToStaticGain(sparseData, rampDurationSec = 1.2)
        assertEquals(-6.0f, handoffGain.effectiveGainDb, 0.05f)
        assertTrue(processor.isFinalLocked)
        assertEquals(LoudnessSource.SPARSE_RANGE_SCAN, processor.currentSource)

        // 喂入 1.5 秒信号使 1.2s 斜坡完整结束
        val rampFrames = (sr * 1.5).toInt()
        val rampBuf = ByteBuffer.allocateDirect(rampFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(rampFrames) {
            rampBuf.putShort(10000)
            rampBuf.putShort(10000)
        }
        rampBuf.flip()
        processor.queueInput(rampBuf)

        val out = processor.output
        out.order(ByteOrder.LITTLE_ENDIAN)
        out.position((rampFrames - 10) * 4)
        val settled = out.short.toInt()
        assertTrue("1.2s 对数斜坡交接后应平稳收敛至 ~5012，实际: $settled", settled in 4900..5150)
    }

    @Test
    fun `Tier3在副歌骤降至安静段落时触发FreezeGate冻结增益防止底噪抬升`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = true
        processor.targetLufs = -14.0f
        processor.peakLimitEnabled = true

        val sr = 48000
        processor.configure(AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.resetForTrack("freeze_gate_track", null)

        // 1. 喂入 2.0 秒高响度副歌 (约 -7 LUFS)
        val loudFrames = sr * 2
        val loudBuf = ByteBuffer.allocateDirect(loudFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until loudFrames) {
            val s = (15000.0 * kotlin.math.sin(2.0 * kotlin.math.PI * 1000.0 * i / sr)).toInt().toShort()
            loudBuf.putShort(s)
            loudBuf.putShort(s)
        }
        loudBuf.flip()
        processor.queueInput(loudBuf)
        val gainAfterChorus = processor.currentMultiplier
        assertTrue("高响度副歌应被 Tier 3 快压衰减 (< 0.85)，实际: $gainAfterChorus", gainAfterChorus < 0.85f)

        // 2. 紧接着喂入 3.5 秒极轻过门段落 (约 -34 LUFS，落差 > 11 LU)
        val quietFrames = (sr * 3.5).toInt()
        val quietBuf = ByteBuffer.allocateDirect(quietFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until quietFrames) {
            val s = (600.0 * kotlin.math.sin(2.0 * kotlin.math.PI * 1000.0 * i / sr)).toInt().toShort()
            quietBuf.putShort(s)
            quietBuf.putShort(s)
        }
        quietBuf.flip()
        processor.queueInput(quietBuf)

        assertTrue("安静过门段落落差超过 11 LU 时应触发 Freeze Gate", processor.isFreezeGateActive)
        assertTrue(
            "Freeze Gate 激活期间增益不得向上泵浦超过 +0.15，实际: before=$gainAfterChorus, after=${processor.currentMultiplier}",
            processor.currentMultiplier <= gainAfterChorus + 0.15f
        )
    }

    @Test
    fun `末端4x过采样真峰值限幅器将Nyquist四分之一过冲严控在负1dBTP以内`() {
        val processor = LoudnessAudioProcessor()
        processor.isEnabled = true
        processor.targetLufs = -14.0f
        processor.peakLimitEnabled = true

        val sr = 48000
        processor.configure(AudioFormat(sr, 2, C.ENCODING_PCM_FLOAT))
        processor.flush()
        // 给定 0 dB 静态增益，但输入信号本身存在采样点间真峰值 +2.1 dBTP 的过冲
        processor.resetForTrack("tp_limiter_track", LoudnessData(-14.0f, 0.5f, LoudnessSource.BS1770_SCAN))

        val frames = sr / 2 // 0.5s
        val input = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val s = if ((i / 2) % 2 == 0) 0.92f else -0.92f
            input.putFloat(s)
            input.putFloat(s)
        }
        input.flip()

        processor.queueInput(input)
        val out = processor.output
        out.order(ByteOrder.LITTLE_ENDIAN)

        // 跳过前 2000 帧稳态建立后测量 4x 真峰值
        val tailFrames = frames - 2000
        val outL = FloatArray(tailFrames)
        val outR = FloatArray(tailFrames)
        out.position(2000 * 8)
        for (i in 0 until tailFrames) {
            outL[i] = out.float
            outR[i] = out.float
        }
        val measuredTruePeak = Akari.NCM.player.core.loudness.EbuR128Calculator.measureTruePeak4x(outL, outR)
        // -1.0 dBTP = 0.89125 线性，留极小浮点容差 0.905
        assertTrue(
            "4x 过采样真峰值限幅器应将输出真峰值压制在 <= 0.905 (-0.87 dBTP)，实际: $measuredTruePeak",
            measuredTruePeak <= 0.905f
        )
    }

    @Test
    fun `Tier3模拟参考音频ハテ10秒轻前奏骤升11LU爆发时严控正增益上限且全程不超负1dBTP`() {
        val processor = LoudnessAudioProcessor()
        processor.allowStreamingAutoLock = false
        processor.isEnabled = true
        processor.targetLufs = -14.0f
        processor.peakLimitEnabled = true

        val sr = 48000
        processor.configure(AudioFormat(sr, 2, C.ENCODING_PCM_FLOAT))
        processor.flush()
        processor.resetForTrack("hate_dynamic_jump", null)

        // 1. 模拟 10 秒安静前奏 (~-19.5 LUFS, amp ≈ 0.106)
        val introFrames = sr * 10
        val introBuf = ByteBuffer.allocateDirect(introFrames * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until introFrames) {
            val s = (0.106 * kotlin.math.sin(2.0 * kotlin.math.PI * 1000.0 * i / sr)).toFloat()
            introBuf.putFloat(s)
            introBuf.putFloat(s)
        }
        introBuf.flip()
        processor.queueInput(introBuf)

        // 前奏期间保守正向增益绝对不得超过 +2.0 dB (线性乘数 <= 1.259)
        assertTrue(
            "Tier 3 安静前奏正向增益不得超过 +2.0 dB，实际: ${processor.currentGainDbLive} dB",
            processor.currentGainDbLive <= 2.05f
        )
        assertFalse("关闭 18s 盲锁时 Tier 3 不得在未知后文前锁定静态模式", processor.isFinalLocked)

        // 2. 第 11 秒骤升 +12 LU 至极限高潮 (~-7.5 LUFS，含采样点间过冲 > +0.6 dBTP)
        val burstFrames = sr * 2
        val burstBuf = ByteBuffer.allocateDirect(burstFrames * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until burstFrames) {
            val s = if ((i / 2) % 2 == 0) 0.90f else -0.90f
            burstBuf.putFloat(s)
            burstBuf.putFloat(s)
        }
        burstBuf.flip()
        processor.queueInput(burstBuf)

        val out = processor.output
        out.order(ByteOrder.LITTLE_ENDIAN)
        val outL = FloatArray(burstFrames)
        val outR = FloatArray(burstFrames)
        for (i in 0 until burstFrames) {
            outL[i] = out.float
            outR[i] = out.float
        }

        // 前瞻非对称 AGC 应在 2 秒爆发期内迅速压低增益（至少衰减到 -4 dB 以下）
        assertTrue(
            "骤升爆发后 Tier 3 快压应迅速将增益压至 <= -4.0 dB，实际: ${processor.currentGainDbLive} dB",
            processor.currentGainDbLive <= -4.0f
        )
        // 全程（包含爆发瞬间首帧）4x 真峰值严控在 -1.0 dBTP 附近不削波炸耳
        val burstTruePeak = Akari.NCM.player.core.loudness.EbuR128Calculator.measureTruePeak4x(outL, outR)
        assertTrue(
            "骤升爆发全程 4x 过采样真峰值必须 <= 0.905 (-0.87 dBTP)，实际: $burstTruePeak",
            burstTruePeak <= 0.905f
        )
    }
}