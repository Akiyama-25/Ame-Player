package Akari.NCM.player.ui.screen

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.LyricLine
import Akari.NCM.player.core.LyricSource
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.PlayerController
import Akari.NCM.player.core.PlayerState
import Akari.NCM.player.core.RepeatMode
import Akari.NCM.player.data.LyricResult
import Akari.NCM.player.data.LyricsManager
import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*

import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.absoluteValue
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
    ncmApi: Akari.NCM.player.api.NcmApi,
    onBackClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onToggleQueueClick: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val rawTrack by playerState.currentMedia.collectAsState()

    // 缓存缓冲当前渲染曲目：网络/磁盘封面图片加载完成前不切换曲目信息与画面，防切歌空白
    var displayedTrack by remember { mutableStateOf(rawTrack) }

    LaunchedEffect(rawTrack) {
        val target = rawTrack
        if (target == null) {
            displayedTrack = null
        } else if (displayedTrack == null || displayedTrack?.id != target.id || displayedTrack?.coverUrl != target.coverUrl) {
            val url = target.coverUrl
            if (!url.isNullOrEmpty()) {
                withTimeoutOrNull(450) {
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        .size(Size.ORIGINAL)
                        .build()
                    context.imageLoader.execute(request)
                }
            }
            displayedTrack = target
        } else {
            displayedTrack = target
        }
    }

    val activeTrack = displayedTrack ?: rawTrack
    val coverUrl = activeTrack?.coverUrl

    var showPlayQueuePage by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || configuration.screenWidthDp > configuration.screenHeightDp

    val queueProgress by animateFloatAsState(
        targetValue = if (showPlayQueuePage && !isLandscape) 1f else 0f,
        animationSpec = tween(450, easing = EaseInOut),
        label = "QueueParallaxProgress"
    )

    BackHandler(enabled = showPlayQueuePage) {
        showPlayQueuePage = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
    ) {
        // ── 1. 全局沉浸式高斯模糊背景 (Salt Player 风格：鲜艳高饱和、无重度灰黑遮罩) ──
        if (coverUrl != null) {
            AsyncImage(
                model            = coverUrl,
                contentDescription = null,
                modifier         = Modifier.fillMaxSize().blur(70.dp),
                contentScale     = ContentScale.Crop,
                alpha            = 0.85f
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color(0x66000000),
                        Color(0x80000000),
                        Color(0xB3000000)
                    )
                )
            )
        )

        // ── 2. 主播放界面（带有 3D 视差推退与微缩放效果）─────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = lerpF(1.0f, 0.92f, queueProgress)
                    val translateY = lerpF(0f, -32.dp.toPx(), queueProgress)
                    val alphaVal = lerpF(1.0f, 0.45f, queueProgress)

                    scaleX = scale
                    scaleY = scale
                    translationY = translateY
                    alpha = alphaVal
                }
                .clip(RoundedCornerShape(with(density) { lerpF(0f, 24f, queueProgress).toDp() }))
        ) {
            PlayerMainContentView(
                playerState = playerState,
                playerController = playerController,
                displayedTrack = activeTrack,
                ncmApi = ncmApi,
                onBackClick = onBackClick,
                onAddToPlaylistClick = onAddToPlaylistClick,
                onOpenQueueClick = { showPlayQueuePage = true }
            )
        }

        // ── 3. 播放队列视图 ───────────────────────────
        if (isLandscape && showPlayQueuePage) {
            val playlist by playerState.playlist.collectAsState()
            val currentIndex by playerState.currentIndex.collectAsState()
            val isPlaying by playerState.isPlaying.collectAsState()
            
            @OptIn(ExperimentalMaterial3Api::class)
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { showPlayQueuePage = false }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = configuration.screenHeightDp.dp * 0.85f)
                        .padding(bottom = 16.dp)
                ) {
                    Text(
                        "播放队列 (${playlist.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp)
                    )
                    if (playlist.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("队列为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            itemsIndexed(playlist) { index, media ->
                                Akari.NCM.player.ui.screen.SongItem(
                                    media = media,
                                    isCurrentTrack = index == currentIndex,
                                    isPlaying = isPlaying && index == currentIndex,
                                    onClick = {
                                        playerController.skipToIndex(index)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !isLandscape && showPlayQueuePage,
            enter = slideInVertically(
                initialOffsetY = { fullHeight -> fullHeight },
                animationSpec = tween(450, easing = EaseInOut)
            ) + fadeIn(tween(300)),
            exit = slideOutVertically(
                targetOffsetY = { fullHeight -> fullHeight },
                animationSpec = tween(450, easing = EaseInOut)
            ) + fadeOut(tween(300))
        ) {
            SaltPlayQueueView(
                playerState = playerState,
                playerController = playerController,
                displayedTrack = activeTrack,
                onDismiss = { showPlayQueuePage = false }
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
    ncmApi: Akari.NCM.player.api.NcmApi,
    onBackClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onOpenQueueClick: () -> Unit
) {
    val currentTrack = displayedTrack

    // ── 快捷功能与播放状态弹窗 ──────────────────────────────────────────────
    var showQuickFunctionsDialog by remember { mutableStateOf(false) }
    var showPlaybackStatusDialog by remember { mutableStateOf(false) }
    var showUsbExclusiveDialog by remember { mutableStateOf(false) }

    val isUsbExclusiveMode by playerState.isUsbExclusiveMode.collectAsState()
    val connectedUsbDeviceName by playerState.connectedUsbDeviceName.collectAsState()

    if (showQuickFunctionsDialog) {
        QuickFunctionsDialog(
            isUsbExclusive = isUsbExclusiveMode,
            usbDeviceName = connectedUsbDeviceName,
            onDismiss = { showQuickFunctionsDialog = false },
            onShowStatusClick = { showPlaybackStatusDialog = true },
            onShowUsbExclusiveClick = { showUsbExclusiveDialog = true },
            onAddToPlaylistClick = onAddToPlaylistClick
        )
    }

    if (showUsbExclusiveDialog) {
        UsbExclusiveDialog(
            isExclusive = isUsbExclusiveMode,
            usbDeviceName = connectedUsbDeviceName,
            onToggleExclusive = { enabled ->
                playerController.setUsbExclusiveMode(enabled)
            },
            onDismiss = { showUsbExclusiveDialog = false }
        )
    }

    if (showPlaybackStatusDialog) {
        val audioFormatInfo by playerState.audioFormatInfo.collectAsState()
        PlaybackStatusDialog(
            media = currentTrack,
            audioFormatInfo = audioFormatInfo,
            isUsbExclusive = isUsbExclusiveMode,
            usbDeviceName = connectedUsbDeviceName,
            onDismiss = { showPlaybackStatusDialog = false }
        )
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // ── 播放状态 ───────────────────────────────────────────────────────────────
    val isPlaying      by playerState.isPlaying.collectAsState()
    val positionMs     by playerState.currentPositionMs.collectAsState()
    val durationMs     by playerState.durationMs.collectAsState()
    val repeatMode     by playerState.repeatMode.collectAsState()
    val shuffleEnabled by playerState.shuffleEnabled.collectAsState()

    // ── 歌词 ───────────────────────────────────────────────────────────────────
    val lyricsManager   = remember { Akari.NCM.player.data.LyricsManager(context, ncmApi) }
    var lyricResult     by remember { mutableStateOf(Akari.NCM.player.data.LyricResult(emptyList(), Akari.NCM.player.core.LyricSource.NONE)) }
    var showTranslation by remember { mutableStateOf(true) }

    LaunchedEffect(currentTrack?.id) {
        lyricResult = currentTrack?.let { lyricsManager.loadLyrics(it) }
            ?: Akari.NCM.player.data.LyricResult(emptyList(), Akari.NCM.player.core.LyricSource.NONE)
    }

    val currentLineIndex = remember(lyricResult.lines, positionMs) {
        val lines = lyricResult.lines
        if (lines.isEmpty()) -1
        else lines.indexOfLast { it.timeMs <= positionMs }.let { if (it == -1) 0 else it }
    }

    // 当前播放歌词行
    val currentLyricLine = lyricResult.lines.getOrNull(currentLineIndex)

    // ── 进度条拖拽 ─────────────────────────────────────────────────────────────
    var isDragging     by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableStateOf(0L) }

    // ── 动画 ───────────────────────────────────────────────────────────────────
    // progress = 0 → 封面大图模式   progress = 1 → 歌词全屏模式
    val progress    = remember { Animatable(0f) }
    var isLyricsMode by remember { mutableStateOf(false) }
    val p = progress.value
    val jobRef = remember { object { var job: Job? = null } }
    val lyricsListState = rememberLazyListState()

    fun goToLyrics() {
        jobRef.job?.cancel()
        isLyricsMode = true
        jobRef.job = scope.launch {
            progress.animateTo(1f, tween(500, easing = EaseInOut))
        }
    }

    fun goToCover() {
        jobRef.job?.cancel()
        jobRef.job = scope.launch {
            progress.animateTo(0f, tween(500, easing = EaseInOut))
            isLyricsMode = false
        }
    }

    // ── 位置量测：封面大图 ─────────────────────────────────────────────────────
    var largeCoverTopLeft by remember { mutableStateOf(Offset.Zero) }
    var largeCoverSizePx  by remember { mutableStateOf(0f) }

    // ── 位置量测：迷你封面目标 ─────────────────────────────────────────────────
    val miniCoverSizePx = with(density) { 52.dp.toPx() }
    var miniCoverTopLeft by remember { mutableStateOf(Offset.Zero) }

    // ── 动画插值：封面覆层 ─────────────────────────────────────────────────────
    val coverX      = lerpF(largeCoverTopLeft.x, miniCoverTopLeft.x, p)
    val coverY      = lerpF(largeCoverTopLeft.y, miniCoverTopLeft.y, p)
    val coverW      = lerpF(largeCoverSizePx,    miniCoverSizePx,    p)
    val cornerDp    = with(density) { lerpF(24f, 8f, p).toDp() }
    val shadowDp    = with(density) { lerpF(20f, 2f, p).toDp() }

    val coverUrl = currentTrack?.coverUrl

    val prevLyricAlpha     = lerpF(1f, 0f, p)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight || maxWidth >= 550.dp

        // ── 氛围背景 (Salt Player 风格：鲜艳高饱和、无重度灰黑遮罩) ───────────
        Box(Modifier.fillMaxSize().background(Color(0xFF0F172A)))
        if (coverUrl != null) {
            AsyncImage(
                model            = coverUrl,
                contentDescription = null,
                modifier         = Modifier.fillMaxSize().blur(70.dp),
                contentScale     = ContentScale.Crop,
                alpha            = 0.85f
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color(0x20000000),
                        Color(0x40000000),
                        Color(0x70000000)
                    )
                )
            )
        )

        val TitleAndArtistBlock: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier = modifier) {
                Text(
                    text       = currentTrack?.title ?: "未在播放",
                    fontSize   = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text     = buildString {
                        append(currentTrack?.artist ?: "未知歌手")
                        if (!currentTrack?.album.isNullOrBlank()) append(" / ${currentTrack?.album}")
                    },
                    fontSize = 14.sp,
                    color    = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        val CurrentLyricBlock: @Composable (Alignment.Horizontal) -> Unit = { align ->
            Box(
                modifier = Modifier.fillMaxWidth().height(64.dp),
                contentAlignment = if (align == Alignment.CenterHorizontally) Alignment.Center else Alignment.CenterStart
            ) {
                if (currentLyricLine != null) {
                    val textToShow = if (currentLyricLine.text.isBlank()) "..." else currentLyricLine.text.trim()
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = align,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text       = textToShow,
                            fontSize   = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color      = Color.White,
                            textAlign  = if (align == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis
                        )
                        if (showTranslation) {
                            Spacer(Modifier.height(4.dp))
                            if (!currentLyricLine.translation.isNullOrBlank()) {
                                Text(
                                    text       = currentLyricLine.translation.trim(),
                                    fontSize   = 16.sp,
                                    color      = Color.White.copy(alpha = 0.75f),
                                    textAlign  = if (align == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start,
                                    maxLines   = 1,
                                    overflow   = TextOverflow.Ellipsis
                                )
                            } else {
                                Text(" ", fontSize = 16.sp)
                            }
                        }
                    }
                } else {
                    Text(
                        "暂无歌词 / 纯音乐",
                        fontSize  = 16.sp,
                        color     = Color.White.copy(alpha = 0.5f),
                        textAlign = if (align == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start
                    )
                }
            }
        }

        val ProgressBarBlock: @Composable () -> Unit = {
            val effectivePosition = if (isDragging) dragPositionMs else positionMs
            val sliderProgress =
                if (durationMs > 0) (effectivePosition.toFloat() / durationMs).coerceIn(0f, 1f)
                else 0f

            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value               = sliderProgress,
                    onValueChange       = { pct ->
                        isDragging     = true
                        dragPositionMs = (pct * durationMs).toLong()
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        playerController.seekTo(dragPositionMs)
                    },
                    thumb = {
                        Surface(
                            shape           = CircleShape,
                            color           = Color.White,
                            shadowElevation = 4.dp,
                            modifier        = Modifier.size(16.dp)
                        ) {}
                    },
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState           = sliderState,
                            colors                = SliderDefaults.colors(
                                activeTrackColor   = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                            ),
                            drawStopIndicator     = null,
                            thumbTrackGapSize     = 0.dp,
                            trackInsideCornerSize = 0.dp
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(effectivePosition), fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                    Text(formatTime(durationMs),        fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                }
            }
        }

        val ControlsBlock: @Composable () -> Unit = {
            val skipSize = 48.dp
            val skipIconSize = 36.dp
            val playBgSize = 64.dp
            val playIconSize = 36.dp
            
            Row(
                modifier              = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                IconButton(onClick = { playerController.skipToPrevious() }, modifier = Modifier.size(skipSize)) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(skipIconSize))
                }
                Surface(
                    onClick  = { playerController.togglePlayPause() },
                    shape    = CircleShape,
                    color    = Color.White,
                    modifier = Modifier.size(playBgSize)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint               = Color(0xFF0F172A),
                            modifier           = Modifier.size(playIconSize)
                        )
                    }
                }
                IconButton(onClick = { playerController.skipToNext() }, modifier = Modifier.size(skipSize)) {
                    Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(skipIconSize))
                }
            }
        }

        val ToolbarBlock: @Composable () -> Unit = {
            val btnSize = 48.dp
            val iconSize = 24.dp
            Row(
                modifier              = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (shuffleEnabled) {
                        playerController.toggleShuffle()
                        playerController.setRepeatMode(RepeatMode.OFF)
                    } else when (repeatMode) {
                        RepeatMode.OFF -> playerController.setRepeatMode(RepeatMode.ONE)
                        RepeatMode.ONE -> { playerController.setRepeatMode(RepeatMode.OFF); playerController.toggleShuffle() }
                        else           -> playerController.setRepeatMode(RepeatMode.OFF)
                    }
                }, modifier = Modifier.size(btnSize)) {
                    val (modeIcon, modeDesc) = when {
                        shuffleEnabled           -> Pair(Icons.Rounded.Shuffle,                   "随机播放")
                        repeatMode == RepeatMode.ONE -> Pair(Icons.Rounded.RepeatOne,             "单曲循环")
                        else                     -> Pair(Icons.AutoMirrored.Rounded.ArrowForward, "顺序播放")
                    }
                    Icon(
                        modeIcon, modeDesc,
                        tint     = if (repeatMode == RepeatMode.ONE || shuffleEnabled) Color(0xFF60A5FA)
                                   else Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(iconSize)
                    )
                }
                IconButton(onClick = onOpenQueueClick, modifier = Modifier.size(btnSize)) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(iconSize))
                }
                IconButton(onClick = { showQuickFunctionsDialog = true }, modifier = Modifier.size(btnSize)) {
                    Icon(Icons.Rounded.MoreHoriz, "快捷功能", tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(iconSize))
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            val containerWidth = this@BoxWithConstraints.maxWidth
            val containerHeight = this@BoxWithConstraints.maxHeight

            if (isLandscape) {
                val isShortLandscape = containerHeight < 500.dp
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp)
                ) {
                    if (isShortLandscape) {
                        // Left Side: Title and Cover Art
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
                                        .shadow(16.dp, RoundedCornerShape(16.dp))
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            if (isLyricsMode) goToCover() else goToLyrics()
                                        }
                                ) {
                                    if (coverUrl != null) {
                                        AsyncImage(
                                            model = coverUrl,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Rounded.MusicNote,
                                                contentDescription = null,
                                                tint = Color.White.copy(alpha = 0.4f),
                                                modifier = Modifier.size(maxCoverSize * 0.4f)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.width(48.dp))

                        // Right Side: Content
                        BoxWithConstraints(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            val rightMaxHeight = maxHeight
                            if (isLyricsMode) {
                                SaltFullLyricsView(
                                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = p },
                                    lyricResult = lyricResult,
                                    currentIndex = currentLineIndex,
                                    showTranslation = showTranslation,
                                    listState = lyricsListState,
                                    onToggleTranslation = { showTranslation = !showTranslation },
                                    onLyricClick = { playerController.seekTo(it) }
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
                                    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.CenterEnd) {
                                        IconButton(onClick = { showTranslation = !showTranslation }) {
                                            Icon(
                                                Icons.Rounded.Translate,
                                                contentDescription = "Translate Toggle",
                                                tint = if (showTranslation) Color.White else Color.White.copy(alpha = 0.5f)
                                            )
                                        }
                                    }

                                    // Account for translation button (56dp), spacers (32dp), progress (20dp), controls (64dp), toolbar (48dp)
                                    val controlsHeight = 220.dp
                                    val availableHeight = rightMaxHeight - controlsHeight
                                    val fixedLineHeight = 64.dp
                                    val maxLinesCount = when {
                                        availableHeight >= fixedLineHeight * 3 -> 3
                                        availableHeight >= fixedLineHeight * 2 -> 2
                                        else -> 1
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(fixedLineHeight * maxLinesCount)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = { goToLyrics() }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (lyricResult.lines.isEmpty()) {
                                            Text("暂无歌词 / 纯音乐", fontSize = 16.sp, color = Color.White.copy(alpha = 0.5f))
                                        } else {
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
                                                    val isSelected = (adjStart + i) == actualCurrent
                                                    Column(
                                                        modifier = Modifier.fillMaxWidth().height(fixedLineHeight),
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.Center
                                                    ) {
                                                        Text(
                                                            text = if (line.text.isBlank()) "..." else line.text.trim(),
                                                            fontSize = if (isSelected) 22.sp else 18.sp,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                            color = Color.White.copy(alpha = if (isSelected) 1f else 0.4f),
                                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (showTranslation) {
                                                            Spacer(Modifier.height(4.dp))
                                                            if (!line.translation.isNullOrBlank()) {
                                                                Text(
                                                                    text = line.translation.trim(),
                                                                    fontSize = if (isSelected) 16.sp else 14.sp,
                                                                    color = Color.White.copy(alpha = if (isSelected) 0.75f else 0.3f),
                                                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                                                )
                                                            } else {
                                                                Text(" ", fontSize = if (isSelected) 16.sp else 14.sp)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(16.dp))
                                    ProgressBarBlock()
                                    Spacer(Modifier.height(8.dp))
                                    ControlsBlock()
                                    Spacer(Modifier.height(8.dp))
                                    ToolbarBlock()
                                }
                            }
                        }
                    } else {
                        // Left Side: Player Controls & Info
                    BoxWithConstraints(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        val controlsHeight = 150.dp
                        val topPadding = 48.dp
                        val availableHeightForCover = maxHeight - controlsHeight - topPadding - 16.dp
                        val maxCoverSize = minOf(maxWidth * 0.85f, availableHeightForCover)
                        
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = topPadding),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Cover
                            Box(
                                modifier = Modifier
                                    .size(maxCoverSize)
                                    .shadow(24.dp, RoundedCornerShape(16.dp))
                                    .clip(RoundedCornerShape(16.dp))
                            ) {
                                if (coverUrl != null) {
                                    AsyncImage(
                                        model = coverUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.4f),
                                            modifier = Modifier.size(maxCoverSize * 0.4f)
                                        )
                                    }
                                }
                            }
                            
                            Spacer(Modifier.height(24.dp))
                            
                            // Info & Controls Area
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                TitleAndArtistBlock(Modifier.fillMaxWidth(0.9f))
                                Spacer(Modifier.height(16.dp))
                                ProgressBarBlock()
                                Spacer(Modifier.height(8.dp))
                                ControlsBlock()
                                Spacer(Modifier.height(8.dp))
                                ToolbarBlock()
                            }
                        }
                    }

                    Spacer(Modifier.width(32.dp))

                    // Right Side: Full Screen Lyrics Always Visible
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        SaltFullLyricsView(
                            modifier = Modifier.fillMaxSize(),
                            lyricResult = lyricResult,
                            currentIndex = currentLineIndex,
                            showTranslation = showTranslation,
                            listState = lyricsListState,
                            onToggleTranslation = { showTranslation = !showTranslation },
                            onLyricClick = { playerController.seekTo(it) }
                        )
                    }
                    }
                }
                
                // Back Button Absolute Top Left
                Box(modifier = Modifier.fillMaxSize()) {
                    IconButton(onClick = onBackClick, modifier = Modifier.padding(16.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            } else {
                // Portrait Layout (Original)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                ) {
                    // ── 顶部栏 ────────────────────────────
                    Row(
                        modifier           = Modifier.fillMaxWidth().height(64.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment  = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                        }
                        
                        IconButton(
                            onClick = { showTranslation = !showTranslation },
                            modifier = Modifier.graphicsLayer { alpha = 1f - p }
                        ) {
                            Icon(
                                Icons.Rounded.Translate,
                                contentDescription = "Translate Toggle",
                                tint = if (showTranslation) Color.White else Color.White.copy(alpha = 0.5f)
                            )
                        }
                    }

                    // ── 标题行 ────────────────────────────────────────────────────────
                    Row(
                        modifier          = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(Modifier.width((52.dp + 12.dp) * p))

                        TitleAndArtistBlock(
                            Modifier
                                .weight(1f)
                                .onGloballyPositioned { coords ->
                                    val colPos = coords.positionInRoot()
                                    val colH   = coords.size.height.toFloat()
                                    val mx = colPos.x - with(density) { (52.dp + 12.dp).toPx() }
                                    val my = colPos.y + (colH - miniCoverSizePx) / 2f
                                    if ((miniCoverTopLeft - Offset(mx, my)).getDistanceSquared() > 1f) {
                                        miniCoverTopLeft = Offset(mx, my)
                                    }
                                }
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // ── 内容区 ────────────────────────────────────────────────────────
                    BoxWithConstraints(
                        modifier          = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment  = Alignment.Center
                    ) {
                        val containerWidth = maxWidth
                        val containerHeight = maxHeight
                        
                        val maxCoverSize = minOf(containerWidth * 0.82f, containerHeight * 0.75f)

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
                                            largeCoverSizePx  = coords.size.width.toFloat()
                                        }
                                )

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer {
                                                alpha        = prevLyricAlpha
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CurrentLyricBlock(Alignment.CenterHorizontally)
                                    }
                                }
                            }
                        }

                        if (isLyricsMode) {
                            SaltFullLyricsView(
                                modifier             = Modifier.fillMaxSize().graphicsLayer { alpha = p },
                                lyricResult          = lyricResult,
                                currentIndex         = currentLineIndex,
                                showTranslation      = showTranslation,
                                listState            = lyricsListState,
                                onToggleTranslation  = { showTranslation = !showTranslation },
                                onLyricClick         = { playerController.seekTo(it) }
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    ProgressBarBlock()
                    Spacer(Modifier.height(12.dp))
                    ControlsBlock()
                    Spacer(Modifier.height(8.dp))
                    ToolbarBlock()
                }
            }
        }

        // ── 封面覆层 (仅在 Portrait 模式下显示) ──────────────────────────────────────────────────────────
        if (!isLandscape && largeCoverSizePx > 0f && miniCoverTopLeft != Offset.Zero) {
            Box(
                modifier = Modifier
                    .absoluteOffset {
                        IntOffset(coverX.roundToInt(), coverY.roundToInt())
                    }
                    .size(with(density) { coverW.toDp() })
                    .shadow(shadowDp, RoundedCornerShape(cornerDp))
                    .clip(RoundedCornerShape(cornerDp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication        = null
                    ) {
                        if (p < 0.5f) goToLyrics() else goToCover()
                    }
            ) {
                if (coverUrl != null) {
                    AsyncImage(
                        model              = coverUrl,
                        contentDescription = null,
                        modifier           = Modifier.fillMaxSize(),
                        contentScale       = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint               = Color.White.copy(alpha = 0.4f),
                            modifier           = Modifier.size(with(density) { (coverW * 0.4f).toDp() })
                        )
                    }
                }
            }
        }
}
    }


