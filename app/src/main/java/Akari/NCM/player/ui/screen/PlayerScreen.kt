package Akari.NCM.player.ui.screen

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.LyricLine
import Akari.NCM.player.core.LyricSource
import Akari.NCM.player.core.NcmArtistBrief
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.PlayerController
import Akari.NCM.player.core.PlayerState
import Akari.NCM.player.core.PlaylistSourceType
import Akari.NCM.player.core.RepeatMode
import Akari.NCM.player.core.extractArtistCandidates
import Akari.NCM.player.data.LyricResult
import Akari.NCM.player.data.LyricsManager
import Akari.NCM.player.data.PlaylistManager
import Akari.NCM.player.data.ThemePrefs
import android.widget.Toast
import Akari.NCM.player.ui.component.AdaptiveButtonGroup
import Akari.NCM.player.ui.component.AdaptiveButtonSpec
import Akari.NCM.player.ui.component.ExpressiveButtonStyle
import Akari.NCM.player.ui.component.DrawerPullHandle
import Akari.NCM.player.ui.component.PlayerBackgroundLayer
import Akari.NCM.player.ui.component.PortraitPullDownDrawerOverlay
import Akari.NCM.player.ui.component.PredictiveBackEasing
import Akari.NCM.player.ui.component.SaltLandscapePlayQueueSheet
import Akari.NCM.player.ui.component.SaltPlayQueueView
import Akari.NCM.player.ui.component.WordByWordLyricView
import Akari.NCM.player.ui.component.predictiveBackForeground
import Akari.NCM.player.ui.component.rememberDrawerNestedScrollConnection
import Akari.NCM.player.ui.component.rememberPlayerCoverPalette
import Akari.NCM.player.ui.component.rememberPredictiveBackState
import Akari.NCM.player.ui.component.rememberPullDownDrawerState
import Akari.NCM.player.ui.component.rememberSmoothPosition
import Akari.NCM.player.ui.theme.PlayerExpressivePalette
import Akari.NCM.player.ui.theme.ensureContrast
import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

// 插值工具
private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)
private val EaseInOut = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

@SuppressLint("DefaultLocale")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    playerState: PlayerState,
    playerController: PlayerController,
    ncmApi: NcmApi,
    playlistManager: PlaylistManager? = null,
    onBackClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onToggleQueueClick: () -> Unit,
    onNavigateToArtist: ((NcmArtistBrief) -> Unit)? = null,
    onNavigateToAlbum: ((albumId: Long, albumName: String, artistName: String, coverUrl: String?) -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val effectivePlaylistManager = playlistManager ?: remember(context) { PlaylistManager(context) }
    val rawTrack by playerState.currentMedia.collectAsState()
    val (activeTrack, palette, blurCoverBg) = rememberPlayerCoverPalette(rawTrack)

    // 根据播放页面当前背景明暗动态调整状态栏与导航栏图标颜色，退出时恢复
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(view, palette.isLightBackground) {
            val window = (view.context as? Activity)?.window
            val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
            val originalLightStatusBars = insetsController?.isAppearanceLightStatusBars ?: false
            val originalLightNavBars = insetsController?.isAppearanceLightNavigationBars ?: false

            insetsController?.isAppearanceLightStatusBars = palette.isLightBackground
            insetsController?.isAppearanceLightNavigationBars = palette.isLightBackground

            onDispose {
                insetsController?.isAppearanceLightStatusBars = originalLightStatusBars
                insetsController?.isAppearanceLightNavigationBars = originalLightNavBars
            }
        }
    }

    val coverUrl = activeTrack?.coverUrl

    var showPlayQueuePage by remember { mutableStateOf(false) }
    var showQuickFunctionsPage by remember { mutableStateOf(false) }

    val isUsbExclusiveMode by playerState.isUsbExclusiveMode.collectAsState()
    val connectedUsbDeviceName by playerState.connectedUsbDeviceName.collectAsState()
    val usbUacVersion by playerState.usbUacVersion.collectAsState()
    val usbDeviceDetails by playerState.usbDeviceDetails.collectAsState()
    val loudnessState by playerState.loudnessState.collectAsState()
    val audioFormatInfo by playerState.audioFormatInfo.collectAsState()

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenWidthDp > configuration.screenHeightDp

    val queueDrawerState = rememberPullDownDrawerState(
        isOpen = showPlayQueuePage,
        isLandscape = isLandscape,
        backEnabled = showPlayQueuePage,
        label = "QueueParallaxProgress",
        onDismiss = { showPlayQueuePage = false }
    )
    val quickFunctionsDrawerState = rememberPullDownDrawerState(
        isOpen = showQuickFunctionsPage,
        isLandscape = isLandscape,
        backEnabled = showQuickFunctionsPage && !showPlayQueuePage,
        label = "QuickFunctionsParallaxProgress",
        onDismiss = { showQuickFunctionsPage = false }
    )

    val effectiveOverlayProgress = maxOf(
        queueDrawerState.effectiveProgress,
        quickFunctionsDrawerState.effectiveProgress
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.effectiveBackgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
    ) {
        // ── 1. 全局背景层：根据开关选择模糊歌曲封面或纯色/Material取色背景 ──
        PlayerBackgroundLayer(
            coverUrl = coverUrl,
            blurCoverBg = blurCoverBg,
            palette = palette
        )

        // ── 2. 主播放界面（带有 3D 视差推退与微缩放效果）────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = lerpF(1.0f, 0.92f, effectiveOverlayProgress)
                    val translateY = lerpF(0f, -32.dp.toPx(), effectiveOverlayProgress)
                    val alphaVal = lerpF(1.0f, 0.45f, effectiveOverlayProgress)

                    scaleX = scale
                    scaleY = scale
                    translationY = translateY
                    alpha = alphaVal
                }
                .clip(RoundedCornerShape(with(density) { lerpF(0f, 24f, effectiveOverlayProgress).toDp() }))
        ) {
            PlayerMainContentView(
                playerState = playerState,
                playerController = playerController,
                displayedTrack = activeTrack,
                ncmApi = ncmApi,
                playlistManager = effectivePlaylistManager,
                palette = palette,
                blurCoverBg = blurCoverBg,
                isQueueOpen = showPlayQueuePage || showQuickFunctionsPage,
                onBackClick = onBackClick,
                onOpenQueueClick = { showPlayQueuePage = true },
                onOpenQuickFunctionsClick = { showQuickFunctionsPage = true },
                onNavigateToArtist = onNavigateToArtist,
                onNavigateToAlbum = onNavigateToAlbum
            )
        }

        // ── 3. 播放队列抽屉视图 ───────────────────────────
        if (isLandscape && showPlayQueuePage) {
            SaltLandscapePlayQueueSheet(
                playerState = playerState,
                playerController = playerController,
                palette = palette,
                onDismiss = { showPlayQueuePage = false }
            )
        }

        PortraitPullDownDrawerOverlay(
            visible = !isLandscape && showPlayQueuePage,
            drawerState = queueDrawerState,
            sheetTestTag = "play_queue_sheet"
        ) {
            SaltPlayQueueView(
                playerState = playerState,
                playerController = playerController,
                displayedTrack = activeTrack,
                palette = palette,
                blurCoverBg = blurCoverBg,
                pullHintText = "下滑此处返回播放界面",
                pullHandleTestTag = "play_queue_pull_handle",
                currentPullOffsetPx = { queueDrawerState.pullOffsetPx.value },
                onPullDelta = queueDrawerState.onPullDelta,
                onPullSettle = queueDrawerState.onPullSettle,
                onDismiss = { showPlayQueuePage = false }
            )
        }

        // ── 4. 快捷功能抽屉视图（与播放列表统一样式，内联二级菜单展开）───────────────────────
        if (isLandscape && showQuickFunctionsPage) {
            ModalBottomSheet(
                onDismissRequest = { showQuickFunctionsPage = false },
                containerColor = palette.cardSurfaceColor,
                contentColor = palette.primaryTextColor
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = configuration.screenHeightDp.dp * 0.85f)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 20.dp)
                ) {
                    Text(
                        text = "快捷功能与外观",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = palette.primaryTextColor,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        QuickFunctionsCards(
                            displayedTrack = activeTrack,
                            audioFormatInfo = audioFormatInfo,
                            isUsbExclusive = isUsbExclusiveMode,
                            usbDeviceName = connectedUsbDeviceName,
                            uacVersion = usbUacVersion,
                            usbDeviceDetails = usbDeviceDetails,
                            loudnessState = loudnessState,
                            blurCoverBg = blurCoverBg,
                            playlistManager = effectivePlaylistManager,
                            ncmApi = ncmApi,
                            palette = palette,
                            onToggleUsbExclusive = { enabled ->
                                playerController.setUsbExclusiveMode(enabled)
                            },
                            onRequestUsbPermission = {
                                playerController.requestUsbPermission()
                            },
                            onToggleLoudness = {
                                playerController.setVolumeNormalizationEnabled(!loudnessState.isEnabled)
                            },
                            onToggleBlurCoverBg = {
                                ThemePrefs.playerBlurCoverBackground = !blurCoverBg
                            }
                        )
                    }
                }
            }
        }

        PortraitPullDownDrawerOverlay(
            visible = !isLandscape && showQuickFunctionsPage,
            drawerState = quickFunctionsDrawerState,
            sheetTestTag = "quick_functions_sheet"
        ) {
            SaltQuickFunctionsView(
                displayedTrack = activeTrack,
                audioFormatInfo = audioFormatInfo,
                isUsbExclusive = isUsbExclusiveMode,
                usbDeviceName = connectedUsbDeviceName,
                uacVersion = usbUacVersion,
                usbDeviceDetails = usbDeviceDetails,
                loudnessState = loudnessState,
                blurCoverBg = blurCoverBg,
                playlistManager = effectivePlaylistManager,
                ncmApi = ncmApi,
                palette = palette,
                currentPullOffsetPx = { quickFunctionsDrawerState.pullOffsetPx.value },
                onPullDelta = quickFunctionsDrawerState.onPullDelta,
                onPullSettle = quickFunctionsDrawerState.onPullSettle,
                onDismiss = { showQuickFunctionsPage = false },
                onToggleUsbExclusive = { enabled ->
                    playerController.setUsbExclusiveMode(enabled)
                },
                onRequestUsbPermission = {
                    playerController.requestUsbPermission()
                },
                onToggleLoudness = {
                    playerController.setVolumeNormalizationEnabled(!loudnessState.isEnabled)
                },
                onToggleBlurCoverBg = {
                    ThemePrefs.playerBlurCoverBackground = !blurCoverBg
                }
            )
        }
    }
}

