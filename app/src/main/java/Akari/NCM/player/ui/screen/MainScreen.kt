package Akari.NCM.player.ui.screen

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.NcmUserPlaylist
import Akari.NCM.player.core.OnlinePlaylistTarget
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.PlaylistSourceType
import Akari.NCM.player.data.CustomPlaylist
import Akari.NCM.player.data.LyricsManager
import Akari.NCM.player.data.LocalFolderManager
import Akari.NCM.player.data.NcmFileScanner
import Akari.NCM.player.data.PlaylistManager
import Akari.NCM.player.data.SearchHistoryManager
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.player.AmePlayerEngine
import Akari.NCM.player.ui.component.AdaptiveButtonGroup
import Akari.NCM.player.ui.component.AdaptiveButtonSpec
import Akari.NCM.player.ui.component.AddToPlaylistDialog
import Akari.NCM.player.ui.component.ExpressiveButtonStyle
import Akari.NCM.player.ui.component.PortraitPullDownDrawerOverlay
import Akari.NCM.player.ui.component.PredictiveBackEasing
import Akari.NCM.player.ui.component.SaltLandscapePlayQueueSheet
import Akari.NCM.player.ui.component.SaltPlayQueueView
import Akari.NCM.player.ui.component.SongActionBottomSheet
import Akari.NCM.player.ui.component.predictiveBackBackground
import Akari.NCM.player.ui.component.predictiveBackForeground
import Akari.NCM.player.ui.component.rememberPlayerCoverPalette
import Akari.NCM.player.ui.component.rememberPredictiveBackState
import Akari.NCM.player.ui.component.rememberPullDownDrawerState
import Akari.NCM.player.ui.theme.blend
import Akari.NCM.player.ui.theme.buildExpressiveColorScheme
import Akari.NCM.player.ui.theme.ensureContrast
import Akari.NCM.player.ui.theme.extractSeedColorFromBitmap
import Akari.NCM.player.ui.theme.rememberIsDarkTheme
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

enum class TopLevelScreen {
    PLAYER, LOG, WEB_LOGIN, SETTINGS, SEARCH, MAIN
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
    val durationMs by playerEngine.durationMs.collectAsState()
    val infinitePlayMode by playerEngine.infinitePlayMode.collectAsState()
    val isFetchingRecommendations by playerEngine.isFetchingRecommendations.collectAsState()
    val openPlayerCount by openPlayerRequests.collectAsState()

