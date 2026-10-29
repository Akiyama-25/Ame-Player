package Akari.NCM.player.core

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
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
data class UsbDeviceDetails(
    val deviceName: String = "",
    val productName: String = "",
    val manufacturerName: String? = null,
    val vendorId: Int = 0,
    val productId: Int = 0,
    val uacVersion: String = "未连接",
    val isExclusiveClaimed: Boolean = false,
    val hasPermission: Boolean = false
) {
    val formattedVidPid: String
        get() = if (vendorId > 0 || productId > 0) String.format("VID:%04X  PID:%04X", vendorId, productId) else "未知标识"
}

/**
 * 无限推荐播放模式状态定义
 */
@Stable
sealed class InfinitePlayMode(val label: String) {
    data object None : InfinitePlayMode("")

    /** 单曲漫游或带种子歌曲的雷达歌单：使用相似歌曲推荐接口 (simiSong) 续接 */
    data class SongRadio(
        val seedSongId: String,
        val seedSongTitle: String = "",
        val fallbackPlaylistId: Long? = null
    ) : InfinitePlayMode(if (fallbackPlaylistId != null) "雷达漫游 · 无限推荐" else "单曲漫游 · 无限推荐")

    /** 私人 FM：使用私人 FM 接口 (radio/get) 续接 */
    data object PersonalFm : InfinitePlayMode("私人 FM · 无限推荐")

    /** 心动模式：使用智能推荐列表接口 (playmode/intelligence/list) 续接 */
    data class Heartbeat(
        val playlistId: Long,
        val seedSongId: String = ""
    ) : InfinitePlayMode("心动模式 · 智能推荐")

    val isActive: Boolean
        get() = this !is None
}

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
    val usbUacVersion: StateFlow<String?>
    val usbDeviceDetails: StateFlow<UsbDeviceDetails?>
    val infinitePlayMode: StateFlow<InfinitePlayMode>
        get() = MutableStateFlow(InfinitePlayMode.None)
    val isFetchingRecommendations: StateFlow<Boolean>
        get() = MutableStateFlow(false)
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
    fun startInfinitePlayback(mode: InfinitePlayMode, initialTracks: List<PlayableMedia> = emptyList()) {}
    fun disableInfinitePlayback() {}
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