@SuppressLint("DefaultLocale")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerMainContentView(
    playerState: PlayerState,
    playerController: PlayerController,
    displayedTrack: PlayableMedia?,
    ncmApi: NcmApi,
    playlistManager: PlaylistManager,
    palette: PlayerExpressivePalette,
    blurCoverBg: Boolean,
    isQueueOpen: Boolean = false,
    onBackClick: () -> Unit,
    onOpenQueueClick: () -> Unit,
    onOpenQuickFunctionsClick: () -> Unit,
    onNavigateToArtist: ((NcmArtistBrief) -> Unit)? = null,
    onNavigateToAlbum: ((albumId: Long, albumName: String, artistName: String, coverUrl: String?) -> Unit)? = null
) {
    val currentTrack = displayedTrack

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val favoriteSongIds by playlistManager.favoriteSongIds.collectAsState()
    val playlistSourceType by playerState.playlistSourceType.collectAsState()
    val isFromLocalPlaylist = playlistSourceType == PlaylistSourceType.LOCAL
    val isFavorite = remember(currentTrack?.id, favoriteSongIds) {
        currentTrack?.let { favoriteSongIds.contains(it.id) } ?: false
    }

    // ── 播放状态（不在此顶层订阅高频 currentPositionMs / rememberSmoothPosition，避免整页 60~120Hz 全量重组） ──
    val isPlaying by playerState.isPlaying.collectAsState()
    val durationMs by playerState.durationMs.collectAsState()
    val repeatMode by playerState.repeatMode.collectAsState()
    val shuffleEnabled by playerState.shuffleEnabled.collectAsState()
    val infinitePlayMode by playerState.infinitePlayMode.collectAsState()

    // ── 歌词（优先直接复用引擎已加载的歌词状态，避免重复实例化 LyricsManager 造成双倍 IO 与网络开销） ──
    val engineLyricsFlow = playerState.currentLyricsResult
    val lyricResult: LyricResult = if (engineLyricsFlow != null) {
        val collected by engineLyricsFlow.collectAsState()
        collected
    } else {
        val lyricsManager = remember { LyricsManager(context, ncmApi) }
        var fallbackResult by remember { mutableStateOf(LyricResult(emptyList(), LyricSource.NONE)) }
        LaunchedEffect(currentTrack?.id, playlistSourceType) {
            fallbackResult = currentTrack?.let { lyricsManager.loadLyrics(it, playlistSourceType) }
                ?: LyricResult(emptyList(), LyricSource.NONE)
        }
        fallbackResult
    }
    var showTranslation by remember { mutableStateOf(true) }

    // ── 动画 ───────────────────────────────────────────────────────────────────
    val progress = remember { Animatable(0f) }
    var isLyricsMode by remember { mutableStateOf(false) }
    var lyricsBackDragProgress by remember { mutableFloatStateOf(0f) }
    var isLyricsBackDragging by remember { mutableStateOf(false) }
    val p = if (isLyricsBackDragging) {
        (1f - PredictiveBackEasing.transform(lyricsBackDragProgress.coerceIn(0f, 1f)) * 0.88f).coerceIn(0f, 1f)
    } else {
        progress.value
    }
    val jobRef = remember { object { var job: Job? = null } }
    val lyricsListState = rememberLazyListState()

    fun goToLyrics() {
        jobRef.job?.cancel()
        isLyricsBackDragging = false
        lyricsBackDragProgress = 0f
        isLyricsMode = true
        jobRef.job = scope.launch {
            progress.animateTo(1f, tween(500, easing = EaseInOut))
        }
    }

    fun goToCover() {
        jobRef.job?.cancel()
        isLyricsBackDragging = false
        lyricsBackDragProgress = 0f
        jobRef.job = scope.launch {
            progress.animateTo(0f, tween(500, easing = EaseInOut))
            isLyricsMode = false
        }
    }

    // ── 位置量测：封面大图 ─────────────────────────────────────────────────────
    var largeCoverTopLeft by remember { mutableStateOf(Offset.Zero) }
    var largeCoverSizePx by remember { mutableStateOf(0f) }

    // ── 位置量测：迷你封面目标 ─────────────────────────────────────────────────
    val miniCoverSizePx = with(density) { 52.dp.toPx() }
    var miniCoverTopLeft by remember { mutableStateOf(Offset.Zero) }

    // ── 动画插值：封面覆层 ─────────────────────────────────────────────────────
    val coverX = lerpF(largeCoverTopLeft.x, miniCoverTopLeft.x, p)
    val coverY = lerpF(largeCoverTopLeft.y, miniCoverTopLeft.y, p)
    val coverW = lerpF(largeCoverSizePx, miniCoverSizePx, p)
    val cornerDp = with(density) { lerpF(28f, 12f, p).toDp() }
    val shadowDp = with(density) { lerpF(22f, 2f, p).toDp() }

    val coverUrl = currentTrack?.coverUrl
    val prevLyricAlpha = lerpF(1f, 0f, p)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight || maxWidth >= 550.dp
        val isShortLandscape = isLandscape && maxHeight < 500.dp
        val supportsLyricsToggle = !isLandscape || isShortLandscape

        PredictiveBackHandler(enabled = isLyricsMode && supportsLyricsToggle && !isQueueOpen) { backEvents ->
            jobRef.job?.cancel()
            try {
                isLyricsBackDragging = true
                lyricsBackDragProgress = 0f
                backEvents.collect { event ->
                    lyricsBackDragProgress = event.progress.coerceIn(0f, 1f)
                }
                val settledP = (1f - PredictiveBackEasing.transform(lyricsBackDragProgress) * 0.88f).coerceIn(0f, 1f)
                progress.snapTo(settledP)
                isLyricsBackDragging = false
                lyricsBackDragProgress = 0f
                progress.animateTo(0f, tween(220, easing = EaseInOut))
                isLyricsMode = false
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val settledP = (1f - PredictiveBackEasing.transform(lyricsBackDragProgress) * 0.88f).coerceIn(0f, 1f)
                    progress.snapTo(settledP)
                    isLyricsBackDragging = false
                    lyricsBackDragProgress = 0f
                    progress.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                }
                throw e
            }
        }

        // ── 播放页背景层 ──
        PlayerBackgroundLayer(
            coverUrl = coverUrl,
            blurCoverBg = blurCoverBg,
            palette = palette
        )

        val TitleAndArtistBlock: @Composable (Modifier) -> Unit = { modifier ->
            val artistCandidates = remember(currentTrack) {
                currentTrack?.extractArtistCandidates().orEmpty()
            }
            val onlineAlbumId = (currentTrack as? PlayableMedia.Online)?.albumId ?: 0L
            val hasAlbum = !currentTrack?.album.isNullOrBlank()
            var showArtistSelector by remember(currentTrack?.id) { mutableStateOf(false) }
            var artistAnchorHeightPx by remember { mutableIntStateOf(0) }

            Column(modifier = modifier) {
                Text(
                    text = currentTrack?.title ?: "未在播放",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = palette.primaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .onSizeChanged { artistAnchorHeightPx = it.height }
                    ) {
                        Text(
                            text = currentTrack?.artist ?: "未知歌手",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.secondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = currentTrack != null && artistCandidates.isNotEmpty()) {
                                    if (artistCandidates.size > 1) {
                                        showArtistSelector = true
                                    } else {
                                        artistCandidates.firstOrNull()?.let { onNavigateToArtist?.invoke(it) }
                                    }
                                }
                                .testTag("player_artist_click")
                        )
                        if (showArtistSelector && artistCandidates.size > 1) {
                            Popup(
                                alignment = Alignment.TopStart,
                                offset = IntOffset(0, artistAnchorHeightPx + with(density) { 6.dp.roundToPx() }),
                                onDismissRequest = { showArtistSelector = false },
                                properties = PopupProperties(focusable = true)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = palette.cardSurfaceColor.copy(alpha = 0.96f),
                                    border = BorderStroke(1.dp, palette.cardOutlineColor.copy(alpha = 0.45f)),
                                    shadowElevation = 8.dp,
                                    modifier = Modifier
                                        .widthIn(min = 160.dp, max = 260.dp)
                                        .testTag("player_artist_selector_box")
                                ) {
                                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                        Text(
                                            text = "选择歌手",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = palette.secondaryTextColor,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
                                        )
                                        artistCandidates.forEach { candidate ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        showArtistSelector = false
                                                        onNavigateToArtist?.invoke(candidate)
                                                    }
                                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Person,
                                                    contentDescription = null,
                                                    tint = palette.playedLyricColor,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = candidate.name,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = palette.primaryTextColor,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (hasAlbum) {
                        Text(
                            text = " · ",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.secondaryTextColor
                        )
                        Text(
                            text = currentTrack?.album.orEmpty(),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = palette.secondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = currentTrack != null && (onlineAlbumId > 0L || onNavigateToAlbum != null)) {
                                    val albumName = currentTrack?.album.orEmpty()
                                    val artistName = currentTrack?.artist.orEmpty()
                                    onNavigateToAlbum?.invoke(onlineAlbumId, albumName, artistName, currentTrack?.coverUrl)
                                }
                                .testTag("player_album_click")
                        )
                    }
                }
            }
        }

        val CurrentLyricBlock: @Composable (Alignment.Horizontal) -> Unit = { align ->
            PlayerCurrentLyricSection(
                playerState = playerState,
                isPlaying = isPlaying,
                durationMs = durationMs,
                lyricResult = lyricResult,
                showTranslation = showTranslation,
                palette = palette,
                align = align
            )
        }

        val ProgressBarBlock: @Composable () -> Unit = {
            PlayerProgressBarSection(
                playerState = playerState,
                playerController = playerController,
                durationMs = durationMs,
                palette = palette
            )
        }

        val ControlsBlock: @Composable () -> Unit = {
            val playCorner by animateDpAsState(
                targetValue = if (isPlaying) 22.dp else 36.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "ExpressivePlayCorner"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = { playerController.skipToPrevious() },
                    shape = RoundedCornerShape(22.dp),
                    color = palette.secondaryButtonContainer,
                    border = BorderStroke(1.dp, palette.cardOutlineColor.copy(alpha = 0.35f)),
                    modifier = Modifier.size(width = 64.dp, height = 54.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "上一首",
                            tint = palette.onSecondaryButton,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                Surface(
                    onClick = { playerController.togglePlayPause() },
                    shape = RoundedCornerShape(playCorner),
                    color = palette.primaryButtonContainer,
                    shadowElevation = 8.dp,
                    modifier = Modifier.size(width = 88.dp, height = 68.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "播放/暂停",
                            tint = palette.onPrimaryButton,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                Surface(
                    onClick = { playerController.skipToNext() },
                    shape = RoundedCornerShape(22.dp),
                    color = palette.secondaryButtonContainer,
                    border = BorderStroke(1.dp, palette.cardOutlineColor.copy(alpha = 0.35f)),
                    modifier = Modifier.size(width = 64.dp, height = 54.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "下一首",
                            tint = palette.onSecondaryButton,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        }

        val ToolbarBlock: @Composable () -> Unit = {
            val btnSize = 48.dp
            val iconSize = 24.dp
            val bottomIconColor = ensureContrast(
                foreground = palette.playedLyricColor,
                background = palette.effectiveBackgroundColor,
                minRatio = 4.5f,
                preferChroma = true
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = {
                        if (infinitePlayMode.isActive) {
                            android.widget.Toast.makeText(context, "无限模式", android.widget.Toast.LENGTH_SHORT).show()
                        } else if (shuffleEnabled) {
                            playerController.toggleShuffle()
                            playerController.setRepeatMode(RepeatMode.OFF)
                        } else when (repeatMode) {
                            RepeatMode.OFF -> playerController.setRepeatMode(RepeatMode.ONE)
                            RepeatMode.ONE -> {
                                playerController.setRepeatMode(RepeatMode.OFF)
                                playerController.toggleShuffle()
                            }
                            else -> playerController.setRepeatMode(RepeatMode.OFF)
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    modifier = Modifier
                        .size(btnSize)
                        .testTag("btn_play_mode")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (infinitePlayMode.isActive) {
                            Text(
                                text = "∞",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = bottomIconColor,
                                modifier = Modifier.semantics {
                                    contentDescription = "无限模式"
                                }
                            )
                        } else {
                            val (modeIcon, modeDesc) = when {
                                shuffleEnabled -> Pair(Icons.Rounded.Shuffle, "随机播放")
                                repeatMode == RepeatMode.ONE -> Pair(Icons.Rounded.RepeatOne, "单曲循环")
                                else -> Pair(Icons.AutoMirrored.Rounded.ArrowForward, "顺序播放")
                            }
                            Icon(
                                modeIcon,
                                modeDesc,
                                tint = bottomIconColor,
                                modifier = Modifier.size(iconSize)
                            )
                        }
                    }
                }

                Surface(
                    onClick = onOpenQueueClick,
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    modifier = Modifier
                        .size(btnSize)
                        .testTag("btn_open_queue")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            "播放队列",
                            tint = bottomIconColor,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                }

                Surface(
                    onClick = onOpenQuickFunctionsClick,
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Transparent,
                    modifier = Modifier
                        .size(btnSize)
                        .testTag("btn_quick_functions")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.MoreHoriz,
                            "快捷功能",
                            tint = bottomIconColor,
                            modifier = Modifier.size(iconSize)
                        )
                    }
                }

                if (!isFromLocalPlaylist) {
                    Surface(
                        onClick = {
                            val track = currentTrack ?: return@Surface
                            val isNowFav = playlistManager.toggleFavorite(track)
                            val msg = if (isNowFav) "已添加到云端喜欢" else "已取消云端喜欢"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            scope.launch(Dispatchers.IO) {
                                ncmApi.likeSong(track.id, isNowFav)
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .size(btnSize)
                            .testTag("btn_favorite_song")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = if (isFavorite) "取消喜欢" else "喜欢",
                                tint = if (isFavorite) palette.playedLyricColor else bottomIconColor,
                                modifier = Modifier.size(iconSize)
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            val containerWidth = this@BoxWithConstraints.maxWidth
            val containerHeight = this@BoxWithConstraints.maxHeight

            if (isLandscape) {
                val isShortLandscape = containerHeight < 500.dp
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 16.dp)
                ) {
                    if (isShortLandscape) {
                        BoxWithConstraints(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            val titleHeight = 72.dp
                            val maxCoverSize = minOf(maxWidth, maxHeight - titleHeight) * 0.9f

                            Column(
                                modifier = Modifier.width(maxCoverSize),
                                horizontalAlignment = Alignment.Start,
                                verticalArrangement = Arrangement.Center
                            ) {
                                TitleAndArtistBlock(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 16.dp)
                                )

                                Box(
                                    modifier = Modifier
                                        .size(maxCoverSize)
                                        .shadow(16.dp, RoundedCornerShape(24.dp))
                                        .clip(RoundedCornerShape(24.dp))
                                ) {
                                    if (coverUrl != null) {
                                        AsyncImage(
                                            model = coverUrl,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .background(palette.secondaryButtonContainer),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = androidx.compose.ui.res.painterResource(id = Akari.NCM.player.R.drawable.ic_ame_status),
                                                contentDescription = null,
                                                tint = palette.onSecondaryButton,
                                                modifier = Modifier.size(maxCoverSize * 0.45f)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.width(48.dp))

                        BoxWithConstraints(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            val rightMaxHeight = maxHeight
                            Column(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            enabled = isLyricsMode,
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { goToCover() }
                                        )
                                        .padding(bottom = 8.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Surface(
                                        onClick = { showTranslation = !showTranslation },
                                        shape = CircleShape,
                                        color = if (showTranslation) palette.secondaryButtonContainer else Color.Transparent,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Rounded.Translate,
                                                contentDescription = "翻译开关",
                                                tint = if (showTranslation) palette.onSecondaryButton else palette.secondaryTextColor
                                            )
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isLyricsMode) {
                                        PlayerLiveFullLyricsSection(
                                            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = p },
                                            playerState = playerState,
                                            isPlaying = isPlaying,
                                            durationMs = durationMs,
                                            lyricResult = lyricResult,
                                            showTranslation = showTranslation,
                                            palette = palette,
                                            listState = lyricsListState,
                                            onLyricClick = { playerController.seekTo(it) },
                                            onCollapseLyrics = { goToCover() }
                                        )
                                    }

                                    if (p < 1f) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .graphicsLayer { alpha = 1f - p },
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            val controlsHeight = 220.dp
                                            val availableHeight = rightMaxHeight - controlsHeight
                                            val fixedLineHeight = 64.dp
                                            val maxLinesCount = when {
                                                availableHeight >= fixedLineHeight * 3 -> 3
                                                availableHeight >= fixedLineHeight * 2 -> 2
                                                else -> 1
                                            }

                                            PlayerShortLandscapeLyricSection(
                                                playerState = playerState,
                                                isPlaying = isPlaying,
                                                durationMs = durationMs,
                                                lyricResult = lyricResult,
                                                showTranslation = showTranslation,
                                                palette = palette,
                                                fixedLineHeight = fixedLineHeight,
                                                maxLinesCount = maxLinesCount,
                                                onClickExpand = { goToLyrics() }
                                            )

                                            Spacer(Modifier.height(16.dp))
                                            ProgressBarBlock()
                                            Spacer(Modifier.height(8.dp))
                                            ControlsBlock()
                                            Spacer(Modifier.height(8.dp))
                                            ToolbarBlock()
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Wide Landscape: Left Side Cover + Controls, Right Side Full Lyrics
                        BoxWithConstraints(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            val controlsHeight = 160.dp
                            val topPadding = 48.dp
                            val availableHeightForCover = maxHeight - controlsHeight - topPadding - 16.dp
                            val maxCoverSize = minOf(maxWidth * 0.85f, availableHeightForCover)

                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = topPadding),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(maxCoverSize)
                                        .shadow(24.dp, RoundedCornerShape(24.dp))
                                        .clip(RoundedCornerShape(24.dp))
                                ) {
                                    if (coverUrl != null) {
                                        AsyncImage(
                                            model = coverUrl,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .background(palette.secondaryButtonContainer),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = androidx.compose.ui.res.painterResource(id = Akari.NCM.player.R.drawable.ic_ame_status),
                                                contentDescription = null,
                                                tint = palette.onSecondaryButton,
                                                modifier = Modifier.size(maxCoverSize * 0.45f)
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(20.dp))

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    TitleAndArtistBlock(Modifier.fillMaxWidth(0.92f))
                                    Spacer(Modifier.height(14.dp))
                                    ProgressBarBlock()
                                    Spacer(Modifier.height(8.dp))
                                    ControlsBlock()
                                    Spacer(Modifier.height(8.dp))
                                    ToolbarBlock()
                                }
                            }
                        }

                        Spacer(Modifier.width(32.dp))

                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Surface(
                                    onClick = { showTranslation = !showTranslation },
                                    shape = CircleShape,
                                    color = if (showTranslation) palette.secondaryButtonContainer else Color.Transparent,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Rounded.Translate,
                                            contentDescription = "翻译开关",
                                            tint = if (showTranslation) palette.onSecondaryButton else palette.secondaryTextColor
                                        )
                                    }
                                }
                            }

                            PlayerLiveFullLyricsSection(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                playerState = playerState,
                                isPlaying = isPlaying,
                                durationMs = durationMs,
                                lyricResult = lyricResult,
                                showTranslation = showTranslation,
                                palette = palette,
                                listState = lyricsListState,
                                onLyricClick = { playerController.seekTo(it) },
                                onCollapseLyrics = { goToCover() }
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    IconButton(onClick = onBackClick, modifier = Modifier.padding(16.dp)) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            "返回",
                            tint = palette.primaryTextColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            } else {
                // Portrait Layout
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                ) {
                    // ── 顶部栏 (展开歌词后翻译开关保持在右上角，点击顶部空白区域可收起歌词) ────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .clickable(
                                enabled = isLyricsMode,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { goToCover() }
                            )
                            .testTag("player_top_header_blank"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = onBackClick,
                            shape = CircleShape,
                            color = palette.secondaryButtonContainer.copy(alpha = 0.72f),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    "返回",
                                    tint = palette.onSecondaryButton
                                )
                            }
                        }

                        Surface(
                            onClick = { showTranslation = !showTranslation },
                            shape = CircleShape,
                            color = if (showTranslation) palette.secondaryButtonContainer else Color.Transparent,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("btn_player_top_translate")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.Translate,
                                    contentDescription = "翻译开关",
                                    tint = if (showTranslation) palette.onSecondaryButton else palette.secondaryTextColor
                                )
                            }
                        }
                    }

                    // ── 标题行 (展开歌词时点击歌词上方的区域可收起歌词) ────────────────────────────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = isLyricsMode,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { goToCover() }
                            )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Spacer(Modifier.width((52.dp + 12.dp) * p))

                            TitleAndArtistBlock(
                                Modifier
                                    .weight(1f)
                                    .onGloballyPositioned { coords ->
                                        val colPos = coords.positionInRoot()
                                        val colH = coords.size.height.toFloat()
                                        val mx = colPos.x - with(density) { (52.dp + 12.dp).toPx() }
                                        val my = colPos.y + (colH - miniCoverSizePx) / 2f
                                        if ((miniCoverTopLeft - Offset(mx, my)).getDistanceSquared() > 1f) {
                                            miniCoverTopLeft = Offset(mx, my)
                                        }
                                    }
                            )
                        }

                        Spacer(Modifier.height(14.dp))
                    }

                    // ── 内容区 ────────────────────────────────────────────────────────
                    BoxWithConstraints(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        val maxCoverSize = minOf(maxWidth * 0.82f, maxHeight * 0.75f)

                        if (p < 1f) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 12.dp)
                                        .size(maxCoverSize)
                                        .graphicsLayer { alpha = 0f }
                                        .onGloballyPositioned { coords ->
                                            largeCoverTopLeft = coords.positionInRoot()
                                            largeCoverSizePx = coords.size.width.toFloat()
                                        }
                                )

                                // 未展开时的歌词区域作为展开歌词的触发范围
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clickable(
                                            enabled = !isLyricsMode,
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = { goToLyrics() }
                                        )
                                        .testTag("collapsed_lyric_expand_trigger"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer {
                                                alpha = prevLyricAlpha
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CurrentLyricBlock(Alignment.CenterHorizontally)
                                    }
                                }
                            }
                        }

                        if (isLyricsMode) {
                            PlayerLiveFullLyricsSection(
                                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = p },
                                playerState = playerState,
                                isPlaying = isPlaying,
                                durationMs = durationMs,
                                lyricResult = lyricResult,
                                showTranslation = showTranslation,
                                palette = palette,
                                listState = lyricsListState,
                                onLyricClick = { playerController.seekTo(it) },
                                onCollapseLyrics = { goToCover() }
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    ProgressBarBlock()
                    Spacer(Modifier.height(10.dp))
                    ControlsBlock()
                    Spacer(Modifier.height(8.dp))
                    ToolbarBlock()
                }
            }
        }

        // ── 封面覆层 (仅在 Portrait 模式下显示：移除未展开时点击封面展开歌词的触发范围，仅在展开歌词后允许点击顶部小封面收起) ──
        if (!isLandscape && largeCoverSizePx > 0f && miniCoverTopLeft != Offset.Zero) {
            Box(
                modifier = Modifier
                    .absoluteOffset {
                        IntOffset(coverX.roundToInt(), coverY.roundToInt())
                    }
                    .size(with(density) { coverW.toDp() })
                    .shadow(shadowDp, RoundedCornerShape(cornerDp))
                    .clip(RoundedCornerShape(cornerDp))
                    .background(palette.secondaryButtonContainer)
                    .then(
                        if (p >= 0.5f) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                goToCover()
                            }
                        } else {
                            Modifier
                        }
                    )
                    .testTag("player_album_cover")
            ) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = Akari.NCM.player.R.drawable.ic_ame_status),
                            contentDescription = null,
                            tint = palette.onSecondaryButton,
                            modifier = Modifier.size(with(density) { (coverW * 0.45f).toDp() })
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 隔离高频进度/平滑时钟的子组件，防止整页 PlayerMainContentView（封面、按钮、模糊背景）随播放进度高频重组
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerProgressBarSection(
    playerState: PlayerState,
    playerController: PlayerController,
    durationMs: Long,
    palette: PlayerExpressivePalette
) {
    val positionMs by playerState.currentPositionMs.collectAsState()
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableStateOf(0L) }

    val effectivePosition = if (isDragging) dragPositionMs else positionMs
    val sliderProgress =
        if (durationMs > 0) (effectivePosition.toFloat() / durationMs).coerceIn(0f, 1f)
        else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = sliderProgress,
            onValueChange = { pct ->
                isDragging = true
                dragPositionMs = (pct * durationMs).toLong()
            },
            onValueChangeFinished = {
                isDragging = false
                playerController.seekTo(dragPositionMs)
            },
            thumb = {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = palette.sliderActiveColor,
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(width = 18.dp, height = 22.dp)
                ) {}
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    colors = SliderDefaults.colors(
                        activeTrackColor = palette.sliderActiveColor,
                        inactiveTrackColor = palette.sliderInactiveColor
                    ),
                    drawStopIndicator = null,
                    thumbTrackGapSize = 2.dp,
                    trackInsideCornerSize = 6.dp
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                formatTime(effectivePosition),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.playedLyricColor
            )
            Text(
                formatTime(durationMs),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = palette.secondaryTextColor
            )
        }
    }
}