// ─────────────────────────────────────────────────────────────────────────────
// 独立播放队列视图 (PLAY QUEUE)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SaltPlayQueueView(
    playerState: PlayerState,
    playerController: PlayerController,
    displayedTrack: PlayableMedia?,
    onDismiss: () -> Unit
) {
    val currentTrack   = displayedTrack
    val isPlaying      by playerState.isPlaying.collectAsState()
    val playlist       by playerState.playlist.collectAsState()
    val currentIndex   by playerState.currentIndex.collectAsState()
    val repeatMode     by playerState.repeatMode.collectAsState()
    val shuffleEnabled by playerState.shuffleEnabled.collectAsState()

    val coverUrl = currentTrack?.coverUrl

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F172A))) {
        if (coverUrl != null) {
            AsyncImage(
                model            = coverUrl,
                contentDescription = null,
                modifier         = Modifier.fillMaxSize().blur(70.dp),
                contentScale     = ContentScale.Crop,
                alpha            = 0.85f
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color(0xB30F172A), Color(0xE60F172A), Color(0xFF0F172A))
                )
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ── 下滑/点击返回提示栏 ───────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.4f))
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "下滑此处返回播放界面",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 顶部当前播放 Mini 卡片 ─────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
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
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.1f)),
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
                                Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentTrack?.title ?: "未在播放",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = buildString {
                                append(currentTrack?.artist ?: "未知歌手")
                                if (!currentTrack?.album.isNullOrBlank()) append(" - ${currentTrack?.album}")
                            },
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { playerController.togglePlayPause() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "播放/暂停",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        IconButton(
                            onClick = { playerController.skipToNext() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Rounded.SkipNext,
                                contentDescription = "下一首",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 队列 Header 栏 ─────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${if (playlist.isNotEmpty()) currentIndex + 1 else 0} / ${playlist.size}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.7f)
                )

                Spacer(Modifier.weight(1f))

                Text(
                    text = "播放队列",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )

                Spacer(Modifier.weight(1f))

                TextButton(
                    onClick = { /* Clear or reset queue */ },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "清空",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 队列曲目列表 ───────────────────────────────────────────────
            if (playlist.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "播放队列为空",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    itemsIndexed(playlist) { index, media ->
                        val isSelected = index == currentIndex
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                            border = if (isSelected) BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { playerController.skipToIndex(index) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = media.title,
                                        fontSize = 15.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        text = buildString {
                                            append(media.artist)
                                            if (media.album.isNotBlank()) append(" - ${media.album}")
                                        },
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.55f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isSelected) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        Icons.Rounded.Equalizer,
                                        contentDescription = "Playing",
                                        tint = Color(0xFF60A5FA),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 底部播放模式 Toggle 按钮 ────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                val modeText = when {
                    shuffleEnabled -> "Circle playback Mode"
                    repeatMode == RepeatMode.ONE -> "Circle playback Mode"
                    else -> "Sequential playback Mode"
                }

                Surface(
                    onClick = {
                        if (shuffleEnabled) {
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
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val modeIcon = when {
                            shuffleEnabled -> Icons.Rounded.Shuffle
                            repeatMode == RepeatMode.ONE -> Icons.Rounded.RepeatOne
                            else -> Icons.AutoMirrored.Rounded.ArrowForward
                        }
                        Icon(
                            modeIcon,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = modeText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 全屏歌词视图
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SaltFullLyricsView(
    modifier: Modifier = Modifier,
    lyricResult: LyricResult,
    currentIndex: Int,
    showTranslation: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggleTranslation: () -> Unit,
    onLyricClick: (Long) -> Unit
) {
    var isInitialScroll by remember { mutableStateOf(true) }
    LaunchedEffect(currentIndex, showTranslation) {
        if (currentIndex >= 0 && lyricResult.lines.isNotEmpty()) {
            if (isInitialScroll) {
                listState.scrollToItem(currentIndex)
                isInitialScroll = false
            } else {
                listState.animateScrollToItem(currentIndex)
            }
        }
    }

    Column(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val halfHeight = maxHeight / 2
            if (lyricResult.lines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无歌词", fontSize = 18.sp, color = Color.White.copy(alpha = 0.5f))
                }
            } else {
                LazyColumn(
                    state    = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                            alpha = 0.99f
                        }
                        .drawWithContent {
                            drawContent()
                            val fadePx = 120.dp.toPx()
                            drawRect(
                                brush     = Brush.verticalGradient(
                                    listOf(Color.Black, Color.Transparent),
                                    startY = 0f, endY = fadePx
                                ),
                                blendMode = BlendMode.DstOut
                            )
                            drawRect(
                                brush     = Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black),
                                    startY = size.height - fadePx, endY = size.height
                                ),
                                blendMode = BlendMode.DstOut
                            )
                        },
                    contentPadding      = PaddingValues(top = halfHeight, bottom = halfHeight),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    itemsIndexed(lyricResult.lines) { index, line ->
                        val isSelected = index == currentIndex
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLyricClick(line.timeMs) }
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    text       = line.text.trim(),
                                    fontSize   = if (isSelected) 22.sp else 18.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color      = Color.White.copy(alpha = if (isSelected) 1f else 0.35f),
                                    lineHeight = 28.sp,
                                    textAlign  = TextAlign.Center
                                )
                            }
                            if (showTranslation && !line.translation.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text       = line.translation.trim(),
                                        fontSize   = if (isSelected) 16.sp else 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                        color      = Color.White.copy(alpha = if (isSelected) 0.85f else 0.3f),
                                        lineHeight = 22.sp,
                                        textAlign  = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 译文开关
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp, end = 24.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            IconButton(onClick = onToggleTranslation) {
                Icon(
                    Icons.Rounded.Translate,
                    contentDescription = "Translate Toggle",
                    tint = if (showTranslation) Color.White else Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@SuppressLint("DefaultLocale")
private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun QuickFunctionsDialog(
    isUsbExclusive: Boolean,
    usbDeviceName: String?,
    onDismiss: () -> Unit,
    onShowStatusClick: () -> Unit,
    onShowUsbExclusiveClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF1E293B),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("快捷功能", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    IconButton(onClick = onDismiss, modifier = Modifier.offset(x = 8.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White.copy(alpha = 0.7f))
                    }
                }
                
                Spacer(Modifier.height(16.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Surface(
                        onClick = {
                            onDismiss()
                            onShowStatusClick()
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Equalizer, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("播放状态", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text("查看实际比特率、采样率、位深及存储信息", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                            }
                        }
                    }

                    Surface(
                        onClick = {
                            onDismiss()
                            onShowUsbExclusiveClick()
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Usb, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("独占 USB 通道", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    if (isUsbExclusive && !usbDeviceName.isNullOrBlank()) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFF10B981).copy(alpha = 0.2f)
                                        ) {
                                            Text("ON", fontSize = 10.sp, color = Color(0xFF34D399), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                        }
                                    }
                                }
                                val subText = when {
                                    isUsbExclusive && !usbDeviceName.isNullOrBlank() -> "已独占绑定: $usbDeviceName"
                                    !usbDeviceName.isNullOrBlank() -> "已检测到: $usbDeviceName (点击配置)"
                                    else -> "绕过系统混音器实现 Bit-Perfect 无损直通"
                                }
                                Text(subText, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    Surface(
                        onClick = {
                            onDismiss()
                            onAddToPlaylistClick()
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.PlaylistAdd, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("添加到歌单", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text("将当前歌曲添加至自定义本地或云端歌单", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsbExclusiveDialog(
    isExclusive: Boolean,
    usbDeviceName: String?,
    onToggleExclusive: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val hasDevice = !usbDeviceName.isNullOrBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = Color(0xFF60A5FA))
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Usb, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("独占 USB 音频通道", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.06f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用 USB 独占输出", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (hasDevice) "锁定至 USB DAC 避开 AudioFlinger 混音器"
                                else "未检测到 USB Audio 连入设备",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.55f)
                            )
                        }
                        Switch(
                            checked = isExclusive,
                            onCheckedChange = { onToggleExclusive(it) },
                            enabled = hasDevice || isExclusive
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (hasDevice) Color(0xFF10B981).copy(alpha = 0.12f) else Color(0xFFEF4444).copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, if (hasDevice) Color(0xFF10B981).copy(alpha = 0.3f) else Color(0xFFEF4444).copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (hasDevice) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = if (hasDevice) Color(0xFF34D399) else Color(0xFFF87171),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                if (hasDevice) "检测到 USB 音频设备" else "尚未接入 USB 声卡",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                if (hasDevice) usbDeviceName ?: "Unknown USB Audio" else "请插紧 USB 耳放/解码器或检查 Type-C 连接",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                    Text("💡 独占模式提示：", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f))
                    Spacer(Modifier.height(4.dp))
                    Text("1. 开启后音频数据将绕过 Android 系统 SRC 重采样，实现 Bit-Perfect 无损源码输出。", fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f))
                    Spacer(Modifier.height(2.dp))
                    Text("2. 当插入的 USB 耳放拔出时，播放引擎会自动触发暂停保护，防止误操作产生爆音。", fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f))
                }
            }
        },
        containerColor = Color(0xFF1E293B),
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = 0.9f)
    )
}

@SuppressLint("DefaultLocale")
@Composable
private fun PlaybackStatusDialog(
    media: PlayableMedia?,
    audioFormatInfo: Akari.NCM.player.core.AudioFormatInfo,
    isUsbExclusive: Boolean = false,
    usbDeviceName: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    if (media == null) return

    val isLocal = media.isLocal
    val sourceText = if (isLocal) "本地存储 (Local)" else "云端在线 (Cloud)"

    val fileSizeText = remember(media) {
        if (media is PlayableMedia.LocalNcm) {
            val bytes = runCatching {
                val uri = android.net.Uri.parse(media.uri)
                if (uri.scheme == "content") {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                } else {
                    java.io.File(uri.path ?: "").length()
                }
            }.getOrDefault(0L)
            if (bytes > 0) {
                val mb = bytes / (1024f * 1024f)
                if (mb >= 1.0f) String.format("%.2f MB (%d 字节)", mb, bytes)
                else String.format("%.1f KB (%d 字节)", bytes / 1024f, bytes)
            } else "未知"
        } else null
    }

    val sampleRateText = if (audioFormatInfo.sampleRateHz > 0) {
        val khz = audioFormatInfo.sampleRateHz / 1000f
        String.format("%.1f kHz", khz)
    } else "44.1 kHz"

    val bitrateText = if (audioFormatInfo.bitrateKbps > 0) {
        "${audioFormatInfo.bitrateKbps} kbps"
    } else "标准 / 动态比特率"

    val bitDepthText = "${audioFormatInfo.bitDepthBits}-bit"

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("确定", color = Color(0xFF60A5FA))
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Equalizer, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("播放状态与音频信息", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoRow("歌曲名称", media.title)
                InfoRow("艺术家", media.artist)
                InfoRow("专辑名称", media.album)
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                InfoRow("音频来源", sourceText)
                if (fileSizeText != null) {
                    InfoRow("本地文件大小", fileSizeText)
                }
                if (isUsbExclusive && !usbDeviceName.isNullOrBlank()) {
                    InfoRow("输出通道", "USB 独占直通 · $usbDeviceName")
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                InfoRow("实时比特率", bitrateText)
                InfoRow("采样率 / 位深", "$sampleRateText  ·  $bitDepthText")
                if (!audioFormatInfo.mimeType.isNullOrBlank()) {
                    InfoRow("音频编码格式", audioFormatInfo.mimeType)
                }
            }
        },
        containerColor = Color(0xFF1E293B),
        titleContentColor = Color.White,
        textContentColor = Color.White.copy(alpha = 0.9f)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
        Spacer(Modifier.height(2.dp))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

