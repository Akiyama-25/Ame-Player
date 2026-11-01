package com.decent.usbaudio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * ExoPlayer [AudioSink] wrapper that delivers direct bit-perfect PCM to a
 * USB Audio Class 2.0 DAC via Linux usbdevfs isochronous transfers, bypassing
 * the entire Android audio stack (AudioFlinger, AudioTrack, AAudio).
 *
 * Supports arbitrary sample rates up to 384 kHz, 768 kHz without Android AudioTrack limits.
 *
 * The delegate [DefaultAudioSink] is kept alive (muted) for ExoPlayer clock and
 * media session state synchronization.
 */
@OptIn(UnstableApi::class)
class UsbAudioSink(
    private val delegate: DefaultAudioSink,
    private val context: Context,
    private val config: UsbAudioSinkConfig = UsbAudioSinkConfig()
) : ForwardingAudioSink(delegate) {

    companion object {
        private const val TAG = "UsbAudioSink"
        private const val QUEUE_BACKPRESSURE_THRESHOLD = 16
    }

    private val usbAudioDevice = UsbAudioDevice.getInstance(context)
    private var usbAudioStream: UsbAudioStream? = null
    private var usbStreamingThread: UsbStreamingThread? = null

    private var currentEncoding: Int = C.ENCODING_PCM_16BIT
    private var currentSampleRate: Int = 0
    private var currentChannelCount: Int = 0
    private var pendingVolume: Float = 1f
    private var delegateMuted: Boolean = false
    private var handleBufferCallCount: Long = 0

    private var usbStartMediaTimeUs: Long = 0L
    private var usbStartMediaTimeNeedsInit: Boolean = true
    private var handledEndOfStream: Boolean = false
    private var isPlaying: Boolean = false

    @Volatile
    private var attachedPlayer: Player? = null

    fun setBitPerfectEnabled(enabled: Boolean) {
        if (config.bitPerfectEnabled != enabled) {
            config.bitPerfectEnabled = enabled
            Log.i(TAG, "setBitPerfectEnabled: $enabled")
            if (!enabled) {
                releaseUsbStream()
                unmuteDelegateIfNeeded()
            }
        }
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val enc = inputFormat.pcmEncoding
        if (enc != Format.NO_VALUE) currentEncoding = enc
        handleBufferCallCount = 0
        val sr = inputFormat.sampleRate.takeIf { it > 0 }
        val ch = inputFormat.channelCount.takeIf { it > 0 }

        Log.i(TAG, "configure: pcmEncoding=$enc rate=${inputFormat.sampleRate} ch=${inputFormat.channelCount} bitPerfect=${config.bitPerfectEnabled}")

        if (config.bitPerfectEnabled && sr != null && ch != null) {
            val device = usbAudioDevice.findUsbAudioDevice()
            if (device != null && usbAudioDevice.hasPermission(device)) {
                configureUsbBitPerfect(sr, ch, enc)
                usbStartMediaTimeNeedsInit = true
                if (config.forceRouteToSpeaker) forceMediaToSpeaker()

                // Crucial: Android AudioTrack hard-limits SAMPLE_RATE_HZ_MAX = 192000.
                // The delegate is muted and only used for ExoPlayer timeline tracking.
                // We clamp the delegate's format to <= 192000 so DefaultAudioSink.configure
                // never throws IllegalStateException on 384kHz / DXD files.
                val safeDelegateFormat = if (inputFormat.sampleRate > 192000) {
                    inputFormat.buildUpon().setSampleRate(192000).build()
                } else {
                    inputFormat
                }
                super.configure(safeDelegateFormat, specifiedBufferSize, outputChannels)
                muteDelegateIfNeeded()
                Log.i(TAG, "Delegate configured for timeline clock (muted, routed to speaker)")
                return
            } else if (device != null) {
                Log.w(TAG, "USB DAC found but permission not yet granted")
            }
        }

        super.configure(inputFormat, specifiedBufferSize, outputChannels)

        if (usbAudioStream != null && !config.bitPerfectEnabled) {
            releaseUsbStream()
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        val stream = usbAudioStream
        if (config.bitPerfectEnabled && stream?.isAlive == true) {
            muteDelegateIfNeeded()

            if (usbStartMediaTimeNeedsInit) {
                usbStartMediaTimeUs = maxOf(0L, presentationTimeUs)
                usbStartMediaTimeNeedsInit = false
                Log.i(TAG, "Captured usbStartMediaTimeUs=$usbStartMediaTimeUs")
            }

            val thread = usbStreamingThread ?: return true
            if (thread.queueSize() >= QUEUE_BACKPRESSURE_THRESHOLD) {
                return false
            }

            handleBufferCallCount++
            val snapshot: ByteBuffer = buffer.slice().order(buffer.order())

            if (currentEncoding == C.ENCODING_PCM_FLOAT) {
                val totalSamples = snapshot.remaining() / 4
                if (totalSamples > 0) {
                    val floatBuf = FloatArray(totalSamples)
                    snapshot.asFloatBuffer().get(floatBuf)
                    if (handleBufferCallCount <= 3) {
                        Log.i(TAG, "handleBuffer #$handleBufferCallCount: FLOAT samples=$totalSamples")
                    }
                    thread.enqueue(floatBuf)
                }
            } else {
                val remaining = snapshot.remaining()
                if (remaining > 0) {
                    val rawBytes = ByteArray(remaining)
                    snapshot.get(rawBytes)
                    if (handleBufferCallCount <= 3) {
                        val bps = PcmUtils.bytesPerSample(currentEncoding)
                        Log.i(TAG, "handleBuffer #$handleBufferCallCount: RAW ${bps * 8}bit bytes=$remaining")
                    }
                    thread.enqueueRaw(rawBytes, currentEncoding)
                }
            }

            buffer.position(buffer.limit())
            return true
        }

        unmuteDelegateIfNeeded()
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (config.bitPerfectEnabled) {
            val streamAlive = usbAudioStream?.isAlive == true
            if (streamAlive) {
                if (usbStartMediaTimeNeedsInit) return AudioSink.CURRENT_POSITION_NOT_SET
                val frames = usbAudioStream?.framesWritten ?: 0L
                return if (currentSampleRate > 0) {
                    usbStartMediaTimeUs + frames * C.MICROS_PER_SECOND / currentSampleRate
                } else AudioSink.CURRENT_POSITION_NOT_SET
            }
        }
        return super.getCurrentPositionUs(sourceEnded)
    }

    override fun isEnded(): Boolean {
        if (config.bitPerfectEnabled && usbAudioStream?.isAlive == true) {
            return handledEndOfStream && usbStreamingThread?.hasPendingData() == false
        }
        return super.isEnded()
    }

    override fun hasPendingData(): Boolean {
        if (config.bitPerfectEnabled && usbAudioStream?.isAlive == true) {
            if (usbStreamingThread?.hasPendingData() == true) return true
        }
        return super.hasPendingData()
    }

    override fun playToEndOfStream() {
        handledEndOfStream = true
        super.playToEndOfStream()
    }

    override fun play() {
        super.play()
        isPlaying = true
        usbStreamingThread?.resumeStreaming()
    }

    override fun pause() {
        isPlaying = false
        usbStreamingThread?.pauseStreaming()
        super.pause()
    }

    override fun setVolume(volume: Float) {
        pendingVolume = volume
        if (config.bitPerfectEnabled && usbAudioStream?.isAlive == true) {
            muteDelegateIfNeeded()
        } else {
            unmuteDelegateIfNeeded()
        }
    }

    override fun flush() {
        super.flush()
        usbStreamingThread?.flush()
        usbAudioStream?.flush()
        usbStartMediaTimeNeedsInit = true
        handledEndOfStream = false
    }

    override fun reset() {
        super.reset()
    }

    override fun release() {
        releaseUsbStream()
        super.release()
    }

    private fun configureUsbBitPerfect(sampleRate: Int, channelCount: Int, encoding: Int) {
        if (sampleRate == currentSampleRate && channelCount == currentChannelCount
            && usbAudioStream?.isAlive == true) {
            Log.d(TAG, "USB stream cached for rate=$sampleRate ch=$channelCount — reusing")
            return
        }

        if (usbAudioStream != null) releaseUsbStream()

        val usbDevice = usbAudioDevice.findUsbAudioDevice() ?: return
        var deviceInfo = usbAudioDevice.openDevice(usbDevice)
        if (deviceInfo == null) {
            Log.e(TAG, "Failed to open USB device")
            return
        }

        val bitDepth = deviceInfo.bestBitDepth
        val altSetting = deviceInfo.bestAltSetting
        Log.i(TAG, "Bit-perfect: alt=$altSetting usb=${bitDepth}bit clockSource=0x${deviceInfo.clockSourceId.toString(16)} targetRate=$sampleRate")

        var stream = UsbAudioStream(
            fd = deviceInfo.fd,
            interfaceId = deviceInfo.interfaceId,
            endpointOut = deviceInfo.endpointOutAddress,
            endpointFeedback = deviceInfo.endpointFeedbackAddress,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitDepth = bitDepth,
            maxPacketSize = deviceInfo.maxPacketSize
        )

        if (!stream.isReady) {
            Log.e(TAG, "USB stream creation failed")
            stream.release()
            return
        }

        // ─── UAC2 xHCI-verified rate transition sequence ───
        // 1. setAlt(0)       → FREE old ISO rings
        if (!usbAudioDevice.setAltSetting(0)) {
            Log.w(TAG, "setAlt(0) failed — stale fd, reopening device...")
            usbAudioDevice.closeDevice()
            stream.release()
            deviceInfo = usbAudioDevice.openDevice(usbDevice)
            if (deviceInfo == null) {
                Log.e(TAG, "Failed to reopen USB device")
                return
            }
            stream = UsbAudioStream(
                fd = deviceInfo.fd,
                interfaceId = deviceInfo.interfaceId,
                endpointOut = deviceInfo.endpointOutAddress,
                endpointFeedback = deviceInfo.endpointFeedbackAddress,
                sampleRate = sampleRate,
                channelCount = channelCount,
                bitDepth = bitDepth,
                maxPacketSize = deviceInfo.maxPacketSize
            )
            if (!stream.isReady) {
                Log.e(TAG, "USB stream recreation failed after reopen")
                stream.release()
                return
            }
            Log.i(TAG, "Device reopened with fresh fd=${deviceInfo.fd}")
        }
        Log.i(TAG, "Step 1: setAlt(0) — old ISO ring freed")

        // 2. SET_CUR → write new sample rate to Clock Source entity
        usbAudioDevice.setSampleRate(sampleRate)

        // 3. GET_CUR(CLOCK_VALID_CONTROL) → verify clock locked
        val clockValid = usbAudioDevice.readClockValid()
        Log.i(TAG, "Step 2-3: SET_CUR=$sampleRate, CLOCK_VALID=$clockValid")

        // 4. setAlt(0) AGAIN → defensive reset after clock change
        usbAudioDevice.setAltSetting(0)
        Log.i(TAG, "Step 4: setAlt(0) again — defensive reset")

        // 5. setAlt(N) → ALLOC new ISO rings with target format
        val altResult = usbAudioDevice.setAltSetting(altSetting)
        Log.i(TAG, "Step 5: setAlt($altSetting): $altResult — new ISO ring allocated")

        // 6. wait 50ms → DAC hardware PLL lock time
        Thread.sleep(50)

        // 7. start stream
        if (!stream.start()) {
            Log.e(TAG, "USB stream start failed")
            stream.release()
            return
        }

        usbAudioStream = stream
        currentSampleRate = sampleRate
        currentChannelCount = channelCount
        muteDelegateIfNeeded()

        usbStreamingThread?.stop()
        usbStreamingThread = UsbStreamingThread(stream).also { it.start() }

        Log.i(TAG, "USB bit-perfect stream ACTIVE: rate=$sampleRate ch=$channelCount bits=$bitDepth device=${deviceInfo.deviceName}")
    }

    private fun releaseUsbStream() {
        val stream = usbAudioStream ?: return
        usbAudioStream = null

        stream.stop()
        usbStreamingThread?.stop()
        usbStreamingThread = null

        val drained = stream.drainUrbs()
        Log.i(TAG, "USB stream drained $drained URBs")

        stream.release()
        clearForcedRouting()
    }

    private fun muteDelegateIfNeeded() {
        if (!delegateMuted) {
            super.setVolume(0f)
            delegateMuted = true
        }
    }

    private fun unmuteDelegateIfNeeded() {
        if (delegateMuted) {
            super.setVolume(pendingVolume)
            delegateMuted = false
        }
    }

    private fun forceMediaToSpeaker() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                delegate.setPreferredDevice(speaker)
                Log.i(TAG, "Delegate routed to speaker")
            }
        } catch (e: Exception) {
            Log.w(TAG, "forceMediaToSpeaker failed: ${e.message}")
        }
    }

    private fun clearForcedRouting() {
        try {
            delegate.setPreferredDevice(null)
        } catch (_: Exception) {}
    }

    fun attachToPlayer(player: Player) {
        attachedPlayer = player
    }

    fun detachFromPlayer() {
        attachedPlayer = null
    }
}