@Composable
private fun PlayerCurrentLyricSection(
    playerState: PlayerState,
    isPlaying: Boolean,
    durationMs: Long,
    lyricResult: LyricResult,
    showTranslation: Boolean,
    palette: PlayerExpressivePalette,
    align: Alignment.Horizontal
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        contentAlignment = if (align == Alignment.CenterHorizontally) Alignment.Center else Alignment.CenterStart
    ) {
        if (lyricResult.lines.isEmpty()) {
            Text(
                "暂无歌词 / 纯音乐",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = palette.unplayedLyricColor,
                textAlign = if (align == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start
            )
        } else {
            val positionMs by playerState.currentPositionMs.collectAsState()
            val smoothPositionMs by rememberSmoothPosition(
                positionMs = positionMs,
                isPlaying = isPlaying,
                durationMs = durationMs,
                enabled = isPlaying && lyricResult.lines.isNotEmpty()
            )
            val currentLineIndex = remember(lyricResult.lines, smoothPositionMs) {
                lyricResult.lines.indexOfLast { it.timeMs <= smoothPositionMs }.let { if (it == -1) 0 else it }
            }
            val currentLyricLine = lyricResult.lines.getOrNull(currentLineIndex)
            if (currentLyricLine != null) {
                val nextLyricLineTimeMs = lyricResult.lines.getOrNull(currentLineIndex + 1)?.timeMs
                val fallbackDur = if (nextLyricLineTimeMs != null && nextLyricLineTimeMs > currentLyricLine.timeMs) {
                    (nextLyricLineTimeMs - currentLyricLine.timeMs).coerceIn(800L, 10000L)
                } else 3600L

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = align,
                    verticalArrangement = Arrangement.Center
                ) {
                    WordByWordLyricView(
                        line = currentLyricLine,
                        currentPositionMs = smoothPositionMs,
                        fontSize = 22.sp,
                        lineHeight = 30.sp,
                        activeColor = palette.playedLyricColor,
                        inactiveColor = palette.unplayedLyricColor,
                        horizontalAlignment = align,
                        hasWordTimestamps = lyricResult.hasWordTimestamps,
                        fallbackLineDurationMs = fallbackDur
                    )
                    AnimatedVisibility(
                        visible = showTranslation && !currentLyricLine.translation.isNullOrBlank(),
                        enter = fadeIn(animationSpec = tween(450, easing = FastOutSlowInEasing)) +
                            expandVertically(
                                animationSpec = tween(450, easing = FastOutSlowInEasing),
                                clip = false,
                                expandFrom = Alignment.Top
                            ),
                        exit = fadeOut(animationSpec = tween(450, easing = FastOutSlowInEasing)) +
                            shrinkVertically(
                                animationSpec = tween(450, easing = FastOutSlowInEasing),
                                clip = false,
                                shrinkTowards = Alignment.Top
                            )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = align
                        ) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = currentLyricLine.translation?.trim().orEmpty(),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = palette.playedLyricColor,
                                textAlign = if (align == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerShortLandscapeLyricSection(
    playerState: PlayerState,
    isPlaying: Boolean,
    durationMs: Long,
    lyricResult: LyricResult,
    showTranslation: Boolean,
    palette: PlayerExpressivePalette,
    fixedLineHeight: androidx.compose.ui.unit.Dp,
    maxLinesCount: Int,
    onClickExpand: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(fixedLineHeight * maxLinesCount)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClickExpand
            ),
        contentAlignment = Alignment.Center
    ) {
        if (lyricResult.lines.isEmpty()) {
            Text(
                "暂无歌词 / 纯音乐",
                fontSize = 16.sp,
                color = palette.unplayedLyricColor
            )
        } else {
            val positionMs by playerState.currentPositionMs.collectAsState()
            val smoothPositionMs by rememberSmoothPosition(
                positionMs = positionMs,
                isPlaying = isPlaying,
                durationMs = durationMs,
                enabled = isPlaying && lyricResult.lines.isNotEmpty()
            )
            val currentLineIndex = remember(lyricResult.lines, smoothPositionMs) {
                lyricResult.lines.indexOfLast { it.timeMs <= smoothPositionMs }.let { if (it == -1) 0 else it }
            }
            val actualCurrent = maxOf(0, currentLineIndex)
            val start = maxOf(0, actualCurrent - (maxLinesCount - 1) / 2)
            val end = minOf(maxOf(0, lyricResult.lines.size - 1), start + maxLinesCount - 1)
            val adjStart = maxOf(0, end - maxLinesCount + 1)
            val linesToShow = lyricResult.lines.slice(adjStart..end)

            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                linesToShow.forEachIndexed { i, line ->
                    val lineGlobalIndex = adjStart + i
                    val isSelected = lineGlobalIndex == actualCurrent
                    val isPlayedLine = lineGlobalIndex < actualCurrent
                    val lineColor = if (isPlayedLine) palette.playedLyricColor else palette.unplayedLyricColor

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(fixedLineHeight),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (isSelected) {
                            val nextLineMs = lyricResult.lines.getOrNull(lineGlobalIndex + 1)?.timeMs
                            val fallbackDur = if (nextLineMs != null && nextLineMs > line.timeMs) {
                                (nextLineMs - line.timeMs).coerceIn(800L, 10000L)
                            } else 3600L
                            WordByWordLyricView(
                                line = line,
                                currentPositionMs = smoothPositionMs,
                                fontSize = 21.sp,
                                lineHeight = 27.sp,
                                activeColor = palette.playedLyricColor,
                                inactiveColor = palette.unplayedLyricColor,
                                horizontalAlignment = Alignment.CenterHorizontally,
                                hasWordTimestamps = lyricResult.hasWordTimestamps,
                                fallbackLineDurationMs = fallbackDur
                            )
                        } else {
                            Text(
                                text = if (line.text.isBlank()) "..." else line.text.trim(),
                                fontSize = 18.sp,
                                fontWeight = if (isPlayedLine) FontWeight.SemiBold else FontWeight.Medium,
                                color = lineColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (showTranslation && !line.translation.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = line.translation.trim(),
                                fontSize = if (isSelected) 15.sp else 13.sp,
                                color = if (isSelected || isPlayedLine) palette.playedLyricColor else palette.unplayedLyricColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerLiveFullLyricsSection(
    modifier: Modifier = Modifier,
    playerState: PlayerState,
    isPlaying: Boolean,
    durationMs: Long,
    lyricResult: LyricResult,
    showTranslation: Boolean,
    palette: PlayerExpressivePalette,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onLyricClick: (Long) -> Unit,
    onCollapseLyrics: () -> Unit = {}
) {
    val hasLyrics = lyricResult.lines.isNotEmpty()
    val positionMs by playerState.currentPositionMs.collectAsState()
    val smoothPositionMs by rememberSmoothPosition(
        positionMs = positionMs,
        isPlaying = isPlaying,
        durationMs = durationMs,
        enabled = isPlaying && hasLyrics
    )
    val currentLineIndex = remember(lyricResult.lines, smoothPositionMs) {
        if (!hasLyrics) -1
        else lyricResult.lines.indexOfLast { it.timeMs <= smoothPositionMs }.let { if (it == -1) 0 else it }
    }
    SaltFullLyricsView(
        modifier = modifier,
        lyricResult = lyricResult,
        currentIndex = currentLineIndex,
        currentPositionMs = smoothPositionMs,
        showTranslation = showTranslation,
        palette = palette,
        listState = listState,
        onLyricClick = onLyricClick,
        onCollapseLyrics = onCollapseLyrics
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 全屏歌词视图：
// - 已播放歌词（前序歌词行 index < currentIndex 与当前行已播放部分）使用强调色 palette.playedLyricColor
// - 未播放歌词（后续歌词行 index > currentIndex 与当前行未播放部分）使用纯黑或纯白 palette.unplayedLyricColor
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SaltFullLyricsView(
    modifier: Modifier = Modifier,
    lyricResult: LyricResult,
    currentIndex: Int,
    currentPositionMs: Long,
    showTranslation: Boolean,
    palette: PlayerExpressivePalette,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onLyricClick: (Long) -> Unit,
    onCollapseLyrics: () -> Unit = {}
) {
    var isInitialScroll by remember { mutableStateOf(true) }
    var lastScrolledIndex by remember { mutableIntStateOf(-1) }
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    val hasWordTimestamps = remember(lyricResult) { lyricResult.hasWordTimestamps }

    LaunchedEffect(currentIndex, showTranslation, isDragged, listState.layoutInfo.viewportSize.height) {
        if (currentIndex < 0 || lyricResult.lines.isEmpty()) return@LaunchedEffect
        val containerHeight = listState.layoutInfo.viewportSize.height
        if (containerHeight <= 0) return@LaunchedEffect

        if (isDragged) return@LaunchedEffect

        if (!isInitialScroll && currentIndex == lastScrolledIndex) {
            delay(2500)
        }

        suspend fun centerItem(index: Int, animate: Boolean) {
            var layoutInfo = listState.layoutInfo
            var viewportHeight = layoutInfo.viewportSize.height
            if (viewportHeight <= 0) return

            var target = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (target == null) {
                listState.scrollToItem(index)
                delay(30)
                layoutInfo = listState.layoutInfo
                viewportHeight = layoutInfo.viewportSize.height
                target = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            }

            if (target != null && viewportHeight > 0) {
                val itemTopInViewport = target.offset + layoutInfo.beforeContentPadding
                val itemCenterInViewport = itemTopInViewport + (target.size / 2f)
                val viewportCenter = viewportHeight / 2f
                val delta = itemCenterInViewport - viewportCenter

                if (kotlin.math.abs(delta) > 1f) {
                    if (animate) {
                        listState.animateScrollBy(
                            value = delta,
                            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
                        )
                    } else {
                        listState.scrollBy(delta)
                    }
                }
            }
        }

        if (isInitialScroll) {
            centerItem(currentIndex, animate = false)
            isInitialScroll = false
            lastScrolledIndex = currentIndex
        } else {
            centerItem(currentIndex, animate = true)
            lastScrolledIndex = currentIndex
        }
    }

    Column(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCollapseLyrics
                )
                .testTag("expanded_lyrics_blank_area")
        ) {
            val halfHeight = maxHeight / 2
            val maxTextWidth = (maxWidth - 64.dp).coerceAtLeast(120.dp)
            if (lyricResult.lines.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onCollapseLyrics
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "暂无歌词",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = palette.unplayedLyricColor
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                            alpha = 0.99f
                        }
                        .drawWithContent {
                            drawContent()
                            val fadePx = 96.dp.toPx()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(Color.Black, Color.Transparent),
                                    startY = 0f, endY = fadePx
                                ),
                                blendMode = BlendMode.DstOut
                            )
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black),
                                    startY = size.height - fadePx, endY = size.height
                                ),
                                blendMode = BlendMode.DstOut
                            )
                        },
                    contentPadding = PaddingValues(top = halfHeight, bottom = halfHeight),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    itemsIndexed(lyricResult.lines) { index, line ->
                        val isSelected = index == currentIndex
                        val isPlayedLine = index < currentIndex

                        // 已播放的前序歌词使用强调色，未播放的后续歌词使用纯黑或纯白
                        val nonSelectedLineColor = if (isPlayedLine) {
                            palette.playedLyricColor
                        } else {
                            palette.unplayedLyricColor
                        }

                        // 外层行容器：点击歌词两侧空白区域触发收起歌词
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onCollapseLyrics
                                )
                                .padding(vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            // 内层歌词文字容器：点击歌词文字本身跳转该句进度
                            Column(
                                modifier = Modifier
                                    .widthIn(max = maxTextWidth)
                                    .wrapContentWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onLyricClick(line.timeMs) }
                                    .padding(vertical = 4.dp, horizontal = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (isSelected) {
                                        val nextLineMs = lyricResult.lines.getOrNull(index + 1)?.timeMs
                                        val fallbackDur = if (nextLineMs != null && nextLineMs > line.timeMs) {
                                            (nextLineMs - line.timeMs).coerceIn(800L, 10000L)
                                        } else 3600L

                                        WordByWordLyricView(
                                            line = line,
                                            currentPositionMs = currentPositionMs,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            lineHeight = 30.sp,
                                            activeColor = palette.playedLyricColor,
                                            inactiveColor = palette.unplayedLyricColor,
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            hasWordTimestamps = hasWordTimestamps,
                                            fallbackLineDurationMs = fallbackDur
                                        )
                                    } else {
                                        Text(
                                            text = line.text.trim(),
                                            fontSize = 18.sp,
                                            fontWeight = if (isPlayedLine) FontWeight.SemiBold else FontWeight.Medium,
                                            color = nonSelectedLineColor,
                                            lineHeight = 26.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }

                                AnimatedVisibility(
                                    visible = showTranslation && !line.translation.isNullOrBlank(),
                                    enter = fadeIn(animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)) +
                                        expandVertically(
                                            animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
                                            clip = false,
                                            expandFrom = Alignment.Top
                                        ),
                                    exit = fadeOut(animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)) +
                                        shrinkVertically(
                                            animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
                                            clip = false,
                                            shrinkTowards = Alignment.Top
                                        )
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = line.translation?.trim().orEmpty(),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isSelected || isPlayedLine) palette.playedLyricColor else palette.unplayedLyricColor,
                                            lineHeight = 20.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("DefaultLocale")
private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}

private enum class QuickSubMenu {
    PLAYBACK_STATUS,
    USB_EXCLUSIVE,
    ADD_TO_PLAYLIST
}

@Composable
private fun SaltQuickFunctionsView(
    displayedTrack: PlayableMedia?,
    audioFormatInfo: Akari.NCM.player.core.AudioFormatInfo,
    isUsbExclusive: Boolean,
    usbDeviceName: String?,
    uacVersion: String? = null,
    usbDeviceDetails: Akari.NCM.player.core.UsbDeviceDetails? = null,
    loudnessState: Akari.NCM.player.core.loudness.LoudnessNormalizationState,
    blurCoverBg: Boolean,
    playlistManager: PlaylistManager,
    ncmApi: NcmApi,
    palette: PlayerExpressivePalette,
    currentPullOffsetPx: () -> Float = { 0f },
    onPullDelta: (Float) -> Unit = {},
    onPullSettle: (Float) -> Unit = {},
    onDismiss: () -> Unit,
    onToggleUsbExclusive: (Boolean) -> Unit,
    onRequestUsbPermission: () -> Unit,
    onToggleLoudness: () -> Unit,
    onToggleBlurCoverBg: () -> Unit
) {
    val coverUrl = displayedTrack?.coverUrl
    val headerDraggableState = rememberDraggableState { deltaY ->
        onPullDelta(deltaY)
    }
    val nestedScrollConnection = rememberDrawerNestedScrollConnection(
        currentPullOffsetPx = currentPullOffsetPx,
        onPullDelta = onPullDelta,
        onPullSettle = onPullSettle
    )

    Box(modifier = Modifier.fillMaxSize().background(palette.effectiveBackgroundColor)) {
        PlayerBackgroundLayer(
            coverUrl = coverUrl,
            blurCoverBg = blurCoverBg,
            palette = palette
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // ── 顶部可下拉收起区域（提示条 + 当前曲目卡片 + Header）───────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = headerDraggableState,
                        onDragStopped = { velocity -> onPullSettle(velocity) }
                    )
            ) {
                Spacer(Modifier.height(8.dp))

                DrawerPullHandle(
                    hintText = "下滑此处返回播放界面",
                    contentDesc = "下滑或点击返回播放界面",
                    handleTestTag = "quick_functions_pull_handle",
                    palette = palette,
                    onDismiss = onDismiss
                )

                Spacer(Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = palette.cardSurfaceColor.copy(alpha = 0.90f),
                    border = BorderStroke(1.dp, palette.cardOutlineColor.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.secondaryButtonContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            if (coverUrl != null) {
                                AsyncImage(
                                    model = coverUrl,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    painter = androidx.compose.ui.res.painterResource(id = Akari.NCM.player.R.drawable.ic_ame_status),
                                    contentDescription = null,
                                    tint = palette.onSecondaryButton,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayedTrack?.title ?: "未在播放",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.primaryTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = buildString {
                                    append(displayedTrack?.artist ?: "未知歌手")
                                    if (!displayedTrack?.album.isNullOrBlank()) append(" - ${displayedTrack?.album}")
                                },
                                fontSize = 12.sp,
                                color = palette.secondaryTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "快捷功能与外观",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.primaryTextColor,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "播放器",
                        fontSize = 12.sp,
                        color = palette.secondaryTextColor
                    )
                }

                Spacer(Modifier.height(8.dp))
            }

            // ── 快捷功能卡片列表（支持内联二级菜单展开）─────────────────────────────────
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .nestedScroll(nestedScrollConnection)
                    .padding(vertical = 4.dp)
            ) {
                QuickFunctionsCards(
                    displayedTrack = displayedTrack,
                    audioFormatInfo = audioFormatInfo,
                    isUsbExclusive = isUsbExclusive,
                    usbDeviceName = usbDeviceName,
                    uacVersion = uacVersion,
                    usbDeviceDetails = usbDeviceDetails,
                    loudnessState = loudnessState,
                    blurCoverBg = blurCoverBg,
                    playlistManager = playlistManager,
                    ncmApi = ncmApi,
                    palette = palette,
                    onToggleUsbExclusive = onToggleUsbExclusive,
                    onRequestUsbPermission = onRequestUsbPermission,
                    onToggleLoudness = onToggleLoudness,
                    onToggleBlurCoverBg = onToggleBlurCoverBg
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── 底部收起胶囊按钮 ────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(22.dp),
                    color = palette.secondaryButtonContainer,
                    border = BorderStroke(1.dp, palette.cardOutlineColor.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "收起快捷菜单",
                            tint = palette.onSecondaryButton,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "收起菜单",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.onSecondaryButton
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickFunctionsCards(
    displayedTrack: PlayableMedia?,
    audioFormatInfo: Akari.NCM.player.core.AudioFormatInfo,
    isUsbExclusive: Boolean,
    usbDeviceName: String?,
    uacVersion: String? = null,
    usbDeviceDetails: Akari.NCM.player.core.UsbDeviceDetails? = null,
    loudnessState: Akari.NCM.player.core.loudness.LoudnessNormalizationState,
    blurCoverBg: Boolean,
    playlistManager: PlaylistManager,
    ncmApi: NcmApi,
    palette: PlayerExpressivePalette,
    onToggleUsbExclusive: (Boolean) -> Unit,
    onRequestUsbPermission: () -> Unit,
    onToggleLoudness: () -> Unit,
    onToggleBlurCoverBg: () -> Unit
) {
    val surfaceColor = palette.cardSurfaceColor.copy(alpha = 0.85f)
    val cardBorder = BorderStroke(1.dp, palette.cardOutlineColor.copy(alpha = 0.35f))
    val cardText = ensureContrast(palette.primaryTextColor, palette.cardSurfaceColor, 4.5f)
    val cardSubText = ensureContrast(palette.secondaryTextColor, palette.cardSurfaceColor, 3.8f)

    var expandedSubMenu by remember { mutableStateOf<QuickSubMenu?>(null) }

    fun toggleSubMenu(target: QuickSubMenu) {
        expandedSubMenu = if (expandedSubMenu == target) null else target
    }

    // ── 1. 播放页模糊封面背景切换开关 ──
    Surface(
        onClick = onToggleBlurCoverBg,
        shape = RoundedCornerShape(20.dp),
        color = surfaceColor,
        border = cardBorder,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("quick_toggle_blur_cover_bg")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (blurCoverBg) Icons.Rounded.BlurOn else Icons.Rounded.BlurOff,
                contentDescription = null,
                tint = palette.playedLyricColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("模糊歌曲封面背景", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cardText)
                Text(
                    if (blurCoverBg) "已开启 · 使用高斯模糊歌曲封面作为播放页背景"
                    else "已关闭 · 使用当前应用背景色（取色背景或纯黑/纯白）",
                    fontSize = 12.sp,
                    color = cardSubText
                )
            }
            Switch(
                checked = blurCoverBg,
                onCheckedChange = { onToggleBlurCoverBg() },
                colors = playerSwitchColors(palette),
                modifier = Modifier.testTag("switch_player_blur_cover_bg")
            )
        }
    }

    // ── 2. 播放状态（二级菜单内联展开）──
    val isStatusExpanded = expandedSubMenu == QuickSubMenu.PLAYBACK_STATUS
    val statusChevronRotation by animateFloatAsState(
        targetValue = if (isStatusExpanded) 180f else 0f,
        animationSpec = tween(220),
        label = "StatusChevronRotation"
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = surfaceColor,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { toggleSubMenu(QuickSubMenu.PLAYBACK_STATUS) }
                    .padding(14.dp)
                    .testTag("quick_card_playback_status"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Equalizer, contentDescription = null, tint = palette.playedLyricColor, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("播放状态", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cardText)
                    Text("查看实际比特率、采样率、位深、响度与通道信息", fontSize = 12.sp, color = cardSubText)
                }
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = if (isStatusExpanded) "收起播放状态" else "展开播放状态",
                    tint = cardSubText,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = statusChevronRotation }
                )
            }

            AnimatedVisibility(
                visible = isStatusExpanded,
                enter = expandVertically(tween(240)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(160))
            ) {
                PlaybackStatusSubMenuContent(
                    media = displayedTrack,
                    audioFormatInfo = audioFormatInfo,
                    isUsbExclusive = isUsbExclusive,
                    usbDeviceName = usbDeviceName,
                    uacVersion = uacVersion,
                    hasUsbPermission = usbDeviceDetails?.hasPermission == true,
                    loudnessState = loudnessState,
                    cardText = cardText,
                    cardSubText = cardSubText
                )
            }
        }
    }

    // ── 3. 独占 USB 通道（二级菜单内联展开 + 重写说明）──
    val hasDevice = !usbDeviceName.isNullOrBlank()
    val hasUsbPermission = usbDeviceDetails?.hasPermission == true
    val isUsbExclusiveActive = isUsbExclusive && hasDevice && hasUsbPermission
    val isUsbPendingPermission = isUsbExclusive && hasDevice && !hasUsbPermission
    val isUsbExpanded = expandedSubMenu == QuickSubMenu.USB_EXCLUSIVE
    val usbChevronRotation by animateFloatAsState(
        targetValue = if (isUsbExpanded) 180f else 0f,
        animationSpec = tween(220),
        label = "UsbChevronRotation"
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = surfaceColor,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { toggleSubMenu(QuickSubMenu.USB_EXCLUSIVE) }
                    .padding(14.dp)
                    .testTag("quick_card_usb_exclusive"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Usb, contentDescription = null, tint = palette.playedLyricColor, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("独占 USB 通道", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cardText)
                        when {
                            isUsbExclusiveActive -> {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = palette.primaryButtonContainer
                                ) {
                                    Text(
                                        "ON",
                                        fontSize = 10.sp,
                                        color = palette.onPrimaryButton,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            isUsbPendingPermission -> {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = palette.secondaryButtonContainer,
                                    modifier = Modifier.testTag("badge_usb_pending_permission")
                                ) {
                                    Text(
                                        "待授权",
                                        fontSize = 10.sp,
                                        color = palette.onSecondaryButton,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            isUsbExclusive -> {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = palette.secondaryButtonContainer
                                ) {
                                    Text(
                                        "待接入",
                                        fontSize = 10.sp,
                                        color = palette.onSecondaryButton,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                    val uacTag = if (!uacVersion.isNullOrBlank() && uacVersion != "未连接") " ($uacVersion)" else ""
                    val subText = when {
                        isUsbExclusiveActive -> "已独占绑定: $usbDeviceName$uacTag"
                        isUsbPendingPermission -> "待授权: 请确认 USB 权限以启用独占 ($usbDeviceName$uacTag)"
                        isUsbExclusive -> "开关已开启 · 等待 USB DAC 声卡接入"
                        hasDevice -> "已检测到: $usbDeviceName$uacTag (点击展开配置)"
                        else -> "绕过系统混音与重采样，直连 USB DAC 实现无损源码输出"
                    }
                    Text(subText, fontSize = 12.sp, color = cardSubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = if (isUsbExpanded) "收起独占 USB 通道" else "展开独占 USB 通道",
                    tint = cardSubText,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = usbChevronRotation }
                )
            }

            AnimatedVisibility(
                visible = isUsbExpanded,
                enter = expandVertically(tween(240)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(160))
            ) {
                UsbExclusiveSubMenuContent(
                    isExclusive = isUsbExclusive,
                    usbDeviceName = usbDeviceName,
                    uacVersion = uacVersion,
                    deviceDetails = usbDeviceDetails,
                    palette = palette,
                    cardText = cardText,
                    cardSubText = cardSubText,
                    onToggleExclusive = onToggleUsbExclusive,
                    onRequestPermission = onRequestUsbPermission
                )
            }
        }
    }

    // ── 4. 音量均衡快捷开关 ──
    Surface(
        onClick = onToggleLoudness,
        shape = RoundedCornerShape(20.dp),
        color = surfaceColor,
        border = cardBorder,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("quick_card_loudness")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = palette.playedLyricColor, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("音量均衡 (EBU R128)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cardText)
                    if (loudnessState.isBypassedForUsbExclusive) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = palette.secondaryButtonContainer
                        ) {
                            Text(
                                "已旁路",
                                fontSize = 10.sp,
                                color = palette.onSecondaryButton,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    } else if (loudnessState.isEnabled) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = palette.primaryButtonContainer
                        ) {
                            Text(
                                "ON",
                                fontSize = 10.sp,
                                color = palette.onPrimaryButton,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Text(
                    loudnessState.statusSummary,
                    fontSize = 12.sp,
                    color = cardSubText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    // ── 5. 添加到歌单（二级菜单内联展开）──
    val isPlaylistExpanded = expandedSubMenu == QuickSubMenu.ADD_TO_PLAYLIST
    val playlistChevronRotation by animateFloatAsState(
        targetValue = if (isPlaylistExpanded) 180f else 0f,
        animationSpec = tween(220),
        label = "PlaylistChevronRotation"
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = surfaceColor,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { toggleSubMenu(QuickSubMenu.ADD_TO_PLAYLIST) }
                    .padding(14.dp)
                    .testTag("quick_card_add_to_playlist"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.PlaylistAdd, contentDescription = null, tint = palette.playedLyricColor, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("添加到歌单", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = cardText)
                    Text("将当前歌曲添加至自定义本地或云端歌单", fontSize = 12.sp, color = cardSubText)
                }
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = if (isPlaylistExpanded) "收起添加到歌单" else "展开添加到歌单",
                    tint = cardSubText,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = playlistChevronRotation }
                )
            }

            AnimatedVisibility(
                visible = isPlaylistExpanded,
                enter = expandVertically(tween(240)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(160))
            ) {
                AddToPlaylistSubMenuContent(
                    song = displayedTrack,
                    playlistManager = playlistManager,
                    ncmApi = ncmApi,
                    palette = palette,
                    cardText = cardText,
                    cardSubText = cardSubText
                )
            }
        }
    }
}

@Composable
private fun UsbExclusiveSubMenuContent(
    isExclusive: Boolean,
    usbDeviceName: String?,
    uacVersion: String?,
    deviceDetails: Akari.NCM.player.core.UsbDeviceDetails?,
    palette: PlayerExpressivePalette,
    cardText: Color,
    cardSubText: Color,
    onToggleExclusive: (Boolean) -> Unit,
    onRequestPermission: () -> Unit
) {
    val hasDevice = !usbDeviceName.isNullOrBlank()
    val hasPermission = deviceDetails?.hasPermission == true
    val isExclusiveActive = isExclusive && hasDevice && hasPermission
    val isPendingPermission = isExclusive && hasDevice && !hasPermission
    val isUac2 = uacVersion?.contains("2.0") == true
    val isUac3 = uacVersion?.contains("3.0") == true

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
            .testTag("quick_submenu_usb_exclusive")
    ) {
        HorizontalDivider(color = cardSubText.copy(alpha = 0.2f))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = palette.secondaryButtonContainer.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用 USB 硬件独占", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cardText)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            isPendingPermission -> "待授权 · 尚未获得 USB 访问权限，独占尚未生效"
                            isExclusiveActive -> "已启用 · 已绕过系统混音直连 USB DAC，自动旁路音量均衡 DSP"
                            hasDevice -> "直接接管 USB DAC 输出通道，绕过系统混音与重采样；开启后自动旁路音量均衡"
                            else -> "连接 USB DAC 或小尾巴后，可绕过 Android 系统重采样实现无损源码直通"
                        },
                        fontSize = 12.sp,
                        color = cardSubText
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = isExclusive,
                    onCheckedChange = { onToggleExclusive(it) },
                    enabled = hasDevice || isExclusive,
                    colors = playerSwitchColors(palette),
                    modifier = Modifier.testTag("switch_usb_exclusive")
                )
            }
        }

        if (isPendingPermission) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = palette.secondaryButtonContainer.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, palette.cardOutlineColor.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("usb_pending_permission_banner")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "待授权：系统尚未授予此 USB 声卡硬件访问权限，当前仍使用系统音频路由。",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = cardText
                    )
                    FilledTonalButton(
                        onClick = onRequestPermission,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("btn_request_usb_permission")
                    ) {
                        Icon(Icons.Rounded.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("申请 USB 硬件权限", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = palette.secondaryButtonContainer.copy(alpha = 0.30f),
            border = BorderStroke(1.dp, palette.cardOutlineColor.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (hasDevice) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = palette.playedLyricColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            if (hasDevice) "已识别 USB 音频硬件" else "尚未接入 USB 声卡",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cardText
                        )
                        Text(
                            if (hasDevice) {
                                val mfg = deviceDetails?.manufacturerName?.takeIf { it.isNotBlank() }
                                if (mfg != null) "$mfg · $usbDeviceName" else (usbDeviceName ?: "未知 USB 音频设备")
                            } else "请插紧 USB 耳放/小尾巴或检查 Type-C 连接",
                            fontSize = 12.sp,
                            color = cardSubText
                        )
                    }
                }

                if (hasDevice) {
                    HorizontalDivider(color = cardSubText.copy(alpha = 0.2f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = palette.primaryButtonContainer
                        ) {
                            Text(
                                text = uacVersion ?: "UAC 1.0 / 2.0",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.onPrimaryButton,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when {
                                isUac2 -> "高速异步等时传输 (支持 192kHz+ 及 Hi-Res)"
                                isUac3 -> "新一代低功耗音频协议"
                                else -> "标准音频模式 (最高 96kHz / 24-bit)"
                            },
                            fontSize = 11.sp,
                            color = cardSubText
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (deviceDetails != null && (deviceDetails.vendorId > 0 || deviceDetails.productId > 0)) {
                            Text(
                                "硬件标识: ${deviceDetails.formattedVidPid}",
                                fontSize = 11.sp,
                                color = cardSubText
                            )
                        }
                        val statusText = when {
                            isExclusiveActive -> "独占状态: 🟢 硬件独占直通生效中 (Bit-Perfect · 音量均衡已旁路)"
                            isPendingPermission -> "独占状态: 🟠 待授权 · 等待授予 USB 硬件访问权限（尚未启用独占）"
                            isExclusive -> "独占状态: 🟡 等待 USB DAC 硬件接入"
                            else -> "独占状态: ⚪ 系统共享音频路由"
                        }
                        Text(
                            statusText,
                            fontSize = 11.sp,
                            color = if (isExclusiveActive) palette.playedLyricColor else cardSubText
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("DefaultLocale")
@Composable
private fun PlaybackStatusSubMenuContent(
    media: PlayableMedia?,
    audioFormatInfo: Akari.NCM.player.core.AudioFormatInfo,
    isUsbExclusive: Boolean = false,
    usbDeviceName: String? = null,
    uacVersion: String? = null,
    hasUsbPermission: Boolean = false,
    loudnessState: Akari.NCM.player.core.loudness.LoudnessNormalizationState,
    cardText: Color,
    cardSubText: Color
) {
    val context = LocalContext.current

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
            .testTag("quick_submenu_playback_status")
    ) {
        HorizontalDivider(color = cardSubText.copy(alpha = 0.2f))

        if (media == null) {
            Text("当前未加载任何曲目", fontSize = 13.sp, color = cardSubText)
            return
        }

        val isLocal = media.isLocal
        val sourceText = if (isLocal) {
            "本地存储 (Local)"
        } else {
            "云端在线 (Cloud) · ${Akari.NCM.player.core.QualityLevel.displayLabelFor(audioFormatInfo.qualityLevel)}"
        }
        val actualQualityText = if (audioFormatInfo.qualityLevel != null) {
            "${Akari.NCM.player.core.QualityLevel.displayLabelFor(audioFormatInfo.qualityLevel)} (${audioFormatInfo.qualityLevel})"
        } else {
            "正在解析..."
        }

        val fileSizeText = remember(media, audioFormatInfo.fileSizeBytes) {
            val bytes = if (media is PlayableMedia.LocalNcm) {
                runCatching {
                    val uri = android.net.Uri.parse(media.uri)
                    if (uri.scheme == "content") {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                    } else {
                        java.io.File(uri.path ?: "").length()
                    }
                }.getOrDefault(0L)
            } else {
                audioFormatInfo.fileSizeBytes
            }
            if (bytes > 0) {
                val mb = bytes / (1024f * 1024f)
                if (mb >= 1.0f) String.format("%.2f MB (%d 字节)", mb, bytes)
                else String.format("%.1f KB (%d 字节)", bytes / 1024f, bytes)
            } else if (media is PlayableMedia.LocalNcm) "未知" else null
        }

        val sampleRateText = if (audioFormatInfo.sampleRateHz > 0) {
            String.format("%.1f kHz", audioFormatInfo.sampleRateHz / 1000f)
        } else "44.1 kHz"

        val bitrateText = if (audioFormatInfo.bitrateKbps > 0) {
            "${audioFormatInfo.bitrateKbps} kbps"
        } else "标准 / 动态比特率"

        val bitDepthText = "${audioFormatInfo.bitDepthBits}-bit"

        InfoRow("歌曲名称", media.title, cardSubText, cardText)
        InfoRow("艺术家", media.artist, cardSubText, cardText)
        InfoRow("专辑名称", media.album, cardSubText, cardText)
        HorizontalDivider(color = cardSubText.copy(alpha = 0.16f))
        InfoRow("音频来源", sourceText, cardSubText, cardText)
        if (!isLocal) {
            InfoRow("实际音质层级", actualQualityText, cardSubText, cardText)
        }
        if (fileSizeText != null) {
            val sizeLabel = if (isLocal) "本地文件大小" else "音频文件大小"
            InfoRow(sizeLabel, fileSizeText, cardSubText, cardText)
        }
        if (isUsbExclusive && !usbDeviceName.isNullOrBlank()) {
            val uacTag = if (!uacVersion.isNullOrBlank() && uacVersion != "未连接") " ($uacVersion)" else ""
            val routeValue = if (hasUsbPermission) {
                "USB 硬件独占直通 · $usbDeviceName$uacTag"
            } else {
                "系统共享音频路由 · $usbDeviceName$uacTag (USB 独占待授权)"
            }
            InfoRow("输出通道", routeValue, cardSubText, cardText)
        }
        HorizontalDivider(color = cardSubText.copy(alpha = 0.16f))
        InfoRow("实时比特率", bitrateText, cardSubText, cardText)
        InfoRow("采样率 / 位深", "$sampleRateText  ·  $bitDepthText", cardSubText, cardText)
        if (!audioFormatInfo.mimeType.isNullOrBlank()) {
            InfoRow("音频编码格式", audioFormatInfo.mimeType, cardSubText, cardText)
        }
        HorizontalDivider(color = cardSubText.copy(alpha = 0.16f))
        val normStatus = when {
            loudnessState.isBypassedForUsbExclusive -> "USB 独占已自动旁路（源码直通 Bit-Perfect）"
            loudnessState.isEnabled -> {
                val gainStr = if (loudnessState.appliedGainDb >= 0) "+${String.format("%.1f", loudnessState.appliedGainDb)}" else String.format("%.1f", loudnessState.appliedGainDb)
                val peakStr = if (loudnessState.isPeakLimited) " · 触发 -1.0 dBTP 限幅" else ""
                val negStr = if (loudnessState.isNegativeGainOnly) " · 仅降不升" else ""
                val waitStr = if (loudnessState.isInterTrackWaiting) " [曲间校准中]" else ""
                "${loudnessState.source.tierLabel} · ${loudnessState.source.label} · 增益: $gainStr dB (目标: ${loudnessState.targetLufs} LUFS)$negStr$peakStr$waitStr"
            }
            else -> "未开启"
        }
        InfoRow("音量均衡状态 (EBU R128)", normStatus, cardSubText, cardText)
        if (!loudnessState.isBypassedForUsbExclusive && loudnessState.sourceLoudnessLufs != null) {
            val peakText = if (loudnessState.sourcePeakDb != null) String.format(" · 真峰值: %.1f dBTP", loudnessState.sourcePeakDb) else ""
            InfoRow(
                "曲目综合响度",
                String.format("%.1f LUFS%s (%s)", loudnessState.sourceLoudnessLufs, peakText, loudnessState.source.label),
                cardSubText,
                cardText
            )
        }
        if (loudnessState.isEnabled && !loudnessState.isBypassedForUsbExclusive && loudnessState.prefetchedTotalCount > 0) {
            val avgText = loudnessState.upcomingAverageLufs?.let {
                String.format(" · 平均 %.1f LUFS", it)
            } ?: ""
            InfoRow(
                "未来队列预计算 (最多 3 首)",
                "已缓存 ${loudnessState.prefetchedCount}/${loudnessState.prefetchedTotalCount} 首$avgText",
                cardSubText,
                cardText
            )
        }
    }
}

@Composable
private fun AddToPlaylistSubMenuContent(
    song: PlayableMedia?,
    playlistManager: PlaylistManager,
    ncmApi: NcmApi,
    palette: PlayerExpressivePalette,
    cardText: Color,
    cardSubText: Color
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by playlistManager.playlists.collectAsState()
    var showInlineCreate by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var lastAddedPlaylistName by remember { mutableStateOf<String?>(null) }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
            .testTag("quick_submenu_add_to_playlist")
    ) {
        HorizontalDivider(color = cardSubText.copy(alpha = 0.2f))

        if (song == null) {
            Text("当前未播放任何曲目", fontSize = 13.sp, color = cardSubText)
            return
        }

        if (lastAddedPlaylistName != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = palette.primaryButtonContainer.copy(alpha = 0.22f),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quick_playlist_added_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = palette.playedLyricColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "已添加至「$lastAddedPlaylistName」",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cardText
                    )
                }
            }
        }

        Surface(
            onClick = { showInlineCreate = !showInlineCreate },
            shape = RoundedCornerShape(12.dp),
            color = palette.primaryButtonContainer,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("quick_playlist_create_toggle")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (showInlineCreate) Icons.Rounded.Close else Icons.Rounded.Add,
                    contentDescription = "新建歌单",
                    tint = palette.onPrimaryButton,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (showInlineCreate) "收起新建歌单" else "新建歌单并添加",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.onPrimaryButton
                )
            }
        }

        AnimatedVisibility(
            visible = showInlineCreate,
            enter = expandVertically(tween(200)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    placeholder = { Text("输入新歌单名称", fontSize = 13.sp, color = cardSubText) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("quick_playlist_name_input")
                )
                FilledTonalButton(
                    onClick = {
                        val trimmed = newPlaylistName.trim()
                        if (trimmed.isNotEmpty()) {
                            val created = playlistManager.createPlaylist(trimmed)
                            playlistManager.addSongToPlaylist(created.id, song)
                            lastAddedPlaylistName = created.name
                            newPlaylistName = ""
                            showInlineCreate = false
                            Toast.makeText(context, "已创建并添加到「${created.name}」", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = newPlaylistName.isNotBlank(),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("quick_playlist_create_confirm")
                ) {
                    Text("添加", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (playlists.isEmpty()) {
            Text(
                "暂无自建歌单，点击上方新建歌单即可添加",
                fontSize = 12.sp,
                color = cardSubText,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                playlists.forEach { playlist ->
                    Surface(
                        onClick = {
                            playlistManager.addSongToPlaylist(playlist.id, song)
                            if (playlist.ncmPlaylistId != null) {
                                scope.launch(Dispatchers.IO) {
                                    ncmApi.updatePlaylistTracks("add", playlist.ncmPlaylistId, song.id)
                                }
                            }
                            lastAddedPlaylistName = playlist.name
                            Toast.makeText(context, "已添加到「${playlist.name}」", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = palette.secondaryButtonContainer.copy(alpha = 0.35f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("quick_playlist_item_${playlist.id}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = null,
                                tint = palette.playedLyricColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    playlist.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = cardText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${playlist.songs.size} 首歌曲",
                                    fontSize = 11.sp,
                                    color = cardSubText
                                )
                            }
                            Icon(
                                Icons.Rounded.AddCircleOutline,
                                contentDescription = "添加到 ${playlist.name}",
                                tint = cardSubText,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color
) {
    Column {
        Text(label, fontSize = 12.sp, color = labelColor)
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

@Composable
private fun playerSwitchColors(palette: PlayerExpressivePalette): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = palette.onPrimaryButton,
    checkedTrackColor = palette.primaryButtonContainer,
    checkedBorderColor = palette.primaryButtonContainer,
    uncheckedThumbColor = palette.secondaryTextColor,
    uncheckedTrackColor = palette.secondaryButtonContainer.copy(alpha = 0.65f),
    uncheckedBorderColor = palette.cardOutlineColor.copy(alpha = 0.55f),
    disabledCheckedThumbColor = palette.onPrimaryButton.copy(alpha = 0.5f),
    disabledCheckedTrackColor = palette.primaryButtonContainer.copy(alpha = 0.4f),
    disabledUncheckedThumbColor = palette.secondaryTextColor.copy(alpha = 0.4f),
    disabledUncheckedTrackColor = palette.secondaryButtonContainer.copy(alpha = 0.3f),
    disabledUncheckedBorderColor = palette.cardOutlineColor.copy(alpha = 0.25f)
)