    var showPlayerScreen by remember { mutableStateOf(false) }
    var showQueueBottomSheet by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var showLogScreen by remember { mutableStateOf(false) }
    var showWebLoginScreen by remember { mutableStateOf(false) }
    var showSearchScreen by remember { mutableStateOf(false) }
    var isSearchInputInProgress by remember { mutableStateOf(false) }
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
            showLogScreen = false
            showWebLoginScreen = false
            showSearchScreen = false
            showPlayerScreen = true
        }
    }

    // AOSP 预见性返回手势状态：按优先级响应日志页、网页登录页、设置页、搜索页、播放页、在线歌单详情页与本地歌单详情页
    val logBackState = rememberPredictiveBackState(enabled = showLogScreen) {
        showLogScreen = false
    }

    val webLoginBackState = rememberPredictiveBackState(enabled = showWebLoginScreen && !showLogScreen) {
        showWebLoginScreen = false
    }

    val settingsBackState = rememberPredictiveBackState(enabled = showSettingsScreen && !showLogScreen && !showWebLoginScreen) {
        showSettingsScreen = false
    }

    val searchBackState = rememberPredictiveBackState(enabled = showSearchScreen && !showSettingsScreen && !showLogScreen && !showWebLoginScreen) {
        showSearchScreen = false
    }

    val playerBackState = rememberPredictiveBackState(
        enabled = showPlayerScreen && !showSettingsScreen && !showSearchScreen && !showLogScreen && !showWebLoginScreen
    ) {
        showPlayerScreen = false
    }

    val onlinePlaylistBackState = rememberPredictiveBackState(
        enabled = selectedOnlinePlaylist != null && selectedTab == 0 && !showPlayerScreen && !showSettingsScreen && !showSearchScreen && !showLogScreen && !showWebLoginScreen
    ) {
        selectedOnlinePlaylist = null
    }

    val playlistDetailBackState = rememberPredictiveBackState(
        enabled = selectedPlaylistForDetail != null && selectedTab == 1 && !showPlayerScreen && !showSettingsScreen && !showSearchScreen && !showLogScreen && !showWebLoginScreen
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
            playlistManager.syncAllCloudPlaylistsSilently(ncmApi, sessionManager)
        }
    }
    val customPlaylists by playlistManager.playlists.collectAsState()
    val favoriteSongIds by playlistManager.favoriteSongIds.collectAsState()
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
                    playlistManager.syncCloudFavorites(ncmApi, sessionManager)
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
        showLogScreen -> TopLevelScreen.LOG
        showWebLoginScreen -> TopLevelScreen.WEB_LOGIN
        showSettingsScreen -> TopLevelScreen.SETTINGS
        showSearchScreen -> TopLevelScreen.SEARCH
        else -> TopLevelScreen.MAIN
    }

    val isTopBackActive = (showPlayerScreen && playerBackState.isVisibleInBackground) ||
        (showSettingsScreen && !showLogScreen && !showWebLoginScreen && settingsBackState.isVisibleInBackground) ||
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

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenWidthDp > configuration.screenHeightDp
    val (queueActiveTrack, queuePalette, queueBlurCoverBg) = rememberPlayerCoverPalette(currentMedia)
    val mainQueueDrawerState = rememberPullDownDrawerState(
        isOpen = showQueueBottomSheet,
        isLandscape = isLandscape,
        backEnabled = showQueueBottomSheet && !showPlayerScreen && !showSearchScreen && !showSettingsScreen && !showLogScreen && !showWebLoginScreen,
        label = "MainQueueParallax",
        onDismiss = { showQueueBottomSheet = false }
    )
    val mainQueueDensity = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (topLevelScreen != TopLevelScreen.MAIN && isTopBackActive) {
                        Modifier.predictiveBackBackground(
                            progress = activeTopBackProgress,
                            swipeEdge = activeTopBackEdge
                        )
                    } else if (!isLandscape && mainQueueDrawerState.effectiveProgress > 0f) {
                        val p = mainQueueDrawerState.effectiveProgress
                        Modifier
                            .graphicsLayer {
                                val scale = 1.0f + (0.92f - 1.0f) * p
                                val translateY = with(mainQueueDensity) { (-32.dp.toPx()) * p }
                                val alphaVal = 1.0f + (0.45f - 1.0f) * p
                                scaleX = scale
                                scaleY = scale
                                translationY = translateY
                                alpha = alphaVal
                            }
                            .clip(RoundedCornerShape(24.dp * p))
                    } else {
                        Modifier
                    }
                )
        ) {
                val adaptiveInfo = androidx.compose.material3.adaptive.currentWindowAdaptiveInfo()
                val useNavRail = with(adaptiveInfo.windowSizeClass) {
                    windowHeightSizeClass == androidx.window.core.layout.WindowHeightSizeClass.COMPACT ||
                        windowWidthSizeClass == androidx.window.core.layout.WindowWidthSizeClass.EXPANDED ||
                        windowWidthSizeClass == androidx.window.core.layout.WindowWidthSizeClass.MEDIUM
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (useNavRail) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.background,
                            modifier = Modifier.testTag("main_nav_rail")
                        ) {
                            Spacer(modifier = Modifier.height(12.dp))
                            if (hasApi) {
                                NavigationRailItem(
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
                                    label = null,
                                    alwaysShowLabel = false,
                                    modifier = Modifier.testTag("nav_tab_home")
                                )
                            }
                            NavigationRailItem(
                                selected = selectedTab == 1,
                                onClick = {
                                    selectedTab = 1
                                },
                                icon = {
                                    Icon(Icons.Rounded.LibraryMusic, contentDescription = "我的歌单")
                                },
                                label = null,
                                alwaysShowLabel = false,
                                modifier = Modifier.testTag("nav_tab_playlists")
                            )
                        }
                    }

                    Scaffold(
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.background,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                            TopAppBar(
                                title = {},
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
                                    containerColor = MaterialTheme.colorScheme.background
                                )
                            )
                        },
                        bottomBar = {
                            // 仅保留图标的紧凑底部页面切换栏，背景色无缝延伸至屏幕最底端（消除导航手势区色块断开/留白）
                            if (!useNavRail) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    tonalElevation = 2.dp,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    NavigationBar(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .windowInsetsPadding(WindowInsets.navigationBars)
                                            .height(52.dp)
                                            .testTag("main_bottom_nav_bar"),
                                        containerColor = Color.Transparent,
                                        tonalElevation = 0.dp,
                                        windowInsets = WindowInsets(0, 0, 0, 0)
                                    ) {
                                        if (hasApi) {
                                            NavigationBarItem(
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
                                                label = null,
                                                alwaysShowLabel = false,
                                                modifier = Modifier.testTag("nav_tab_home")
                                            )
                                        }
                                        NavigationBarItem(
                                            selected = selectedTab == 1,
                                            onClick = {
                                                selectedTab = 1
                                            },
                                            icon = {
                                                Icon(Icons.Rounded.LibraryMusic, contentDescription = "我的歌单")
                                            },
                                            label = null,
                                            alwaysShowLabel = false,
                                            modifier = Modifier.testTag("nav_tab_playlists")
                                        )
                                    }
                                }
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
                                                        if (onlinePlaylistBackState.isDismissedByGesture) {
                                                            EnterTransition.None togetherWith ExitTransition.None
                                                        } else {
                                                            fadeIn(animationSpec = tween(300)) togetherWith slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
                                                        }
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
                                                            .background(MaterialTheme.colorScheme.background)
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
                                                            },
                                                            onOpenPlaylistTarget = { nextTarget ->
                                                                selectedOnlinePlaylist = nextTarget
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
                                                        if (playlistDetailBackState.isDismissedByGesture) {
                                                            EnterTransition.None togetherWith ExitTransition.None
                                                        } else {
                                                            fadeIn(animationSpec = tween(300)) togetherWith slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
                                                        }
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
                                                    val localPlaylistListState = rememberSaveable(currentPlaylist.id, saver = LazyListState.Saver) {
                                                        LazyListState()
                                                    }
                                                    val currentPlaylistSourceType by playerEngine.playlistSourceType.collectAsState()
                                                    val isCloudSyncedPlaylist = currentPlaylist.ncmPlaylistId != null && currentPlaylist.ncmPlaylistId != -9999L
                                                    val detailPlaylistSourceType = if (isCloudSyncedPlaylist) PlaylistSourceType.ONLINE else PlaylistSourceType.LOCAL
                                                    val isCurrentFromLocal = currentPlaylistSourceType == detailPlaylistSourceType
                                                    var inPlaylistSearchQuery by remember(currentPlaylist.id) { mutableStateOf("") }
                                                    var isPlaylistSearchActive by remember(currentPlaylist.id) { mutableStateOf(false) }

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

                                                    val displayedSongs = remember(songMatchTriples, matchFilterMode, showMatchStatusPreference) {
                                                        if (!showMatchStatusPreference || matchFilterMode == 0) {
                                                            songMatchTriples
                                                        } else if (matchFilterMode == 1) {
                                                            songMatchTriples.filter { it.third }
                                                        } else {
                                                            songMatchTriples.filter { !it.third }
                                                        }
                                                    }

                                                    val curMedia = currentMedia
                                                    val isCurrentMediaInThisLocalPlaylist = remember(currentPlaylist.songs, curMedia, isCurrentFromLocal) {
                                                        isCurrentFromLocal && curMedia != null && currentPlaylist.songs.any { songDto ->
                                                            val m = songDto.toPlayableMedia()
                                                            m.id == curMedia.id || (curMedia is PlayableMedia.LocalNcm && songDto.uri != null && songDto.uri == curMedia.uri)
                                                        }
                                                    }
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .predictiveBackForeground(state = playlistDetailBackState, pullDownDp = 12f)
                                                            .background(MaterialTheme.colorScheme.background)
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
                                                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                IconButton(onClick = { selectedPlaylistForDetail = null }) {
                                                                    Icon(
                                                                        Icons.AutoMirrored.Rounded.ArrowBack,
                                                                        contentDescription = "返回",
                                                                        tint = MaterialTheme.colorScheme.onSurface
                                                                    )
                                                                }
                                                                Column(
                                                                    modifier = Modifier
                                                                        .weight(1f)
                                                                        .padding(horizontal = 8.dp)
                                                                ) {
                                                                    Text(
                                                                        currentPlaylist.name,
                                                                        style = MaterialTheme.typography.titleLarge,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = MaterialTheme.colorScheme.onSurface,
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
                                                                            Surface(
                                                                                shape = RoundedCornerShape(4.dp),
                                                                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                                                            ) {
                                                                                Text(
                                                                                    text = "云端",
                                                                                    fontSize = 10.sp,
                                                                                    fontWeight = FontWeight.Medium,
                                                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                                )
                                                                            }
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

                                                            // Action Row: Play All + Locate + Filter Chips (Scrollable horizontally)
                                                            if (currentPlaylist.songs.isNotEmpty()) {
                                                                Row(
                                                                    modifier = Modifier
                                                                        .fillMaxWidth()
                                                                        .horizontalScroll(rememberScrollState())
                                                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
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
                                                                            playerEngine.loadPlaylist(playableList, 0, detailPlaylistSourceType)
                                                                            playerEngine.play()
                                                                        },
                                                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                                                    ) {
                                                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                                                        Spacer(modifier = Modifier.width(4.dp))
                                                                        Text(if (inPlaylistSearchQuery.isNotBlank()) "播放筛选 (${displayedSongs.size})" else "全部播放 (${currentPlaylist.songs.size})")
                                                                    }

                                                                    if (isCurrentMediaInThisLocalPlaylist) {
                                                                        Spacer(modifier = Modifier.width(8.dp))
                                                                        FilledTonalButton(
                                                                            onClick = {
                                                                                scope.launch {
                                                                                    val cur = currentMedia
                                                                                    val isTarget = { songDto: Akari.NCM.player.data.PlayableMediaDTO ->
                                                                                        val m = songDto.toPlayableMedia()
                                                                                        m.id == cur?.id || (cur is PlayableMedia.LocalNcm && songDto.uri != null && songDto.uri == cur.uri)
                                                                                    }
                                                                                    if (inPlaylistSearchQuery.isNotBlank() && displayedSongs.none { (_, songDto, _) -> isTarget(songDto) }) {
                                                                                        inPlaylistSearchQuery = ""
                                                                                    }
                                                                                    if (matchFilterMode != 0 && displayedSongs.none { (_, songDto, _) -> isTarget(songDto) }) {
                                                                                        matchFilterMode = 0
                                                                                    }
                                                                                    val targetIdx = displayedSongs.indexOfFirst { (_, songDto, _) -> isTarget(songDto) }
                                                                                    if (targetIdx >= 0) {
                                                                                        localPlaylistListState.animateScrollToItem(targetIdx)
                                                                                    }
                                                                                }
                                                                            },
                                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                                            modifier = Modifier.testTag("btn_locate_local_track")
                                                                        ) {
                                                                            Icon(
                                                                                imageVector = Icons.Rounded.MyLocation,
                                                                                contentDescription = "定位当前播放",
                                                                                modifier = Modifier.size(16.dp)
                                                                            )
                                                                            Spacer(modifier = Modifier.width(4.dp))
                                                                            Text("定位", fontSize = 12.sp)
                                                                        }
                                                                    }

                                                                    if (showMatchStatusPreference) {
                                                                        Spacer(modifier = Modifier.width(12.dp))
                                                                        Row(
                                                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                            verticalAlignment = Alignment.CenterVertically
                                                                        ) {
                                                                            FilterChip(
                                                                                selected = matchFilterMode == 0,
                                                                                onClick = { matchFilterMode = 0 },
                                                                                label = { Text("全部 (${songMatchTriples.size})", fontSize = 12.sp) }
                                                                            )
                                                                            FilterChip(
                                                                                selected = matchFilterMode == 1,
                                                                                onClick = { matchFilterMode = 1 },
                                                                                label = { Text("已匹配 ($matchedCount)", fontSize = 12.sp) }
                                                                            )
                                                                            FilterChip(
                                                                                selected = matchFilterMode == 2,
                                                                                onClick = { matchFilterMode = 2 },
                                                                                label = { Text("未匹配 ($unmatchedCount)", fontSize = 12.sp) }
                                                                            )
                                                                        }
                                                                    }
                                                                }
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
                                                                    LazyColumn(
                                                                        state = localPlaylistListState,
                                                                        modifier = Modifier.fillMaxSize(),
                                                                        contentPadding = PaddingValues(bottom = 96.dp)
                                                                    ) {
                                                                        items(displayedSongs, key = { "${it.first}_${it.second.id}" }) { (origIndex, songDto, isMatched) ->
                                                                            val media = songDto.toPlayableMedia()
                                                                            SongItem(
                                                                                media = media,
                                                                                isCurrentTrack = currentMedia?.id == media.id,
                                                                                isPlaying = isPlaying && currentMedia?.id == media.id,
                                                                                isLocalMatch = isMatched,
                                                                                onClick = {
                                                                                    val songsSource = if (inPlaylistSearchQuery.isNotBlank() || matchFilterMode == 0) {
                                                                                        currentPlaylist.songs
                                                                                    } else {
                                                                                        displayedSongs.map { it.second }
                                                                                    }
                                                                                    val playableList = songsSource.map { dto ->
                                                                                        val m = dto.toPlayableMedia()
                                                                                        if (m is PlayableMedia.Online) {
                                                                                            playlistManager.findLocalMatch(m) ?: m
                                                                                        } else m
                                                                                    }
                                                                                    val targetIndex = playableList.indexOfFirst { it.id == media.id }.coerceAtLeast(0)
                                                                                    playerEngine.loadPlaylist(playableList, targetIndex, detailPlaylistSourceType)
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

                            // 常驻悬浮底部播放状态栏（无外圈背景遮罩，直接悬浮于主页面内容上方）
                            androidx.compose.animation.AnimatedVisibility(
                                visible = !showPlayerScreen && !showSearchScreen,
                                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .then(
                                        if (useNavRail) Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                                        else Modifier
                                    )
                            ) {
                                MiniPlayerBar(
                                    media = currentMedia,
                                    isPlaying = isPlaying,
                                    positionMsFlow = playerEngine.currentPositionMs,
                                    durationMs = durationMs,
                                    onTogglePlayPause = { playerEngine.togglePlayPause() },
                                    onClick = {
                                        if (currentMedia != null) {
                                            showPlayerScreen = true
                                        }
                                    },
                                    onSkipNext = { playerEngine.skipToNext() },
                                    onSkipPrevious = { playerEngine.skipToPrevious() },
                                    onQueueClick = { showQueueBottomSheet = true }
                                )
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

        AnimatedVisibility(
            visible = showSearchScreen,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400)) + fadeIn(tween(300)),
            exit = if (searchBackState.isDismissedByGesture) {
                ExitTransition.None
            } else {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(320)) + fadeOut(tween(250))
            },
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
                    },
                    onInputProgressChanged = { inProgress ->
                        isSearchInputInProgress = inProgress
                    }
                )
            }
        }

        // 搜索页悬浮迷你播放条（位于 SearchScreen 上方，低于 PlayerScreen / SettingsScreen / PlayQueueDrawer）
        AnimatedVisibility(
            visible = showSearchScreen && !showPlayerScreen && !showSettingsScreen && !showLogScreen && !showWebLoginScreen && !isSearchInputInProgress,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            MiniPlayerBar(
                media = currentMedia,
                isPlaying = isPlaying,
                positionMsFlow = playerEngine.currentPositionMs,
                durationMs = durationMs,
                onTogglePlayPause = { playerEngine.togglePlayPause() },
                onClick = {
                    if (currentMedia != null) {
                        showPlayerScreen = true
                    }
                },
                onSkipNext = { playerEngine.skipToNext() },
                onSkipPrevious = { playerEngine.skipToPrevious() },
                onQueueClick = { showQueueBottomSheet = true }
            )
        }

        AnimatedVisibility(
            visible = showPlayerScreen,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn(tween(300)),
            exit = if (playerBackState.isDismissedByGesture) {
                ExitTransition.None
            } else {
                slideOutVertically(targetOffsetY = { it }, animationSpec = tween(320)) + fadeOut(tween(250))
            },
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
                    playlistManager = playlistManager,
                    onBackClick = { showPlayerScreen = false },
                    onAddToPlaylistClick = { songToAddToPlaylist = currentMedia },
                    onToggleQueueClick = {
                        showPlayerScreen = false
                        selectedTab = 0
                    },
                    onNavigateToArtist = { artist ->
                        showPlayerScreen = false
                        selectedTab = 0
                        selectedOnlinePlaylist = OnlinePlaylistTarget(
                            id = artist.id,
                            title = artist.name,
                            creatorName = artist.name,
                            description = "歌手精选 · ${artist.name}",
                            isArtist = true
                        )
                    },
                    onNavigateToAlbum = { albumId, albumName, artistName, coverUrl ->
                        showPlayerScreen = false
                        selectedTab = 0
                        selectedOnlinePlaylist = OnlinePlaylistTarget(
                            id = albumId,
                            title = albumName,
                            coverImgUrl = coverUrl ?: "",
                            creatorName = artistName,
                            description = "专辑 · $artistName",
                            isAlbum = true
                        )
                    }
                )
            }
        }

        AnimatedVisibility(
            visible = showSettingsScreen,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400)) + fadeIn(tween(300)),
            exit = if (settingsBackState.isDismissedByGesture) {
                ExitTransition.None
            } else {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(320)) + fadeOut(tween(250))
            },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (showLogScreen && logBackState.isVisibleInBackground) {
                            Modifier.predictiveBackBackground(
                                progress = logBackState.progress,
                                swipeEdge = logBackState.swipeEdge
                            )
                        } else if (showWebLoginScreen && webLoginBackState.isVisibleInBackground) {
                            Modifier.predictiveBackBackground(
                                progress = webLoginBackState.progress,
                                swipeEdge = webLoginBackState.swipeEdge
                            )
                        } else {
                            Modifier.predictiveBackForeground(state = settingsBackState, pullDownDp = 12f)
                        }
                    )
            ) {
                SettingsScreen(
                    sessionManager = sessionManager,
                    ncmApi = ncmApi,
                    localFolderManager = localFolderManager,
                    onOpenLog = { showLogScreen = true },
                    onOpenWebLogin = { showWebLoginScreen = true },
                    onBack = { showSettingsScreen = false }
                )
            }
        }

        AnimatedVisibility(
            visible = showWebLoginScreen,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400)) + fadeIn(tween(300)),
            exit = if (webLoginBackState.isDismissedByGesture) {
                ExitTransition.None
            } else {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(320)) + fadeOut(tween(250))
            },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .predictiveBackForeground(state = webLoginBackState, pullDownDp = 12f)
            ) {
                WebViewLoginScreen(
                    sessionManager = sessionManager,
                    ncmApi = ncmApi,
                    onLoginSuccess = {
                        showWebLoginScreen = false
                        homepageRefreshTrigger++
                        scope.launch(Dispatchers.IO) {
                            playlistManager.syncAllCloudPlaylistsSilently(ncmApi, sessionManager)
                        }
                        Toast.makeText(context, "网易云账号登录成功！", Toast.LENGTH_SHORT).show()
                    },
                    onBack = { showWebLoginScreen = false }
                )
            }
        }

        AnimatedVisibility(
            visible = showLogScreen,
            enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400)) + fadeIn(tween(300)),
            exit = if (logBackState.isDismissedByGesture) {
                ExitTransition.None
            } else {
                slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(320)) + fadeOut(tween(250))
            },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .predictiveBackForeground(state = logBackState, pullDownDp = 12f)
            ) {
                LogScreen(
                    onBack = { showLogScreen = false }
                )
            }
        }
        // Main Page Unified Play Queue Drawer (Portrait pull-down drawer + Landscape themed sheet)
        PortraitPullDownDrawerOverlay(
            visible = !isLandscape && showQueueBottomSheet,
            drawerState = mainQueueDrawerState,
            sheetTestTag = "play_queue_sheet"
        ) {
            SaltPlayQueueView(
                playerState = playerEngine,
                playerController = playerEngine,
                displayedTrack = queueActiveTrack,
                palette = queuePalette,
                blurCoverBg = queueBlurCoverBg,
                pullHintText = "下滑此处返回",
                pullHandleTestTag = "play_queue_pull_handle",
                currentPullOffsetPx = { mainQueueDrawerState.pullOffsetPx.value },
                onPullDelta = mainQueueDrawerState.onPullDelta,
                onPullSettle = mainQueueDrawerState.onPullSettle,
                onDismiss = { showQueueBottomSheet = false }
            )
        }
    }

    // Landscape Play Queue BottomSheet (Unified Salt Theme Style)
    if (isLandscape && showQueueBottomSheet) {
        SaltLandscapePlayQueueSheet(
            playerState = playerEngine,
            playerController = playerEngine,
            palette = queuePalette,
            onDismiss = { showQueueBottomSheet = false }
        )
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
        val isFav = remember(actionMedia?.id, favoriteSongIds) {
            actionMedia?.let { playlistManager.isFavorite(it.id) } ?: false
        }
        val isLocalPlaylistAction = actionPlaylistContext != null &&
            (actionPlaylistContext!!.ncmPlaylistId == null || actionPlaylistContext!!.ncmPlaylistId == -9999L)
        SongActionBottomSheet(
            media = actionMedia!!,
            currentPlaylist = actionPlaylistContext,
            isFavorite = isFav,
            showFavoriteAction = hasApi && isLoggedIn && !isLocalPlaylistAction,
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
                val msg = if (isNowFav) "已添加到云端喜欢" else "已取消云端喜欢"
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
                AdaptiveButtonGroup(
                    buttons = listOf(
                        AdaptiveButtonSpec(
                            text = "创建",
                            style = ExpressiveButtonStyle.FILLED,
                            onClick = {
                                if (newPlaylistName.isNotBlank()) {
                                    playlistManager.createPlaylist(newPlaylistName)
                                    newPlaylistName = ""
                                    showCreateDialog = false
                                }
                            }
                        ),
                        AdaptiveButtonSpec(
                            text = "取消",
                            style = ExpressiveButtonStyle.TEXT,
                            onClick = { showCreateDialog = false }
                        )
                    )
                )
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                itemsIndexed(playlists) { _, playlist ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable { onSelectPlaylist(playlist) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
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
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        ) {
                                            Text(
                                                text = "云端",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
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
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ) {
                                        Text(
                                            text = "已在本地",
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val buttons = buildList {
                if (!isLoading && !isImporting) {
                    add(
                        AdaptiveButtonSpec(
                            text = "导入选中的歌单 (${selectedPlaylistIds.size})",
                            style = ExpressiveButtonStyle.FILLED,
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
                                    playlistManager.syncCloudFavorites(ncmApi, sessionManager, cloudPlaylists)
                                    withContext(Dispatchers.Main) {
                                        isImporting = false
                                        onDismiss()
                                        Toast.makeText(context, "已成功同步 ${targetList.size} 个歌单到本地", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    )
                }
                add(
                    AdaptiveButtonSpec(
                        text = if (isImporting) "后台运行" else "取消",
                        style = ExpressiveButtonStyle.TEXT,
                        onClick = onDismiss
                    )
                )
            }
            AdaptiveButtonGroup(buttons = buttons)
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
    positionMsFlow: kotlinx.coroutines.flow.StateFlow<Long>,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onClick: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onQueueClick: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val hasMedia = media != null

    val isDarkTheme = rememberIsDarkTheme()
    val colorVariant by ThemePrefs.colorVariantFlow.collectAsState()
    val contrastLevel by ThemePrefs.contrastLevelFlow.collectAsState()
    val fallbackAccentInt by ThemePrefs.accentColorFlow.collectAsState()
    val defaultSeedColor = remember(fallbackAccentInt) { Color(fallbackAccentInt.toInt()) }

    var extractedSeedColor by remember { mutableStateOf(defaultSeedColor) }

    LaunchedEffect(media?.id, media?.coverUrl, defaultSeedColor) {
        val url = media?.coverUrl
        if (!url.isNullOrEmpty()) {
            val seed = withTimeoutOrNull(650) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val request = ImageRequest.Builder(context)
                            .data(url)
                            .allowHardware(false)
                            .size(96, 96)
                            .build()
                        val result = context.imageLoader.execute(request)
                        val bmp = (result.drawable as? BitmapDrawable)?.bitmap
                        if (bmp != null) {
                            extractSeedColorFromBitmap(bmp, defaultSeedColor)
                        } else null
                    }.getOrNull()
                }
            }
            extractedSeedColor = seed ?: defaultSeedColor
        } else {
            extractedSeedColor = defaultSeedColor
        }
    }

    val animatedSeedColor by animateColorAsState(
        targetValue = extractedSeedColor,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "MiniPlayerCoverSeedColor"
    )

    val coverScheme = remember(animatedSeedColor, isDarkTheme, colorVariant, contrastLevel) {
        buildExpressiveColorScheme(
            seedColor = animatedSeedColor,
            darkTheme = isDarkTheme,
            variant = colorVariant,
            contrastLevel = contrastLevel,
            useMaterialBackground = true
        )
    }

    // 未播放部分色彩：使用歌曲封面 Material 取色的背景色
    val targetUnplayedBgColor = remember(coverScheme, isDarkTheme) {
        if (isDarkTheme) {
            coverScheme.surfaceContainerHigh
        } else {
            coverScheme.surfaceContainerHigh
        }
    }

    // 已播放部分色彩：使用歌曲封面的 Material 取色结果，并与未播放背景色拉开清晰层次
    val targetPlayedFillColor = remember(coverScheme, targetUnplayedBgColor, isDarkTheme) {
        val rawFill = if (isDarkTheme) {
            blend(coverScheme.primaryContainer, coverScheme.primary, 0.36f)
        } else {
            blend(coverScheme.primaryContainer, coverScheme.primary, 0.32f)
        }
        ensureContrast(
            foreground = rawFill,
            background = targetUnplayedBgColor,
            minRatio = 1.42f,
            preferChroma = true
        )
    }

    // 分别针对已播放端与未播放端背景计算高对比度前景色，允许同一文字/图标被进度条分割为两种颜色
    val targetPlayedPrimaryColor = remember(coverScheme, targetPlayedFillColor, contrastLevel) {
        ensureContrast(
            coverScheme.onPrimaryContainer,
            targetPlayedFillColor,
            maxOf(contrastLevel.minTextContrast, 4.5f),
            preferChroma = false
        )
    }
    val targetPlayedSecondaryColor = remember(coverScheme, targetPlayedFillColor, targetPlayedPrimaryColor, contrastLevel) {
        val softCandidate = blend(targetPlayedPrimaryColor, targetPlayedFillColor, 0.18f)
        ensureContrast(
            softCandidate,
            targetPlayedFillColor,
            maxOf(contrastLevel.minTextContrast * 0.88f, 4.5f),
            preferChroma = false
        )
    }

    val targetUnplayedPrimaryColor = remember(coverScheme, targetUnplayedBgColor, contrastLevel) {
        ensureContrast(
            coverScheme.onSurface,
            targetUnplayedBgColor,
            maxOf(contrastLevel.minTextContrast, 4.5f),
            preferChroma = false
        )
    }
    val targetUnplayedSecondaryColor = remember(coverScheme, targetUnplayedBgColor, contrastLevel) {
        ensureContrast(
            coverScheme.onSurfaceVariant,
            targetUnplayedBgColor,
            maxOf(contrastLevel.minTextContrast * 0.88f, 4.5f),
            preferChroma = false
        )
    }

    val animatedUnplayedColor by animateColorAsState(
        targetValue = targetUnplayedBgColor,
        animationSpec = tween(360),
        label = "MiniPlayerUnplayedColor"
    )
    val animatedPlayedColor by animateColorAsState(
        targetValue = targetPlayedFillColor,
        animationSpec = tween(360),
        label = "MiniPlayerPlayedColor"
    )
    val animatedPlayedPrimaryColor by animateColorAsState(
        targetValue = targetPlayedPrimaryColor,
        animationSpec = tween(360),
        label = "MiniPlayerPlayedPrimaryColor"
    )
    val animatedPlayedSecondaryColor by animateColorAsState(
        targetValue = targetPlayedSecondaryColor,
        animationSpec = tween(360),
        label = "MiniPlayerPlayedSecondaryColor"
    )
    val animatedUnplayedPrimaryColor by animateColorAsState(
        targetValue = targetUnplayedPrimaryColor,
        animationSpec = tween(360),
        label = "MiniPlayerUnplayedPrimaryColor"
    )
    val animatedUnplayedSecondaryColor by animateColorAsState(
        targetValue = targetUnplayedSecondaryColor,
        animationSpec = tween(360),
        label = "MiniPlayerUnplayedSecondaryColor"
    )

    // 仅在 drawBehind 绘制阶段读取 positionMsState.value，避免整个 MainScreen 与 MiniPlayerBar 随播放进度高频重组
    val positionMsState = positionMsFlow.collectAsState()

    // 水平滑动切歌：跟随手指水平偏移，松手超过阈值切歌并回弹
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val swipeThresholdPx = with(density) { 52.dp.toPx() }
    val maxDragPx = with(density) { 140.dp.toPx() }

    val animatedOffsetX by animateFloatAsState(
        targetValue = dragOffsetX,
        animationSpec = if (isDragging) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium
            )
        },
        label = "MiniPlayerSwipeOffset"
    )

    val capsuleShape = RoundedCornerShape(percent = 50)

    // 外圈无任何背景遮罩，仅保留半圆胶囊本体
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffsetX.roundToInt(), 0) }
                .shadow(elevation = 8.dp, shape = capsuleShape, clip = false)
                .clip(capsuleShape)
                .drawBehind {
                    // 未播放部分色彩：封面取色的背景色
                    drawRect(color = animatedUnplayedColor)
                    // 已播放部分色彩：歌曲封面的 Material 取色结果（整条播放栏作为进度条）
                    val p = if (hasMedia && durationMs > 0L) {
                        (positionMsState.value.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    if (p > 0f) {
                        drawRect(
                            color = animatedPlayedColor,
                            size = size.copy(width = size.width * p)
                        )
                    }
                }
                .border(
                    width = 0.5.dp,
                    color = coverScheme.outlineVariant.copy(alpha = 0.45f),
                    shape = capsuleShape
                )
                .pointerInput(hasMedia, onSkipNext, onSkipPrevious) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            isDragging = true
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetX = (dragOffsetX + dragAmount).coerceIn(-maxDragPx, maxDragPx)
                        },
                        onDragEnd = {
                            val finalDrag = dragOffsetX
                            isDragging = false
                            dragOffsetX = 0f
                            if (hasMedia) {
                                if (finalDrag <= -swipeThresholdPx) {
                                    onSkipNext()
                                } else if (finalDrag >= swipeThresholdPx) {
                                    onSkipPrevious()
                                }
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            dragOffsetX = 0f
                        }
                    )
                }
                .clickable(enabled = hasMedia, onClick = onClick)
                .testTag("floating_mini_player_bar")
        ) {
            // 1. 未播放端前景层（承载交互、无障碍语义与 testTag，视觉裁剪至 [splitX, width]）
            MiniPlayerForegroundRow(
                media = media,
                isPlaying = isPlaying,
                hasMedia = hasMedia,
                primaryColor = animatedUnplayedPrimaryColor,
                secondaryColor = animatedUnplayedSecondaryColor,
                onTogglePlayPause = onTogglePlayPause,
                onQueueClick = onQueueClick,
                interactive = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        val splitX = if (hasMedia && durationMs > 0L) {
                            size.width * (positionMsState.value.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        if (splitX < size.width) {
                            clipRect(left = splitX, right = size.width) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    }
            )

            // 2. 已播放端前景层（像素级对齐的镜像层，视觉裁剪至 [0, splitX]，允许单个字符被进度线分割）
            if (hasMedia) {
                MiniPlayerForegroundRow(
                    media = media,
                    isPlaying = isPlaying,
                    hasMedia = hasMedia,
                    primaryColor = animatedPlayedPrimaryColor,
                    secondaryColor = animatedPlayedSecondaryColor,
                    onTogglePlayPause = {},
                    onQueueClick = {},
                    interactive = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics { }
                        .drawWithContent {
                            val splitX = if (durationMs > 0L) {
                                size.width * (positionMsState.value.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            if (splitX > 0f) {
                                clipRect(left = 0f, right = splitX) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                        }
                )
            }

            // 3. 左侧圆形封面（独立单一层，保持原色不被进度裁剪）
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(coverScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (media?.coverUrl != null) {
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
                        tint = coverScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerForegroundRow(
    media: PlayableMedia?,
    isPlaying: Boolean,
    hasMedia: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    onTogglePlayPause: () -> Unit,
    onQueueClick: () -> Unit,
    interactive: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(start = 8.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面占位，保证两侧前景层与独立封面层像素级对齐
        Spacer(modifier = Modifier.size(42.dp))
        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = media?.title ?: "暂未播放音乐",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (hasMedia) primaryColor else secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = media?.artist ?: "左右滑动切换歌曲",
                style = MaterialTheme.typography.bodySmall,
                color = secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .then(
                    if (interactive) {
                        Modifier
                            .clickable(
                                enabled = hasMedia,
                                role = Role.Button,
                                onClick = onTogglePlayPause
                            )
                            .testTag("mini_player_play_pause")
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (interactive) {
                    if (isPlaying) "暂停" else "播放"
                } else {
                    null
                },
                tint = primaryColor,
                modifier = Modifier.size(28.dp)
            )
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .then(
                    if (interactive) {
                        Modifier
                            .clickable(
                                role = Role.Button,
                                onClick = onQueueClick
                            )
                            .testTag("mini_player_queue")
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = if (interactive) "播放队列" else null,
                tint = primaryColor,
                modifier = Modifier.size(25.dp)
            )
        }
    }
}
