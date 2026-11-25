package Akari.NCM.player.ui.component

import Akari.NCM.player.R
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.PlayerController
import Akari.NCM.player.core.PlayerState
import Akari.NCM.player.core.RepeatMode
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.ui.theme.PlayerExpressivePalette
import Akari.NCM.player.ui.theme.blend
import Akari.NCM.player.ui.theme.buildPlayerExpressivePalette
import Akari.NCM.player.ui.theme.extractAverageColorFromBitmap
import Akari.NCM.player.ui.theme.extractBottomStripAverageColor
import Akari.NCM.player.ui.theme.extractSeedColorFromBitmap
import Akari.NCM.player.ui.theme.extractTopStripAverageColor
import Akari.NCM.player.ui.theme.rememberIsDarkTheme
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val DrawerEaseInOut = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

private data class ExtractedCoverColors(
    val seed: Color,
    val avg: Color,
    val topAvg: Color,
    val bottomAvg: Color
)

@Composable
fun rememberPlayerCoverPalette(
    rawTrack: PlayableMedia?
): Triple<PlayableMedia?, PlayerExpressivePalette, Boolean> {
    val context = LocalContext.current
    val isDarkTheme = rememberIsDarkTheme()
    val colorVariant by ThemePrefs.colorVariantFlow.collectAsState()
    val contrastLevel by ThemePrefs.contrastLevelFlow.collectAsState()
    val useMaterialBg by ThemePrefs.useMaterialBgFlow.collectAsState()
    val blurCoverBg by ThemePrefs.playerBlurCoverBgFlow.collectAsState()
    val fallbackAccentInt by ThemePrefs.accentColorFlow.collectAsState()
    val defaultSeedColor = remember(fallbackAccentInt) { Color(fallbackAccentInt.toInt()) }

    var displayedTrack by remember { mutableStateOf(rawTrack) }
    var extractedSeedColor by remember { mutableStateOf(defaultSeedColor) }
    var extractedAvgColor by remember { mutableStateOf<Color?>(null) }
    var extractedTopAvgColor by remember { mutableStateOf<Color?>(null) }
    var extractedBottomAvgColor by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(rawTrack, defaultSeedColor) {
        val target = rawTrack
        if (target == null) {
            displayedTrack = null
            extractedSeedColor = defaultSeedColor
            extractedAvgColor = null
            extractedTopAvgColor = null
            extractedBottomAvgColor = null
        } else {
            val url = target.coverUrl
            if (!url.isNullOrEmpty()) {
                val extracted = withTimeoutOrNull(650) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val request = ImageRequest.Builder(context)
                                .data(url)
                                .allowHardware(false)
                                .size(128, 128)
                                .build()
                            val result = context.imageLoader.execute(request)
                            val bmp = (result.drawable as? BitmapDrawable)?.bitmap
                            if (bmp != null) {
                                val seed = extractSeedColorFromBitmap(bmp, defaultSeedColor)
                                val avg = extractAverageColorFromBitmap(bmp, Color(0xFF16161D))
                                val topAvg = extractTopStripAverageColor(bmp, avg)
                                val bottomAvg = extractBottomStripAverageColor(bmp, avg)
                                ExtractedCoverColors(seed, avg, topAvg, bottomAvg)
                            } else null
                        }.getOrNull()
                    }
                }
                if (extracted != null) {
                    extractedSeedColor = extracted.seed
                    extractedAvgColor = extracted.avg
                    extractedTopAvgColor = extracted.topAvg
                    extractedBottomAvgColor = extracted.bottomAvg
                } else {
                    extractedSeedColor = defaultSeedColor
                    extractedAvgColor = null
                    extractedTopAvgColor = null
                    extractedBottomAvgColor = null
                }
            } else {
                extractedSeedColor = defaultSeedColor
                extractedAvgColor = null
                extractedTopAvgColor = null
                extractedBottomAvgColor = null
            }
            displayedTrack = target
        }
    }

    val animatedSeedColor by animateColorAsState(
        targetValue = extractedSeedColor,
        animationSpec = tween(480, easing = FastOutSlowInEasing),
        label = "PlayerCoverSeedColor"
    )
    val animatedAvgColor by animateColorAsState(
        targetValue = extractedAvgColor ?: Color(0xFF16161D),
        animationSpec = tween(480, easing = FastOutSlowInEasing),
        label = "PlayerCoverAvgColor"
    )
    val animatedTopAvgColor by animateColorAsState(
        targetValue = extractedTopAvgColor ?: extractedAvgColor ?: Color(0xFF16161D),
        animationSpec = tween(480, easing = FastOutSlowInEasing),
        label = "PlayerCoverTopAvgColor"
    )
    val animatedBottomAvgColor by animateColorAsState(
        targetValue = extractedBottomAvgColor ?: extractedAvgColor ?: Color(0xFF16161D),
        animationSpec = tween(480, easing = FastOutSlowInEasing),
        label = "PlayerCoverBottomAvgColor"
    )

    val palette = remember(
        animatedSeedColor,
        animatedAvgColor,
        animatedTopAvgColor,
        animatedBottomAvgColor,
        extractedAvgColor,
        extractedTopAvgColor,
        extractedBottomAvgColor,
        isDarkTheme,
        colorVariant,
        contrastLevel,
        blurCoverBg,
        useMaterialBg
    ) {
        buildPlayerExpressivePalette(
            coverSeedColor = animatedSeedColor,
            coverAverageColor = if (extractedAvgColor != null) animatedAvgColor else null,
            darkTheme = isDarkTheme,
            variant = colorVariant,
            contrastLevel = contrastLevel,
            blurCoverBackground = blurCoverBg,
            useMaterialBackground = useMaterialBg,
            coverTopAverageColor = if (extractedTopAvgColor != null) animatedTopAvgColor else null,
            coverBottomAverageColor = if (extractedBottomAvgColor != null) animatedBottomAvgColor else null
        )
    }

    return Triple(displayedTrack ?: rawTrack, palette, blurCoverBg)
}

