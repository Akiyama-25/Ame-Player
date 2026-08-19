package Akari.NCM.player.ui.screen

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.HomepageBlock
import Akari.NCM.player.core.HomepageDragonBall
import Akari.NCM.player.core.HomepageMode
import Akari.NCM.player.core.NcmUserPlaylist
import Akari.NCM.player.data.UserSessionManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

object HomepageCache {
    var dragonBalls: List<HomepageDragonBall>? = null
    var blocks: List<HomepageBlock>? = null
    var firstSongCover: String? = null
    var recommendBgUrls: List<String>? = null
    var recommendPlaylistIds: List<Long>? = null
    var lastLoginState: Boolean = false

    fun clear() {
        dragonBalls = null
        blocks = null
        firstSongCover = null
        recommendBgUrls = null
        recommendPlaylistIds = null
        lastLoginState = false
    }
}

@Composable
fun UserHomepageView(
    ncmApi: NcmApi,
    sessionManager: UserSessionManager,
    refreshTrigger: Int = 0,
    onPlaylistClick: (Long) -> Unit,
    onDailyRecommendClick: () -> Unit
) {
    val mode by sessionManager.homepageModeState.collectAsState()
    
    when (mode) {
        HomepageMode.APP_LIKE -> DiscoverBlocksHomepage(ncmApi, sessionManager, refreshTrigger, onPlaylistClick, onDailyRecommendClick)
        HomepageMode.DAILY_ONLY -> DailyOnlyHomepage(ncmApi, sessionManager, refreshTrigger, onPlaylistClick)
        HomepageMode.HIGH_QUALITY_ONLY -> HighQualityOnlyHomepage(ncmApi, refreshTrigger, onPlaylistClick)
    }
}

