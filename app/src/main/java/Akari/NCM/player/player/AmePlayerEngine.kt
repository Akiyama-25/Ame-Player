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
import Akari.NCM.player.core.AppLogger
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

import Akari.NCM.player.MainActivity
import Akari.NCM.player.core.LyricLine
import Akari.NCM.player.core.LyricSource
import Akari.NCM.player.core.UsbDeviceDetails
import Akari.NCM.player.data.LyricResult
import Akari.NCM.player.data.LyricsManager
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.data.UsbAudioManager
import Akari.NCM.player.player.island.IslandLiveNotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.AudioDeviceInfo
import android.os.Build
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

@UnstableApi
@Singleton
class AmePlayerEngine @Inject constructor(
    private val context: Context,
    private val ncmApi: NcmApi,
    private val usbAudioManager: UsbAudioManager,
    private val sessionManager: UserSessionManager
) : PlayerState, PlayerController {

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val islandManager = IslandLiveNotificationManager(context)
    private val lyricsManager = LyricsManager(context, ncmApi)

    var isLiveIslandEnabled: Boolean
        get() = ThemePrefs.liveIslandEnabled
        set(value) {
            ThemePrefs.liveIslandEnabled = value
            syncIslandState()
        }

    private val _currentLyricState = MutableStateFlow<LyricLine?>(null)
    val currentLyricState: StateFlow<LyricLine?> = _currentLyricState.asStateFlow()

    private val _currentLyricsResult = MutableStateFlow(LyricResult(emptyList(), LyricSource.NONE))
    val currentLyricsResult: StateFlow<LyricResult> = _currentLyricsResult.asStateFlow()

    private var currentArtworkBitmap: Bitmap? = null
    private var lyricLoadJob: Job? = null
    private var artworkLoadJob: Job? = null

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
    override val usbUacVersion: StateFlow<String?> = usbAudioManager.uacVersion
    override val usbDeviceDetails: StateFlow<UsbDeviceDetails?> = usbAudioManager.usbDeviceDetails

    // Map mediaId to PlayableMedia for quick resolution
    private val mediaMap = ConcurrentHashMap<String, PlayableMedia>()
    private val resolvedUrlCache = ConcurrentHashMap<String, String>()

    // Disk Cache for online audio streaming (500MB max)
    private val audioCache: SimpleCache by lazy {
        val cacheDir = File(context.cacheDir, "online_audio_cache")
        SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(500 * 1024 * 1024L))
    }

    @Volatile
    private var isReleased = false

    private var exoPlayer: ExoPlayer

    init {
        exoPlayer = createExoPlayer()
        startBackgroundObservers()
    }

    private fun ensureActive() {
        val isDead = isReleased || runCatching {
            val looper = exoPlayer.playbackLooper
            !looper.thread.isAlive
        }.getOrDefault(true)

        if (isDead) {
            AppLogger.w("[AME_ONLINE_PLAY]", ">>> ExoPlayer internal thread is dead or released. Recovering player instance...")
            isReleased = false
            if (!scope.isActive) {
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                startBackgroundObservers()
            }
            runCatching { exoPlayer.release() }
            exoPlayer = createExoPlayer()
        }
    }

    private fun createExoPlayer(): ExoPlayer {
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
            AppLogger.d("[AME_ONLINE_PLAY]", ">>> [ExoPlayer ResolvingDataSource] Intercepting dataSpec key='$key', uri='$uriStr'")

            val media: PlayableMedia.Online? = (if (!key.isNullOrBlank()) mediaMap[key] as? PlayableMedia.Online else null)
                ?: mediaMap.values.filterIsInstance<PlayableMedia.Online>().find { online ->
                    uriStr.contains(online.id) || key?.contains(online.id) == true
                }

            if (media != null) {
                val cachedUrl = resolvedUrlCache[media.id]
                val url = if (!cachedUrl.isNullOrBlank()) {
                    AppLogger.d("[AME_ONLINE_PLAY]", ">>> Using cached resolved URL for '${media.title}' (id=${media.id}): $cachedUrl")
                    cachedUrl
                } else {
                    AppLogger.i("[AME_ONLINE_PLAY]", ">>> Matched Online Track: id='${media.id}', title='${media.title}'")
                    val fetchedUrl = runBlocking { ncmApi.getSongUrl(media) }
                    if (fetchedUrl != null) {
                        resolvedUrlCache[media.id] = fetchedUrl
                    }
                    fetchedUrl
                }

                if (url != null) {
                    AppLogger.i("[AME_ONLINE_PLAY]", ">>> Injecting resolved URL into ExoPlayer DataSpec: $url")
                    dataSpec.withUri(url.toUri())
                } else {
                    AppLogger.e("[AME_ONLINE_PLAY]", ">>> CRITICAL: Failed to get URL for '${media.title}', throwing PlaybackException")
                    throw PlaybackException(
                        "Failed to resolve song URL for ${media.title}",
                        null,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                    )
                }
            } else {
                AppLogger.w("[AME_ONLINE_PLAY]", ">>> WARNING: No PlayableMedia.Online match found in mediaMap for key='$key', uri='$uriStr'")
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

        val player = ExoPlayer.Builder(context, audioOnlyFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        // Audio offload for battery efficiency
        val offloadPrefs = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            .setIsGaplessSupportRequired(true)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(offloadPrefs)
            .build()

        player.repeatMode = when (_repeatMode.value) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        player.shuffleModeEnabled = _shuffleEnabled.value
        player.volume = _volume.value

        // Player listener
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                AppLogger.i("[AME_ONLINE_PLAY]", ">>> ExoPlayer onIsPlayingChanged: $isPlaying")
                if (isPlaying) {
                    syncIslandState()
                } else {
                    islandManager.cancelIsland()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                AppLogger.i("[AME_ONLINE_PLAY]", ">>> ExoPlayer onPlaybackStateChanged: $stateName ($playbackState)")
                _playbackState.value = when (playbackState) {
                    Player.STATE_IDLE -> PlaybackState.IDLE
                    Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                    Player.STATE_READY -> PlaybackState.READY
                    Player.STATE_ENDED -> PlaybackState.ENDED
                    else -> PlaybackState.IDLE
                }
                if (playbackState == Player.STATE_READY) {
                    val realDuration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                    if (realDuration > 0L) {
                        _durationMs.value = realDuration
                        val currentItem = player.currentMediaItem
                        val existingDuration = currentItem?.mediaMetadata?.durationMs ?: 0L
                        if (currentItem != null && existingDuration <= 0L) {
                            val updatedMeta = currentItem.mediaMetadata.buildUpon()
                                .setDurationMs(realDuration)
                                .build()
                            MediaMetadataHelper.updateCurrentPlayingMetadata(
                                player = player,
                                mediaId = currentItem.mediaId,
                                updatedMetadata = updatedMeta
                            )
                        }
                    }
                }
                if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                    islandManager.cancelIsland()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId
                AppLogger.i("[AME_ONLINE_PLAY]", ">>> ExoPlayer onMediaItemTransition: mediaId='$id', reason=$reason")
                val currentMedia = id?.let { mediaMap[it] }
                _currentMedia.value = currentMedia
                _currentIndex.value = player.currentMediaItemIndex
                _durationMs.value = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L

                onTrackChanged(currentMedia)
                syncIslandState()
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLogger.e("[AME_ONLINE_PLAY]", ">>> ExoPlayer Playback ERROR: errorCode=${error.errorCode}, message=${error.message}, cause=${error.cause}", error)
                val errorMessage = error.message ?: "播放遇到了错误 (${error.errorCode})"
                _error.value = errorMessage
                _isPlaying.value = false
                _playbackState.value = PlaybackState.IDLE
                islandManager.cancelIsland()
                player.currentMediaItem?.mediaId?.let { resolvedUrlCache.remove(it) }
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
                updateAudioFormatFromExo(player.audioFormat)
            }
        })

        val currentDevice = usbAudioManager.connectedUsbDevice.value
        val isExclusive = usbAudioManager.isExclusiveModeEnabled.value
        if (isExclusive && currentDevice != null) {
            player.setPreferredAudioDevice(currentDevice)
        }

        return player
    }

    private fun startBackgroundObservers() {
        // Position ticker & in-app lyric sync
        scope.launch {
            while (isActive) {
                if (_isPlaying.value) {
                    val pos = exoPlayer.currentPosition
                    _currentPositionMs.value = pos
                    _durationMs.value = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                    updateAudioFormatFromExo(exoPlayer.audioFormat)
                    updateCurrentLyricForPosition(pos)
                }
                delay(200.milliseconds)
            }
        }

        // Observe Live Island switch changes from ThemePrefs
        scope.launch {
            ThemePrefs.liveIslandEnabledFlow.collect {
                syncIslandState()
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
                AppLogger.i("[AME_ONLINE_PLAY]", ">>> Audio quality preference changed to '${quality.label}' (${quality.value}), clearing resolved URL cache.")
                resolvedUrlCache.clear()
            }
        }
    }

    private fun updateCurrentLyricForPosition(positionMs: Long): Boolean {
        val lines = _currentLyricsResult.value.lines
        val newLine = if (lines.isEmpty()) {
            null
        } else {
            val idx = lines.indexOfLast { it.timeMs <= positionMs }.let { if (it == -1) 0 else it }
            lines.getOrNull(idx)
        }
        val changed = _currentLyricState.value != newLine
        if (changed) {
            _currentLyricState.value = newLine
        }
        return changed
    }

    private fun onTrackChanged(currentMedia: PlayableMedia?) {
        lyricLoadJob?.cancel()
        artworkLoadJob?.cancel()
        _currentLyricState.value = null
        _currentLyricsResult.value = LyricResult(emptyList(), LyricSource.NONE)
        currentArtworkBitmap = null

        if (currentMedia == null) {
            islandManager.cancelIsland()
            return
        }

        // 1. 异步加载当前曲目的歌词（供播放页同步使用）
        lyricLoadJob = scope.launch {
            val loaded = runCatching { lyricsManager.loadLyrics(currentMedia) }
                .getOrDefault(LyricResult(emptyList(), LyricSource.NONE))
            if (_currentMedia.value?.id == currentMedia.id) {
                _currentLyricsResult.value = loaded
                updateCurrentLyricForPosition(exoPlayer.currentPosition)
            }
        }

        // 2. 异步解析封面（含本地 .ncm 字节、ID3 内嵌封面或云端匹配封面）并写入 MediaSession MediaMetadata
        artworkLoadJob = scope.launch(Dispatchers.IO) {
            try {
                var finalCoverUrl: String? = currentMedia.coverUrl
                var localCoverBytes: ByteArray? = null

                if (currentMedia is PlayableMedia.LocalNcm) {
                    val coverDir = File(context.cacheDir, "covers")
                    if (!coverDir.exists()) coverDir.mkdirs()
                    val cachedCoverFile = File(coverDir, "${currentMedia.id}.jpg")
                    if (cachedCoverFile.exists()) {
                        localCoverBytes = runCatching { cachedCoverFile.readBytes() }.getOrNull()
                        if (finalCoverUrl.isNullOrBlank()) {
                            finalCoverUrl = "file://${cachedCoverFile.absolutePath}"
                        }
                    }

                    // 1. 如果是 NCM 文件，直接从文件头解析准确的 albumPic 与内嵌封面字节
                    if (currentMedia.isNcm && (finalCoverUrl.isNullOrBlank() || localCoverBytes == null)) {
                        val ncmMeta = NcmDataSource.scanMetadata(
                            context.contentResolver,
                            Uri.parse(currentMedia.uri)
                        )
                        val albumPic = ncmMeta?.first?.albumPic
                        val embeddedBytes = ncmMeta?.third
                        if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
                            localCoverBytes = embeddedBytes
                            runCatching { cachedCoverFile.writeBytes(embeddedBytes) }
                        }
                        if (finalCoverUrl.isNullOrBlank() && !albumPic.isNullOrBlank()) {
                            finalCoverUrl = albumPic
                        }
                    }

                    // 2. 如果是普通 MP3/FLAC，尝试提取本地 ID3 内嵌封面
                    if (!currentMedia.isNcm && (finalCoverUrl.isNullOrBlank() || localCoverBytes == null)) {
                        try {
                            val mmr = android.media.MediaMetadataRetriever()
                            mmr.setDataSource(context, Uri.parse(currentMedia.uri))
                            val picBytes = mmr.embeddedPicture
                            mmr.release()
                            if (picBytes != null && picBytes.isNotEmpty()) {
                                localCoverBytes = picBytes
                                cachedCoverFile.writeBytes(picBytes)
                                if (finalCoverUrl.isNullOrBlank()) {
                                    finalCoverUrl = "file://${cachedCoverFile.absolutePath}"
                                }
                            }
                        } catch (e: Exception) {
                            AppLogger.e("[AME_COVER_DEBUG]", "Failed to extract ID3 cover for ${currentMedia.title}", e)
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
                        val searchResults = ncmApi.searchSongs(query.trim(), limit = 5)
                        val searchResult = searchResults.find {
                            it.artist.contains(searchArtist, ignoreCase = true)
                        }

                        if (searchResult != null) {
                            finalCoverUrl = if (!searchResult.coverUrl.isNullOrBlank()) {
                                searchResult.coverUrl
                            } else {
                                ncmApi.getSongDetails(listOf(searchResult.id)).firstOrNull()?.coverUrl
                            }
                        }
                    }

                    if (!finalCoverUrl.isNullOrBlank() && finalCoverUrl != currentMedia.coverUrl) {
                        val updatedMedia = currentMedia.copy(coverUrl = finalCoverUrl)
                        mediaMap[updatedMedia.id] = updatedMedia
                        withContext(Dispatchers.Main) {
                            if (_currentMedia.value?.id == updatedMedia.id) {
                                _currentMedia.value = updatedMedia
                            }
                        }
                        AppLogger.i("[AME_ONLINE_PLAY]", "Lazy loaded cover for ${currentMedia.title}: $finalCoverUrl")
                    }
                }

                // 解码 Bitmap 并生成压缩字节，确保 MediaSession 的 artworkData 与 artworkUri 同时就绪
                val bitmap: Bitmap? = when {
                    localCoverBytes != null && localCoverBytes.isNotEmpty() -> {
                        MediaMetadataHelper.decodeSafeBitmap(localCoverBytes)
                    }
                    !finalCoverUrl.isNullOrBlank() -> {
                        val optimizedUrl = MediaMetadataHelper.optimizeNeteaseCoverUrl(finalCoverUrl)
                        val req = ImageRequest.Builder(context)
                            .data(optimizedUrl)
                            .size(Size(512, 512))
                            .allowHardware(false)
                            .build()
                        (context.imageLoader.execute(req).drawable as? BitmapDrawable)?.bitmap
                    }
                    else -> null
                }

                val artworkBytes = localCoverBytes?.takeIf { it.isNotEmpty() }
                    ?: bitmap?.let { MediaMetadataHelper.bitmapToCompressedBytes(it) }

                withContext(Dispatchers.Main) {
                    if (_currentMedia.value?.id == currentMedia.id) {
                        currentArtworkBitmap = bitmap
                        if (artworkBytes != null || finalCoverUrl != currentMedia.coverUrl) {
                            val updatedMetadata = MediaMetadataHelper.buildTrackMediaMetadata(
                                title = currentMedia.title,
                                artist = currentMedia.artist,
                                album = currentMedia.album,
                                durationMs = _durationMs.value.takeIf { it > 0 } ?: currentMedia.durationMs,
                                remoteArtworkUrl = finalCoverUrl,
                                localArtworkBytes = artworkBytes
                            )
                            MediaMetadataHelper.updateCurrentPlayingMetadata(
                                player = exoPlayer,
                                mediaId = currentMedia.id,
                                updatedMetadata = updatedMetadata
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("[AME_ONLINE_PLAY]", "Failed to resolve cover/metadata for media session", e)
            }
        }
    }

    /**
     * 统一的媒体实时窗状态同步入口：
     * 确保取消多余的独立横幅通知（NOTIFICATION_ID = 2002），统一由原生 MediaSession 驱动播放状态展示与快捷控制实时窗。
     */
    fun syncIslandState() {
        islandManager.cancelIsland()
        if (isLiveIslandEnabled && exoPlayer.isPlaying) {
            runCatching {
                context.startService(Intent(context, AmePlaybackService::class.java))
            }
        }
    }

    private fun buildOpenPlayerPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_player", true)
        }
        return PendingIntent.getActivity(
            context,
            IslandLiveNotificationManager.NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
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
        ensureActive()
        val mediaItem = buildMediaItem(media)
        mediaMap[media.id] = media
        AppLogger.i("[AME_ONLINE_PLAY]", ">>> load single track '${media.title}' (id=${media.id})")
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        _currentMedia.value = media
        onTrackChanged(media)
    }

    override fun loadPlaylist(items: List<PlayableMedia>, startIndex: Int) {
        ensureActive()
        AppLogger.i("[AME_ONLINE_PLAY]", ">>> loadPlaylist size=${items.size}, startIndex=$startIndex")
        mediaMap.clear()
        resolvedUrlCache.clear()
        val mediaItems = items.map { media ->
            mediaMap[media.id] = media
            buildMediaItem(media)
        }
        _playlist.value = items
        exoPlayer.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
        exoPlayer.prepare()
        val startMedia = items.getOrNull(startIndex)
        _currentMedia.value = startMedia
        _currentIndex.value = startIndex
        onTrackChanged(startMedia)
    }

    override fun play() {
        ensureActive()
        AppLogger.i("[AME_ONLINE_PLAY]", ">>> play() called")
        exoPlayer.play()
    }

    override fun pause() {
        ensureActive()
        AppLogger.i("[AME_ONLINE_PLAY]", ">>> pause() called")
        exoPlayer.pause()
    }

    override fun togglePlayPause() {
        ensureActive()
        if (exoPlayer.isPlaying) pause() else play()
    }

    override fun seekTo(positionMs: Long) {
        ensureActive()
        exoPlayer.seekTo(positionMs)
        _currentPositionMs.value = positionMs
        updateCurrentLyricForPosition(positionMs)
        syncIslandState()
    }

    override fun skipToNext() {
        ensureActive()
        if (exoPlayer.hasNextMediaItem()) {
            exoPlayer.seekToNext()
            exoPlayer.prepare()
            exoPlayer.play()
        } else if (exoPlayer.mediaItemCount > 0) {
            exoPlayer.seekToDefaultPosition(0)
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    override fun skipToPrevious() {
        ensureActive()
        if (exoPlayer.currentPosition > 3000) {
            exoPlayer.seekTo(0)
        } else if (exoPlayer.hasPreviousMediaItem()) {
            exoPlayer.seekToPrevious()
            exoPlayer.prepare()
            exoPlayer.play()
        } else if (exoPlayer.mediaItemCount > 0) {
            val lastIdx = (exoPlayer.mediaItemCount - 1).coerceAtLeast(0)
            exoPlayer.seekToDefaultPosition(lastIdx)
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    override fun skipToIndex(index: Int) {
        ensureActive()
        exoPlayer.seekToDefaultPosition(index)
        exoPlayer.prepare()
        exoPlayer.play()
        _currentIndex.value = index
        _currentMedia.value = _playlist.value.getOrNull(index)
    }

    override fun insertAsNext(media: PlayableMedia) {
        ensureActive()
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
        AppLogger.i("[AME_ONLINE_PLAY]", ">>> Inserted '${media.title}' as next track at index $insertIndex")
    }

    override fun setRepeatMode(mode: RepeatMode) {
        ensureActive()
        exoPlayer.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    override fun toggleShuffle() {
        ensureActive()
        exoPlayer.shuffleModeEnabled = !exoPlayer.shuffleModeEnabled
    }

    override fun setVolume(volume: Float) {
        ensureActive()
        exoPlayer.volume = volume.coerceIn(0f, 1f)
        _volume.value = exoPlayer.volume
    }

    override fun setUsbExclusiveMode(enabled: Boolean) {
        ensureActive()
        usbAudioManager.setExclusiveModeEnabled(enabled)
        val currentDevice = usbAudioManager.connectedUsbDevice.value
        applyUsbAudioRouting(currentDevice, enabled)
    }

    private fun applyUsbAudioRouting(device: AudioDeviceInfo?, isExclusive: Boolean) {
        if (isExclusive && device != null) {
            val uac = usbAudioManager.uacVersion.value ?: "UAC"
            AppLogger.i("[AME_USB_AUDIO]", ">>> Applying USB Exclusive Routing to device: '${device.productName}' ($uac, id=${device.id}, type=${device.type})")
            val routeSuccess = exoPlayer.setPreferredAudioDevice(device)
            AppLogger.i("[AME_USB_AUDIO]", ">>> exoPlayer.setPreferredAudioDevice result: $routeSuccess")
            val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE)
                .build()
            exoPlayer.setAudioAttributes(audioAttributes, false)
        } else {
            AppLogger.i("[AME_USB_AUDIO]", ">>> Reverting USB Audio routing to default (isExclusive=$isExclusive, device=${device?.productName})")
            exoPlayer.setPreferredAudioDevice(null)
            if (isExclusive && device == null && _isPlaying.value) {
                AppLogger.w("[AME_USB_AUDIO]", ">>> USB Audio DAC unplugged during exclusive playback! Pausing audio to protect ears/speakers.")
                pause()
            }
        }
    }

    override fun clearUrlCache() {
        resolvedUrlCache.clear()
        AppLogger.i("[AME_ONLINE_PLAY]", ">>> Manually cleared resolved URL cache")
    }

    override fun release() {
        isReleased = true
        islandManager.cancelIsland()
        scope.cancel()
        runCatching { exoPlayer.release() }
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

        val localCoverBytes = if (media is PlayableMedia.LocalNcm) {
            val cachedFile = File(File(context.cacheDir, "covers"), "${media.id}.jpg")
            if (cachedFile.exists()) runCatching { cachedFile.readBytes() }.getOrNull() else null
        } else null

        val metadata = MediaMetadataHelper.buildTrackMediaMetadata(
            title = media.title,
            artist = media.artist,
            album = media.album,
            durationMs = media.durationMs,
            remoteArtworkUrl = media.coverUrl,
            localArtworkBytes = localCoverBytes
        )

        val builder = MediaItem.Builder()
            .setMediaId(media.id)
            .setUri(uriStr.toUri())
            .setCustomCacheKey(media.id)
            .setMediaMetadata(metadata)

        if (media is PlayableMedia.LocalNcm && media.isNcm) {
            builder.setCustomCacheKey("ncm:${media.id}")
        }

        return builder.build()
    }
}
