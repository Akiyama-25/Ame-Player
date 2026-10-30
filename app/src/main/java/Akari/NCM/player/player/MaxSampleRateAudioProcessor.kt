package Akari.NCM.player.player

import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * 限制并安全降采样过高音频采样率的 AudioProcessor
 *
 * Android 原生 AudioTrack（包括 AudioFormat）硬性限制 SAMPLE_RATE_HZ_MAX = 192000 Hz。
 * 当播放 384kHz (DXD) 或 352.8kHz 等超高采样率无损音轨时，若直接传递给 AudioTrack，
 * 会在底层抛出 IllegalArgumentException: 384000Hz is not a supported sample rate。
 *
 * 本处理器在输入采样率 <= [maxSampleRateHz] 时保持静默旁路（isActive = false，零额外开销）；
 * 仅在采样率 > [maxSampleRateHz] 时激活，按整数倍抽取降采样至不超过 192kHz 的安全采样率输出。
 */
@UnstableApi
class MaxSampleRateAudioProcessor(
    private val maxSampleRateHz: Int = 192000
) : BaseAudioProcessor() {

    private var downsampleFactor: Int = 1

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.sampleRate <= maxSampleRateHz) {
            downsampleFactor = 1
            return AudioFormat.NOT_SET
        }

        downsampleFactor = (inputAudioFormat.sampleRate + maxSampleRateHz - 1) / maxSampleRateHz
        val targetSampleRate = inputAudioFormat.sampleRate / downsampleFactor

        return AudioFormat(
            targetSampleRate,
            inputAudioFormat.channelCount,
            inputAudioFormat.encoding
        )
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

        val framesToSkip = downsampleFactor
        val outputFrameCount = (frameCount + framesToSkip - 1) / framesToSkip
        val outputBytes = outputFrameCount * bytesPerFrame

        val out = replaceOutputBuffer(outputBytes)
        val frameBuf = ByteArray(bytesPerFrame)

        var framesProcessed = 0
        while (framesProcessed < frameCount) {
            inputBuffer.get(frameBuf)
            out.put(frameBuf)
            framesProcessed++

            val toSkip = (framesToSkip - 1).coerceAtMost(frameCount - framesProcessed)
            if (toSkip > 0) {
                inputBuffer.position(inputBuffer.position() + toSkip * bytesPerFrame)
                framesProcessed += toSkip
            }
        }
        out.flip()
    }
}