@Composable
fun PlayerBackgroundLayer(
    coverUrl: String?,
    blurCoverBg: Boolean,
    palette: PlayerExpressivePalette
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.effectiveBackgroundColor)
    ) {
        if (blurCoverBg && coverUrl != null) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(72.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.82f
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0x4D06060A),
                                Color(0x7306060A),
                                Color(0x9E06060A)
                            )
                        )
                    )
            )
        }
    }
}

class PullDownDrawerState(
    val pullOffsetPx: Animatable<Float, AnimationVector1D>,
    val scope: CoroutineScope,
    val backState: PredictiveBackState,
    val effectiveProgress: Float,
    val effectiveHeightPx: Float,
    val isDismissedByPullDown: Boolean,
    val onUpdateContainerHeight: (Float) -> Unit,
    val onPullDelta: (Float) -> Unit,
    val onPullSettle: (Float) -> Unit
)

@Composable
fun rememberPullDownDrawerState(
    isOpen: Boolean,
    isLandscape: Boolean,
    backEnabled: Boolean = isOpen,
    label: String = "DrawerParallax",
    onDismiss: () -> Unit
): PullDownDrawerState {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val pullOffsetPx = remember { Animatable(0f) }
    var isDismissedByPullDown by remember { mutableStateOf(false) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    val fallbackHeightPx = with(density) { 640.dp.toPx() }
    val effectiveHeightPx = containerHeightPx.takeIf { it > 0f } ?: fallbackHeightPx

    val openProgress by animateFloatAsState(
        targetValue = if (isOpen && !isLandscape) 1f else 0f,
        animationSpec = tween(450, easing = DrawerEaseInOut),
        label = label
    )

    val backState = rememberPredictiveBackState(enabled = backEnabled) {
        onDismiss()
    }

    LaunchedEffect(isOpen) {
        if (isOpen) {
            isDismissedByPullDown = false
            pullOffsetPx.snapTo(0f)
        }
    }

    val pullDismissFraction = (pullOffsetPx.value / effectiveHeightPx).coerceIn(0f, 1f)
    val effectiveProgress = (
        openProgress *
            (1f - PredictiveBackEasing.transform(backState.progress.coerceIn(0f, 1f)) * 0.85f) *
            (1f - pullDismissFraction)
        ).coerceIn(0f, 1f)

    val handlePullDelta: (Float) -> Unit = { deltaY ->
        val nextOffset = (pullOffsetPx.value + deltaY).coerceAtLeast(0f)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pullOffsetPx.snapTo(nextOffset)
        }
    }

    val handlePullSettle: (Float) -> Unit = { velocityY ->
        scope.launch {
            val currentOffset = pullOffsetPx.value
            val distanceThresholdPx = with(density) { 108.dp.toPx() }
            val velocityThresholdPx = with(density) { 650.dp.toPx() }
            val minDragForFlingPx = with(density) { 20.dp.toPx() }
            val shouldDismiss = currentOffset >= distanceThresholdPx ||
                (velocityY >= velocityThresholdPx && currentOffset >= minDragForFlingPx)
            if (shouldDismiss) {
                pullOffsetPx.animateTo(
                    targetValue = effectiveHeightPx,
                    initialVelocity = velocityY.coerceAtLeast(0f),
                    animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing)
                )
                isDismissedByPullDown = true
                onDismiss()
            } else if (currentOffset > 0.5f) {
                pullOffsetPx.animateTo(
                    targetValue = 0f,
                    initialVelocity = velocityY,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            } else {
                pullOffsetPx.snapTo(0f)
            }
        }
    }

    return PullDownDrawerState(
        pullOffsetPx = pullOffsetPx,
        scope = scope,
        backState = backState,
        effectiveProgress = effectiveProgress,
        effectiveHeightPx = effectiveHeightPx,
        isDismissedByPullDown = isDismissedByPullDown,
        onUpdateContainerHeight = { containerHeightPx = it },
        onPullDelta = handlePullDelta,
        onPullSettle = handlePullSettle
    )
}