@Composable
internal fun DiscoverBlocksHomepage(
    ncmApi: NcmApi,
    sessionManager: UserSessionManager,
    refreshTrigger: Int = 0,
    onPlaylistClick: (Long) -> Unit,
    onDailyRecommendClick: () -> Unit
) {
    val cookieState by sessionManager.cookieState.collectAsState()
    val isLoggedIn = remember(cookieState) { sessionManager.isLoggedIn() }
    
    if (HomepageCache.lastLoginState != isLoggedIn) {
        HomepageCache.clear()
    }
    HomepageCache.lastLoginState = isLoggedIn

    var dragonBalls by remember { mutableStateOf(HomepageCache.dragonBalls ?: emptyList<HomepageDragonBall>()) }
    var blocks by remember { mutableStateOf(HomepageCache.blocks ?: emptyList<HomepageBlock>()) }
    var isLoading by remember { mutableStateOf(HomepageCache.dragonBalls == null) }
    
    var recommendBgUrls by remember { mutableStateOf(HomepageCache.recommendBgUrls ?: emptyList<String>()) }
    var recommendPlaylistIds by remember { mutableStateOf(HomepageCache.recommendPlaylistIds ?: emptyList<Long>()) }
    var firstSongCover by remember { mutableStateOf(HomepageCache.firstSongCover) }

    var lastProcessedTrigger by remember { mutableIntStateOf(refreshTrigger) }
    if (refreshTrigger != lastProcessedTrigger) {
        lastProcessedTrigger = refreshTrigger
        HomepageCache.clear()
        dragonBalls = emptyList()
        blocks = emptyList()
        isLoading = true
    }

    LaunchedEffect(isLoggedIn, refreshTrigger) {
        if (HomepageCache.dragonBalls != null) return@LaunchedEffect
        isLoading = true

        var balls = ncmApi.getHomepageDragonBalls()
        if (balls.isEmpty()) {
            balls = listOf(
                HomepageDragonBall(id = -1L, name = "每日推荐"),
                HomepageDragonBall(id = -2L, name = "私人FM")
            )
        }
        // 过滤掉直播和电台
        balls = balls.filter { !it.name.contains("直播") && !it.name.contains("电台") }
        dragonBalls = balls
        HomepageCache.dragonBalls = balls

        if (isLoggedIn) {
            try {
                val songs = ncmApi.getDailyRecommendSongs()
                if (songs.isNotEmpty()) {
                    firstSongCover = songs.first().coverUrl
                    HomepageCache.firstSongCover = firstSongCover
                }
                val playlists = ncmApi.getDailyRecommendPlaylists()
                if (playlists.isNotEmpty()) {
                    recommendBgUrls = playlists.take(3).map { it.coverImgUrl }
                    HomepageCache.recommendBgUrls = recommendBgUrls
                    recommendPlaylistIds = playlists.map { it.id }
                    HomepageCache.recommendPlaylistIds = recommendPlaylistIds
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }

        blocks = ncmApi.getHomepageBlocks()
        HomepageCache.blocks = blocks
        isLoading = false
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isExpanded = maxWidth >= 840.dp
            val isMedium = maxWidth in 600.dp..839.dp

            val horizontalPadding = when {
                isExpanded -> 32.dp
                isMedium -> 24.dp
                else -> 16.dp
            }
            val itemSpacing = when {
                isExpanded -> 16.dp
                isMedium -> 14.dp
                else -> 12.dp
            }
            val dragonBallWidth = when {
                isExpanded -> 180.dp
                isMedium -> 150.dp
                else -> 120.dp
            }
            val dragonBallHeight = when {
                isExpanded -> 195.dp
                isMedium -> 175.dp
                else -> 160.dp
            }
            val dragonBallCornerRadius = when {
                isExpanded -> 16.dp
                isMedium -> 14.dp
                else -> 12.dp
            }
            val dragonBallTitleSize = when {
                isExpanded -> 16.sp
                isMedium -> 15.sp
                else -> 14.sp
            }
            val dragonBallSubtitleSize = when {
                isExpanded -> 12.sp
                isMedium -> 11.sp
                else -> 10.sp
            }
            val dragonBallPlayIconSize = when {
                isExpanded -> 32.dp
                isMedium -> 28.dp
                else -> 24.dp
            }
            val creativeCardWidth = when {
                isExpanded -> 160.dp
                isMedium -> 135.dp
                else -> 110.dp
            }
            val creativeCornerRadius = when {
                isExpanded -> 12.dp
                isMedium -> 10.dp
                else -> 8.dp
            }
            val creativeTitleSize = when {
                isExpanded -> 14.sp
                isMedium -> 13.sp
                else -> 12.sp
            }
            val creativeLineHeight = when {
                isExpanded -> 18.sp
                isMedium -> 17.sp
                else -> 16.sp
            }
            val sectionTitleStyle = when {
                isExpanded -> MaterialTheme.typography.titleLarge
                isMedium -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleMedium
            }
            val sectionVerticalPadding = when {
                isExpanded -> 16.dp
                isMedium -> 14.dp
                else -> 12.dp
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // Dragon Balls
                if (dragonBalls.isNotEmpty()) {
                    item {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = sectionVerticalPadding),
                            contentPadding = PaddingValues(horizontal = horizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(itemSpacing)
                        ) {
                            items(
                                items = dragonBalls,
                                key = { it.id }
                            ) { ball ->
                                val bgUrl = when {
                                    ball.name.contains("推荐") -> firstSongCover
                                    ball.name.contains("FM") || ball.name.contains("心动") -> recommendBgUrls.getOrNull(0)
                                    ball.name.contains("歌单") -> recommendBgUrls.getOrNull(1)
                                    ball.name.contains("排行") -> recommendBgUrls.getOrNull(2)
                                    else -> null
                                }
                                DragonBallCard(
                                      modifier = Modifier.animateItem(),
                                      ball = ball,
                                    bgUrl = bgUrl,
                                    cardWidth = dragonBallWidth,
                                    cardHeight = dragonBallHeight,
                                    cornerRadius = dragonBallCornerRadius,
                                    titleSize = dragonBallTitleSize,
                                    subtitleSize = dragonBallSubtitleSize,
                                    playIconSize = dragonBallPlayIconSize,
                                    onClick = {
                                        if (ball.name.contains("推荐")) {
                                            onDailyRecommendClick()
                                        } else if (ball.name.contains("FM") || ball.name.contains("心动")) {
                                            val realId = ball.url?.substringAfterLast("id=")?.toLongOrNull() ?: recommendPlaylistIds.getOrNull(0)
                                            if (realId != null) {
                                                onPlaylistClick(realId)
                                            } else if (ball.id > 0) {
                                                onPlaylistClick(ball.id)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Blocks (Playlists)
                items(
                    items = blocks,
                    key = { it.blockCode.ifBlank { it.title } }
                ) { block ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = sectionVerticalPadding)
                    ) {
                        Text(
                            text = block.title.ifBlank { "推荐歌单" },
                            style = sectionTitleStyle,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp)
                        )
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = horizontalPadding),
                            horizontalArrangement = Arrangement.spacedBy(itemSpacing)
                        ) {
                            items(
                                items = block.creatives,
                                key = { it.resourceId.ifBlank { it.title } }
                            ) { creative ->
                                BlockCreativeCard(
                                      modifier = Modifier.animateItem(),
                                      creative = creative,
                                    cardWidth = creativeCardWidth,
                                    cornerRadius = creativeCornerRadius,
                                    titleSize = creativeTitleSize,
                                    lineHeight = creativeLineHeight,
                                    onClick = {
                                        val id = creative.resourceId.toLongOrNull()
                                        if (id != null) onPlaylistClick(id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DailyOnlyHomepage(ncmApi: NcmApi, sessionManager: UserSessionManager, refreshTrigger: Int = 0, onPlaylistClick: (Long) -> Unit) {
    var playlists by remember { mutableStateOf(emptyList<NcmUserPlaylist>()) }
    var isLoading by remember { mutableStateOf(true) }
    val cookieState by sessionManager.cookieState.collectAsState()
    val isLoggedIn = remember(cookieState) { sessionManager.isLoggedIn() }

    if (!isLoggedIn) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("每日推荐需要登录网易云音乐账号才能查看", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        playlists = ncmApi.getDailyRecommendPlaylists()
        isLoading = false
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        PlaylistGrid(playlists, onPlaylistClick)
    }
}

@Composable
internal fun HighQualityOnlyHomepage(ncmApi: NcmApi, refreshTrigger: Int = 0, onPlaylistClick: (Long) -> Unit) {
    var playlists by remember { mutableStateOf(emptyList<NcmUserPlaylist>()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(refreshTrigger) {
        isLoading = true
        playlists = ncmApi.getHighQualityPlaylists()
        isLoading = false
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        PlaylistGrid(playlists, onPlaylistClick)
    }
}

@Composable
internal fun PlaylistGrid(
    playlists: List<NcmUserPlaylist>,
    onPlaylistClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = maxWidth
        val isExpanded = width >= 840.dp
        val isMedium = width in 600.dp..839.dp

        val horizontalPadding = when {
            isExpanded -> 28.dp
            isMedium -> 20.dp
            else -> 16.dp
        }
        val topPadding = when {
            isExpanded -> 20.dp
            isMedium -> 16.dp
            else -> 12.dp
        }
        val bottomPadding = when {
            isExpanded -> 36.dp
            else -> 28.dp
        }
        val horizontalSpacing = when {
            isExpanded -> 16.dp
            isMedium -> 14.dp
            else -> 12.dp
        }
        val verticalSpacing = when {
            isExpanded -> 20.dp
            isMedium -> 16.dp
            else -> 14.dp
        }
        val minItemSize = when {
            isExpanded -> 140.dp
            isMedium -> 130.dp
            else -> 110.dp
        }
        val titleFontSize = when {
            isExpanded -> 13.sp
            isMedium -> 12.5.sp
            else -> 12.sp
        }
        val titleLineHeight = when {
            isExpanded -> 18.sp
            isMedium -> 17.sp
            else -> 16.sp
        }
        val spacerHeight = when {
            isExpanded -> 8.dp
            else -> 6.dp
        }

        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无歌单内容",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = minItemSize),
                contentPadding = PaddingValues(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = topPadding,
                    bottom = bottomPadding
                ),
                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
                verticalArrangement = Arrangement.spacedBy(verticalSpacing),
                modifier = Modifier.fillMaxSize()
            ) {
                items(
                    items = playlists,
                    key = { it.id }
                ) { playlist ->
                    PlaylistGridItem(
                          modifier = Modifier.animateItem(),
                          playlist = playlist,
                        titleFontSize = titleFontSize,
                        titleLineHeight = titleLineHeight,
                        spacerHeight = spacerHeight,
                        onClick = { onPlaylistClick(playlist.id) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlaylistGridItem(
    playlist: NcmUserPlaylist,
    titleFontSize: TextUnit,
    titleLineHeight: TextUnit,
    spacerHeight: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (playlist.coverImgUrl.isNotBlank()) {
                AsyncImage(
                    model = playlist.coverImgUrl,
                    contentDescription = playlist.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        Spacer(modifier = Modifier.height(spacerHeight))
        Text(
            text = playlist.name,
            fontSize = titleFontSize,
            maxLines = 2,
            lineHeight = titleLineHeight,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
internal fun DragonBallCard(
    ball: HomepageDragonBall,
    modifier: Modifier = Modifier,
    bgUrl: String?,
    cardWidth: Dp = 120.dp,
    cardHeight: Dp = 160.dp,
    cornerRadius: Dp = 12.dp,
    titleSize: TextUnit = 14.sp,
    subtitleSize: TextUnit = 10.sp,
    playIconSize: Dp = 24.dp,
    onClick: () -> Unit
) {
    val title = when {
        ball.name.contains("推荐") -> "每日推荐"
        ball.name.contains("FM") || ball.name.contains("心动") -> "心动模式"
        else -> ball.name
    }
    val subtitle = when {
        ball.name.contains("推荐") -> "今日限定好歌推荐"
        ball.name.contains("FM") || ball.name.contains("心动") -> "红心歌曲和相似推荐"
        else -> ""
    }
    val gradientColors = remember(ball.id) {
        when {
            ball.name.contains("推荐") -> listOf(androidx.compose.ui.graphics.Color(0xFF8E9EAB), androidx.compose.ui.graphics.Color(0xFFEEF2F3))
            ball.name.contains("FM") || ball.name.contains("心动") -> listOf(androidx.compose.ui.graphics.Color(0xFFff9966), androidx.compose.ui.graphics.Color(0xFFff5e62))
            else -> listOf(androidx.compose.ui.graphics.Color(0xFF9D50BB), androidx.compose.ui.graphics.Color(0xFF6E48AA))
        }
    }
    
    Box(
        modifier = modifier
            .width(cardWidth)            .height(cardHeight)
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(gradientColors)
            )
            .clickable(onClick = onClick)
    ) {
        if (bgUrl != null) {
            AsyncImage(
                model = bgUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.5f
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            androidx.compose.ui.graphics.Color.Transparent,
                            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)
                        )
                    )
                )
        )
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding((cornerRadius.value * 0.75f).dp.coerceAtLeast(8.dp))
                .size(playIconSize)
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(cornerRadius)
        ) {
            Text(
                text = title,
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
                    fontSize = subtitleSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun BlockCreativeCard(
    creative: HomepageBlock.Creative,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 110.dp,
    cornerRadius: Dp = 8.dp,
    titleSize: TextUnit = 12.sp,
    lineHeight: TextUnit = 16.sp,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .width(cardWidth)            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(cardWidth)
                .clip(RoundedCornerShape(cornerRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = creative.imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = creative.title,
            fontSize = titleSize,
            maxLines = 2,
            lineHeight = lineHeight,
            overflow = TextOverflow.Ellipsis
        )
    }
}
