package Akari.NCM.player.ui.screen

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.NcmUserPlaylist
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.data.CustomPlaylist
import Akari.NCM.player.data.LyricsManager
import Akari.NCM.player.data.LocalFolderManager
import Akari.NCM.player.data.NcmFileScanner
import Akari.NCM.player.data.PlaylistManager
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.player.AmePlayerEngine
import Akari.NCM.player.ui.component.AddToPlaylistDialog
import Akari.NCM.player.ui.component.SongActionBottomSheet
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
    PLAYER, SETTINGS, MAIN
}

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    playerEngine: AmePlayerEngine
) {
    val currentMedia by playerEngine.currentMedia.collectAsState()
    val isPlaying by playerEngine.isPlaying.collectAsState()
    val playlist by playerEngine.playlist.collectAsState()
    val currentIndex by playerEngine.currentIndex.collectAsState()
    val positionMs by playerEngine.currentPositionMs.collectAsState()
    val durationMs by playerEngine.durationMs.collectAsState()

    var showPlayerScreen by remember { mutableStateOf(false) }
    var showQueueBottomSheet by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var showSyncCloudDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: User Homepage, 1: Playlists
    var selectedPlaylistForDetail by remember { mutableStateOf<CustomPlaylist?>(null) }
    var songToAddToPlaylist by remember { mutableStateOf<PlayableMedia?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var homepageRefreshTrigger by remember { mutableIntStateOf(0) }
    
    // 歌曲操作弹窗状态
    var actionMedia by remember { mutableStateOf<PlayableMedia?>(null) }
    var actionPlaylistContext by remember { mutableStateOf<CustomPlaylist?>(null) }
    var actionRemoveCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 系统返回手势拦截：按优先级响应各层级
    BackHandler(enabled = showSettingsScreen) {
        showSettingsScreen = false
    }

    BackHandler(enabled = showPlayerScreen && !showSettingsScreen) {
        showPlayerScreen = false
    }

    BackHandler(enabled = selectedPlaylistForDetail != null && !showPlayerScreen && !showSettingsScreen) {
        selectedPlaylistForDetail = null
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sessionManager = remember { UserSessionManager(context) }
    val playlistManager = remember { PlaylistManager(context) }
    val ncmApi = remember { NcmApi(sessionManager) }
    val localFolderManager = remember { LocalFolderManager(context, sessionManager, playlistManager, ncmApi) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            localFolderManager.scanAllFolders()
        }
    }
    val customPlaylists by playlistManager.playlists.collectAsState()
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
        else -> TopLevelScreen.MAIN
    }

    AnimatedContent(
        targetState = topLevelScreen,
        transitionSpec = {
            when {
                targetState == TopLevelScreen.PLAYER -> {
                    slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) togetherWith
                    fadeOut(animationSpec = tween(400))
                }
                initialState == TopLevelScreen.PLAYER -> {
                    fadeIn(animationSpec = tween(400)) togetherWith
                    slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400))
                }
                targetState == TopLevelScreen.SETTINGS -> {
                    slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(400)) togetherWith
                    fadeOut(animationSpec = tween(400))
                }
                initialState == TopLevelScreen.SETTINGS -> {
                    fadeIn(animationSpec = tween(400)) togetherWith
                    slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(400))
                }
                else -> {
                    fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                }
            }
        },
        label = "TopLevelTransition",
        modifier = Modifier.fillMaxSize()
    ) { screen ->
        when (screen) {
            TopLevelScreen.PLAYER -> {
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
            TopLevelScreen.SETTINGS -> {
                SettingsScreen(
                    sessionManager = sessionManager,
                    ncmApi = ncmApi,
                    localFolderManager = localFolderManager,
                    onBack = { showSettingsScreen = false }
                )
            }
            TopLevelScreen.MAIN -> {

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
                        title = {},
                        actions = {
                            if (selectedTab == 0) {
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
                            // User Homepage Tab
                            UserHomepageView(
                                ncmApi = ncmApi,
                                sessionManager = sessionManager,
                                refreshTrigger = homepageRefreshTrigger,
                                onPlaylistClick = { playlistId ->
                                    Toast.makeText(context, "正在加载歌单...", Toast.LENGTH_SHORT).show()
                                    scope.launch {
                                        val tracks = ncmApi.getPlaylistTracks(playlistId)
                                        if (tracks.isNotEmpty()) {
                                            playerEngine.loadPlaylist(tracks, 0)
                                            playerEngine.play()
                                            Toast.makeText(context, "开始播放该歌单 (${tracks.size} 首)", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "未获取到歌曲", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onDailyRecommendClick = {
                                    Toast.makeText(context, "正在获取每日推荐...", Toast.LENGTH_SHORT).show()
                                    scope.launch {
                                        val tracks = ncmApi.getDailyRecommendSongs()
                                        if (tracks.isNotEmpty()) {
                                            playerEngine.loadPlaylist(tracks, 0)
                                            playerEngine.play()
                                            Toast.makeText(context, "开始播放每日推荐 (${tracks.size} 首)", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "获取每日推荐失败，请检查是否已登录并有 VIP 权限", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            )
                        }
                        1 -> {
                            // Custom Playlists Tab
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
                                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
                                                if (currentPlaylist.ncmPlaylistId != null) {
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
                                                        contentDescription = "Re-sync",
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(4.dp))
                                            }
                                            if (currentPlaylist.songs.isNotEmpty()) {
                                                FilledTonalButton(
                                                    onClick = {
                                                        val playableList = currentPlaylist.songs.map { dto ->
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
                                                    Text("Play All")
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
                                                "No songs in this playlist yet",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                                            itemsIndexed(currentPlaylist.songs) { index, songDto ->
                                                val media = songDto.toPlayableMedia()
                                                val localMatch = if (media is PlayableMedia.Online) playlistManager.findLocalMatch(media) else null
                                                val isLocalMatch = localMatch != null
                                                SongItem(
                                                    media = media,
                                                    isCurrentTrack = currentMedia?.id == media.id,
                                                    isPlaying = isPlaying && currentMedia?.id == media.id,
                                                    isLocalMatch = isLocalMatch,
                                                    onClick = {
                                                        val playableList = currentPlaylist.songs.map { dto ->
                                                            val m = dto.toPlayableMedia()
                                                            if (m is PlayableMedia.Online) {
                                                                playlistManager.findLocalMatch(m) ?: m
                                                            } else m
                                                        }
                                                        playerEngine.loadPlaylist(playableList, index)
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
                            SongItem(
                                media = media,
                                isCurrentTrack = index == currentIndex,
                                isPlaying = isPlaying && index == currentIndex,
                                onClick = {
                                    playerEngine.loadPlaylist(playlist, index)
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
                                    if (playlist.ncmPlaylistId != null) {
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
            Icons.Rounded.MusicNote,
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
    onClick: () -> Unit,
    onActionClick: (() -> Unit)? = null
) {
    ListItem(
        headlineContent = {
            Text(
                media.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrentTrack) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrentTrack) FontWeight.Bold else FontWeight.Normal
            )
        },
        supportingContent = {
            Text(
                "${media.artist}${if (media.album.isNotBlank()) "  ·  ${media.album}" else ""}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
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
                            contentDescription = "Local Match",
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
                            contentDescription = "Playing",
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
                            contentDescription = "Paused",
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
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
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
                        contentDescription = "Previous",
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp)
                    )
                }

                IconButton(onClick = onSkipNext) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(onClick = onQueueClick) {
                    Icon(
                        Icons.AutoMirrored.Rounded.QueueMusic,
                        contentDescription = "Queue",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}
