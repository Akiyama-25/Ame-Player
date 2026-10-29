package Akari.NCM.player.ui.screen

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.NcmUserPlaylist
import Akari.NCM.player.core.OnlinePlaylistTarget
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.data.CustomPlaylist
import Akari.NCM.player.data.LyricsManager
import Akari.NCM.player.data.LocalFolderManager
import Akari.NCM.player.data.NcmFileScanner
import Akari.NCM.player.data.PlaylistManager
import Akari.NCM.player.data.SearchHistoryManager
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.player.AmePlayerEngine
import Akari.NCM.player.ui.component.AddToPlaylistDialog
import Akari.NCM.player.ui.component.PredictiveBackEasing
import Akari.NCM.player.ui.component.SongActionBottomSheet
import Akari.NCM.player.ui.component.predictiveBackBackground
import Akari.NCM.player.ui.component.predictiveBackForeground
import Akari.NCM.player.ui.component.rememberPredictiveBackState
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.animation.*
import androidx.compose.animation.core.tween

enum class TopLevelScreen {
    PLAYER, SETTINGS, SEARCH, MAIN
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    playerEngine: AmePlayerEngine,
    openPlayerRequests: kotlinx.coroutines.flow.StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0)
) {
    val currentMedia by playerEngine.currentMedia.collectAsState()
    val isPlaying by playerEngine.isPlaying.collectAsState()
    val playlist by playerEngine.playlist.collectAsState()
    val currentIndex by playerEngine.currentIndex.collectAsState()
    val positionMs by playerEngine.currentPositionMs.collectAsState()
    val durationMs by playerEngine.durationMs.collectAsState()
    val infinitePlayMode by playerEngine.infinitePlayMode.collectAsState()
    val isFetchingRecommendations by playerEngine.isFetchingRecommendations.collectAsState()
    val openPlayerCount by openPlayerRequests.collectAsState()

    var showPlayerScreen by remember { mutableStateOf(false) }
    var showQueueBottomSheet by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var showSearchScreen by remember { mutableStateOf(false) }
    var showSyncCloudDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: User Homepage, 1: Playlists
    var selectedPlaylistForDetail by remember { mutableStateOf<CustomPlaylist?>(null) }
    var selectedOnlinePlaylist by remember { mutableStateOf<OnlinePlaylistTarget?>(null) }
    var songToAddToPlaylist by remember { mutableStateOf<PlayableMedia?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var homepageRefreshTrigger by remember { mutableIntStateOf(0) }
    
    // 歌曲操作弹窗状态
    var actionMedia by remember { mutableStateOf<PlayableMedia?>(null) }
    var actionPlaylistContext by remember { mutableStateOf<CustomPlaylist?>(null) }
    var actionRemoveCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(openPlayerCount) {
        if (openPlayerCount > 0 && currentMedia != null) {
            showSettingsScreen = false
            showSearchScreen = false
            showPlayerScreen = true
        }
    }

    // AOSP 预见性返回手势状态：按优先级响应设置页、搜索页、播放页、在线歌单详情页与本地歌单详情页
    val settingsBackState = rememberPredictiveBackState(enabled = showSettingsScreen) {
        showSettingsScreen = false
    }

    val searchBackState = rememberPredictiveBackState(enabled = showSearchScreen && !showSettingsScreen) {
        showSearchScreen = false
    }

    val playerBackState = rememberPredictiveBackState(
        enabled = showPlayerScreen && !showSettingsScreen && !showSearchScreen
    ) {
        showPlayerScreen = false
    }

    val onlinePlaylistBackState = rememberPredictiveBackState(
        enabled = selectedOnlinePlaylist != null && selectedTab == 0 && !showPlayerScreen && !showSettingsScreen && !showSearchScreen
    ) {
        selectedOnlinePlaylist = null
    }

    val playlistDetailBackState = rememberPredictiveBackState(
        enabled = selectedPlaylistForDetail != null && selectedTab == 1 && !showPlayerScreen && !showSettingsScreen && !showSearchScreen
    ) {
        selectedPlaylistForDetail = null
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            playerEngine.syncIslandState()
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying && ThemePrefs.liveIslandEnabled &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                runCatching {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    val sessionManager = remember { UserSessionManager(context) }
    val playlistManager = remember { PlaylistManager(context) }
    val ncmApi = remember { NcmApi(sessionManager) }
    val searchHistoryManager = remember { SearchHistoryManager(context) }
    val localFolderManager = remember { LocalFolderManager(context, sessionManager, playlistManager, ncmApi) }

    LaunchedEffect(playlistManager) {
        playerEngine.localTrackMatcher = { online ->
            playlistManager.findLocalMatch(online) ?: online
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            localFolderManager.scanAllFolders()
        }
    }
    val customPlaylists by playlistManager.playlists.collectAsState()
    val showMatchStatusPreference by ThemePrefs.showMatchStatusFlow.collectAsState()
    var matchFilterMode by remember { mutableStateOf(0) } // 0: 全部, 1: 仅已匹配, 2: 仅未匹配
    LaunchedEffect(showMatchStatusPreference) {
        if (!showMatchStatusPreference) {
            matchFilterMode = 0
        }
    }
    val apiBaseUrl by sessionManager.apiBaseUrlState.collectAsState()
    val hasApi = apiBaseUrl.isNotBlank()
    val isLoggedIn = sessionManager.isLoggedIn()
    
    LaunchedEffect(hasApi) {
        if (!hasApi && selectedTab == 0) {
            selectedTab = 1
        }
    }

    // 重新同步单一云端歌单逻辑 (或重新扫描本地文件夹)
    val reSyncPlaylist: (CustomPlaylist) -> Unit = { target ->
        val ncmId = target.ncmPlaylistId
        if (ncmId != null) {
            scope.launch(Dispatchers.IO) {
                if (ncmId == -9999L) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "正在重新扫描《${target.name}》...", Toast.LENGTH_SHORT).show()
                    }
                    localFolderManager.scanAllFolders()
                    withContext(Dispatchers.Main) {
                        val updated = playlistManager.playlists.value.find { it.id == target.id }
                        if (selectedPlaylistForDetail?.id == target.id) {
                            selectedPlaylistForDetail = updated
                        }
                        val tracksSize = updated?.songs?.size ?: 0
                        Toast.makeText(context, "本地文件夹扫描完成 (${tracksSize} 首)", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "正在重新同步《${target.name}》...", Toast.LENGTH_SHORT).show()
                    }
                    val tracks = ncmApi.getPlaylistTracks(ncmId, target.songs.size)
                    val updated = playlistManager.saveOrUpdateSyncedPlaylist(
                        ncmPlaylistId = ncmId,
                        name = target.name,
                        coverImgUrl = target.coverImgUrl,
                        songs = tracks
                    )
                    withContext(Dispatchers.Main) {
                        if (selectedPlaylistForDetail?.id == target.id) {
                            selectedPlaylistForDetail = updated
                        }
                        Toast.makeText(context, "歌单《${target.name}》更新成功 (${tracks.size} 首)", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val topLevelScreen = when {
        showPlayerScreen -> TopLevelScreen.PLAYER
        showSettingsScreen -> TopLevelScreen.SETTINGS
        showSearchScreen -> TopLevelScreen.SEARCH
        else -> TopLevelScreen.MAIN
    }

    val isTopBackActive = (showPlayerScreen && playerBackState.isVisibleInBackground) ||
        (showSettingsScreen && settingsBackState.isVisibleInBackground) ||
        (showSearchScreen && searchBackState.isVisibleInBackground)
    val activeTopBackProgress = when {
        showSettingsScreen -> settingsBackState.progress
        showSearchScreen -> searchBackState.progress
        else -> playerBackState.progress
    }
    val activeTopBackEdge = when {
        showSettingsScreen -> settingsBackState.swipeEdge
        showSearchScreen -> searchBackState.swipeEdge
        else -> playerBackState.swipeEdge
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (topLevelScreen == TopLevelScreen.MAIN || isTopBackActive) {
            Box(
                modifier = if (topLevelScreen != TopLevelScreen.MAIN && isTopBackActive) {
                    Modifier
                        .fillMaxSize()
                        .predictiveBackBackground(
                            progress = activeTopBackProgress,
                            swipeEdge = activeTopBackEdge
                        )
                } else {
                    Modifier.fillMaxSize()
                }
            ) {
                val adaptiveInfo = androidx.compose.material3.adaptive.currentWindowAdaptiveInfo()
                val customLayoutType = with(adaptiveInfo.windowSizeClass) {
                    when {
                        windowHeightSizeClass == androidx.window.core.layout.WindowHeightSizeClass.COMPACT ->
                            androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType.NavigationRail
                        windowWidthSizeClass == androidx.window.core.layout.WindowWidthSizeClass.EXPANDED ->
                            androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType.NavigationRail
                        windowWidthSizeClass == androidx.window.core.layout.WindowWidthSizeClass.MEDIUM ->
                            androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType.NavigationRail
                        else -> androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType.NavigationBar
                    }
                }

                // Material 3 Adaptive Navigation Suite Scaffold
                NavigationSuiteScaffold(
                    layoutType = customLayoutType,
                    navigationSuiteItems = {
                        if (hasApi) {
                            item(
                                selected = selectedTab == 0,
                                onClick = {
                                    if (selectedTab == 0 && selectedOnlinePlaylist != null) {
                                        selectedOnlinePlaylist = null
                                    }
                                    selectedTab = 0
                                    selectedPlaylistForDetail = null
                                },
                                icon = {
                                    Icon(Icons.Rounded.Home, contentDescription = "用户首页")
                                },
                                label = {
                                    Text("首页")
                                }
                            )
                        }
                        item(
                            selected = selectedTab == 1,
                            onClick = {
                                selectedTab = 1
                            },
                            icon = {
                                Icon(Icons.Rounded.LibraryMusic, contentDescription = "我的歌单")
                            },
                            label = {
                                Text(if (customPlaylists.isNotEmpty()) "歌单 (${customPlaylists.size})" else "歌单")
                            }
                        )
                    }
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            painter = androidx.compose.ui.res.painterResource(id = Akari.NCM.player.R.drawable.ic_ame_status),
                                            contentDescription = "Ame Player",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Text(
                                            text = "Ame Player",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                },
                                actions = {
                                    if (selectedTab == 0 && selectedOnlinePlaylist == null) {
                                        IconButton(onClick = { homepageRefreshTrigger++ }) {
                                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新首页")
                                        }
                                    }
                                    if (selectedTab == 1 && selectedPlaylistForDetail == null) {
                                        if (hasApi && isLoggedIn) {
                                            IconButton(onClick = { showSyncCloudDialog = true }) {
                                                Icon(Icons.Rounded.CloudSync, contentDescription = "同步云端")
                                            }
                                        }
                                        IconButton(onClick = { showCreateDialog = true }) {
                                            Icon(Icons.Rounded.Add, contentDescription = "新建本地")
                                        }
                                    }
                                    IconButton(onClick = {
                                        showSearchScreen = true
                                    }) {
                                        Icon(Icons.Rounded.Search, contentDescription = "搜索")
                                    }
                                    IconButton(onClick = {
                                        showSettingsScreen = true
                                    }) {
                                        Icon(Icons.Rounded.Settings, contentDescription = "设置")
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                        },
                        bottomBar = {
                            // Mini Player Bar
                            AnimatedVisibility(
                                visible = currentMedia != null && !showPlayerScreen,
                                enter = slideInVertically(initialOffsetY = { it }),
                                exit = slideOutVertically(targetOffsetY = { it })
                            ) {
                                MiniPlayerBar(
                                    media = currentMedia,
                                    isPlaying = isPlaying,
                                    positionMs = positionMs,
                                    durationMs = durationMs,
                                    onTogglePlayPause = { playerEngine.togglePlayPause() },
                                    onClick = { showPlayerScreen = true },
                                    onSkipNext = { playerEngine.skipToNext() },
                                    onSkipPrevious = { playerEngine.skipToPrevious() },
                                    onQueueClick = { showQueueBottomSheet = true }
                                )
                            }
                        }
                    ) { padding ->
                        Box(
                            modifier = Modifier
                                .padding(padding)
                                .fillMaxSize()
                        ) {
                            AnimatedContent(
                                targetState = selectedTab,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                                },
                                label = "TabTransition",
                                modifier = Modifier.fillMaxSize()
                            ) { tab ->
                                when (tab) {
                                    0 -> {
                                        // User Homepage Tab (支持在线歌单/每日推荐/专辑详情页预见性返回透出底层主页)
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            if (selectedOnlinePlaylist != null && onlinePlaylistBackState.isVisibleInBackground) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .predictiveBackBackground(
                                                            progress = onlinePlaylistBackState.progress,
                                                            swipeEdge = onlinePlaylistBackState.swipeEdge
                                                        )
                                                ) {
                                                    UserHomepageView(
                                                        ncmApi = ncmApi,
                                                        sessionManager = sessionManager,
                                                        refreshTrigger = homepageRefreshTrigger,
                                                        onPlaylistClick = { target: OnlinePlaylistTarget ->
                                                            selectedOnlinePlaylist = target
                                                        },
                                                        onDailyRecommendClick = {
                                                            selectedOnlinePlaylist = OnlinePlaylistTarget(
                                                                id = -1L,
                                                                title = "每日推荐",
                                                                coverImgUrl = HomepageCache.firstSongCover ?: "",
                                                                description = "根据你的音乐口味生成 · 每天 6:00 更新",
                                                                isDailyRecommend = true
                                                            )
                                                        },
                                                        onInfinitePlayClick = { mode, initialTracks, toastMsg ->
                                                            playerEngine.startInfinitePlayback(mode, initialTracks)
                                                            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                                        }
                                                    )
                                                    val onlineScrimAlpha = 0.35f * (1f - PredictiveBackEasing.transform(onlinePlaylistBackState.progress.coerceIn(0f, 1f)))
                                                    if (onlineScrimAlpha > 0.005f) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxSize()
                                                                .background(Color.Black.copy(alpha = onlineScrimAlpha))
                                                        )
                                                    }
                                                }
                                            }

                                            AnimatedContent(
                                                targetState = selectedOnlinePlaylist,
                                                transitionSpec = {
                                                    if (targetState != null && initialState == null) {
                                                        slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                                                    } else if (targetState == null && initialState != null) {
                                                        fadeIn(animationSpec = tween(300)) togetherWith slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
                                                    } else {
                                                        fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                                                    }
                                                },
                                                label = "OnlinePlaylistDetailTransition",
                                                modifier = Modifier.fillMaxSize()
                                            ) { onlineTarget ->
                                                if (onlineTarget != null) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .predictiveBackForeground(state = onlinePlaylistBackState, pullDownDp = 12f)
                                                            .background(MaterialTheme.colorScheme.surface)
                                                    ) {
                                                        OnlinePlaylistDetailView(
                                                            target = onlineTarget,
                                                            ncmApi = ncmApi,
                                                            playlistManager = playlistManager,
                                                            playerEngine = playerEngine,
                                                            currentMedia = currentMedia,
                                                            isPlaying = isPlaying,
                                                            showMatchStatusPreference = showMatchStatusPreference,
                                                            onBack = { selectedOnlinePlaylist = null },
                                                            onSongAction = { media, pContext ->
                                                                actionMedia = media
                                                                actionPlaylistContext = pContext
                                                                actionRemoveCallback = null
                                                            }
                                                        )
                                                    }
                                                } else {
                                                    UserHomepageView(
                                                        ncmApi = ncmApi,
                                                        sessionManager = sessionManager,
                                                        refreshTrigger = homepageRefreshTrigger,
                                                        onPlaylistClick = { target: OnlinePlaylistTarget ->
                                                            selectedOnlinePlaylist = target
                                                        },
                                                        onDailyRecommendClick = {
                                                            selectedOnlinePlaylist = OnlinePlaylistTarget(
                                                                id = -1L,
                                                                title = "每日推荐",
                                                                coverImgUrl = HomepageCache.firstSongCover ?: "",
                                                                description = "根据你的音乐口味生成 · 每天 6:00 更新",
                                                                isDailyRecommend = true
                                                            )
                                                        },
                                                        onInfinitePlayClick = { mode, initialTracks, toastMsg ->
                                                            playerEngine.startInfinitePlayback(mode, initialTracks)
                                                            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    1 -> {
                                        // Custom Playlists Tab (支持歌单详情页预见性返回透出底层歌单列表)
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            if (selectedPlaylistForDetail != null && playlistDetailBackState.isVisibleInBackground) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .predictiveBackBackground(
                                                            progress = playlistDetailBackState.progress,
                                                            swipeEdge = playlistDetailBackState.swipeEdge
                                                        )
                                                ) {
                                                    PlaylistsView(
                                                        playlists = customPlaylists,
                                                        playlistManager = playlistManager,
                                                        isLoggedIn = isLoggedIn,
                                                        hasApi = hasApi,
                                                        onSelectPlaylist = { selectedPlaylistForDetail = it },
                                                        onReSyncPlaylist = reSyncPlaylist
                                                    )
                                                    val detailScrimAlpha = 0.35f * (1f - PredictiveBackEasing.transform(playlistDetailBackState.progress.coerceIn(0f, 1f)))
                                                    if (detailScrimAlpha > 0.005f) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxSize()
                                                                .background(Color.Black.copy(alpha = detailScrimAlpha))
                                                        )
                                                    }
                                                }
                                            }

                                            AnimatedContent(
                                                targetState = selectedPlaylistForDetail,
                                                transitionSpec = {
                                                    if (targetState != null && initialState == null) {
                                                        slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                                                    } else if (targetState == null && initialState != null) {
                                                        fadeIn(animationSpec = tween(300)) togetherWith slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
                                                    } else {
                                                        fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                                                    }
                                                },
                                                label = "PlaylistDetailTransition",
                                                modifier = Modifier.fillMaxSize()
                                            ) { currentDetail ->
                                                if (currentDetail != null) {
                                                    // Playlist Detail View
                                                    val currentPlaylist = currentDetail
                                                    var inPlaylistSearchQuery by remember(currentPlaylist.id) { mutableStateOf("") }
                                                    var isPlaylistSearchActive by remember(currentPlaylist.id) { mutableStateOf(false) }
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .predictiveBackForeground(state = playlistDetailBackState, pullDownDp = 12f)
                                                            .background(MaterialTheme.colorScheme.surface)
                                                    ) {
                                                        Column(
                                                            modifier = Modifier
                                                                .fillMaxSize()
                                                                .wrapContentWidth(androidx.compose.ui.Alignment.CenterHorizontally)
                                                                .widthIn(max = 840.dp)
                                                        ) {
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(16.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                IconButton(onClick = { selectedPlaylistForDetail = null }) {
                                                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                                                                }
                                                                Column(modifier = Modifier.weight(1f)) {
                                                                    Text(
                                                                        currentPlaylist.name,
                                                                        style = MaterialTheme.typography.titleLarge,
                                                                        fontWeight = FontWeight.Bold,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                    Spacer(modifier = Modifier.height(2.dp))
                                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                                        Text(
                                                                            "${currentPlaylist.songs.size} songs",
                                                                            style = MaterialTheme.typography.bodyMedium,
                                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                        )
                                                                        if (currentPlaylist.ncmPlaylistId != null && currentPlaylist.ncmPlaylistId != -9999L) {
                                                                            Spacer(modifier = Modifier.width(8.dp))
                                                                            SuggestionChip(
                                                                                onClick = {},
                                                                                label = { Text("云端", fontSize = 10.sp) },
                                                                                modifier = Modifier.height(22.dp)
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                                    if (currentPlaylist.ncmPlaylistId != null && hasApi && isLoggedIn) {
                                                                        IconButton(onClick = { reSyncPlaylist(currentPlaylist) }) {
                                                                            Icon(
                                                                                Icons.Rounded.Sync,
                                                                                contentDescription = "重新同步",
                                                                                tint = MaterialTheme.colorScheme.primary
                                                                            )
                                                                        }
                                                                        Spacer(modifier = Modifier.width(4.dp))
                                                                    }
                                                                    if (currentPlaylist.songs.isNotEmpty()) {
                                                                        IconButton(onClick = {
                                                                            isPlaylistSearchActive = !isPlaylistSearchActive
                                                                            if (!isPlaylistSearchActive) inPlaylistSearchQuery = ""
                                                                        }) {
                                                                            Icon(
                                                                                if (isPlaylistSearchActive) Icons.Rounded.SearchOff else Icons.Rounded.Search,
                                                                                contentDescription = "歌单内搜索",
                                                                                tint = if (isPlaylistSearchActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                                            )
                                                                        }
                                                                        Spacer(modifier = Modifier.width(4.dp))
                                                                    }
                                                                    if (currentPlaylist.songs.isNotEmpty()) {
                                                                        FilledTonalButton(
                                                                            onClick = {
                                                                                val filteredSongs = if (inPlaylistSearchQuery.isBlank()) {
                                                                                    currentPlaylist.songs
                                                                                } else {
                                                                                    val q = inPlaylistSearchQuery.trim().lowercase()
                                                                                    currentPlaylist.songs.filter {
                                                                                        it.title.lowercase().contains(q) ||
                                                                                        it.artist.lowercase().contains(q) ||
                                                                                        it.album.lowercase().contains(q)
                                                                                    }
                                                                                }
                                                                                val playableList = filteredSongs.map { dto ->
                                                                                    val m = dto.toPlayableMedia()
                                                                                    if (m is PlayableMedia.Online) {
                                                                                        playlistManager.findLocalMatch(m) ?: m
                                                                                    } else m
                                                                                }
                                                                                playerEngine.loadPlaylist(playableList)
                                                                                playerEngine.play()
                                                                            }
                                                                        ) {
                                                                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                                                            Spacer(modifier = Modifier.width(4.dp))
                                                                            Text(if (inPlaylistSearchQuery.isNotBlank()) "播放筛选" else "播放全部")
                                                                        }
                                                                    }
                                                                }
                                                            }

                                                            // Search bar inside playlist (Animated)
                                                            AnimatedVisibility(
                                                                visible = isPlaylistSearchActive,
                                                                enter = expandVertically() + fadeIn(),
                                                                exit = shrinkVertically() + fadeOut()
                                                            ) {
                                                                OutlinedTextField(
                                                                    value = inPlaylistSearchQuery,
                                                                    onValueChange = { inPlaylistSearchQuery = it },
                                                                    placeholder = {
                                                                        Text(
                                                                            "在《${currentPlaylist.name}》中搜索歌曲/歌手/专辑...",
                                                                            style = MaterialTheme.typography.bodyMedium,
                                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                        )
                                                                    },
                                                                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                                                    trailingIcon = {
                                                                        if (inPlaylistSearchQuery.isNotEmpty()) {
                                                                            IconButton(onClick = { inPlaylistSearchQuery = "" }) {
                                                                                Icon(Icons.Rounded.Close, contentDescription = "清空")
                                                                            }
                                                                        }
                                                                    },
                                                                    singleLine = true,
                                                                    modifier = Modifier
                                                                        .fillMaxWidth()
                                                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                                                    shape = RoundedCornerShape(12.dp)
                                                                )
                                                            }

                                                            if (currentPlaylist.songs.isEmpty()) {
                                                                Box(
                                                                    modifier = Modifier.fillMaxSize(),
                                                                    contentAlignment = Alignment.Center
                                                                ) {
                                                                    Text(
                                                                        "歌单内暂无歌曲",
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                    )
                                                                }
                                                            } else {
                                                                val songMatchTriples = remember(currentPlaylist.songs, inPlaylistSearchQuery, customPlaylists) {
                                                                    val allTriples = currentPlaylist.songs.mapIndexed { index, songDto ->
                                                                        val media = songDto.toPlayableMedia()
                                                                        val localMatch = if (media is PlayableMedia.Online) playlistManager.findLocalMatch(media) else null
                                                                        Triple(index, songDto, localMatch != null)
                                                                    }
                                                                    if (inPlaylistSearchQuery.isBlank()) {
                                                                        allTriples
                                                                    } else {
                                                                        val q = inPlaylistSearchQuery.trim().lowercase()
                                                                        allTriples.filter { (_, songDto, _) ->
                                                                            songDto.title.lowercase().contains(q) ||
                                                                            songDto.artist.lowercase().contains(q) ||
                                                                            songDto.album.lowercase().contains(q)
                                                                        }
                                                                    }
                                                                }
                                                                val matchedCount = remember(songMatchTriples) { songMatchTriples.count { it.third } }
                                                                val unmatchedCount = remember(songMatchTriples) { songMatchTriples.size - matchedCount }

                                                                if (showMatchStatusPreference) {
                                                                    Row(
                                                                        modifier = Modifier
                                                                            .fillMaxWidth()
                                                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                                        verticalAlignment = Alignment.CenterVertically
                                                                    ) {
                                                                        FilterChip(
                                                                            selected = matchFilterMode == 0,
                                                                            onClick = { matchFilterMode = 0 },
                                                                            label = { Text("全部 (${songMatchTriples.size})") }
                                                                        )
                                                                        FilterChip(
                                                                            selected = matchFilterMode == 1,
                                                                            onClick = { matchFilterMode = 1 },
                                                                            label = { Text("已匹配 ($matchedCount)") }
                                                                        )
                                                                        FilterChip(
                                                                            selected = matchFilterMode == 2,
                                                                            onClick = { matchFilterMode = 2 },
                                                                            label = { Text("未匹配 ($unmatchedCount)") }
                                                                        )
                                                                    }
                                                                }

                                                                val displayedSongs = remember(songMatchTriples, matchFilterMode, showMatchStatusPreference) {
                                                                    if (!showMatchStatusPreference || matchFilterMode == 0) {
                                                                        songMatchTriples
                                                                    } else if (matchFilterMode == 1) {
                                                                        songMatchTriples.filter { it.third }
                                                                    } else {
                                                                        songMatchTriples.filter { !it.third }
                                                                    }
                                                                }

                                                                if (displayedSongs.isEmpty()) {
                                                                    Box(
                                                                        modifier = Modifier.fillMaxSize(),
                                                                        contentAlignment = Alignment.Center
                                                                    ) {
                                                                        Text(
                                                                            if (inPlaylistSearchQuery.isNotBlank()) "未在歌单内找到匹配歌曲"
                                                                            else if (matchFilterMode == 1) "暂无已匹配的歌曲"
                                                                            else "暂无未匹配的歌曲",
                                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                        )
                                                                    }
                                                                } else {
                                                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                                                        items(displayedSongs, key = { it.second.id }) { (origIndex, songDto, isMatched) ->
                                                                            val media = songDto.toPlayableMedia()
                                                                            SongItem(
                                                                                media = media,
                                                                                isCurrentTrack = currentMedia?.id == media.id,
                                                                                isPlaying = isPlaying && currentMedia?.id == media.id,
                                                                                isLocalMatch = isMatched,
                                                                                onClick = {
                                                                                    val playableList = displayedSongs.map { (_, dto, _) ->
                                                                                        val m = dto.toPlayableMedia()
                                                                                        if (m is PlayableMedia.Online) {
                                                                                            playlistManager.findLocalMatch(m) ?: m
                                                                                        } else m
                                                                                    }
                                                                                    playerEngine.loadPlaylist(playableList, origIndex)
                                                                                    playerEngine.play()
                                                                                },
                                                                                onActionClick = {
                                                                                    actionMedia = media
                                                                                    actionPlaylistContext = currentPlaylist
                                                                                    actionRemoveCallback = {
                                                                                        playlistManager.removeSongFromPlaylist(currentPlaylist.id, media.id)
                                                                                        selectedPlaylistForDetail = playlistManager.playlists.value.find { it.id == currentPlaylist.id }
                                                                                        if (currentPlaylist.ncmPlaylistId != null) {
                                                                                            scope.launch(Dispatchers.IO) {
                                                                                                ncmApi.updatePlaylistTracks("del", currentPlaylist.ncmPlaylistId, media.id)
                                                                                            }
                                                                                        }
                                                                                    }
                                                                                }
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    // Playlists Grid / List View
                                                    PlaylistsView(
                                                        playlists = customPlaylists,
                                                        playlistManager = playlistManager,
                                                        isLoggedIn = isLoggedIn,
                                                        hasApi = hasApi,
                                                        onSelectPlaylist = { selectedPlaylistForDetail = it },
                                                        onReSyncPlaylist = reSyncPlaylist
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

                if (topLevelScreen != TopLevelScreen.MAIN && isTopBackActive) {
                    val topScrimAlpha = 0.42f * (1f - PredictiveBackEasing.transform(activeTopBackProgress.coerceIn(0f, 1f)))
                    if (topScrimAlpha > 0.005f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = topScrimAlpha))
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = topLevelScreen == TopLevelScreen.PLAYER,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn(tween(300)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(320)) + fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .predictiveBackForeground(state = playerBackState, pullDownDp = 42f)
            ) {
                PlayerScreen(
                    playerState = playerEngine,
                    playerController = playerEngine,
                    ncmApi = ncmApi,
                    onBackClick = { showPlayerScreen = false },
                    onAddToPlaylistClick = { songToAddToPlaylist = currentMedia },
                    onToggleQueueClick = {
                        showPlayerScreen = false
                        selectedTab = 0
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = topLevelScreen == TopLevelScreen.SEARCH,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400)) + fadeIn(tween(300)),
            exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(320)) + fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .predictiveBackForeground(state = searchBackState, pullDownDp = 12f)
            ) {
                SearchScreen(
                    playerState = playerEngine,
                    playerController = playerEngine,
                    ncmApi = ncmApi,
                    playlistManager = playlistManager,
                    searchHistoryManager = searchHistoryManager,
                    onBack = { showSearchScreen = false },
                    onSongActionClick = { media, playlistCtx ->
                        actionMedia = media
                        actionPlaylistContext = playlistCtx
                        actionRemoveCallback = null
                    },
                    onOpenPlaylistTarget = { target ->
                        selectedOnlinePlaylist = target
                        selectedTab = 0
                        showSearchScreen = false
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = topLevelScreen == TopLevelScreen.SETTINGS,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400)) + fadeIn(tween(300)),
            exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(320)) + fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .predictiveBackForeground(state = settingsBackState, pullDownDp = 12f)
            ) {
                SettingsScreen(
                    sessionManager = sessionManager,
                    ncmApi = ncmApi,
                    localFolderManager = localFolderManager,
                    onBack = { showSettingsScreen = false }
                )
            }
        }
    }

    // Add To Playlist Dialog
    if (songToAddToPlaylist != null) {
        AddToPlaylistDialog(
            song = songToAddToPlaylist!!,
            playlistManager = playlistManager,
            ncmApi = ncmApi,
            onDismiss = { songToAddToPlaylist = null },
            onAdded = { playlistName ->
                Toast.makeText(context, "已添加至歌单: $playlistName", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // 歌曲操作弹窗 (Song Action BottomSheet)
    if (actionMedia != null) {
        val isFav = remember(actionMedia, customPlaylists) {
            playlistManager.isFavorite(actionMedia!!.id)
        }
        SongActionBottomSheet(
            media = actionMedia!!,
            currentPlaylist = actionPlaylistContext,
            isFavorite = isFav,
            showFavoriteAction = hasApi && isLoggedIn,
            onDismiss = {
                actionMedia = null
                actionPlaylistContext = null
                actionRemoveCallback = null
            },
            onPlayNext = {
                playerEngine.insertAsNext(actionMedia!!)
                Toast.makeText(context, "已设为下一首播放: ${actionMedia!!.title}", Toast.LENGTH_SHORT).show()
            },
            onToggleFavorite = {
                val mediaToFav = actionMedia!!
                val isNowFav = playlistManager.toggleFavorite(mediaToFav)
                val msg = if (isNowFav) "已添加到我喜欢的音乐" else "已从我喜欢的音乐移除"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                scope.launch(Dispatchers.IO) {
                    ncmApi.likeSong(mediaToFav.id, isNowFav)
                }
            },
            onAddToPlaylist = {
                songToAddToPlaylist = actionMedia
            },
            onRemoveFromCurrentPlaylist = actionRemoveCallback
        )
    }

    // Play Queue BottomSheet
    if (showQueueBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQueueBottomSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "播放队列 (${playlist.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (infinitePlayMode.isActive) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "∞ ${infinitePlayMode.label}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 180.dp)
                                )
                                if (isFetchingRecommendations) {
                                    Spacer(Modifier.width(6.dp))
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "退出无限推荐",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            playerEngine.disableInfinitePlayback()
                                            Toast.makeText(context, "已关闭无限推荐", Toast.LENGTH_SHORT).show()
                                        }
                                )
                            }
                        }
                    }
                }
                if (playlist.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (isFetchingRecommendations) "正在加载推荐曲目..." else "队列为空",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        itemsIndexed(playlist) { index, media ->
                            SongItem(
                                media = media,
                                isCurrentTrack = index == currentIndex,
                                isPlaying = isPlaying && index == currentIndex,
                                onClick = {
                                    playerEngine.skipToIndex(index)
                                    playerEngine.play()
                                },
                                onActionClick = {
                                    actionMedia = media
                                    actionPlaylistContext = null
                                    actionRemoveCallback = null
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Cloud Playlist Sync Dialog
    if (showSyncCloudDialog) {
        SyncCloudPlaylistsDialog(
            ncmApi = ncmApi,
            sessionManager = sessionManager,
            playlistManager = playlistManager,
            appScope = scope,
            onDismiss = { showSyncCloudDialog = false }
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建歌单") },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("歌单名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            playlistManager.createPlaylist(newPlaylistName)
                            newPlaylistName = ""
                            showCreateDialog = false
                        }
                    }
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun PlaylistsView(
    playlists: List<CustomPlaylist>,
    playlistManager: PlaylistManager,
    isLoggedIn: Boolean,
    hasApi: Boolean,
    onSelectPlaylist: (CustomPlaylist) -> Unit,
    onReSyncPlaylist: (CustomPlaylist) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .wrapContentWidth(androidx.compose.ui.Alignment.CenterHorizontally)
            .widthIn(max = 840.dp)
            .padding(16.dp)
    ) {
        if (playlists.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "尚未创建任何歌单",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(playlists) { _, playlist ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable { onSelectPlaylist(playlist) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!playlist.coverImgUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = playlist.coverImgUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.QueueMusic,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    playlist.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${playlist.songs.size} 首歌曲",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (playlist.ncmPlaylistId != null && playlist.ncmPlaylistId != -9999L) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        SuggestionChip(
                                            onClick = {},
                                            label = { Text("云端", fontSize = 10.sp) },
                                            modifier = Modifier.height(20.dp)
                                        )
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (playlist.ncmPlaylistId != null && hasApi && isLoggedIn) {
                                    IconButton(onClick = { onReSyncPlaylist(playlist) }) {
                                        Icon(
                                            Icons.Rounded.Sync,
                                            contentDescription = "重新同步",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                IconButton(onClick = { playlistManager.deletePlaylist(playlist.id) }) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "删除歌单",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
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

@Composable
private fun SyncCloudPlaylistsDialog(
    ncmApi: NcmApi,
    sessionManager: UserSessionManager,
    playlistManager: PlaylistManager,
    appScope: CoroutineScope,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val dialogScope = rememberCoroutineScope()
    val userProfile by sessionManager.userProfileState.collectAsState()
    val uid = userProfile?.userId ?: 0L

    var isLoading by remember { mutableStateOf(true) }
    var cloudPlaylists by remember { mutableStateOf<List<NcmUserPlaylist>>(emptyList()) }
    val selectedPlaylistIds = remember { mutableStateListOf<Long>() }
    var isImporting by remember { mutableStateOf(false) }
    var currentPlaylistName by remember { mutableStateOf("") }
    var currentPlaylistIndex by remember { mutableIntStateOf(0) }
    var totalPlaylistsToSync by remember { mutableIntStateOf(0) }
    var currentFetchedSongs by remember { mutableIntStateOf(0) }
    var currentTotalSongs by remember { mutableIntStateOf(0) }

    LaunchedEffect(uid) {
        var targetUid = uid
        if (targetUid <= 0L) {
            val profile = ncmApi.getUserProfile()
            if (profile != null && profile.userId > 0L) {
                targetUid = profile.userId
            }
        }

        if (targetUid > 0L) {
            val list = ncmApi.getUserPlaylists(targetUid)
            cloudPlaylists = list
        } else {
            Toast.makeText(context, "无法获取用户 ID，请确认登录状态", Toast.LENGTH_SHORT).show()
        }
        isLoading = false
    }

    val existingNcmIds = remember(playlistManager.playlists.collectAsState().value) {
        playlistManager.playlists.value.mapNotNull { it.ncmPlaylistId }.toSet()
    }

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = {
            Text(
                if (isImporting) "正在同步歌单 ($currentPlaylistIndex/$totalPlaylistsToSync)"
                else "同步网易云歌单",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                if (isImporting) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "正在同步: $currentPlaylistName",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "已抓取: $currentFetchedSongs / ${if (currentTotalSongs > 0) currentTotalSongs else "未知"} 首",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (cloudPlaylists.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂未获取到云端歌单", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(cloudPlaylists) { _, item ->
                            val isAlreadyImported = existingNcmIds.contains(item.id)
                            val isChecked = selectedPlaylistIds.contains(item.id)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isChecked) selectedPlaylistIds.remove(item.id)
                                        else selectedPlaylistIds.add(item.id)
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedPlaylistIds.add(item.id)
                                        else selectedPlaylistIds.remove(item.id)
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                if (item.coverImgUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = item.coverImgUrl,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.name,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${item.trackCount} 首  ·  ${item.creatorName.ifBlank { "网易云" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isAlreadyImported) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    SuggestionChip(
                                        onClick = {},
                                        label = { Text("已在本地", fontSize = 10.sp) },
                                        modifier = Modifier.height(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isLoading && !isImporting) {
                Button(
                    enabled = selectedPlaylistIds.isNotEmpty(),
                    onClick = {
                        isImporting = true
                        val targetList = cloudPlaylists.filter { selectedPlaylistIds.contains(it.id) }
                        totalPlaylistsToSync = targetList.size

                        appScope.launch(Dispatchers.IO) {
                            targetList.forEachIndexed { index, ncmPlaylist ->
                                currentPlaylistIndex = index + 1
                                currentPlaylistName = ncmPlaylist.name
                                currentFetchedSongs = 0
                                currentTotalSongs = ncmPlaylist.trackCount

                                val tracks = ncmApi.getPlaylistTracks(
                                    playlistId = ncmPlaylist.id,
                                    totalCount = ncmPlaylist.trackCount,
                                    onProgress = { fetched, total ->
                                        dialogScope.launch {
                                            val start = currentFetchedSongs
                                            if (fetched > start) {
                                                val step = ((fetched - start) / 5).coerceAtLeast(1)
                                                var curr = start
                                                while (curr < fetched) {
                                                    curr = (curr + step).coerceAtMost(fetched)
                                                    currentFetchedSongs = curr
                                                    delay(10.milliseconds)
                                                }
                                            } else {
                                                currentFetchedSongs = fetched
                                            }
                                            currentTotalSongs = total
                                        }
                                    }
                                )
                                playlistManager.saveOrUpdateSyncedPlaylist(
                                    ncmPlaylistId = ncmPlaylist.id,
                                    name = ncmPlaylist.name,
                                    coverImgUrl = ncmPlaylist.coverImgUrl,
                                    songs = tracks
                                )
                            }
                            withContext(Dispatchers.Main) {
                                isImporting = false
                                onDismiss()
                                Toast.makeText(context, "已成功同步 ${targetList.size} 个歌单到本地", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("导入选中的歌单 (${selectedPlaylistIds.size})")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isImporting) "后台运行" else "取消")
            }
        }
    )
}

@Composable
private fun EmptyState(onOpenFiles: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(id = Akari.NCM.player.R.drawable.ic_ame_status),
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "暂无正在播放的音乐",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "导入 NCM 或本地音频文件即可开始播放",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        FilledTonalButton(onClick = onOpenFiles) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("打开文件")
        }
    }
}

@Composable
fun SongItem(
    media: PlayableMedia,
    isCurrentTrack: Boolean,
    isPlaying: Boolean,
    isLocalMatch: Boolean = false,
    matchedLyric: String? = (media as? PlayableMedia.Online)?.matchedLyric,
    onClick: () -> Unit,
    onActionClick: (() -> Unit)? = null
) {
    ListItem(
        headlineContent = {
            Text(
                media.displayTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrentTrack) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrentTrack) FontWeight.Bold else FontWeight.Normal
            )
        },
        supportingContent = {
            Column {
                Text(
                    "${media.artist}${if (media.album.isNotBlank()) "  ·  ${media.album}" else ""}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!matchedLyric.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "💬 $matchedLyric",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        fontSize = 11.sp
                    )
                }
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (media.coverUrl != null) {
                    AsyncImage(
                        model = media.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = Akari.NCM.player.R.drawable.ic_ame_status),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (isLocalMatch) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .size(14.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = "本地匹配",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                }

                if (isCurrentTrack && isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Equalizer,
                            contentDescription = "正在播放",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else if (isCurrentTrack) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Pause,
                            contentDescription = "已暂停",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (media.isLocal && (media as? PlayableMedia.LocalNcm)?.isNcm == true) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("NCM", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(24.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                if (onActionClick != null) {
                    IconButton(onClick = onActionClick) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "歌曲操作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun MiniPlayerBar(
    media: PlayableMedia?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onClick: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onQueueClick: () -> Unit
) {
    if (media == null) return

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column {
            if (durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (media.coverUrl != null) {
                        AsyncImage(
                            model = media.coverUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = Akari.NCM.player.R.drawable.ic_ame_status),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        media.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        media.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onSkipPrevious) {
                    Icon(
                        Icons.Rounded.SkipPrevious,
                        contentDescription = "上一首",
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(onClick = onSkipNext) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = "下一首",
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(onClick = onQueueClick) {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = "播放队列",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}
