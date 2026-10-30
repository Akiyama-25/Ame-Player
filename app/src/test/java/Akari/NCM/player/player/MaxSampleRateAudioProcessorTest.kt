package Akari.NCM.player.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MaxSampleRateAudioProcessorTest {

    @Test
    fun `正常及标准高解析度采样率保持不激活旁路`() {
        val processor = MaxSampleRateAudioProcessor(192000)

        for (sr in listOf(44100, 48000, 88200, 96000, 176400, 192000)) {
            val format = AudioFormat(sr, 2, C.ENCODING_PCM_16BIT)
            val output = processor.configure(format)
            assertEquals(AudioFormat.NOT_SET, output)
            assertFalse(processor.isActive)
        }
    }

    @Test
    fun `超高采样率384kHz激活降采样至192kHz安全范围`() {
        val processor = MaxSampleRateAudioProcessor(192000)
        val format = AudioFormat(384000, 2, C.ENCODING_PCM_16BIT)

        val output = processor.configure(format)
        assertTrue(processor.isActive)
        assertEquals(192000, output.sampleRate)
        assertEquals(2, output.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, output.encoding)
    }

    @Test
    fun `超高采样率352_8kHz激活降采样至176_4kHz`() {
        val processor = MaxSampleRateAudioProcessor(192000)
        val format = AudioFormat(352800, 2, C.ENCODING_PCM_16BIT)

        val output = processor.configure(format)
        assertTrue(processor.isActive)
        assertEquals(176400, output.sampleRate)
        assertEquals(2, output.channelCount)
    }

    @Test
    fun `PCM帧按倍数抽取降采样且数据结构完备`() {
        val processor = MaxSampleRateAudioProcessor(192000)
        val format = AudioFormat(384000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        // 构造 4 个立体声 16bit 样本帧 (每个帧 4 字节，共 16 字节)
        val input = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
        input.putShort(1); input.putShort(2)   // 帧 0 (保留)
        input.putShort(3); input.putShort(4)   // 帧 1 (跳过)
        input.putShort(5); input.putShort(6)   // 帧 2 (保留)
        input.putShort(7); input.putShort(8)   // 帧 3 (跳过)
        input.flip()

        processor.queueInput(input)
        val out = processor.output

        // 经过 2 倍降采样后应输出 2 个帧 (8 字节)
        assertEquals(8, out.remaining())
        val outShorts = out.order(ByteOrder.nativeOrder()).asShortBuffer()
        assertEquals(1.toShort(), outShorts.get())
        assertEquals(2.toShort(), outShorts.get())
        assertEquals(5.toShort(), outShorts.get())
        assertEquals(6.toShort(), outShorts.get())
    }
}