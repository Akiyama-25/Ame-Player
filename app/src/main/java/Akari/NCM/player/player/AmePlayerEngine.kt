package Akari.NCM.player.player

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.InfinitePlayMode
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.PlaybackState
import Akari.NCM.player.core.PlayerController
import Akari.NCM.player.core.PlayerState
import Akari.NCM.player.core.PlaylistSourceType
import Akari.NCM.player.core.RepeatMode
import Akari.NCM.player.data.NcmDataSource
import Akari.NCM.player.data.PlayableMediaDTO
import android.content.Context
import android.net.Uri
import Akari.NCM.player.core.AppLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import kotlinx.coroutines.CancellationException
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
import Akari.NCM.player.core.loudness.EbuR128Calculator
import Akari.NCM.player.core.loudness.LoudnessData
import Akari.NCM.player.core.loudness.LoudnessNormalizationState
import Akari.NCM.player.core.loudness.LoudnessSource
import Akari.NCM.player.data.LocalMediaMetadataHelper
import Akari.NCM.player.data.LoudnessManager
import Akari.NCM.player.data.LyricResult
import Akari.NCM.player.data.LyricsManager
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.data.UsbAudioManager
import Akari.NCM.player.player.island.IslandLiveNotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.os.SystemClock
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    val loudnessManager = LoudnessManager(context, ncmApi)
    val loudnessAudioProcessor = LoudnessAudioProcessor().apply {
        // 完整三级架构下，Tier 3 保持保守正增益上限 + Look-ahead 非对称 AGC + Freeze Gate，
        // 仅当 Tier 1 元数据或 Tier 2 11×3s / 全曲扫描完成时通过 handoffToStaticGain 锁定静态增益。
        allowStreamingAutoLock = false
    }
    var usbAudioSink: com.decent.usbaudio.UsbAudioSink? = null

    private val _loudnessState = MutableStateFlow(
        LoudnessNormalizationState(
            isEnabled = ThemePrefs.volumeNormalizationEnabled,
            targetLufs = ThemePrefs.targetLoudnessLufs,
            isNegativeGainOnly = ThemePrefs.loudnessNegativeGainOnly
        )
    )
    override val loudnessState: StateFlow<LoudnessNormalizationState> = _loudnessState.asStateFlow()

    private var loudnessJob: Job? = null
    private var interTrackWaitJob: Job? = null
    private var prefetchLoudnessJob: Job? = null
    private var persistPlaylistJob: Job? = null

    @Volatile
    private var upcomingAverageLufs: Float? = null

    @Volatile
    private var upcomingPrefetchedCount: Int = 0

    @Volatile
    private var upcomingPrefetchedTotalCount: Int = 0

    @Volatile
    private var isInterTrackWaiting: Boolean = false

    companion object {
        private const val INTER_TRACK_GAP_MS = 1000L
        private const val UPCOMING_PREFETCH_COUNT = 3

        /**
         * 兼容历史持久化队列：旧版 MainScreen 曾将已同步的网易云歌单保存为 LOCAL。
         * 若队列中存在 Online 曲目，或存在非 NCM 本地曲目却携带 http(s) 云端封面（仅云端匹配写入），
         * 则自动纠正为 ONLINE；纯本地文件夹与本地 .ncm 歌单保持 LOCAL 不受影响。
         */
        internal fun resolveRestoredSourceType(
            savedSource: PlaylistSourceType,
            items: List<PlayableMedia>
        ): PlaylistSourceType {
            if (savedSource != PlaylistSourceType.LOCAL || items.isEmpty()) return savedSource
            val hasOnlineTracks = items.any { it is PlayableMedia.Online }
            val hasCloudMatchedLocalTracks = items.any { media ->
                media is PlayableMedia.LocalNcm &&
                    !media.isNcm &&
                    (media.coverUrl?.startsWith("http://", ignoreCase = true) == true ||
                        media.coverUrl?.startsWith("https://", ignoreCase = true) == true)
            }
            return if (hasOnlineTracks || hasCloudMatchedLocalTracks) {
                PlaylistSourceType.ONLINE
            } else {
                savedSource
            }
        }

        internal fun sanitizeMediaForSource(
            media: PlayableMedia?,
            sourceType: PlaylistSourceType
        ): PlayableMedia? {
            if (sourceType == PlaylistSourceType.LOCAL &&
                media is PlayableMedia.LocalNcm &&
                !media.coverUrl.isNullOrBlank() &&
                !media.coverUrl.startsWith("file://") &&
                !media.coverUrl.startsWith("content://")
            ) {
                return media.copy(coverUrl = null)
            }
            return media
        }
    }

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
    @Volatile
    private var activeTrackTaskKey: String? = null

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

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (streamType == AudioManager.STREAM_MUSIC) {
                    syncVolumeFromSystem()
                }
            }
        }
    }

    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            syncVolumeFromSystem()
        }
    }

    private var currentFadeMultiplier: Float = 1.0f
    private var fadeJob: Job? = null
    private var isFadingOut: Boolean = false

    private fun readSystemVolumeRatio(): Float {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 1f
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) (current.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 1f
        } catch (_: Exception) {
            1f
        }
    }

    private fun isUsbBitPerfectActive(): Boolean {
        return usbAudioManager.isExclusiveModeEnabled.value &&
            usbAudioManager.connectedUsbDevice.value != null &&
            (usbAudioManager.usbDeviceDetails.value?.hasPermission == true)
    }

    private fun applyEffectiveVolume(fadeMultiplier: Float = currentFadeMultiplier) {
        currentFadeMultiplier = fadeMultiplier.coerceIn(0f, 1f)
        val baseVol = _volume.value
        val effectiveVol = (baseVol * currentFadeMultiplier).coerceIn(0f, 1f)

        // USB DAC 独占 / Bit-Perfect 模式下强制完全旁路音量均衡 DSP，不叠加任何响度增益系数
        usbAudioSink?.setExclusiveVolume(effectiveVol)

        val isUsbExclusiveActive = usbAudioManager.isExclusiveModeEnabled.value &&
            (usbAudioSink?.isExclusiveStreamActive() == true)
        exoPlayer.volume = if (isUsbExclusiveActive) 0f else effectiveVol
    }

    fun syncVolumeFromSystem() {
        val ratio = readSystemVolumeRatio()
        _volume.value = ratio
        applyEffectiveVolume()
    }

    private var fixedRepeatMode: RepeatMode = when (ThemePrefs.playbackRepeatMode) {
        "ONE" -> RepeatMode.ONE
        "ALL" -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }
    private var fixedShuffleEnabled: Boolean = ThemePrefs.playbackShuffleEnabled

    private val _repeatMode = MutableStateFlow(fixedRepeatMode)
    override val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(fixedShuffleEnabled)
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

    private val _infinitePlayMode = MutableStateFlow<InfinitePlayMode>(InfinitePlayMode.None)
    override val infinitePlayMode: StateFlow<InfinitePlayMode> = _infinitePlayMode.asStateFlow()

    private val _playlistSourceType = MutableStateFlow(PlaylistSourceType.NONE)
    override val playlistSourceType: StateFlow<PlaylistSourceType> = _playlistSourceType.asStateFlow()

    private val _isFetchingRecommendations = MutableStateFlow(false)
    override val isFetchingRecommendations: StateFlow<Boolean> = _isFetchingRecommendations.asStateFlow()

    /** Optional local library matcher so recommended online tracks automatically resolve to local NCM/FLAC files when matched */
    var localTrackMatcher: ((PlayableMedia.Online) -> PlayableMedia)? = null

    private val seenInfiniteTrackIds = ConcurrentHashMap.newKeySet<String>()
    private val fallbackRecommendationPool = mutableListOf<PlayableMedia.Online>()
    private var infiniteStartJob: Job? = null
    private var infinitePrefetchJob: Job? = null

    // Map mediaId to PlayableMedia for quick resolution
    private val mediaMap = ConcurrentHashMap<String, PlayableMedia>()
    private val resolvedUrlCache = ConcurrentHashMap<String, String>()
    private val resolvedDetailsCache = ConcurrentHashMap<String, Akari.NCM.player.api.ResolvedSongUrl>()
    private val inflightUrlFetches = ConcurrentHashMap<String, Deferred<Akari.NCM.player.api.ResolvedSongUrl?>>()

    @Volatile
    private var isAudioCacheInitialized = false

    // Disk Cache for online audio streaming (500MB max)
    private val audioCache: SimpleCache by lazy {
        isAudioCacheInitialized = true
        val cacheDir = File(context.cacheDir, "online_audio_cache")
        SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(500 * 1024 * 1024L))
    }

    @Volatile
    private var isReleased = false

    private val playbackPersistJson = Json { ignoreUnknownKeys = true }

    private var exoPlayer: ExoPlayer

    init {
        _volume.value = readSystemVolumeRatio()
        exoPlayer = createExoPlayer()
        restoreLastPlaybackState()
        startBackgroundObservers()
    }

    private fun saveLastPlaybackState(savePlaylistItems: Boolean = true) {
        val items = _playlist.value
        if (items.isEmpty()) return
        val idx = _currentIndex.value.coerceIn(0, items.lastIndex)
        val sourceType = _playlistSourceType.value.name
        runCatching {
            ThemePrefs.lastPlaybackIndex = idx
            ThemePrefs.lastPlaybackSourceType = sourceType
        }
        val shouldPersistList = savePlaylistItems || ThemePrefs.lastPlaybackPlaylistJson.isBlank()
        if (!shouldPersistList) return

        val snapshot = items.toList()
        persistPlaylistJob?.cancel()
        persistPlaylistJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val dtos = snapshot.map { PlayableMediaDTO.fromPlayableMedia(it) }
                ThemePrefs.lastPlaybackPlaylistJson = playbackPersistJson.encodeToString(dtos)
            }
        }
    }

    private fun restoreLastPlaybackState() {
        runCatching {
            val rawJson = ThemePrefs.lastPlaybackPlaylistJson
            if (rawJson.isBlank()) return
            val dtos = playbackPersistJson.decodeFromString<List<PlayableMediaDTO>>(rawJson)
            if (dtos.isEmpty()) return
            val items = dtos.map { it.toPlayableMedia() }
            val savedIndex = ThemePrefs.lastPlaybackIndex.coerceIn(0, items.lastIndex)
            val savedSource = runCatching {
                PlaylistSourceType.valueOf(ThemePrefs.lastPlaybackSourceType)
            }.getOrDefault(PlaylistSourceType.NONE)
            val resolvedSource = resolveRestoredSourceType(savedSource, items)
            if (resolvedSource != savedSource) {
                ThemePrefs.lastPlaybackSourceType = resolvedSource.name
                AppLogger.i(
                    "[AME_ONLINE_PLAY]",
                    ">>> Migrated legacy restored playlistSourceType from $savedSource to $resolvedSource (size=${items.size})"
                )
            }

            _playlistSourceType.value = resolvedSource
            mediaMap.clear()
            val mediaItems = items.mapIndexed { index, media ->
                mediaMap[media.id] = media
                buildMediaItem(media, includeLocalArtworkBytes = (index == savedIndex))
            }
            val restoredMedia = items[savedIndex]
            _playlist.value = items
            _currentIndex.value = savedIndex
            _currentMedia.value = sanitizeMediaForSource(restoredMedia, resolvedSource)
            _currentPositionMs.value = 0L
            _durationMs.value = restoredMedia.durationMs
            _isPlaying.value = false
            prepareLoudnessSync(restoredMedia)
            exoPlayer.playWhenReady = false
            exoPlayer.setMediaItems(mediaItems, savedIndex, 0L)
            onTrackChanged(restoredMedia)
        }
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
        // Audio-only renderer with Hi-Res safe AudioSink and decoder fallback
        // 注意：切勿开启 setEnableFloatOutput(true)。在 Media3 DefaultAudioSink 架构中，
        // 一旦开启浮点输出且输入为高解析度 PCM 时，DefaultAudioSink 会硬编码绕过并丢弃所有自定义 AudioProcessor（包括降采样与响度均衡），
        // 导致 >192kHz 音频无法进入 MaxSampleRateAudioProcessor 降采样，进而触发 AudioTrack.getMinBufferSize 越界抛出 IllegalStateException。
        val defaultAudioSink = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(
                MaxSampleRateAudioProcessor(192000),
                loudnessAudioProcessor
            ))
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(false)
            .build()

        val isUsbExclusiveActive = isUsbBitPerfectActive()
        loudnessAudioProcessor.usbExclusiveBypass = isUsbExclusiveActive
        val finalAudioSink = com.decent.usbaudio.UsbAudioSink(
            delegate = defaultAudioSink,
            context = context,
            config = com.decent.usbaudio.UsbAudioSinkConfig(
                bitPerfectEnabled = isUsbExclusiveActive,
                forceRouteToSpeaker = true
            ),
            loudnessAudioProcessor = loudnessAudioProcessor
        ).also {
            usbAudioSink = it
        }

        val audioOnlyFactory = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf(
                MediaCodecAudioRenderer(
                    context,
                    MediaCodecSelector.DEFAULT,
                    /* enableDecoderFallback = */ true,
                    handler,
                    audioListener,
                    finalAudioSink
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
                val cachedDetails = resolvedDetailsCache[media.id]
                val url = if (!cachedUrl.isNullOrBlank()) {
                    AppLogger.d("[AME_ONLINE_PLAY]", ">>> Using cached resolved URL for '${media.title}' (id=${media.id}): $cachedUrl")
                    if (_currentMedia.value?.id == media.id &&
                        ThemePrefs.volumeNormalizationEnabled &&
                        !isUsbBitPerfectActive()
                    ) {
                        val syncData = loudnessManager.getCachedLoudnessSync(
                            media = media,
                            qualityLevel = cachedDetails?.level.orEmpty(),
                            fileSignature = cachedDetails?.fileSignature.orEmpty()
                        )
                        if (syncData != null) {
                            loudnessAudioProcessor.resetForTrack(media.id, syncData)
                        }
                    }
                    cachedUrl
                } else {
                    AppLogger.i("[AME_ONLINE_PLAY]", ">>> Matched Online Track: id='${media.id}', title='${media.title}'")
                    val fetchedDetails = runBlocking { resolveOnlineSongDetails(media) }
                    if (fetchedDetails != null) {
                        val registered = loudnessManager.registerOnlineLoudness(
                            songId = media.id,
                            gainDb = fetchedDetails.gain,
                            peak = fetchedDetails.peak,
                            vdDb = fetchedDetails.vdDb,
                            isFreeTrial = fetchedDetails.isFreeTrial,
                            qualityLevel = fetchedDetails.level.orEmpty(),
                            fileSignature = fetchedDetails.fileSignature
                        )
                        if (_currentMedia.value?.id == media.id) {
                            if (registered != null &&
                                ThemePrefs.volumeNormalizationEnabled &&
                                !isUsbBitPerfectActive()
                            ) {
                                loudnessAudioProcessor.resetForTrack(media.id, registered)
                            }
                            scope.launch(Dispatchers.Main) {
                                if (_currentMedia.value?.id == media.id) {
                                    applyAudioFormatFromResolvedDetails(fetchedDetails)
                                    applyLoudnessNormalization(media)
                                }
                            }
                        }
                    }
                    fetchedDetails?.url
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
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // Audio offload disabled for maximum high-sample-rate stability (192kHz/384kHz)
        val offloadPrefs = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
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
        // 必须保持 false：让 ExoPlayer 在当前曲目自然播完时无缝自动推进到下一首，避免曲间音频输出中断
        player.pauseAtEndOfMediaItems = false

        // Player listener
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                AppLogger.i("[AME_ONLINE_PLAY]", ">>> ExoPlayer onIsPlayingChanged: $isPlaying")
                syncIslandState()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val reasonName = when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "USER_REQUEST"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "AUDIO_FOCUS_LOSS"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "AUDIO_BECOMING_NOISY"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "REMOTE"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "END_OF_MEDIA_ITEM"
                    Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "SUPPRESSED_TOO_LONG"
                    else -> "UNKNOWN($reason)"
                }
                AppLogger.i("[AME_ONLINE_PLAY]", ">>> ExoPlayer onPlayWhenReadyChanged: playWhenReady=$playWhenReady, reason=$reasonName")
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) {
                    AppLogger.w("[AME_ONLINE_PLAY]", ">>> ExoPlayer onPlaybackSuppressionReasonChanged: $playbackSuppressionReason")
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
                if (playbackState == Player.STATE_ENDED && _infinitePlayMode.value.isActive) {
                    checkAndPrefetchInfiniteQueue(autoAdvanceWhenAppended = true)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId
                AppLogger.i("[AME_ONLINE_PLAY]", ">>> ExoPlayer onMediaItemTransition: mediaId='$id', reason=$reason")
                val currentMedia = id?.let { mediaMap[it] }
                _currentMedia.value = sanitizeMediaForSource(currentMedia, effectiveSourceTypeFor(currentMedia))
                _currentIndex.value = player.currentMediaItemIndex
                _currentPositionMs.value = player.currentPosition.coerceAtLeast(0L)
                _durationMs.value = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L

                // 优先同步预置音频处理器增益（利用预加载的下一曲响度，零延迟且无需暂停打断音频输出）
                prepareLoudnessSync(currentMedia)

                if (currentMedia != null) {
                    saveLastPlaybackState(savePlaylistItems = false)
                }

                if (currentMedia is PlayableMedia.Online) {
                    val details = resolvedDetailsCache[currentMedia.id]
                    if (details != null) {
                        applyAudioFormatFromResolvedDetails(details)
                    } else {
                        updateAudioFormatFromExo(player.audioFormat)
                    }
                } else {
                    updateAudioFormatFromExo(player.audioFormat)
                }

                // 自然切歌（上一首播完自动进入下一首 / 单曲循环重复）保持无缝连续输出：
                // 不再强制调用 player.pause() 与 1s 等候 / 淡入静音，避免切歌后音频短暂暂停而播放状态继续推进
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                ) {
                    interTrackWaitJob?.cancel()
                    interTrackWaitJob = null
                    if (isInterTrackWaiting) {
                        isInterTrackWaiting = false
                        _loudnessState.value = _loudnessState.value.copy(isInterTrackWaiting = false)
                    }
                    if (!isFadingOut && fadeJob?.isActive != true && currentFadeMultiplier < 1.0f) {
                        applyEffectiveVolume(1.0f)
                    }
                    AppLogger.i(
                        "[AME_ONLINE_PLAY]",
                        ">>> Seamless auto-transition to '${currentMedia?.title ?: id}' (reason=$reason), keeping continuous audio output"
                    )
                }

                onTrackChanged(currentMedia)
                applyLoudnessNormalization(currentMedia)
                prefetchUpcomingTracksLoudness()
                syncIslandState()
                if (_infinitePlayMode.value.isActive) {
                    checkAndPrefetchInfiniteQueue(autoAdvanceWhenAppended = false)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLogger.e("[AME_ONLINE_PLAY]", ">>> ExoPlayer Playback ERROR: errorCode=${error.errorCode}, message=${error.message}, cause=${error.cause}", error)
                val errorMessage = error.message ?: "播放遇到了错误 (${error.errorCode})"
                _error.value = errorMessage
                _isPlaying.value = false
                _playbackState.value = PlaybackState.IDLE
                islandManager.cancelIsland()
                player.currentMediaItem?.mediaId?.let {
                    resolvedUrlCache.remove(it)
                    resolvedDetailsCache.remove(it)
                }
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (_infinitePlayMode.value.isActive) {
                    if (repeatMode != Player.REPEAT_MODE_OFF) {
                        player.repeatMode = Player.REPEAT_MODE_OFF
                    }
                    _repeatMode.value = RepeatMode.OFF
                    return
                }
                val mode = when (repeatMode) {
                    Player.REPEAT_MODE_OFF -> RepeatMode.OFF
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    else -> RepeatMode.OFF
                }
                fixedRepeatMode = mode
                _repeatMode.value = mode
                ThemePrefs.playbackRepeatMode = mode.name
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (_infinitePlayMode.value.isActive) {
                    if (shuffleModeEnabled) {
                        player.shuffleModeEnabled = false
                    }
                    _shuffleEnabled.value = false
                    return
                }
                fixedShuffleEnabled = shuffleModeEnabled
                _shuffleEnabled.value = shuffleModeEnabled
                ThemePrefs.playbackShuffleEnabled = shuffleModeEnabled
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val currentMedia = _currentMedia.value
                if (currentMedia is PlayableMedia.Online) {
                    val details = resolvedDetailsCache[currentMedia.id]
                    if (details != null) {
                        applyAudioFormatFromResolvedDetails(details)
                    } else {
                        updateAudioFormatFromExo(player.audioFormat)
                    }
                } else {
                    updateAudioFormatFromExo(player.audioFormat)
                }
            }
        })

        val currentDevice = usbAudioManager.connectedUsbDevice.value
        val isExclusiveActive = usbAudioManager.isExclusiveModeEnabled.value &&
            currentDevice != null &&
            (usbAudioManager.usbDeviceDetails.value?.hasPermission == true)
        finalAudioSink.setExclusiveVolume(_volume.value)
        if (isExclusiveActive) {
            player.setPreferredAudioDevice(null)
        } else if (currentDevice != null) {
            player.setPreferredAudioDevice(currentDevice)
        }

        finalAudioSink.attachToPlayer(player)

        return player
    }

    private fun startBackgroundObservers() {
        // Position ticker & in-app lyric sync & live island refresh
        scope.launch {
            var islandTickCounter = 0
            while (isActive) {
                if (_isPlaying.value) {
                    val pos = exoPlayer.currentPosition
                    _currentPositionMs.value = pos
                    _durationMs.value = exoPlayer.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                    val currentMedia = _currentMedia.value
                    if (currentMedia is PlayableMedia.Online) {
                        val details = resolvedDetailsCache[currentMedia.id]
                        if (details == null) {
                            updateAudioFormatFromExo(exoPlayer.audioFormat)
                        }
                    } else {
                        updateAudioFormatFromExo(exoPlayer.audioFormat)
                    }
                    val lyricChanged = updateCurrentLyricForPosition(pos)
                    islandTickCounter++
                    if (islandTickCounter % 5 == 0 &&
                        ThemePrefs.volumeNormalizationEnabled &&
                        !isUsbBitPerfectActive() &&
                        loudnessAudioProcessor.isRealtimeAgcRunning &&
                        currentMedia != null &&
                        loudnessAudioProcessor.currentMediaId == currentMedia.id
                    ) {
                        val liveDb = loudnessAudioProcessor.currentGainDbLive
                        val curState = _loudnessState.value
                        if (kotlin.math.abs(curState.appliedGainDb - liveDb) >= 0.1f) {
                            _loudnessState.value = curState.copy(
                                appliedGainDb = liveDb,
                                linearMultiplier = loudnessAudioProcessor.currentMultiplier,
                                source = if (curState.source == LoudnessSource.DEFAULT_FALLBACK) {
                                    LoudnessSource.REALTIME_AGC
                                } else {
                                    curState.source
                                }
                            )
                        }
                    }
                    if (isLiveIslandEnabled && (lyricChanged || islandTickCounter >= 20)) {
                        islandTickCounter = 0
                        syncIslandState()
                    }
                }
                delay(50.milliseconds)
            }
        }

        // Observe Live Island switch changes from ThemePrefs
        scope.launch {
            ThemePrefs.liveIslandEnabledFlow.collect {
                syncIslandState()
            }
        }

        // Observe playback fade enabled switch changes
        scope.launch {
            ThemePrefs.playbackFadeEnabledFlow.collect { enabled ->
                if (!enabled && fadeJob?.isActive == true) {
                    fadeJob?.cancel()
                    isFadingOut = false
                    applyEffectiveVolume(1.0f)
                }
            }
        }

        // USB Exclusive mode, device, and permission observer
        scope.launch {
            kotlinx.coroutines.flow.combine(
                usbAudioManager.isExclusiveModeEnabled,
                usbAudioManager.connectedUsbDevice,
                usbAudioManager.usbDeviceDetails
            ) { isExclusive, device, details ->
                Triple(isExclusive, device, details?.hasPermission == true)
            }.collect { (isExclusive, device, hasPermission) ->
                applyUsbAudioRouting(device, isExclusive, hasPermission)
            }
        }

        // Observe JYEffect preference changes to clear URL cache dynamically
        scope.launch {
            ThemePrefs.enableJyEffectFlow.collect { enabled ->
                AppLogger.i("[AME_ONLINE_PLAY]", ">>> JYEffect preference changed to $enabled, clearing resolved URL cache.")
                resolvedUrlCache.clear()
                resolvedDetailsCache.clear()
            }
        }

        // Observe Volume Normalization preference changes (including Negative Gain Only)
        scope.launch {
            kotlinx.coroutines.flow.combine(
                ThemePrefs.volumeNormalizationFlow,
                ThemePrefs.targetLoudnessFlow,
                ThemePrefs.loudnessPeakLimitFlow,
                ThemePrefs.loudnessNegativeGainOnlyFlow
            ) { enabled, targetLufs, peakLimit, negativeOnly ->
                listOf(enabled, targetLufs, peakLimit, negativeOnly)
            }.collect {
                applyLoudnessNormalization()
            }
        }

        loudnessAudioProcessor.onPreviewLoudnessMeasured = { mediaId, previewData ->
            val details = resolvedDetailsCache[mediaId]
            val platform = if (mediaMap[mediaId] is PlayableMedia.LocalNcm) {
                LoudnessManager.PLATFORM_LOCAL
            } else {
                LoudnessManager.PLATFORM_NETEASE
            }
            loudnessManager.saveLoudness(
                mediaId = mediaId,
                data = previewData,
                sourcePlatform = platform,
                qualityLevel = details?.level.orEmpty(),
                fileSignature = details?.fileSignature.orEmpty()
            )
            val cur = _currentMedia.value
            if (cur?.id == mediaId &&
                ThemePrefs.volumeNormalizationEnabled &&
                !isUsbBitPerfectActive() &&
                !loudnessAudioProcessor.isFinalLocked
            ) {
                val existingState = _loudnessState.value
                val sameTrack = existingState.currentMediaTitle == cur.title
                if (!sameTrack || previewData.source.priority >= existingState.source.priority) {
                    val target = ThemePrefs.targetLoudnessLufs
                    val negOnly = ThemePrefs.loudnessNegativeGainOnly
                    val gain = EbuR128Calculator.computeGain(
                        loudness = previewData,
                        targetLufs = target,
                        peakLimitEnabled = ThemePrefs.loudnessPeakLimitEnabled,
                        negativeGainOnly = negOnly
                    )
                    _loudnessState.value = LoudnessNormalizationState(
                        isEnabled = true,
                        targetLufs = target,
                        currentMediaTitle = cur.title,
                        sourceLoudnessLufs = previewData.integratedLufs,
                        sourcePeakDb = previewData.peakDb,
                        appliedGainDb = gain.effectiveGainDb,
                        linearMultiplier = gain.linearMultiplier,
                        isPeakLimited = gain.isPeakLimited,
                        isNegativeGainOnly = negOnly,
                        isBypassedForUsbExclusive = false,
                        source = previewData.source,
                        upcomingAverageLufs = upcomingAverageLufs,
                        prefetchedCount = upcomingPrefetchedCount,
                        prefetchedTotalCount = upcomingPrefetchedTotalCount,
                        isInterTrackWaiting = isInterTrackWaiting
                    )
                }
            }
        }

        loudnessAudioProcessor.onLoudnessMeasured = { mediaId, data ->
            val details = resolvedDetailsCache[mediaId]
            val platform = if (mediaMap[mediaId] is PlayableMedia.LocalNcm) {
                LoudnessManager.PLATFORM_LOCAL
            } else {
                LoudnessManager.PLATFORM_NETEASE
            }
            loudnessManager.saveLoudness(
                mediaId = mediaId,
                data = data,
                sourcePlatform = platform,
                qualityLevel = details?.level.orEmpty(),
                fileSignature = details?.fileSignature.orEmpty()
            )
            val cur = _currentMedia.value
            if (cur?.id == mediaId && !isUsbBitPerfectActive()) {
                val target = ThemePrefs.targetLoudnessLufs
                val negOnly = ThemePrefs.loudnessNegativeGainOnly
                val gain = EbuR128Calculator.computeGain(
                    loudness = data,
                    targetLufs = target,
                    peakLimitEnabled = ThemePrefs.loudnessPeakLimitEnabled,
                    negativeGainOnly = negOnly
                )
                loudnessAudioProcessor.setStaticGain(
                    gain.effectiveGainDb,
                    gain.linearMultiplier,
                    isFinal = true,
                    smoothSec = 0.6,
                    source = data.source
                )
                _loudnessState.value = LoudnessNormalizationState(
                    isEnabled = ThemePrefs.volumeNormalizationEnabled,
                    targetLufs = target,
                    currentMediaTitle = cur.title,
                    sourceLoudnessLufs = data.integratedLufs,
                    sourcePeakDb = data.peakDb,
                    appliedGainDb = gain.effectiveGainDb,
                    linearMultiplier = gain.linearMultiplier,
                    isPeakLimited = gain.isPeakLimited,
                    isNegativeGainOnly = negOnly,
                    isBypassedForUsbExclusive = false,
                    source = data.source,
                    upcomingAverageLufs = upcomingAverageLufs,
                    prefetchedCount = upcomingPrefetchedCount,
                    prefetchedTotalCount = upcomingPrefetchedTotalCount,
                    isInterTrackWaiting = isInterTrackWaiting
                )
            }
        }

        // Register system volume listener to keep player volume and USB DAC volume in sync
        try {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(volumeReceiver, filter)
            }
        } catch (e: Exception) {
            AppLogger.d("[AME_VOLUME]", "Volume broadcast registration skipped: ${e.message}")
        }
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver
            )
        } catch (e: Exception) {
            AppLogger.d("[AME_VOLUME]", "Volume content observer registration skipped: ${e.message}")
        }
        syncVolumeFromSystem()
    }

    private var scanJob: Job? = null

    @Volatile
    private var activeTier2ScanMediaId: String? = null

    private fun effectiveSourceTypeFor(media: PlayableMedia?): PlaylistSourceType {
        return when (val current = _playlistSourceType.value) {
            PlaylistSourceType.ONLINE -> PlaylistSourceType.ONLINE
            PlaylistSourceType.LOCAL -> PlaylistSourceType.LOCAL
            PlaylistSourceType.NONE -> if (media is PlayableMedia.Online) PlaylistSourceType.ONLINE else PlaylistSourceType.LOCAL
        }
    }

    private fun resolveLocalFileSignature(media: PlayableMedia.LocalNcm): String {
        val bytes = runCatching {
            val uri = Uri.parse(media.uri)
            if (uri.scheme == "content") {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            } else {
                File(uri.path ?: media.uri).length()
            }
        }.getOrDefault(0L)
        return if (bytes > 0L) "size:$bytes" else ""
    }

    /**
     * 统一下载/解析在线曲目 URL 详情并自动去重并发请求
     */
    private suspend fun resolveOnlineSongDetails(media: PlayableMedia.Online): Akari.NCM.player.api.ResolvedSongUrl? {
        resolvedDetailsCache[media.id]?.let { cached ->
            if (resolvedUrlCache[media.id] == cached.url) return cached
        }
        val existingDeferred = inflightUrlFetches[media.id]
        if (existingDeferred != null && existingDeferred.isActive) {
            return existingDeferred.await()
        }
        val deferred = scope.async(Dispatchers.IO) {
            try {
                val details = ncmApi.getSongUrlDetails(media, ThemePrefs.enableJyEffect)
                if (details != null) {
                    resolvedUrlCache[media.id] = details.url
                    resolvedDetailsCache[media.id] = details
                    loudnessManager.registerOnlineLoudness(
                        songId = media.id,
                        gainDb = details.gain,
                        peak = details.peak,
                        vdDb = details.vdDb,
                        isFreeTrial = details.isFreeTrial,
                        qualityLevel = details.level.orEmpty(),
                        fileSignature = details.fileSignature
                    )
                }
                details
            } finally {
                inflightUrlFetches.remove(media.id)
            }
        }
        inflightUrlFetches[media.id] = deferred
        return deferred.await()
    }

    /**
     * 切歌瞬间同步预置音频处理器的初始增益（P0 零延迟，避免首帧 PCM 漏出未均衡音频）
     */
    private fun prepareLoudnessSync(media: PlayableMedia?) {
        val usbBypass = isUsbBitPerfectActive()
        val negOnly = ThemePrefs.loudnessNegativeGainOnly
        if (media == null || !ThemePrefs.volumeNormalizationEnabled || usbBypass) {
            loudnessAudioProcessor.updateSettings(
                enabled = ThemePrefs.volumeNormalizationEnabled,
                target = ThemePrefs.targetLoudnessLufs,
                peakLimit = ThemePrefs.loudnessPeakLimitEnabled,
                negativeGainOnly = negOnly,
                usbExclusiveBypass = usbBypass
            )
            loudnessAudioProcessor.resetForTrack(media?.id, null)
            return
        }
        loudnessAudioProcessor.updateSettings(
            enabled = true,
            target = ThemePrefs.targetLoudnessLufs,
            peakLimit = ThemePrefs.loudnessPeakLimitEnabled,
            negativeGainOnly = negOnly,
            usbExclusiveBypass = false
        )
        val details = resolvedDetailsCache[media.id]
        val sourceType = effectiveSourceTypeFor(media)
        val qLevel = if (media is PlayableMedia.LocalNcm) "local" else details?.level.orEmpty()
        val fileSig = if (media is PlayableMedia.LocalNcm) resolveLocalFileSignature(media) else details?.fileSignature.orEmpty()
        val cached = loudnessManager.getCachedLoudnessSync(
            media = media,
            qualityLevel = qLevel,
            fileSignature = fileSig,
            sourceType = sourceType
        )
        if (cached != null) {
            loudnessAudioProcessor.resetForTrack(media.id, cached)
            return
        }
        val avg = upcomingAverageLufs
        if (avg != null) {
            val prior = LoudnessData(avg, 1.0f, LoudnessSource.QUEUE_AVERAGE)
            loudnessAudioProcessor.resetForTrack(media.id, prior)
        } else {
            loudnessAudioProcessor.resetForTrack(media.id, null)
        }
    }

    /**
     * Tier 2 异步 11×3s 等距稀疏探测（HTTP Range 并发拉取 + 容器索引寻址）：
     * - 延迟 ~350ms 避让首帧缓冲带宽竞争，确保起播丝滑；
     * - 优先并发拉取 20%/40%/60%/80% 中段切片快速给出可靠初判；
     * - 全 11 段完成后合并做 400ms/75% 双重门控 + P75 锚点 + 4x 真峰值锁定，
     *   写入 P0 SQLite 缓存，并通过 1.2s 对数斜坡无缝交接至静态模式；
     * - 若服务端不支持 HTTP Range 或失败，无缝交由 Tier 3 Look-ahead 非对称 AGC + Freeze Gate 兜底。
     */
    private fun scanTrackLoudnessTier2(media: PlayableMedia, deferForFirstFrame: Boolean) {
        if (scanJob?.isActive == true && activeTier2ScanMediaId == media.id) {
            return
        }
        scanJob?.cancel()
        activeTier2ScanMediaId = media.id
        scanJob = scope.launch(Dispatchers.IO) {
            try {
            if (isUsbBitPerfectActive()) return@launch

            val platform: String
            var qualityLevel = ""
            var fileSignature = ""
            val dataSource: String? = when (media) {
                is PlayableMedia.LocalNcm -> {
                    platform = LoudnessManager.PLATFORM_LOCAL
                    qualityLevel = "local"
                    fileSignature = resolveLocalFileSignature(media)
                    media.uri
                }
                is PlayableMedia.Online -> {
                    platform = LoudnessManager.PLATFORM_NETEASE
                    val details = resolvedDetailsCache[media.id] ?: resolveOnlineSongDetails(media)
                    if (details != null) {
                        qualityLevel = details.level.orEmpty()
                        fileSignature = details.fileSignature
                        val registered = loudnessManager.registerOnlineLoudness(
                            songId = media.id,
                            gainDb = details.gain,
                            peak = details.peak,
                            vdDb = details.vdDb,
                            isFreeTrial = details.isFreeTrial,
                            qualityLevel = qualityLevel,
                            fileSignature = fileSignature
                        )
                        if (registered != null) {
                            withContext(Dispatchers.Main) {
                                if (_currentMedia.value?.id == media.id &&
                                    ThemePrefs.volumeNormalizationEnabled &&
                                    !isUsbBitPerfectActive()
                                ) {
                                    applyLoudnessNormalization(media)
                                }
                            }
                            return@launch
                        }
                        details.url
                    } else {
                        resolvedUrlCache[media.id]
                    }
                }
            }

            if (dataSource.isNullOrBlank() || !isActive || loudnessAudioProcessor.isFinalLocked) return@launch

            // 在线首帧缓冲优先策略（改善计划 §4.1）：优先保证播放缓冲区前 5 秒下载完成，
            // 再以 3–4 并发 HTTP Range 请求拉取其余稀疏切片，避免阻塞首帧起播（最长等待 2.5s 防卡死）。
            if (deferForFirstFrame && media is PlayableMedia.Online) {
                val waitStart = SystemClock.elapsedRealtime()
                while (isActive && SystemClock.elapsedRealtime() - waitStart < 2_500L) {
                    val hasInitialBuffer = withContext(Dispatchers.Main) {
                        if (_currentMedia.value?.id != media.id) return@withContext true
                        val bufferedAhead = (exoPlayer.bufferedPosition - exoPlayer.currentPosition).coerceAtLeast(0L)
                        val dur = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                        val targetAhead = if (dur in 1L..5_000L) (dur * 8 / 10).coerceAtLeast(1_000L) else 5_000L
                        bufferedAhead >= targetAhead || exoPlayer.playbackState == Player.STATE_ENDED
                    }
                    if (hasInitialBuffer) break
                    delay(120L)
                }
            }
            if (!isActive || loudnessAudioProcessor.isFinalLocked || isUsbBitPerfectActive()) return@launch

            val sparseAnalysis = Akari.NCM.player.core.loudness.TrackLoudnessScanner.scanTrackSparse11x3s(
                context = context,
                dataSource = dataSource,
                onMidSegmentsReady = { midPreview ->
                    if (!loudnessAudioProcessor.isFinalLocked &&
                        !isUsbBitPerfectActive() &&
                        midPreview.source.priority >= loudnessAudioProcessor.currentSource.priority
                    ) {
                        loudnessManager.saveLoudness(
                            mediaId = media.id,
                            data = midPreview,
                            sourcePlatform = platform,
                            qualityLevel = qualityLevel,
                            fileSignature = fileSignature
                        )
                        scope.launch(Dispatchers.Main) {
                            if (_currentMedia.value?.id == media.id &&
                                ThemePrefs.volumeNormalizationEnabled &&
                                !isUsbBitPerfectActive() &&
                                !loudnessAudioProcessor.isFinalLocked &&
                                midPreview.source.priority >= loudnessAudioProcessor.currentSource.priority
                            ) {
                                val target = ThemePrefs.targetLoudnessLufs
                                val negOnly = ThemePrefs.loudnessNegativeGainOnly
                                val gain = EbuR128Calculator.computeGain(
                                    loudness = midPreview,
                                    targetLufs = target,
                                    peakLimitEnabled = ThemePrefs.loudnessPeakLimitEnabled,
                                    negativeGainOnly = negOnly
                                )
                                loudnessAudioProcessor.setStaticGain(
                                    gain.effectiveGainDb,
                                    gain.linearMultiplier,
                                    isFinal = false,
                                    smoothSec = 0.5,
                                    source = midPreview.source
                                )
                                _loudnessState.value = LoudnessNormalizationState(
                                    isEnabled = true,
                                    targetLufs = target,
                                    currentMediaTitle = media.title,
                                    sourceLoudnessLufs = midPreview.integratedLufs,
                                    sourcePeakDb = midPreview.peakDb,
                                    appliedGainDb = gain.effectiveGainDb,
                                    linearMultiplier = gain.linearMultiplier,
                                    isPeakLimited = gain.isPeakLimited,
                                    isNegativeGainOnly = negOnly,
                                    isBypassedForUsbExclusive = false,
                                    source = midPreview.source,
                                    upcomingAverageLufs = upcomingAverageLufs,
                                    prefetchedCount = upcomingPrefetchedCount,
                                    prefetchedTotalCount = upcomingPrefetchedTotalCount,
                                    isInterTrackWaiting = isInterTrackWaiting
                                )
                            }
                        }
                    }
                }
            )

            if (sparseAnalysis != null && isActive && !isUsbBitPerfectActive()) {
                val finalData = sparseAnalysis.loudnessData
                loudnessManager.saveLoudness(
                    mediaId = media.id,
                    data = finalData,
                    sourcePlatform = platform,
                    qualityLevel = qualityLevel,
                    fileSignature = fileSignature
                )
                if (media is PlayableMedia.LocalNcm && media.isNcm && effectiveSourceTypeFor(media) == PlaylistSourceType.ONLINE) {
                    val musicId = runCatching {
                        NcmDataSource.scanMetadata(context.contentResolver, Uri.parse(media.uri))?.first?.musicId?.toString()
                    }.getOrNull()
                    if (!musicId.isNullOrBlank() && musicId != "0" && musicId != media.id) {
                        loudnessManager.saveLoudness(
                            mediaId = musicId,
                            data = finalData,
                            sourcePlatform = LoudnessManager.PLATFORM_NETEASE
                        )
                    }
                }
                withContext(Dispatchers.Main) {
                    if (_currentMedia.value?.id == media.id &&
                        ThemePrefs.volumeNormalizationEnabled &&
                        !isUsbBitPerfectActive()
                    ) {
                        val target = ThemePrefs.targetLoudnessLufs
                        val negOnly = ThemePrefs.loudnessNegativeGainOnly
                        // 1.2s 对数斜坡无缝交接至锁定静态增益
                        val gain = loudnessAudioProcessor.handoffToStaticGain(
                            loudness = finalData,
                            rampDurationSec = LoudnessAudioProcessor.HANDOFF_RAMP_SEC
                        )
                        _loudnessState.value = LoudnessNormalizationState(
                            isEnabled = true,
                            targetLufs = target,
                            currentMediaTitle = media.title,
                            sourceLoudnessLufs = finalData.integratedLufs,
                            sourcePeakDb = finalData.peakDb,
                            appliedGainDb = gain.effectiveGainDb,
                            linearMultiplier = gain.linearMultiplier,
                            isPeakLimited = gain.isPeakLimited,
                            isNegativeGainOnly = negOnly,
                            isBypassedForUsbExclusive = false,
                            source = finalData.source,
                            upcomingAverageLufs = upcomingAverageLufs,
                            prefetchedCount = upcomingPrefetchedCount,
                            prefetchedTotalCount = upcomingPrefetchedTotalCount,
                            isInterTrackWaiting = isInterTrackWaiting
                        )
                        val totalSegs = sparseAnalysis.keptSegmentIndices.size + sparseAnalysis.filteredSegmentIndices.size
                        AppLogger.i(
                            "[AME_LOUDNESS]",
                            "Tier 2 handoff locked (${finalData.source.label}): '${media.title}', " +
                                "lufs=${finalData.integratedLufs} (keptSeg=${sparseAnalysis.keptSegmentIndices.size}/$totalSegs, LRA=${sparseAnalysis.estimatedLraLu} LU), " +
                                "peak=${finalData.peakDb} dBTP, gain=${gain.effectiveGainDb} dB"
                        )
                    }
                }
            } else if (isActive && !loudnessAudioProcessor.isFinalLocked && !isUsbBitPerfectActive()) {
                // Tier 2 不可用（如流媒体不支持 HTTP Range），保持 Tier 3 实时前瞻 AGC + Freeze Gate 兜底
                withContext(Dispatchers.Main) {
                    if (_currentMedia.value?.id == media.id &&
                        ThemePrefs.volumeNormalizationEnabled &&
                        !isUsbBitPerfectActive() &&
                        !loudnessAudioProcessor.isFinalLocked
                    ) {
                        _loudnessState.value = _loudnessState.value.copy(
                            source = LoudnessSource.REALTIME_AGC,
                            isNegativeGainOnly = ThemePrefs.loudnessNegativeGainOnly,
                            isBypassedForUsbExclusive = false
                        )
                    }
                }
                AppLogger.i("[AME_LOUDNESS]", "Tier 2 sparse range unavailable for '${media.title}', staying on Tier 3 Look-ahead AGC + Freeze Gate")
            }
            } finally {
                if (activeTier2ScanMediaId == media.id) {
                    activeTier2ScanMediaId = null
                }
            }
        }
    }

    private fun applyLoudnessNormalization(media: PlayableMedia? = _currentMedia.value) {
        val isEnabled = ThemePrefs.volumeNormalizationEnabled
        val targetLufs = ThemePrefs.targetLoudnessLufs
        val peakLimitEnabled = ThemePrefs.loudnessPeakLimitEnabled
        val negativeGainOnly = ThemePrefs.loudnessNegativeGainOnly
        val usbBypass = isUsbBitPerfectActive()

        loudnessAudioProcessor.updateSettings(
            enabled = isEnabled,
            target = targetLufs,
            peakLimit = peakLimitEnabled,
            negativeGainOnly = negativeGainOnly,
            usbExclusiveBypass = usbBypass
        )

        if (usbBypass) {
            scanJob?.cancel()
            loudnessJob?.cancel()
            _loudnessState.value = LoudnessNormalizationState(
                isEnabled = isEnabled,
                targetLufs = targetLufs,
                currentMediaTitle = media?.title,
                appliedGainDb = 0f,
                linearMultiplier = 1f,
                isPeakLimited = false,
                isNegativeGainOnly = negativeGainOnly,
                isBypassedForUsbExclusive = true,
                upcomingAverageLufs = upcomingAverageLufs,
                prefetchedCount = upcomingPrefetchedCount,
                prefetchedTotalCount = upcomingPrefetchedTotalCount,
                isInterTrackWaiting = isInterTrackWaiting
            )
            return
        }

        if (media == null || !isEnabled) {
            scanJob?.cancel()
            _loudnessState.value = LoudnessNormalizationState(
                isEnabled = isEnabled,
                targetLufs = targetLufs,
                currentMediaTitle = media?.title,
                isNegativeGainOnly = negativeGainOnly,
                isBypassedForUsbExclusive = false,
                upcomingAverageLufs = upcomingAverageLufs,
                prefetchedCount = upcomingPrefetchedCount,
                prefetchedTotalCount = upcomingPrefetchedTotalCount,
                isInterTrackWaiting = isInterTrackWaiting
            )
            return
        }

        loudnessJob?.cancel()
        loudnessJob = scope.launch {
            val details = resolvedDetailsCache[media.id]
            val sourceType = effectiveSourceTypeFor(media)
            val qLevel = if (media is PlayableMedia.LocalNcm) "local" else details?.level.orEmpty()
            val fileSig = if (media is PlayableMedia.LocalNcm) resolveLocalFileSignature(media) else details?.fileSignature.orEmpty()
            val loudnessData = loudnessManager.getLoudness(
                media = media,
                qualityLevel = qLevel,
                fileSignature = fileSig,
                sourceType = sourceType
            )

            if (loudnessData != null && loudnessData.source != LoudnessSource.DEFAULT_FALLBACK) {
                val gainResult = EbuR128Calculator.computeGain(
                    loudness = loudnessData,
                    targetLufs = targetLufs,
                    peakLimitEnabled = peakLimitEnabled,
                    negativeGainOnly = negativeGainOnly
                )

                val isLocked = loudnessData.source.isFinalLocked
                if (loudnessAudioProcessor.currentMediaId != media.id || loudnessAudioProcessor.staticGainDb == null) {
                    loudnessAudioProcessor.resetForTrack(media.id, loudnessData)
                } else {
                    loudnessAudioProcessor.setStaticGain(
                        gainResult.effectiveGainDb,
                        gainResult.linearMultiplier,
                        isFinal = isLocked,
                        smoothSec = 0.25,
                        source = loudnessData.source
                    )
                }

                _loudnessState.value = LoudnessNormalizationState(
                    isEnabled = true,
                    targetLufs = targetLufs,
                    currentMediaTitle = media.title,
                    sourceLoudnessLufs = loudnessData.integratedLufs,
                    sourcePeakDb = loudnessData.peakDb,
                    appliedGainDb = gainResult.effectiveGainDb,
                    linearMultiplier = gainResult.linearMultiplier,
                    isPeakLimited = gainResult.isPeakLimited,
                    isNegativeGainOnly = negativeGainOnly,
                    isBypassedForUsbExclusive = false,
                    source = loudnessData.source,
                    upcomingAverageLufs = upcomingAverageLufs,
                    prefetchedCount = upcomingPrefetchedCount,
                    prefetchedTotalCount = upcomingPrefetchedTotalCount,
                    isInterTrackWaiting = isInterTrackWaiting
                )
                AppLogger.i(
                    "[AME_LOUDNESS]",
                    "Applied ${loudnessData.source.tierLabel} (${loudnessData.source.label}): track='${media.title}', " +
                        "source=${loudnessData.integratedLufs} LUFS, target=$targetLufs LUFS, gain=${gainResult.effectiveGainDb} dB, negOnly=$negativeGainOnly"
                )

                // 若当前仅为预热初判，后台继续完成 Tier 2 11×3s 稀疏探测并 1.2s 无缝锁定
                if (!isLocked) {
                    scanTrackLoudnessTier2(media, deferForFirstFrame = media is PlayableMedia.Online)
                }
            } else {
                val avg = upcomingAverageLufs
                if (avg != null) {
                    val prior = LoudnessData(avg, 1.0f, LoudnessSource.QUEUE_AVERAGE)
                    val priorGain = EbuR128Calculator.computeGain(
                        loudness = prior,
                        targetLufs = targetLufs,
                        peakLimitEnabled = peakLimitEnabled,
                        negativeGainOnly = negativeGainOnly
                    )
                    loudnessAudioProcessor.resetForTrack(media.id, prior)
                    _loudnessState.value = LoudnessNormalizationState(
                        isEnabled = true,
                        targetLufs = targetLufs,
                        currentMediaTitle = media.title,
                        sourceLoudnessLufs = prior.integratedLufs,
                        sourcePeakDb = null,
                        appliedGainDb = priorGain.effectiveGainDb,
                        linearMultiplier = priorGain.linearMultiplier,
                        isPeakLimited = priorGain.isPeakLimited,
                        isNegativeGainOnly = negativeGainOnly,
                        isBypassedForUsbExclusive = false,
                        source = LoudnessSource.QUEUE_AVERAGE,
                        upcomingAverageLufs = avg,
                        prefetchedCount = upcomingPrefetchedCount,
                        prefetchedTotalCount = upcomingPrefetchedTotalCount,
                        isInterTrackWaiting = isInterTrackWaiting
                    )
                } else {
                    val alreadyArmedTier3 = loudnessAudioProcessor.currentMediaId == media.id &&
                        loudnessAudioProcessor.isRealtimeAgcRunning
                    if (!alreadyArmedTier3) {
                        loudnessAudioProcessor.resetForTrack(media.id, null)
                    }
                    _loudnessState.value = LoudnessNormalizationState(
                        isEnabled = true,
                        targetLufs = targetLufs,
                        currentMediaTitle = media.title,
                        appliedGainDb = if (alreadyArmedTier3) loudnessAudioProcessor.currentGainDbLive else 0f,
                        linearMultiplier = if (alreadyArmedTier3) loudnessAudioProcessor.currentMultiplier else 1f,
                        isNegativeGainOnly = negativeGainOnly,
                        isBypassedForUsbExclusive = false,
                        source = LoudnessSource.REALTIME_AGC,
                        upcomingAverageLufs = upcomingAverageLufs,
                        prefetchedCount = upcomingPrefetchedCount,
                        prefetchedTotalCount = upcomingPrefetchedTotalCount,
                        isInterTrackWaiting = isInterTrackWaiting
                    )
                }
                AppLogger.i("[AME_LOUDNESS]", "P0/Tier 1 miss for '${media.title}', launching Tier 2 11x3s sparse scan with Tier 3 Look-ahead AGC active")
                scanTrackLoudnessTier2(media, deferForFirstFrame = media is PlayableMedia.Online)
            }
        }
    }

    /**
     * 提取未来最多 3 首待播曲目（兼容顺序、列表循环与随机播放）
     */
    private fun getUpcomingTracks(count: Int = UPCOMING_PREFETCH_COUNT): List<PlayableMedia> {
        val list = _playlist.value
        if (list.size <= 1) return emptyList()

        val result = mutableListOf<PlayableMedia>()
        val seenIds = HashSet<String>()
        _currentMedia.value?.id?.let { seenIds.add(it) }

        val timeline = exoPlayer.currentTimeline
        if (!timeline.isEmpty && exoPlayer.mediaItemCount > 0) {
            var idx = exoPlayer.currentMediaItemIndex
            val repeat = exoPlayer.repeatMode
            val shuffle = exoPlayer.shuffleModeEnabled
            for (step in 0 until count) {
                val nextIdx = timeline.getNextWindowIndex(idx, repeat, shuffle)
                if (nextIdx == C.INDEX_UNSET || nextIdx == exoPlayer.currentMediaItemIndex) break
                val mediaId = exoPlayer.getMediaItemAt(nextIdx).mediaId
                val media = mediaMap[mediaId] ?: list.getOrNull(nextIdx)
                if (media != null && seenIds.add(media.id)) {
                    result.add(media)
                }
                idx = nextIdx
            }
        }

        if (result.isEmpty()) {
            val curIdx = _currentIndex.value
            for (offset in 1..count) {
                val nextIdx = curIdx + offset
                val media = if (nextIdx < list.size) {
                    list[nextIdx]
                } else if (_repeatMode.value == RepeatMode.ALL && list.isNotEmpty()) {
                    list[nextIdx % list.size]
                } else {
                    null
                }
                if (media != null && seenIds.add(media.id)) {
                    result.add(media)
                }
            }
        }
        return result
    }

    /**
     * 后台预获取并预计算未来 3 首歌曲（含非本地在线曲目）的响度与平均响度基准（提前写入 P0 SQLite 缓存）
     */
    private fun prefetchUpcomingTracksLoudness() {
        val upcoming = getUpcomingTracks(UPCOMING_PREFETCH_COUNT)
        val (initialAvg, initialReady) = loudnessManager.computeUpcomingAverageLoudness(upcoming)
        upcomingAverageLufs = initialAvg
        upcomingPrefetchedCount = initialReady
        upcomingPrefetchedTotalCount = upcoming.size
        _loudnessState.value = _loudnessState.value.copy(
            upcomingAverageLufs = initialAvg,
            prefetchedCount = initialReady,
            prefetchedTotalCount = upcoming.size
        )

        prefetchLoudnessJob?.cancel()
        if (upcoming.isEmpty()) return

        prefetchLoudnessJob = scope.launch(Dispatchers.IO) {
            for (track in upcoming) {
                if (!isActive) break
                val cachedDetails = resolvedDetailsCache[track.id]
                val trackSourceType = effectiveSourceTypeFor(track)
                val qLevel = if (track is PlayableMedia.LocalNcm) "local" else cachedDetails?.level.orEmpty()
                val fileSig = if (track is PlayableMedia.LocalNcm) resolveLocalFileSignature(track) else cachedDetails?.fileSignature.orEmpty()
                var resolved = loudnessManager.getLoudness(
                    media = track,
                    qualityLevel = qLevel,
                    fileSignature = fileSig,
                    sourceType = trackSourceType
                )

                if (track is PlayableMedia.Online) {
                    val needUrl = resolvedUrlCache[track.id].isNullOrBlank()
                    if (needUrl || resolved == null) {
                        val details = resolveOnlineSongDetails(track)
                        if (details != null) {
                            val cloudLoudness = loudnessManager.registerOnlineLoudness(
                                songId = track.id,
                                gainDb = details.gain,
                                peak = details.peak,
                                vdDb = details.vdDb,
                                isFreeTrial = details.isFreeTrial,
                                qualityLevel = details.level.orEmpty(),
                                fileSignature = details.fileSignature
                            )
                            if (cloudLoudness != null) {
                                resolved = cloudLoudness
                            } else if (resolved == null) {
                                val sparse = Akari.NCM.player.core.loudness.TrackLoudnessScanner.scanTrackSparse11x3s(
                                    context = context,
                                    dataSource = details.url
                                )?.loudnessData
                                if (sparse != null && isActive) {
                                    loudnessManager.saveLoudness(
                                        mediaId = track.id,
                                        data = sparse,
                                        sourcePlatform = LoudnessManager.PLATFORM_NETEASE,
                                        qualityLevel = details.level.orEmpty(),
                                        fileSignature = details.fileSignature
                                    )
                                    resolved = sparse
                                }
                            }
                        }
                    }
                } else if (track is PlayableMedia.LocalNcm && resolved == null) {
                    val sparse = Akari.NCM.player.core.loudness.TrackLoudnessScanner.scanTrackSparse11x3s(
                        context = context,
                        dataSource = track.uri
                    )?.loudnessData
                    if (sparse != null && isActive) {
                        loudnessManager.saveLoudness(
                            mediaId = track.id,
                            data = sparse,
                            sourcePlatform = LoudnessManager.PLATFORM_LOCAL,
                            qualityLevel = "local",
                            fileSignature = resolveLocalFileSignature(track)
                        )
                        resolved = sparse
                    }
                }

                val (avg, readyCount) = loudnessManager.computeUpcomingAverageLoudness(upcoming, _playlistSourceType.value)
                upcomingAverageLufs = avg
                upcomingPrefetchedCount = readyCount
                upcomingPrefetchedTotalCount = upcoming.size
                withContext(Dispatchers.Main) {
                    _loudnessState.value = _loudnessState.value.copy(
                        upcomingAverageLufs = avg,
                        prefetchedCount = readyCount,
                        prefetchedTotalCount = upcoming.size
                    )
                }
            }
            AppLogger.d(
                "[AME_LOUDNESS]",
                "Prefetched ${upcomingPrefetchedCount}/${upcoming.size} upcoming tracks, avgLufs=${upcomingAverageLufs}"
            )
        }
    }

    /**
     * 在每首歌之间执行 1 秒等候窗口，并在窗口内完成目标歌曲响度锁定后无延迟入耳起播
     */
    private fun startInterTrackWaitAndResume(media: PlayableMedia?) {
        interTrackWaitJob?.cancel()
        isInterTrackWaiting = true
        _loudnessState.value = _loudnessState.value.copy(isInterTrackWaiting = true)

        interTrackWaitJob = scope.launch {
            val waitStartMs = SystemClock.elapsedRealtime()
            try {
                if (ThemePrefs.volumeNormalizationEnabled && !isUsbBitPerfectActive() && media != null) {
                    withTimeoutOrNull(880L) {
                        withContext(Dispatchers.IO) {
                            val cachedDetails = resolvedDetailsCache[media.id]
                            val sourceType = effectiveSourceTypeFor(media)
                            val qLevel = if (media is PlayableMedia.LocalNcm) "local" else cachedDetails?.level.orEmpty()
                            val fileSig = if (media is PlayableMedia.LocalNcm) resolveLocalFileSignature(media) else cachedDetails?.fileSignature.orEmpty()
                            var data = loudnessManager.getLoudness(
                                media = media,
                                qualityLevel = qLevel,
                                fileSignature = fileSig,
                                sourceType = sourceType
                            )
                            if (data == null && media is PlayableMedia.Online) {
                                val details = cachedDetails ?: resolveOnlineSongDetails(media)
                                if (details != null) {
                                    data = loudnessManager.registerOnlineLoudness(
                                        songId = media.id,
                                        gainDb = details.gain,
                                        peak = details.peak,
                                        vdDb = details.vdDb,
                                        isFreeTrial = details.isFreeTrial,
                                        qualityLevel = details.level.orEmpty(),
                                        fileSignature = details.fileSignature
                                    )
                                    if (data == null) {
                                        data = Akari.NCM.player.core.loudness.TrackLoudnessScanner.scanTrackFast(context, details.url)
                                        if (data != null) {
                                            loudnessManager.saveLoudness(
                                                mediaId = media.id,
                                                data = data,
                                                sourcePlatform = LoudnessManager.PLATFORM_NETEASE,
                                                qualityLevel = details.level.orEmpty(),
                                                fileSignature = details.fileSignature
                                            )
                                        }
                                    }
                                }
                            } else if (data == null && media is PlayableMedia.LocalNcm) {
                                data = Akari.NCM.player.core.loudness.TrackLoudnessScanner.scanTrackFast(context, media.uri)
                                if (data != null) {
                                    loudnessManager.saveLoudness(
                                        mediaId = media.id,
                                        data = data,
                                        sourcePlatform = LoudnessManager.PLATFORM_LOCAL,
                                        qualityLevel = "local",
                                        fileSignature = fileSig
                                    )
                                }
                            }
                            if (data != null) {
                                loudnessAudioProcessor.resetForTrack(media.id, data)
                            }
                        }
                    }
                }

                val elapsed = SystemClock.elapsedRealtime() - waitStartMs
                val remainingMs = INTER_TRACK_GAP_MS - elapsed
                if (remainingMs > 0L) {
                    delay(remainingMs)
                }
            } finally {
                isInterTrackWaiting = false
                _loudnessState.value = _loudnessState.value.copy(isInterTrackWaiting = false)
            }

            if (_currentMedia.value?.id == media?.id || media == null) {
                applyLoudnessNormalization(media)
                interTrackWaitJob = null
                play()
            } else {
                interTrackWaitJob = null
            }
        }
    }

    /**
     * 带 1 秒曲间等候过渡的切歌执行器
     */
    private fun transitionToTrackWithGap(performSeek: () -> Unit) {
        ensureActive()
        interTrackWaitJob?.cancel()
        fadeJob?.cancel()
        isFadingOut = false
        exoPlayer.pause()
        performSeek()
        exoPlayer.prepare()
        val idx = exoPlayer.currentMediaItemIndex
        val targetMedia = exoPlayer.currentMediaItem?.mediaId?.let { mediaMap[it] }
            ?: _playlist.value.getOrNull(idx)
        _currentIndex.value = idx
        _currentMedia.value = targetMedia
        _currentPositionMs.value = 0L
        prepareLoudnessSync(targetMedia)
        startInterTrackWaitAndResume(targetMedia)
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
        if (currentMedia == null) {
            activeTrackTaskKey = null
            lyricLoadJob?.cancel()
            artworkLoadJob?.cancel()
            _currentLyricState.value = null
            _currentLyricsResult.value = LyricResult(emptyList(), LyricSource.NONE)
            currentArtworkBitmap = null
            islandManager.cancelIsland()
            return
        }

        val effectiveSourceType = effectiveSourceTypeFor(currentMedia)
        val taskKey = "${currentMedia.id}|${effectiveSourceType.name}|${(currentMedia as? PlayableMedia.LocalNcm)?.uri.orEmpty()}"
        if (activeTrackTaskKey == taskKey && artworkLoadJob?.isActive == true) {
            return
        }

        activeTrackTaskKey = taskKey
        lyricLoadJob?.cancel()
        artworkLoadJob?.cancel()
        _currentLyricState.value = null
        _currentLyricsResult.value = LyricResult(emptyList(), LyricSource.NONE)
        currentArtworkBitmap = null

        // 1. 异步加载当前曲目的歌词（严格遵循云端歌单 / 本地歌单信息获取优先级）
        lyricLoadJob = scope.launch {
            val loaded = runCatching { lyricsManager.loadLyrics(currentMedia, effectiveSourceType) }
                .getOrDefault(LyricResult(emptyList(), LyricSource.NONE))
            if (_currentMedia.value?.id == currentMedia.id) {
                _currentLyricsResult.value = loaded
                updateCurrentLyricForPosition(exoPlayer.currentPosition)
                syncIslandState()
            }
        }

        // 2. 异步解析封面并写入 MediaSession MediaMetadata：
        // - 本地歌单（PlaylistSourceType.LOCAL）：仅通过文件名匹配同目录封面（.jpg/.jpeg/.png/.webp），不读内嵌、不查云端、不搜索；
        // - 云端歌单（PlaylistSourceType.ONLINE）：
        //   1) 若匹配到本地文件，优先读取本地文件自身内嵌封面；
        //   2) 其次读取云端封面（曲目自带 coverUrl / NCM albumPic / 按精确 songId 获取），不通过歌名搜索。
        artworkLoadJob = scope.launch(Dispatchers.IO) {
            try {
                var finalCoverUrl: String? = null
                var localCoverBytes: ByteArray? = null

                if (effectiveSourceType == PlaylistSourceType.LOCAL) {
                    if (currentMedia is PlayableMedia.LocalNcm) {
                        val sibling = LocalMediaMetadataHelper.findSiblingCover(
                            context = context,
                            uri = Uri.parse(currentMedia.uri),
                            fallbackTitle = currentMedia.title
                        )
                        if (sibling != null) {
                            finalCoverUrl = sibling.first.toString()
                            localCoverBytes = sibling.second
                        } else {
                            // 本地歌单无同名封面文件时显式清空封面（不使用 NCM 云端 albumPic 或内嵌封面）
                            finalCoverUrl = null
                            localCoverBytes = null
                        }
                        if (finalCoverUrl != currentMedia.coverUrl) {
                            val updatedMedia = currentMedia.copy(coverUrl = finalCoverUrl)
                            mediaMap[updatedMedia.id] = updatedMedia
                            withContext(Dispatchers.Main) {
                                if (_currentMedia.value?.id == updatedMedia.id) {
                                    _currentMedia.value = updatedMedia
                                }
                            }
                        }
                    }
                } else {
                    // 云端歌单模式：第 1 优先读取本地文件（如存在）自身的内嵌封面
                    finalCoverUrl = currentMedia.coverUrl
                    if (currentMedia is PlayableMedia.LocalNcm) {
                        val coverDir = File(context.cacheDir, "covers")
                        if (!coverDir.exists()) coverDir.mkdirs()
                        val embeddedCacheFile = File(coverDir, "embedded_${currentMedia.uri.hashCode()}.jpg")

                        val embeddedBytes = LocalMediaMetadataHelper.extractEmbeddedCoverBytes(
                            context = context,
                            uri = Uri.parse(currentMedia.uri),
                            isNcm = currentMedia.isNcm
                        )
                        if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
                            localCoverBytes = embeddedBytes
                            runCatching { embeddedCacheFile.writeBytes(embeddedBytes) }
                            finalCoverUrl = "file://${embeddedCacheFile.absolutePath}"
                        } else {
                            // 第 2 优先：读取云端封面（若已有非本地云端 URL 则沿用；否则仅按精确 ID 或 NCM 头补齐，绝不搜歌名）
                            if (finalCoverUrl.isNullOrBlank() || finalCoverUrl.startsWith("file://")) {
                                var cloudUrl: String? = null
                                if (currentMedia.isNcm) {
                                    cloudUrl = runCatching {
                                        NcmDataSource.scanMetadata(context.contentResolver, Uri.parse(currentMedia.uri))
                                            ?.first?.albumPic?.takeIf { it.isNotBlank() }
                                    }.getOrNull()
                                }
                                if (cloudUrl.isNullOrBlank()) {
                                    val exactId = currentMedia.id.takeIf { (it.toLongOrNull() ?: 0L) > 0L }
                                    if (exactId != null) {
                                        cloudUrl = runCatching {
                                            ncmApi.getSongDetails(listOf(exactId)).firstOrNull()?.coverUrl
                                        }.getOrNull()
                                    }
                                }
                                finalCoverUrl = cloudUrl
                            }
                        }

                        if (finalCoverUrl != currentMedia.coverUrl) {
                            val updatedMedia = currentMedia.copy(coverUrl = finalCoverUrl)
                            mediaMap[updatedMedia.id] = updatedMedia
                            withContext(Dispatchers.Main) {
                                if (_currentMedia.value?.id == updatedMedia.id) {
                                    _currentMedia.value = updatedMedia
                                }
                            }
                            AppLogger.i("[AME_ONLINE_PLAY]", "Resolved cloud-playlist cover for ${currentMedia.title}: $finalCoverUrl")
                        }
                    } else if (currentMedia is PlayableMedia.Online && finalCoverUrl.isNullOrBlank()) {
                        val exactId = currentMedia.id.takeIf { (it.toLongOrNull() ?: 0L) > 0L }
                        if (exactId != null) {
                            finalCoverUrl = runCatching {
                                ncmApi.getSongDetails(listOf(exactId)).firstOrNull()?.coverUrl
                            }.getOrNull()
                            if (!finalCoverUrl.isNullOrBlank() && finalCoverUrl != currentMedia.coverUrl) {
                                val updatedMedia = currentMedia.copy(coverUrl = finalCoverUrl)
                                mediaMap[updatedMedia.id] = updatedMedia
                                withContext(Dispatchers.Main) {
                                    if (_currentMedia.value?.id == updatedMedia.id) {
                                        _currentMedia.value = updatedMedia
                                    }
                                }
                            }
                        }
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
                            AppLogger.d(
                                "[AME_ONLINE_PLAY]",
                                "Updated MediaSession artwork for '${currentMedia.title}' ($effectiveSourceType): bytes=${artworkBytes?.size ?: 0}, uri=$finalCoverUrl"
                            )
                        }
                        syncIslandState()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e("[AME_ONLINE_PLAY]", "Failed to resolve cover/metadata for media session", e)
            }
        }
    }

    /**
     * 统一的媒体实况通知与状态同步入口：
     * 处于运行状态（已加载曲目且未退出）时持续发布与刷新实况通知，
     * 配合 AmePlaybackService 前台服务保障 MediaSession 与系统媒体控件长效保活。
     */
    fun syncIslandState() {
        if (!isLiveIslandEnabled) {
            islandManager.cancelIsland()
            return
        }

        val playbackState = exoPlayer.playbackState
        val isRunning = playbackState != Player.STATE_IDLE &&
            playbackState != Player.STATE_ENDED &&
            exoPlayer.mediaItemCount > 0

        if (!isRunning) {
            islandManager.cancelIsland()
            return
        }

        ensurePlaybackServiceStarted()

        val currentMedia = _currentMedia.value
        val mediaMetadata = exoPlayer.currentMediaItem?.mediaMetadata
        val title = mediaMetadata?.title?.toString().orEmpty().ifBlank { currentMedia?.title.orEmpty() }
        val artist = mediaMetadata?.artist?.toString().orEmpty().ifBlank { currentMedia?.artist.orEmpty() }

        if (title.isBlank()) {
            islandManager.cancelIsland()
            return
        }

        val currentLyric = _currentLyricState.value?.text.orEmpty()
        val currentTrans = _currentLyricState.value?.translation
        val duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: _durationMs.value.takeIf { it > 0 }
            ?: currentMedia?.durationMs?.takeIf { it > 0 }
            ?: 0L

        islandManager.postOrUpdateIsland(
            songTitle = title,
            artist = artist,
            currentLyric = currentLyric,
            currentTranslation = currentTrans,
            currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
            durationMs = duration.coerceAtLeast(0L),
            coverBitmap = currentArtworkBitmap,
            contentIntent = buildOpenPlayerPendingIntent()
        )
    }

    private fun ensurePlaybackServiceStarted() {
        runCatching {
            val intent = Intent(context, AmePlaybackService::class.java)
            context.startService(intent)
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

    private fun applyAudioFormatFromResolvedDetails(details: Akari.NCM.player.api.ResolvedSongUrl) {
        val sampleRate = details.sampleRate.takeIf { it != null && it > 0 } ?: 44100
        val isFlac = details.formatType?.equals("flac", ignoreCase = true) == true
        val isMp3 = details.formatType?.equals("mp3", ignoreCase = true) == true || (!isFlac && details.url.contains(".mp3", ignoreCase = true))
        val isAac = details.formatType?.equals("aac", ignoreCase = true) == true || details.formatType?.equals("m4a", ignoreCase = true) == true

        // 精确判定真实位深：有损压缩（MP3/AAC）源文件均为 16-bit 精度；只有 FLAC 无损根据采样率与档位区分 16-bit 与 Hi-Res 24-bit
        val bitDepth = when {
            isFlac && (details.level.equals("hires", ignoreCase = true) || details.level.equals("jymaster", ignoreCase = true) || sampleRate > 48000) -> 24
            isFlac -> 16
            else -> 16
        }

        val mimeType = when {
            isFlac -> "audio/flac"
            isMp3 -> "audio/mpeg"
            isAac -> "audio/mp4a-latm"
            else -> details.formatType?.let { "audio/$it" } ?: "audio/mpeg"
        }

        var bitrate = details.bitrate.takeIf { it != null && it > 0 } ?: 0
        if (bitrate <= 0 && (details.size ?: 0L) > 0L) {
            val durationSec = (_durationMs.value / 1000f).coerceAtLeast(1f)
            bitrate = (((details.size ?: 0L) * 8) / durationSec / 1000).toInt()
        }
        if (bitrate <= 0) {
            bitrate = when (details.level?.lowercase()) {
                "standard" -> 128
                "higher" -> 192
                "exhigh" -> 320
                "lossless" -> 960
                "hires", "jymaster" -> 1920
                else -> 320
            }
        }

        _audioFormatInfo.value = Akari.NCM.player.core.AudioFormatInfo(
            bitrateKbps = bitrate,
            sampleRateHz = sampleRate,
            bitDepthBits = bitDepth,
            mimeType = mimeType,
            channels = 2,
            fileSizeBytes = details.size ?: 0L,
            qualityLevel = details.level
        )
    }

    private fun updateAudioFormatFromExo(format: androidx.media3.common.Format?) {
        if (format == null) return
        val currentMedia = _currentMedia.value
        // 如果当前是在线曲目且已经有解析好的真实元数据，不再使用 ExoPlayer AudioTrack/AudioSink 渲染参数覆盖
        if (currentMedia is PlayableMedia.Online) {
            val details = resolvedDetailsCache[currentMedia.id]
            if (details != null) {
                applyAudioFormatFromResolvedDetails(details)
                return
            }
        }

        val sampleRate = format.sampleRate.takeIf { it > 0 } ?: 44100
        val channels = format.channelCount.takeIf { it > 0 } ?: 2
        val mime = format.sampleMimeType ?: format.containerMimeType
        val isLossy = mime?.contains("mpeg", ignoreCase = true) == true || mime?.contains("mp4a", ignoreCase = true) == true || mime?.contains("aac", ignoreCase = true) == true
        val pcmEnc = format.pcmEncoding
        val bitDepth = when {
            isLossy -> 16
            pcmEnc == C.ENCODING_PCM_24BIT || pcmEnc == C.ENCODING_PCM_32BIT -> 24
            pcmEnc == C.ENCODING_PCM_16BIT -> 16
            else -> if ((format.bitrate > 500_000) || sampleRate > 48000) 24 else 16
        }
        var bitrate = if (format.bitrate > 0) format.bitrate / 1000 else 0
        var fileSize = 0L
        if (bitrate <= 0) {
            val durationSec = (_durationMs.value / 1000f).coerceAtLeast(1f)
            fileSize = currentMedia?.let { media ->
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
            mimeType = mime,
            channels = channels,
            fileSizeBytes = fileSize,
            qualityLevel = null
        )
    }

    /** Exposes the underlying ExoPlayer so [AmePlaybackService] can build a MediaSession around it. */
    val player: ExoPlayer get() = exoPlayer

    // --- PlayerController ---

    override fun load(media: PlayableMedia) {
        disableInfinitePlayback()
        ensureActive()
        interTrackWaitJob?.cancel()
        interTrackWaitJob = null
        isInterTrackWaiting = false
        _playlistSourceType.value = when (media) {
            is PlayableMedia.Online -> PlaylistSourceType.ONLINE
            is PlayableMedia.LocalNcm -> PlaylistSourceType.LOCAL
        }
        val mediaItem = buildMediaItem(media, includeLocalArtworkBytes = true)
        mediaMap[media.id] = media
        _playlist.value = listOf(media)
        _currentIndex.value = 0
        _currentMedia.value = media
        saveLastPlaybackState()
        prepareLoudnessSync(media)
        AppLogger.i("[AME_ONLINE_PLAY]", ">>> load single track '${media.title}' (id=${media.id})")
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        onTrackChanged(media)
        applyLoudnessNormalization(media)
        prefetchUpcomingTracksLoudness()
    }

    override fun loadPlaylist(
        items: List<PlayableMedia>,
        startIndex: Int,
        sourceType: PlaylistSourceType
    ) {
        _playlistSourceType.value = sourceType
        loadPlaylistInternal(items, startIndex, keepInfiniteMode = false)
    }

    private fun loadPlaylistInternal(
        items: List<PlayableMedia>,
        startIndex: Int,
        keepInfiniteMode: Boolean
    ) {
        if (!keepInfiniteMode) {
            disableInfinitePlayback()
        }
        ensureActive()
        ensurePlaybackServiceStarted()
        interTrackWaitJob?.cancel()
        interTrackWaitJob = null
        isInterTrackWaiting = false
        inflightUrlFetches.values.forEach { it.cancel() }
        inflightUrlFetches.clear()
        AppLogger.i("[AME_ONLINE_PLAY]", ">>> loadPlaylist size=${items.size}, startIndex=$startIndex, keepInfiniteMode=$keepInfiniteMode")
        mediaMap.clear()
        resolvedUrlCache.clear()
        resolvedDetailsCache.clear()
        val safeStartIndex = if (items.isEmpty()) 0 else startIndex.coerceIn(0, items.size - 1)
        val mediaItems = items.mapIndexed { index, media ->
            mediaMap[media.id] = media
            buildMediaItem(media, includeLocalArtworkBytes = (index == safeStartIndex))
        }
        _playlist.value = items
        val startMedia = items.getOrNull(safeStartIndex)
        _currentMedia.value = sanitizeMediaForSource(startMedia, _playlistSourceType.value)
        _currentIndex.value = safeStartIndex
        saveLastPlaybackState()
        prepareLoudnessSync(startMedia)
        exoPlayer.setMediaItems(mediaItems, safeStartIndex, C.TIME_UNSET)
        exoPlayer.prepare()
        onTrackChanged(startMedia)
        applyLoudnessNormalization(startMedia)
        prefetchUpcomingTracksLoudness()
    }

    override fun disableInfinitePlayback() {
        infiniteStartJob?.cancel()
        infinitePrefetchJob?.cancel()
        _isFetchingRecommendations.value = false
        seenInfiniteTrackIds.clear()
        fallbackRecommendationPool.clear()
        if (_infinitePlayMode.value != InfinitePlayMode.None) {
            AppLogger.i("AME_INFINITE_PLAY", ">>> Exiting InfinitePlayMode (${_infinitePlayMode.value.label}), restoring fixed playback mode (repeat=$fixedRepeatMode, shuffle=$fixedShuffleEnabled)")
            _infinitePlayMode.value = InfinitePlayMode.None
            _repeatMode.value = fixedRepeatMode
            _shuffleEnabled.value = fixedShuffleEnabled
            runCatching {
                exoPlayer.repeatMode = when (fixedRepeatMode) {
                    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                }
                exoPlayer.shuffleModeEnabled = fixedShuffleEnabled
            }
        }
    }

    override fun startInfinitePlayback(mode: InfinitePlayMode, initialTracks: List<PlayableMedia>) {
        if (!mode.isActive) {
            disableInfinitePlayback()
            if (initialTracks.isNotEmpty()) {
                loadPlaylist(initialTracks, 0)
                play()
            }
            return
        }

        infiniteStartJob?.cancel()
        infinitePrefetchJob?.cancel()
        seenInfiniteTrackIds.clear()
        fallbackRecommendationPool.clear()
        if (!_infinitePlayMode.value.isActive) {
            fixedRepeatMode = _repeatMode.value
            fixedShuffleEnabled = _shuffleEnabled.value
        }
        _infinitePlayMode.value = mode
        _playlistSourceType.value = PlaylistSourceType.ONLINE
        // 强制设定无限歌单为顺序播放，且不改动固定歌单的播放模式配置
        _repeatMode.value = RepeatMode.OFF
        _shuffleEnabled.value = false
        ensureActive()
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        exoPlayer.shuffleModeEnabled = false
        AppLogger.i("AME_INFINITE_PLAY", ">>> Starting InfinitePlayMode: ${mode.label}, forced sequential mode, initialTracks=${initialTracks.size}")

        // 若传入了已知的起始种子歌曲（例如单曲推荐卡片直接内嵌了歌曲元数据），立即加入新队列起播，随后后台预加载后续推荐
        if (initialTracks.isNotEmpty()) {
            val mappedInitial = initialTracks.map { media ->
                val resolved = if (media is PlayableMedia.Online) {
                    localTrackMatcher?.invoke(media) ?: media
                } else media
                seenInfiniteTrackIds.add(media.id)
                seenInfiniteTrackIds.add(resolved.id)
                resolved
            }
            loadPlaylistInternal(mappedInitial, startIndex = 0, keepInfiniteMode = true)
            play()
            checkAndPrefetchInfiniteQueue(autoAdvanceWhenAppended = false)
            return
        }

        infiniteStartJob = scope.launch {
            _isFetchingRecommendations.value = true
            try {
                val resolvedSeeds = withContext(Dispatchers.IO) {
                    fetchInitialSeedsForMode(mode)
                }
                if (resolvedSeeds.isNotEmpty() && _infinitePlayMode.value == mode) {
                    val mappedSeeds = resolvedSeeds.map { online ->
                        val resolved = localTrackMatcher?.invoke(online) ?: online
                        seenInfiniteTrackIds.add(online.id)
                        seenInfiniteTrackIds.add(resolved.id)
                        resolved
                    }
                    loadPlaylistInternal(mappedSeeds, startIndex = 0, keepInfiniteMode = true)
                    play()
                    checkAndPrefetchInfiniteQueue(autoAdvanceWhenAppended = false)
                } else {
                    AppLogger.w("AME_INFINITE_PLAY", ">>> No initial tracks resolved for mode=${mode.label}")
                    _error.value = "未能获取无限推荐起始曲目，请检查网络或登录状态"
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogger.e("AME_INFINITE_PLAY", ">>> Failed to startInfinitePlayback for mode=${mode.label}", e)
            } finally {
                _isFetchingRecommendations.value = false
            }
        }
    }

    private suspend fun fetchInitialSeedsForMode(mode: InfinitePlayMode): List<PlayableMedia.Online> {
        return when (mode) {
            is InfinitePlayMode.SongRadio -> {
                val seedDetails = if (mode.seedSongId.isNotBlank()) {
                    ncmApi.getSongDetails(listOf(mode.seedSongId))
                } else emptyList()

                if (seedDetails.isNotEmpty()) {
                    // 只将这一首特定种子歌曲加入初始播放队列，后续曲目通过预加载器无限续接
                    listOf(seedDetails.first())
                } else if (mode.fallbackPlaylistId != null && mode.fallbackPlaylistId > 0L) {
                    val radarTracks = ncmApi.getPlaylistTracks(mode.fallbackPlaylistId)
                    if (radarTracks.size > 1) {
                        synchronized(fallbackRecommendationPool) {
                            fallbackRecommendationPool.addAll(radarTracks.drop(1))
                        }
                    }
                    radarTracks.take(1)
                } else {
                    emptyList()
                }
            }
            is InfinitePlayMode.PersonalFm -> {
                val fmTracks = ncmApi.getPersonalFmSongs()
                if (fmTracks.isNotEmpty()) {
                    if (fmTracks.size > 1) {
                        synchronized(fallbackRecommendationPool) {
                            fallbackRecommendationPool.addAll(fmTracks.drop(1))
                        }
                    }
                    fmTracks.take(1)
                } else {
                    val dailyFallback = ncmApi.getDailyRecommendSongs()
                    if (dailyFallback.size > 1) {
                        synchronized(fallbackRecommendationPool) {
                            fallbackRecommendationPool.addAll(dailyFallback.drop(1))
                        }
                    }
                    dailyFallback.take(1)
                }
            }
            is InfinitePlayMode.Heartbeat -> {
                var targetPlaylistId = mode.playlistId
                if (targetPlaylistId <= 0L) {
                    val uid = sessionManager.getUserProfile()?.userId ?: 0L
                    targetPlaylistId = ncmApi.getUserPlaylists(uid).firstOrNull()?.id ?: 0L
                }
                val playlistTracks = if (targetPlaylistId > 0L) {
                    ncmApi.getPlaylistTracks(targetPlaylistId)
                } else emptyList()

                val seedSong = if (mode.seedSongId.isNotBlank()) {
                    playlistTracks.find { it.id == mode.seedSongId }
                        ?: ncmApi.getSongDetails(listOf(mode.seedSongId)).firstOrNull()
                } else {
                    playlistTracks.randomOrNull()
                }

                if (seedSong != null && targetPlaylistId > 0L) {
                    val intelTracks = ncmApi.getIntelligenceList(
                        playlistId = targetPlaylistId,
                        songId = seedSong.id,
                        startMusicId = seedSong.id
                    )
                    synchronized(fallbackRecommendationPool) {
                        fallbackRecommendationPool.addAll(intelTracks.filter { it.id != seedSong.id })
                        fallbackRecommendationPool.addAll(playlistTracks.filter { it.id != seedSong.id })
                    }
                    listOf(seedSong)
                } else if (seedSong != null) {
                    listOf(seedSong)
                } else {
                    val daily = ncmApi.getDailyRecommendSongs()
                    if (daily.size > 1) {
                        synchronized(fallbackRecommendationPool) {
                            fallbackRecommendationPool.addAll(daily.drop(1))
                        }
                    }
                    daily.take(1)
                }
            }
            InfinitePlayMode.None -> emptyList()
        }
    }

    /**
     * 检查当前无限播放队列剩余待播曲目数，当少于 2 首时静默预加载并追加推荐歌曲，保留已播历史。
     */
    internal fun checkAndPrefetchInfiniteQueue(autoAdvanceWhenAppended: Boolean = false) {
        val mode = _infinitePlayMode.value
        if (!mode.isActive) return

        val currentSize = _playlist.value.size
        val remaining = (currentSize - 1 - _currentIndex.value).coerceAtLeast(0)
        if (remaining >= 2 && !autoAdvanceWhenAppended) {
            return
        }
        if (infinitePrefetchJob?.isActive == true) {
            return
        }

        infinitePrefetchJob = scope.launch {
            _isFetchingRecommendations.value = true
            try {
                val nextBatch = withContext(Dispatchers.IO) {
                    fetchNextRecommendationBatch(mode, batchSize = 3)
                }
                if (nextBatch.isNotEmpty() && _infinitePlayMode.value == mode) {
                    val prevLastIndex = _playlist.value.lastIndex
                    val appendedCount = appendTracksToQueue(nextBatch)
                    if (autoAdvanceWhenAppended && appendedCount > 0) {
                        val nextIdx = (prevLastIndex + 1).coerceAtMost(_playlist.value.lastIndex)
                        skipToIndex(nextIdx)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLogger.w("AME_INFINITE_PLAY", ">>> Prefetch recommendation batch failed: ${e.message}")
            } finally {
                _isFetchingRecommendations.value = false
            }
        }
    }

    private suspend fun fetchNextRecommendationBatch(
        mode: InfinitePlayMode,
        batchSize: Int = 3
    ): List<PlayableMedia> {
        val candidates = mutableListOf<PlayableMedia.Online>()

        when (mode) {
            is InfinitePlayMode.SongRadio -> {
                // 优先基于队列最后一首（或当前播放歌曲/初始种子歌曲）调用相似歌曲接口 (simiSong)
                val referenceSongId = (_playlist.value.lastOrNull() as? PlayableMedia.Online)?.id
                    ?: (_currentMedia.value as? PlayableMedia.Online)?.id
                    ?: mode.seedSongId

                val simiSongs = ncmApi.getSimiSongs(referenceSongId)
                    .filter { it.id !in seenInfiniteTrackIds }
                candidates.addAll(simiSongs.take(batchSize))

                // 若当前末尾歌曲的相似歌曲已被耗尽，尝试基于初始种子歌曲请求相似推荐
                if (candidates.isEmpty() && referenceSongId != mode.seedSongId && mode.seedSongId.isNotBlank()) {
                    val seedSimi = ncmApi.getSimiSongs(mode.seedSongId)
                        .filter { it.id !in seenInfiniteTrackIds }
                    candidates.addAll(seedSimi.take(batchSize))
                }

                // 若相似歌曲接口无返回（例如未登录或冷门单曲），在种子雷达歌单下从原雷达歌单池中补充
                if (candidates.isEmpty() && mode.fallbackPlaylistId != null && mode.fallbackPlaylistId > 0L) {
                    val needLoadRadar = synchronized(fallbackRecommendationPool) {
                        fallbackRecommendationPool.none { it.id !in seenInfiniteTrackIds }
                    }
                    if (needLoadRadar) {
                        val radarTracks = ncmApi.getPlaylistTracks(mode.fallbackPlaylistId)
                        synchronized(fallbackRecommendationPool) {
                            fallbackRecommendationPool.addAll(radarTracks)
                        }
                    }
                    synchronized(fallbackRecommendationPool) {
                        val fromPool = fallbackRecommendationPool.filter { it.id !in seenInfiniteTrackIds }.take(batchSize)
                        candidates.addAll(fromPool)
                    }
                }

                // 最终兜底：私人 FM 或每日推荐
                if (candidates.isEmpty()) {
                    val fmFallback = ncmApi.getPersonalFmSongs().filter { it.id !in seenInfiniteTrackIds }
                    candidates.addAll(fmFallback.take(batchSize))
                }
            }
            is InfinitePlayMode.PersonalFm -> {
                synchronized(fallbackRecommendationPool) {
                    val buffered = fallbackRecommendationPool.filter { it.id !in seenInfiniteTrackIds }.take(batchSize)
                    candidates.addAll(buffered)
                    fallbackRecommendationPool.removeAll(buffered.toSet())
                }
                if (candidates.size < batchSize) {
                    val fmSongs = ncmApi.getPersonalFmSongs().filter { it.id !in seenInfiniteTrackIds }
                    candidates.addAll(fmSongs.take(batchSize - candidates.size))
                    if (fmSongs.size > batchSize) {
                        synchronized(fallbackRecommendationPool) {
                            fallbackRecommendationPool.addAll(fmSongs.drop(batchSize))
                        }
                    }
                }
            }
            is InfinitePlayMode.Heartbeat -> {
                synchronized(fallbackRecommendationPool) {
                    val buffered = fallbackRecommendationPool.filter { it.id !in seenInfiniteTrackIds }.take(batchSize)
                    candidates.addAll(buffered)
                    fallbackRecommendationPool.removeAll(buffered.toSet())
                }
                if (candidates.isEmpty()) {
                    val referenceSongId = (_playlist.value.lastOrNull() as? PlayableMedia.Online)?.id
                        ?: mode.seedSongId
                    if (mode.playlistId > 0L && referenceSongId.isNotBlank()) {
                        val intelList = ncmApi.getIntelligenceList(
                            playlistId = mode.playlistId,
                            songId = referenceSongId,
                            startMusicId = referenceSongId
                        ).filter { it.id !in seenInfiniteTrackIds }
                        candidates.addAll(intelList.take(batchSize))
                        if (intelList.size > batchSize) {
                            synchronized(fallbackRecommendationPool) {
                                fallbackRecommendationPool.addAll(intelList.drop(batchSize))
                            }
                        }
                    }
                    if (candidates.isEmpty() && referenceSongId.isNotBlank()) {
                        val simiFallback = ncmApi.getSimiSongs(referenceSongId).filter { it.id !in seenInfiniteTrackIds }
                        candidates.addAll(simiFallback.take(batchSize))
                    }
                }
            }
            InfinitePlayMode.None -> Unit
        }

        return candidates.distinctBy { it.id }.map { online ->
            localTrackMatcher?.invoke(online) ?: online
        }
    }

    /**
     * 将推荐曲目无缝追加至当前播放队列尾部，不打断当前播放且完整保留已播历史。
     */
    internal fun appendTracksToQueue(newTracks: List<PlayableMedia>): Int {
        if (newTracks.isEmpty()) return 0
        ensureActive()
        val currentList = _playlist.value.toMutableList()
        val existingIds = currentList.map { it.id }.toHashSet()
        val uniqueToAdd = newTracks.filter { track ->
            track.id !in existingIds && seenInfiniteTrackIds.add(track.id)
        }
        if (uniqueToAdd.isEmpty()) return 0

        val mediaItemsToAdd = uniqueToAdd.map { media ->
            mediaMap[media.id] = media
            buildMediaItem(media)
        }
        currentList.addAll(uniqueToAdd)
        _playlist.value = currentList
        exoPlayer.addMediaItems(mediaItemsToAdd)
        prefetchUpcomingTracksLoudness()
        AppLogger.i(
            "AME_INFINITE_PLAY",
            ">>> Appended ${uniqueToAdd.size} recommended tracks to queue (total=${currentList.size}): ${uniqueToAdd.joinToString { it.title }}"
        )
        return uniqueToAdd.size
    }

    private fun startFadeIn() {
        fadeJob?.cancel()
        isFadingOut = false
        val startMult = if (exoPlayer.isPlaying) currentFadeMultiplier else 0f
        applyEffectiveVolume(startMult)
        exoPlayer.play()
        val duration = (ThemePrefs.playbackFadeDurationMs * (1.0f - startMult)).toLong().coerceIn(50L, 2000L)
        val stepInterval = 20L
        val steps = (duration / stepInterval).coerceAtLeast(1)
        fadeJob = scope.launch {
            for (i in 1..steps) {
                delay(stepInterval)
                val t = i.toFloat() / steps
                val factor = kotlin.math.sin(t * kotlin.math.PI / 2.0).toFloat()
                val mult = startMult + (1.0f - startMult) * factor
                applyEffectiveVolume(mult)
            }
            applyEffectiveVolume(1.0f)
        }
    }

    private fun startFadeOut(onComplete: () -> Unit) {
        fadeJob?.cancel()
        isFadingOut = true
        val startMult = currentFadeMultiplier
        val duration = (ThemePrefs.playbackFadeDurationMs * startMult).toLong().coerceIn(50L, 2000L)
        val stepInterval = 20L
        val steps = (duration / stepInterval).coerceAtLeast(1)
        fadeJob = scope.launch {
            for (i in 1..steps) {
                delay(stepInterval)
                val t = i.toFloat() / steps
                val factor = kotlin.math.cos(t * kotlin.math.PI / 2.0).toFloat()
                val mult = startMult * factor
                applyEffectiveVolume(mult)
            }
            applyEffectiveVolume(0f)
            isFadingOut = false
            onComplete()
            applyEffectiveVolume(1.0f)
        }
    }

    override fun play() {
        ensureActive()
        ensurePlaybackServiceStarted()
        if (interTrackWaitJob?.isActive == true) {
            interTrackWaitJob?.cancel()
            interTrackWaitJob = null
            isInterTrackWaiting = false
            _loudnessState.value = _loudnessState.value.copy(isInterTrackWaiting = false)
            prepareLoudnessSync(_currentMedia.value)
        }
        if (exoPlayer.playbackState == Player.STATE_IDLE && exoPlayer.mediaItemCount > 0) {
            exoPlayer.prepare()
            applyLoudnessNormalization(_currentMedia.value)
            prefetchUpcomingTracksLoudness()
        }
        AppLogger.i("[AME_ONLINE_PLAY]", ">>> play() called (fadeEnabled=${ThemePrefs.playbackFadeEnabled})")
        if (!ThemePrefs.playbackFadeEnabled) {
            fadeJob?.cancel()
            isFadingOut = false
            applyEffectiveVolume(1.0f)
            exoPlayer.play()
            return
        }
        startFadeIn()
    }

    override fun pause() {
        pause(immediate = false)
    }

    fun pause(immediate: Boolean = false) {
        ensureActive()
        interTrackWaitJob?.cancel()
        interTrackWaitJob = null
        if (isInterTrackWaiting) {
            isInterTrackWaiting = false
            _loudnessState.value = _loudnessState.value.copy(isInterTrackWaiting = false)
        }
        AppLogger.i("[AME_ONLINE_PLAY]", ">>> pause(immediate=$immediate) called (fadeEnabled=${ThemePrefs.playbackFadeEnabled})")
        if (immediate || !ThemePrefs.playbackFadeEnabled || !exoPlayer.isPlaying) {
            fadeJob?.cancel()
            isFadingOut = false
            exoPlayer.pause()
            applyEffectiveVolume(1.0f)
            return
        }
        startFadeOut {
            exoPlayer.pause()
        }
    }

    override fun togglePlayPause() {
        ensureActive()
        if (isFadingOut || !exoPlayer.isPlaying) {
            play()
        } else {
            pause()
        }
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
            transitionToTrackWithGap { exoPlayer.seekToNextMediaItem() }
            if (_infinitePlayMode.value.isActive) {
                checkAndPrefetchInfiniteQueue(autoAdvanceWhenAppended = false)
            }
        } else if (_infinitePlayMode.value.isActive) {
            // 在无限播放模式下处于队尾时点击下一首，立即触发拉取并在追加后自动切到新曲目
            AppLogger.i("AME_INFINITE_PLAY", ">>> skipToNext at end of infinite queue, triggering immediate fetch & advance")
            checkAndPrefetchInfiniteQueue(autoAdvanceWhenAppended = true)
        } else if (exoPlayer.mediaItemCount > 0) {
            val timeline = exoPlayer.currentTimeline
            val firstIdx = if (!timeline.isEmpty) {
                timeline.getFirstWindowIndex(exoPlayer.shuffleModeEnabled)
                    .takeIf { it != C.INDEX_UNSET } ?: 0
            } else {
                0
            }
            transitionToTrackWithGap { exoPlayer.seekToDefaultPosition(firstIdx) }
        }
    }

    override fun skipToPrevious() {
        ensureActive()
        if (exoPlayer.hasPreviousMediaItem()) {
            // 直接使用 seekToPreviousMediaItem() 切至上一曲，避免 ExoPlayer seekToPrevious()
            // 在播放进度超过 3 秒时先回退至当前曲目开头的问题
            transitionToTrackWithGap { exoPlayer.seekToPreviousMediaItem() }
        } else if (exoPlayer.mediaItemCount > 0) {
            val timeline = exoPlayer.currentTimeline
            val lastIdx = if (!timeline.isEmpty) {
                timeline.getLastWindowIndex(exoPlayer.shuffleModeEnabled)
                    .takeIf { it != C.INDEX_UNSET }
                    ?: (exoPlayer.mediaItemCount - 1).coerceAtLeast(0)
            } else {
                (exoPlayer.mediaItemCount - 1).coerceAtLeast(0)
            }
            transitionToTrackWithGap { exoPlayer.seekToDefaultPosition(lastIdx) }
        }
    }

    override fun skipToIndex(index: Int) {
        ensureActive()
        transitionToTrackWithGap { exoPlayer.seekToDefaultPosition(index) }
        if (_infinitePlayMode.value.isActive) {
            checkAndPrefetchInfiniteQueue(autoAdvanceWhenAppended = false)
        }
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
        prefetchUpcomingTracksLoudness()
        AppLogger.i("[AME_ONLINE_PLAY]", ">>> Inserted '${media.title}' as next track at index $insertIndex")
    }

    override fun setRepeatMode(mode: RepeatMode) {
        ensureActive()
        if (_infinitePlayMode.value.isActive) {
            // 无限歌单强制固定为顺序播放，与固定歌单独立
            exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
            _repeatMode.value = RepeatMode.OFF
            return
        }
        exoPlayer.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        fixedRepeatMode = mode
        _repeatMode.value = mode
        ThemePrefs.playbackRepeatMode = mode.name
        prefetchUpcomingTracksLoudness()
    }

    override fun toggleShuffle() {
        ensureActive()
        if (_infinitePlayMode.value.isActive) {
            // 无限歌单强制固定为顺序播放，与固定歌单独立
            exoPlayer.shuffleModeEnabled = false
            _shuffleEnabled.value = false
            return
        }
        val next = !exoPlayer.shuffleModeEnabled
        exoPlayer.shuffleModeEnabled = next
        fixedShuffleEnabled = next
        _shuffleEnabled.value = next
        ThemePrefs.playbackShuffleEnabled = next
        prefetchUpcomingTracksLoudness()
    }

    override fun setVolume(volume: Float) {
        ensureActive()
        _volume.value = volume.coerceIn(0f, 1f)
        applyEffectiveVolume()
    }

    override fun setVolumeNormalizationEnabled(enabled: Boolean) {
        ThemePrefs.volumeNormalizationEnabled = enabled
        applyLoudnessNormalization()
    }

    override fun setTargetLoudness(targetLufs: Float) {
        ThemePrefs.targetLoudnessLufs = targetLufs
        applyLoudnessNormalization()
    }

    override fun requestUsbPermission() {
        usbAudioManager.requestUsbPermissionForCurrentDevice()
    }

    override fun setUsbExclusiveMode(enabled: Boolean) {
        ensureActive()
        if (enabled) {
            syncVolumeFromSystem()
        }
        usbAudioManager.setExclusiveModeEnabled(enabled)
        val currentDevice = usbAudioManager.connectedUsbDevice.value
        val hasPermission = usbAudioManager.usbDeviceDetails.value?.hasPermission == true
        applyUsbAudioRouting(currentDevice, enabled, hasPermission)

        if (!enabled) {
            applyEffectiveVolume()
            if (_isPlaying.value) {
                // Switching OFF exclusive: re-sync ExoPlayer to flush and resume system AudioTrack cleanly
                val currentPos = exoPlayer.currentPosition
                exoPlayer.seekTo(currentPos)
            }
        }
    }

    private fun applyUsbAudioRouting(
        device: AudioDeviceInfo?,
        isExclusive: Boolean,
        hasPermission: Boolean = usbAudioManager.usbDeviceDetails.value?.hasPermission == true
    ) {
        val enableBitPerfect = isExclusive && device != null && hasPermission
        usbAudioSink?.setBitPerfectEnabled(enableBitPerfect)
        // USB DAC 独占 / Bit-Perfect 生效时强制旁路所有响度均衡与限幅 DSP，确保纯源码直通
        loudnessAudioProcessor.usbExclusiveBypass = enableBitPerfect
        applyLoudnessNormalization()
        if (enableBitPerfect && device != null) {
            val uac = usbAudioManager.uacVersion.value ?: "UAC"
            AppLogger.i("[AME_USB_AUDIO]", ">>> Applying USB Exclusive Routing to device: '${device.productName}' ($uac, id=${device.id}, type=${device.type}, hasPerm=$hasPermission)")
            // 禁止 DefaultAudioSink 将硬件路径抢占至 USB DAC：独占通道由 UsbAudioSink 通过 usbdevfs 接管
            exoPlayer.setPreferredAudioDevice(null)
            val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE)
                .build()
            exoPlayer.setAudioAttributes(audioAttributes, false)
            usbAudioSink?.onUsbPermissionGranted()
            applyEffectiveVolume()
        } else {
            if (isExclusive && device != null && !hasPermission) {
                AppLogger.i("[AME_USB_AUDIO]", ">>> USB Exclusive switch ON for '${device.productName}', awaiting hardware USB permission before enabling bit-perfect routing")
            } else {
                AppLogger.i("[AME_USB_AUDIO]", ">>> Reverting USB Audio routing to default (isExclusive=$isExclusive, device=${device?.productName})")
            }
            if (device != null) {
                AppLogger.i("[AME_USB_AUDIO]", ">>> Routing standard AudioTrack to USB device: '${device.productName}'")
                exoPlayer.setPreferredAudioDevice(device)
            } else {
                exoPlayer.setPreferredAudioDevice(null)
            }
            applyEffectiveVolume()
            if (isExclusive && device == null && _isPlaying.value) {
                AppLogger.w("[AME_USB_AUDIO]", ">>> USB Audio DAC unplugged during exclusive playback! Pausing audio to protect ears/speakers.")
                pause(immediate = true)
            }
        }
    }

    override fun clearUrlCache() {
        resolvedUrlCache.clear()
        resolvedDetailsCache.clear()
        AppLogger.i("[AME_ONLINE_PLAY]", ">>> Manually cleared resolved URL cache")
    }

    override fun release() {
        isReleased = true
        fadeJob?.cancel()
        interTrackWaitJob?.cancel()
        prefetchLoudnessJob?.cancel()
        persistPlaylistJob?.cancel()
        scanJob?.cancel()
        loudnessJob?.cancel()
        inflightUrlFetches.values.forEach { it.cancel() }
        inflightUrlFetches.clear()
        isInterTrackWaiting = false
        isFadingOut = false
        applyEffectiveVolume(1.0f)
        islandManager.cancelIsland()
        scope.cancel()
        try {
            context.unregisterReceiver(volumeReceiver)
        } catch (_: Exception) {}
        try {
            context.contentResolver.unregisterContentObserver(volumeObserver)
        } catch (_: Exception) {}
        usbAudioSink?.detachFromPlayer()
        usbAudioSink?.release()
        usbAudioSink = null
        runCatching { exoPlayer.release() }
        if (isAudioCacheInitialized) {
            runCatching { audioCache.release() }
        }
        mediaMap.clear()
        resolvedUrlCache.clear()
        resolvedDetailsCache.clear()
    }

    // --- Helpers ---

    private fun buildMediaItem(
        media: PlayableMedia,
        includeLocalArtworkBytes: Boolean = false
    ): MediaItem {
        val uriStr = if (media is PlayableMedia.Online) {
            "https://music.126.net/ncm/${media.id}"
        } else {
            media.encodeUri()
        }

        val effectiveSource = effectiveSourceTypeFor(media)
        val localCoverBytes = if (includeLocalArtworkBytes &&
            effectiveSource == PlaylistSourceType.ONLINE &&
            media is PlayableMedia.LocalNcm
        ) {
            val embeddedFile = File(File(context.cacheDir, "covers"), "embedded_${media.uri.hashCode()}.jpg")
            if (embeddedFile.exists()) runCatching { embeddedFile.readBytes() }.getOrNull() else null
        } else null

        val initialArtworkUrl = if (effectiveSource == PlaylistSourceType.LOCAL) {
            media.coverUrl?.takeIf { it.startsWith("file://") || it.startsWith("content://") }
        } else {
            media.coverUrl
        }

        val metadata = MediaMetadataHelper.buildTrackMediaMetadata(
            title = media.title,
            artist = media.artist,
            album = media.album,
            durationMs = media.durationMs,
            remoteArtworkUrl = initialArtworkUrl,
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
