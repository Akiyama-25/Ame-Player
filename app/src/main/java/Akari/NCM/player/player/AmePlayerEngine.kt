package Akari.NCM.player.player

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.PlaybackState
import Akari.NCM.player.core.PlayerController
import Akari.NCM.player.core.PlayerState
import Akari.NCM.player.core.RepeatMode
import Akari.NCM.player.data.NcmDataSource
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.flac.FlacExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.data.UsbAudioManager
import android.media.AudioDeviceInfo
import android.os.Build

@UnstableApi
@Singleton
class AmePlayerEngine @Inject constructor(
    private val context: Context,
    private val ncmApi: NcmApi,
    private val usbAudioManager: UsbAudioManager,
    private val sessionManager: UserSessionManager
) : PlayerState, PlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // State flows
    private val _currentMedia = MutableStateFlow<PlayableMedia?>(null)
    override val currentMedia: StateFlow<PlayableMedia?> = _currentMedia.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    override val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    override val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    override val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _playlist = MutableStateFlow<List<PlayableMedia>>(emptyList())
    override val playlist: StateFlow<List<PlayableMedia>> = _playlist.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    override val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private val _audioFormatInfo = MutableStateFlow(Akari.NCM.player.core.AudioFormatInfo())
    override val audioFormatInfo: StateFlow<Akari.NCM.player.core.AudioFormatInfo> = _audioFormatInfo.asStateFlow()

    override val isUsbExclusiveMode: StateFlow<Boolean> = usbAudioManager.isExclusiveModeEnabled
    override val connectedUsbDeviceName: StateFlow<String?> = usbAudioManager.connectedUsbDeviceName

    // Map mediaId to PlayableMedia for quick resolution
    private val mediaMap = ConcurrentHashMap<String, PlayableMedia>()
    private val resolvedUrlCache = ConcurrentHashMap<String, String>()

    // Disk Cache for online audio streaming (500MB max)
    private val audioCache: SimpleCache by lazy {
        val cacheDir = File(context.cacheDir, "online_audio_cache")
        SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(500 * 1024 * 1024L))
    }

    private val exoPlayer: ExoPlayer

    init {
        // Audio-only renderer for efficiency
        val audioOnlyFactory = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf(
                MediaCodecAudioRenderer(
                    context,
                    MediaCodecSelector.DEFAULT,
                    handler,
                    audioListener
                )
            )
        }

        // Data source: local NCM uses NcmDataSource, online uses HTTP + Cache
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("AmePlayer/1.0")
            .setAllowCrossProtocolRedirects(true)

        val httpFactory = ResolvingDataSource.Factory(httpDataSourceFactory) { dataSpec ->
            val key = dataSpec.key
            val uriStr = dataSpec.uri.toString()
            Log.d("[AME_ONLINE_PLAY]", ">>> [ExoPlayer ResolvingDataSource] Intercepting dataSpec key='$key', uri='$uriStr'")

            val media: PlayableMedia.Online? = (if (!key.isNullOrBlank()) mediaMap[key] as? PlayableMedia.Online else null)
                ?: mediaMap.values.filterIsInstance<PlayableMedia.Online>().find { online ->
                    uriStr.contains(online.id) || key?.contains(online.id) == true
                }

            if (media != null) {
                val cachedUrl = resolvedUrlCache[media.id]
                val url = if (!cachedUrl.isNullOrBlank()) {
                    Log.d("[AME_ONLINE_PLAY]", ">>> Using cached resolved URL for '${media.title}' (id=${media.id}): $cachedUrl")
                    cachedUrl
                } else {
                    Log.i("[AME_ONLINE_PLAY]", ">>> Matched Online Track: id='${media.id}', title='${media.title}'")
                    val fetchedUrl = runBlocking { ncmApi.getSongUrl(media) }
                    if (fetchedUrl != null) {
                        resolvedUrlCache[media.id] = fetchedUrl
                    }
                    fetchedUrl
                }

                if (url != null) {
                    Log.i("[AME_ONLINE_PLAY]", ">>> Injecting resolved URL into ExoPlayer DataSpec: $url")
                    dataSpec.withUri(url.toUri())
                } else {
                    Log.e("[AME_ONLINE_PLAY]", ">>> CRITICAL: Failed to get URL for '${media.title}', throwing PlaybackException")
                    throw PlaybackException(
                        "Failed to resolve song URL for ${media.title}",
                        null,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                    )
                }
            } else {
                Log.w("[AME_ONLINE_PLAY]", ">>> WARNING: No PlayableMedia.Online match found in mediaMap for key='$key', uri='$uriStr'")
                dataSpec
            }
        }

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(audioCache)
            .setUpstreamDataSourceFactory(httpFactory)
            .setCacheKeyFactory { dataSpec ->
                val media = mediaMap[dataSpec.key ?: dataSpec.uri.toString()]
                if (media is PlayableMedia.Online) "${media.id}#cached" else ""
            }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // Set contentResolver for NcmDataSource
        NcmDataSource.contentResolver = context.contentResolver

        // Routing DataSource: NCM → NcmDataSource (decrypted), content:// → ContentDataSource, http → Cache
        val contentDataSourceFactory = DataSource.Factory {
            androidx.media3.datasource.ContentDataSource(context)
        }

        val routingDataSourceFactory = DataSource.Factory {
            object : DataSource {
                private var delegate: DataSource? = null

                override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {}

                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                    val key = dataSpec.key ?: ""
                    val scheme = dataSpec.uri.scheme ?: ""
                    delegate = when {
                        key.startsWith("ncm:") -> NcmDataSource()
                        scheme == "content" || scheme == "file" -> contentDataSourceFactory.createDataSource()
                        else -> cacheDataSourceFactory.createDataSource()
                    }
                    return delegate!!.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    return delegate!!.read(buffer, offset, length)
                }

                override fun getUri(): Uri = delegate?.uri ?: Uri.EMPTY

                override fun close() { delegate?.close() }
            }
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(context, DefaultExtractorsFactory())
            .setDataSourceFactory(routingDataSourceFactory)

        // Larger buffer for high-bitrate FLAC (96kHz stereo ≈ 3Mbps)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,  // minBufferMs
                50_000,  // maxBufferMs
                5_000,   // bufferForPlaybackMs
                10_000   // bufferForPlaybackAfterRebufferMs
            )
            .build()

        exoPlayer = ExoPlayer.Builder(context, audioOnlyFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        // Audio offload for battery efficiency
        val offloadPrefs = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            .setIsGaplessSupportRequired(true)
            .build()
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(offloadPrefs)
            .build()

        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF

        // Player listener
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                Log.i("[AME_ONLINE_PLAY]", ">>> ExoPlayer onIsPlayingChanged: $isPlaying")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                Log.i("[AME_ONLINE_PLAY]", ">>> ExoPlayer onPlaybackStateChanged: $stateName ($playbackState)")
                _playbackState.value = when (playbackState) {
                    Player.STATE_IDLE -> PlaybackState.IDLE
                    Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                    Player.STATE_READY -> PlaybackState.READY
                    Player.STATE_ENDED -> PlaybackState.ENDED
                    else -> PlaybackState.IDLE
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId
                Log.i("[AME_ONLINE_PLAY]", ">>> ExoPlayer onMediaItemTransition: mediaId='$id', reason=$reason")
                val currentMedia = id?.let { mediaMap[it] }
                _currentMedia.value = currentMedia
                _currentIndex.value = exoPlayer.currentMediaItemIndex
                _durationMs.value = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0L

                // 懒加载封面：如果是本地文件且封面为空，在此刻异步获取网络匹配封面
                if (currentMedia is PlayableMedia.LocalNcm && currentMedia.coverUrl == null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            var finalCoverUrl: String? = null

                            // 0. 先检查是否有本地缓存的封面 (之前提取过的 ID3)
                            val coverDir = java.io.File(context.cacheDir, "covers")
                            if (!coverDir.exists()) coverDir.mkdirs()
                            val cachedCoverFile = java.io.File(coverDir, "${currentMedia.id}.jpg")
                            if (cachedCoverFile.exists()) {
                                finalCoverUrl = "file://${cachedCoverFile.absolutePath}"
                            }

                            // 1. 如果是 NCM 文件，直接从文件头解析准确的 albumPic (100% 精准，耗时极短)
                            if (finalCoverUrl.isNullOrBlank() && currentMedia.isNcm) {
                                val ncmMeta = NcmDataSource.scanMetadata(context.contentResolver, android.net.Uri.parse(currentMedia.uri))
                                val albumPic = ncmMeta?.first?.albumPic
                                if (!albumPic.isNullOrBlank()) {
                                    finalCoverUrl = albumPic
                                }
                            }

                            // 2. 如果是普通 MP3/FLAC，尝试提取本地 ID3 内嵌封面
                            if (finalCoverUrl.isNullOrBlank() && !currentMedia.isNcm) {
                                try {
                                    val mmr = android.media.MediaMetadataRetriever()
                                    mmr.setDataSource(context, android.net.Uri.parse(currentMedia.uri))
                                    val picBytes = mmr.embeddedPicture
                                    mmr.release()
                                    if (picBytes != null) {
                                        cachedCoverFile.writeBytes(picBytes)
                                        finalCoverUrl = "file://${cachedCoverFile.absolutePath}"
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("[AME_COVER_DEBUG]", "Failed to extract ID3 cover for ${currentMedia.title}", e)
                                }
                            }

                            // 3. 实在没有本地封面，才去网易云搜索兜底
                            if (finalCoverUrl.isNullOrBlank()) {
                                val rawFirstArtist = currentMedia.artist.split(",", "/").firstOrNull()?.trim() ?: ""
                                val searchArtist = rawFirstArtist.split(Regex("\\s+")).firstOrNull()?.trim() ?: ""
                                val cleanTitle = currentMedia.title.replace(Regex("^\\d+\\.?\\s*"), "").trim()
                                val query = if (searchArtist.isNotBlank() && searchArtist != "Unknown") {
                                    "$searchArtist $cleanTitle"
                                } else {
                                    cleanTitle
                                }
                                // 请求前5个结果，严格匹配歌手名！不匹配绝对不能用，否则会拿到错误的封面
                                val searchResults = ncmApi.searchSongs(query.trim(), limit = 5)
                                val searchResult = searchResults.find { 
                                    it.artist.contains(searchArtist, ignoreCase = true) 
                                } // 注意：移除了 fallback 到 firstOrNull() 的逻辑，宁可空着也不能错！
                                
                                if (searchResult != null) {
                                    if (!searchResult.coverUrl.isNullOrBlank()) {
                                        finalCoverUrl = searchResult.coverUrl
                                    } else {
                                        val detailResult = ncmApi.getSongDetails(listOf(searchResult.id)).firstOrNull()
                                        finalCoverUrl = detailResult?.coverUrl
                                    }
                                }
                            }

                            if (!finalCoverUrl.isNullOrBlank()) {
                                val updatedMedia = currentMedia.copy(coverUrl = finalCoverUrl)
                                mediaMap[updatedMedia.id] = updatedMedia
                                // 如果当前还是这首歌，推送更新到 UI
                                if (_currentMedia.value?.id == updatedMedia.id) {
                                    _currentMedia.value = updatedMedia
                                }
                                Log.i("[AME_ONLINE_PLAY]", "Lazy loaded cover for ${currentMedia.title}: $finalCoverUrl")
                            }
                        } catch (e: Exception) {
                            Log.e("[AME_ONLINE_PLAY]", "Failed to lazy load cover", e)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("[AME_ONLINE_PLAY]", ">>> ExoPlayer Playback ERROR: errorCode=${error.errorCode}, message=${error.message}, cause=${error.cause}", error)
                val errorMessage = error.message ?: "播放遇到了错误 (${error.errorCode})"
                _error.value = errorMessage
                _isPlaying.value = false
                _playbackState.value = PlaybackState.IDLE
                exoPlayer.currentMediaItem?.mediaId?.let { resolvedUrlCache.remove(it) }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _repeatMode.value = when (repeatMode) {
                    Player.REPEAT_MODE_OFF -> RepeatMode.OFF
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    else -> RepeatMode.OFF
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _shuffleEnabled.value = shuffleModeEnabled
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                updateAudioFormatFromExo(exoPlayer.audioFormat)
            }
        })

        // Position ticker
        scope.launch {
            while (isActive) {
                if (_isPlaying.value) {
                    _currentPositionMs.value = exoPlayer.currentPosition
                    _durationMs.value = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                    updateAudioFormatFromExo(exoPlayer.audioFormat)
                }
                delay(200.milliseconds)
            }
        }

        // USB Exclusive mode and device observer
        scope.launch {
            kotlinx.coroutines.flow.combine(
                usbAudioManager.isExclusiveModeEnabled,
                usbAudioManager.connectedUsbDevice
            ) { isExclusive, device ->
                Pair(isExclusive, device)
            }.collect { (isExclusive, device) ->
                applyUsbAudioRouting(device, isExclusive)
            }
        }

        // Observe audio quality preference changes to clear URL cache dynamically
        scope.launch {
            sessionManager.qualityState.collect { quality ->
                Log.i("[AME_ONLINE_PLAY]", ">>> Audio quality preference changed to '${quality.label}' (${quality.value}), clearing resolved URL cache.")
                resolvedUrlCache.clear()
            }
        }
    }

    private fun updateAudioFormatFromExo(format: androidx.media3.common.Format?) {
        if (format == null) return
        val sampleRate = format.sampleRate.takeIf { it > 0 } ?: 44100
        val channels = format.channelCount.takeIf { it > 0 } ?: 2
        val pcmEnc = format.pcmEncoding
        val bitDepth = when (pcmEnc) {
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 24
            C.ENCODING_PCM_16BIT -> 16
            else -> if ((format.bitrate > 500_000) || sampleRate > 48000) 24 else 16
        }
        var bitrate = if (format.bitrate > 0) format.bitrate / 1000 else 0
        if (bitrate <= 0) {
            val durationSec = (_durationMs.value / 1000f).coerceAtLeast(1f)
            val currentMedia = _currentMedia.value
            val fileSize = currentMedia?.let { media ->
                if (media is PlayableMedia.LocalNcm) {
                    runCatching {
                        val uri = Uri.parse(media.uri)
                        if (uri.scheme == "content") {
                            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                        } else {
                            File(uri.path ?: "").length()
                        }
                    }.getOrDefault(0L)
                } else 0L
            } ?: 0L
            if (fileSize > 0) {
                bitrate = ((fileSize * 8) / durationSec / 1000).toInt()
            }
        }
        _audioFormatInfo.value = Akari.NCM.player.core.AudioFormatInfo(
            bitrateKbps = bitrate,
            sampleRateHz = sampleRate,
            bitDepthBits = bitDepth,
            mimeType = format.sampleMimeType ?: format.containerMimeType,
            channels = channels
        )
    }

    /** Exposes the underlying ExoPlayer so [AmePlaybackService] can build a MediaSession around it. */
    val player: ExoPlayer get() = exoPlayer

    // --- PlayerController ---

    override fun load(media: PlayableMedia) {
        val mediaItem = buildMediaItem(media)
        mediaMap[media.id] = media
        Log.i("[AME_ONLINE_PLAY]", ">>> load single track '${media.title}' (id=${media.id})")
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        _currentMedia.value = media
    }

    override fun loadPlaylist(items: List<PlayableMedia>, startIndex: Int) {
        Log.i("[AME_ONLINE_PLAY]", ">>> loadPlaylist size=${items.size}, startIndex=$startIndex")
        mediaMap.clear()
        resolvedUrlCache.clear()
        val mediaItems = items.map { media ->
            mediaMap[media.id] = media
            buildMediaItem(media)
        }
        _playlist.value = items
        exoPlayer.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
        exoPlayer.prepare()
        _currentMedia.value = items.getOrNull(startIndex)
        _currentIndex.value = startIndex
    }

    override fun play() {
        Log.i("[AME_ONLINE_PLAY]", ">>> play() called")
        exoPlayer.play()
    }

    override fun pause() {
        Log.i("[AME_ONLINE_PLAY]", ">>> pause() called")
        exoPlayer.pause()
    }

    override fun togglePlayPause() {
        if (exoPlayer.isPlaying) pause() else play()
    }

    override fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    override fun skipToNext() {
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNext()
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    override fun skipToPrevious() {
        if (exoPlayer.currentPosition > 3000) {
            exoPlayer.seekTo(0)
        } else if (exoPlayer.hasPreviousMediaItem()) {
            exoPlayer.seekToPrevious()
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    override fun skipToIndex(index: Int) {
        exoPlayer.seekToDefaultPosition(index)
        exoPlayer.prepare()
        exoPlayer.play()
        _currentIndex.value = index
        _currentMedia.value = _playlist.value.getOrNull(index)
    }

    override fun insertAsNext(media: PlayableMedia) {
        val currentList = _playlist.value.toMutableList()
        val mediaItem = buildMediaItem(media)
        mediaMap[media.id] = media

        if (currentList.isEmpty()) {
            loadPlaylist(listOf(media), 0)
            return
        }

        val insertIndex = (_currentIndex.value + 1).coerceAtMost(currentList.size)
        currentList.add(insertIndex, media)
        _playlist.value = currentList
        exoPlayer.addMediaItem(insertIndex, mediaItem)
        Log.i("[AME_ONLINE_PLAY]", ">>> Inserted '${media.title}' as next track at index $insertIndex")
    }

    override fun setRepeatMode(mode: RepeatMode) {
        exoPlayer.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    override fun toggleShuffle() {
        exoPlayer.shuffleModeEnabled = !exoPlayer.shuffleModeEnabled
    }

    override fun setVolume(volume: Float) {
        exoPlayer.volume = volume.coerceIn(0f, 1f)
        _volume.value = exoPlayer.volume
    }

    override fun setUsbExclusiveMode(enabled: Boolean) {
        usbAudioManager.setExclusiveModeEnabled(enabled)
        val currentDevice = usbAudioManager.connectedUsbDevice.value
        applyUsbAudioRouting(currentDevice, enabled)
    }

    private fun applyUsbAudioRouting(device: AudioDeviceInfo?, isExclusive: Boolean) {
        if (isExclusive && device != null) {
            Log.i("[AME_USB_AUDIO]", ">>> Applying USB Exclusive Routing to device: '${device.productName}' (id=${device.id}, type=${device.type})")
            exoPlayer.setPreferredAudioDevice(device)
        } else {
            Log.i("[AME_USB_AUDIO]", ">>> Reverting USB Audio routing to default (isExclusive=$isExclusive, device=${device?.productName})")
            exoPlayer.setPreferredAudioDevice(null)
            if (isExclusive && device == null && _isPlaying.value) {
                Log.w("[AME_USB_AUDIO]", ">>> USB Audio DAC unplugged during exclusive playback! Pausing audio to protect ears/speakers.")
                pause()
            }
        }
    }

    override fun clearUrlCache() {
        resolvedUrlCache.clear()
        Log.i("[AME_ONLINE_PLAY]", ">>> Manually cleared resolved URL cache")
    }

    override fun release() {
        scope.cancel()
        exoPlayer.release()
        mediaMap.clear()
        resolvedUrlCache.clear()
    }

    // --- Helpers ---

    private fun buildMediaItem(media: PlayableMedia): MediaItem {
        val uriStr = if (media is PlayableMedia.Online) {
            "https://music.126.net/ncm/${media.id}"
        } else {
            media.encodeUri()
        }

        val builder = MediaItem.Builder()
            .setMediaId(media.id)
            .setUri(uriStr.toUri())
            .setCustomCacheKey(media.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(media.title)
                    .setArtist(media.artist)
                    .setAlbumTitle(media.album)
                    .setArtworkUri(media.coverUrl?.toUri())
                    .build()
            )

        if (media is PlayableMedia.LocalNcm && media.isNcm) {
            builder.setCustomCacheKey("ncm:${media.id}")
        }

        return builder.build()
    }
}
