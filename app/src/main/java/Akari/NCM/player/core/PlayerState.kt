package Akari.NCM.player.core

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.StateFlow

@Stable
data class AudioFormatInfo(
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val bitDepthBits: Int = 16,
    val mimeType: String? = null,
    val channels: Int = 2
)

@Stable
interface PlayerState {
    val currentMedia: StateFlow<PlayableMedia?>
    val isPlaying: StateFlow<Boolean>
    val playbackState: StateFlow<PlaybackState>
    val currentPositionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val volume: StateFlow<Float>
    val repeatMode: StateFlow<RepeatMode>
    val shuffleEnabled: StateFlow<Boolean>
    val playlist: StateFlow<List<PlayableMedia>>
    val currentIndex: StateFlow<Int>
    val error: StateFlow<String?>
    val audioFormatInfo: StateFlow<AudioFormatInfo>
    val isUsbExclusiveMode: StateFlow<Boolean>
    val connectedUsbDeviceName: StateFlow<String?>
}

enum class PlaybackState {
    IDLE, BUFFERING, READY, ENDED
}

enum class RepeatMode {
    OFF, ALL, ONE
}

interface PlayerController {
    fun load(media: PlayableMedia)
    fun loadPlaylist(items: List<PlayableMedia>, startIndex: Int = 0)
    fun play()
    fun pause()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun skipToNext()
    fun skipToPrevious()
    fun skipToIndex(index: Int)
    fun setRepeatMode(mode: RepeatMode)
    fun toggleShuffle()
    fun setVolume(volume: Float)
    fun setUsbExclusiveMode(enabled: Boolean)
    fun insertAsNext(media: PlayableMedia)
    fun clearUrlCache()
    fun release()
}