@Composable
fun rememberDrawerNestedScrollConnection(
    currentPullOffsetPx: () -> Float,
    onPullDelta: (Float) -> Unit,
    onPullSettle: (Float) -> Unit
): NestedScrollConnection {
    return remember(currentPullOffsetPx, onPullDelta, onPullSettle) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val offset = currentPullOffsetPx()
                if (offset > 0f && available.y != 0f) {
                    val consumedY = if (available.y < 0f) {
                        maxOf(available.y, -offset)
                    } else {
                        available.y
                    }
                    onPullDelta(consumedY)
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    onPullDelta(available.y)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (currentPullOffsetPx() > 0f) {
                    onPullSettle(available.y)
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (currentPullOffsetPx() > 0f) {
                    onPullSettle(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }
}

@Composable
fun PortraitPullDownDrawerOverlay(
    visible: Boolean,
    drawerState: PullDownDrawerState,
    sheetTestTag: String,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { fullHeight -> fullHeight },
            animationSpec = tween(450, easing = DrawerEaseInOut)
        ) + fadeIn(tween(300)),
        exit = if (drawerState.backState.isDismissedByGesture || drawerState.isDismissedByPullDown) {
            ExitTransition.None
        } else {
            slideOutVertically(
                targetOffsetY = { fullHeight -> fullHeight },
                animationSpec = tween(450, easing = DrawerEaseInOut)
            ) + fadeOut(tween(300))
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { drawerState.onUpdateContainerHeight(it.height.toFloat()) }
                .graphicsLayer {
                    val offset = drawerState.pullOffsetPx.value
                    translationY = offset
                    if (offset > 0.5f) {
                        val fraction = (offset / drawerState.effectiveHeightPx).coerceIn(0f, 1f)
                        val cornerFraction = (fraction * 3.5f).coerceIn(0f, 1f)
                        shape = RoundedCornerShape(
                            topStart = (28f * cornerFraction).dp,
                            topEnd = (28f * cornerFraction).dp
                        )
                        clip = true
                    } else {
                        clip = false
                    }
                }
                .predictiveBackForeground(state = drawerState.backState, pullDownDp = 64f)
                .testTag(sheetTestTag)
        ) {
            content()
        }
    }
}

@Composable
fun DrawerPullHandle(
    hintText: String,
    contentDesc: String,
    handleTestTag: String,
    palette: PlayerExpressivePalette,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(handleTestTag)
            .semantics { contentDescription = contentDesc }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(palette.secondaryTextColor.copy(alpha = 0.5f))
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = hintText,
                fontSize = 12.sp,
                color = palette.secondaryTextColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SaltPlayQueueView(
    playerState: PlayerState,
    playerController: PlayerController,
    displayedTrack: PlayableMedia?,
    palette: PlayerExpressivePalette,
    blurCoverBg: Boolean,
    pullHintText: String = "下滑此处返回播放界面",
    pullHandleTestTag: String = "play_queue_pull_handle",
    currentPullOffsetPx: () -> Float = { 0f },
    onPullDelta: (Float) -> Unit = {},
    onPullSettle: (Float) -> Unit = {},
    onDismiss: () -> Unit
) {
    val currentTrack = displayedTrack
    val isPlaying by playerState.isPlaying.collectAsState()
    val playlist by playerState.playlist.collectAsState()
    val currentIndex by playerState.currentIndex.collectAsState()
    val repeatMode by playerState.repeatMode.collectAsState()
    val shuffleEnabled by playerState.shuffleEnabled.collectAsState()
    val infinitePlayMode by playerState.infinitePlayMode.collectAsState()
    val isFetchingRecommendations by playerState.isFetchingRecommendations.collectAsState()

    val listState = rememberLazyListState()
    LaunchedEffect(playlist.size) {
        if (currentIndex in playlist.indices && currentIndex > 3) {
            listState.scrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }

    val coverUrl = currentTrack?.coverUrl
    val headerDraggableState = rememberDraggableState { deltaY ->
        onPullDelta(deltaY)
    }
    val queueNestedScrollConnection = rememberDrawerNestedScrollConnection(
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
            // ── 顶部可下拉收起区域（提示条 + 迷你卡片 + 队列 Header）───────────
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
                    hintText = pullHintText,
                    contentDesc = pullHintText,
                    handleTestTag = pullHandleTestTag,
                    palette = palette,
                    onDismiss = onDismiss
                )

                Spacer(Modifier.height(8.dp))

                // ── 顶部当前播放 Mini 卡片 ─────────────────────────────────────────
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
                                    painter = painterResource(id = R.drawable.ic_ame_status),
                                    contentDescription = null,
                                    tint = palette.onSecondaryButton,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentTrack?.title ?: "未在播放",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.primaryTextColor,
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
                                color = palette.secondaryTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { playerController.togglePlayPause() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = "播放/暂停",
                                    tint = palette.playedLyricColor,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            IconButton(
                                onClick = { playerController.skipToNext() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.SkipNext,
                                    contentDescription = "下一首",
                                    tint = palette.primaryTextColor,
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
                        color = palette.secondaryTextColor
                    )

                    Spacer(Modifier.weight(1f))

                    Text(
                        text = if (infinitePlayMode.isActive) "∞ ${infinitePlayMode.label}" else "播放队列",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.primaryTextColor,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp)
                    )

                    if (isFetchingRecommendations) {
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = palette.playedLyricColor
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    if (infinitePlayMode.isActive) {
                        TextButtonPill(
                            text = "退出漫游",
                            color = palette.playedLyricColor,
                            onClick = { playerController.disableInfinitePlayback() }
                        )
                    } else {
                        TextButtonPill(
                            text = "队列",
                            color = palette.secondaryTextColor,
                            onClick = {}
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            // ── 队列曲目列表 ───────────────────────────────────────────────
            if (playlist.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = headerDraggableState,
                            onDragStopped = { velocity -> onPullSettle(velocity) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isFetchingRecommendations) "正在加载推荐曲目..." else "播放队列为空",
                        fontSize = 14.sp,
                        color = palette.secondaryTextColor
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(queueNestedScrollConnection),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    itemsIndexed(
                        items = playlist,
                        key = { index, media -> "${index}_${media.id}" }
                    ) { index, media ->
                        val isSelected = index == currentIndex
                        SaltQueueTrackRow(
                            media = media,
                            isSelected = isSelected,
                            palette = palette,
                            onClick = { playerController.skipToIndex(index) }
                        )
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
                val isInfinite = infinitePlayMode.isActive
                val modeText = when {
                    isInfinite -> "无限模式"
                    shuffleEnabled -> "随机播放"
                    repeatMode == RepeatMode.ONE -> "单曲循环"
                    else -> "顺序播放"
                }

                Surface(
                    onClick = {
                        if (!isInfinite) {
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
                        }
                    },
                    shape = RoundedCornerShape(22.dp),
                    color = palette.secondaryButtonContainer,
                    border = BorderStroke(1.dp, palette.cardOutlineColor.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isInfinite) {
                            Text(
                                text = "∞",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.onSecondaryButton,
                                modifier = Modifier.semantics {
                                    contentDescription = "无限模式"
                                }
                            )
                        } else {
                            val modeIcon = when {
                                shuffleEnabled -> Icons.Rounded.Shuffle
                                repeatMode == RepeatMode.ONE -> Icons.Rounded.RepeatOne
                                else -> Icons.AutoMirrored.Rounded.ArrowForward
                            }
                            Icon(
                                modeIcon,
                                contentDescription = modeText,
                                tint = palette.onSecondaryButton,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = modeText,
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
private fun TextButtonPill(
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
fun SaltQueueTrackRow(
    media: PlayableMedia,
    isSelected: Boolean,
    palette: PlayerExpressivePalette,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) {
            blend(palette.cardSurfaceColor, palette.primaryButtonContainer, 0.28f).copy(alpha = 0.92f)
        } else {
            palette.cardSurfaceColor.copy(alpha = 0.65f)
        },
        border = if (isSelected) {
            BorderStroke(1.dp, palette.playedLyricColor.copy(alpha = 0.55f))
        } else {
            BorderStroke(1.dp, palette.cardOutlineColor.copy(alpha = 0.22f))
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                    color = if (isSelected) palette.playedLyricColor else palette.primaryTextColor,
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
                    color = palette.secondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isSelected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Rounded.Equalizer,
                    contentDescription = "正在播放",
                    tint = palette.playedLyricColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaltLandscapePlayQueueSheet(
    playerState: PlayerState,
    playerController: PlayerController,
    palette: PlayerExpressivePalette,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val playlist by playerState.playlist.collectAsState()
    val currentIndex by playerState.currentIndex.collectAsState()
    val infinitePlayMode by playerState.infinitePlayMode.collectAsState()
    val isFetchingRecommendations by playerState.isFetchingRecommendations.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.cardSurfaceColor,
        contentColor = palette.primaryTextColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = configuration.screenHeightDp.dp * 0.85f)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "播放队列 (${if (playlist.isNotEmpty()) currentIndex + 1 else 0} / ${playlist.size})",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.primaryTextColor
                )
                if (infinitePlayMode.isActive) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = palette.secondaryButtonContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "∞ ${infinitePlayMode.label}",
                                fontSize = 12.sp,
                                color = palette.onSecondaryButton,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isFetchingRecommendations) {
                                Spacer(Modifier.width(6.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = palette.onSecondaryButton
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "退出无限推荐",
                                tint = palette.onSecondaryButton,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { playerController.disableInfinitePlayback() }
                            )
                        }
                    }
                }
            }
            if (playlist.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isFetchingRecommendations) "正在加载推荐曲目..." else "播放队列为空",
                        color = palette.secondaryTextColor
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(
                        items = playlist,
                        key = { index, media -> "${index}_${media.id}" }
                    ) { index, media ->
                        SaltQueueTrackRow(
                            media = media,
                            isSelected = index == currentIndex,
                            palette = palette,
                            onClick = { playerController.skipToIndex(index) }
                        )
                    }
                }
            }
        }
    }
}
