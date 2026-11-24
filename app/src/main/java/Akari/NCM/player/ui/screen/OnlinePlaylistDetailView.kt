package Akari.NCM.player.ui.screen

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.NcmAlbum
import Akari.NCM.player.core.OnlinePlaylistTarget
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.PlaylistSourceType
import Akari.NCM.player.data.CustomPlaylist
import Akari.NCM.player.data.PlaylistManager
import Akari.NCM.player.player.AmePlayerEngine
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnlinePlaylistDetailView(
    target: OnlinePlaylistTarget,
    ncmApi: NcmApi,
    playlistManager: PlaylistManager,
    playerEngine: AmePlayerEngine,
    currentMedia: PlayableMedia?,
    isPlaying: Boolean,
    showMatchStatusPreference: Boolean,
    onBack: () -> Unit,
    onSongAction: (media: PlayableMedia, playlistContext: CustomPlaylist?) -> Unit,
    onOpenPlaylistTarget: ((OnlinePlaylistTarget) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val targetKey = "${target.id}_${target.title}_${target.isDailyRecommend}_${target.isArtist}_${target.isAlbum}"
    val listState = rememberSaveable(targetKey, saver = LazyListState.Saver) {
        LazyListState()
    }
    val albumsListState = rememberSaveable(targetKey, "albums", saver = LazyListState.Saver) {
        LazyListState()
    }

    var resolvedId by remember(targetKey) { mutableLongStateOf(target.id) }
    var tracks by remember(targetKey) { mutableStateOf<List<PlayableMedia.Online>?>(null) }
    var playlistMeta by remember(targetKey) { mutableStateOf(target) }
    var isLoading by remember(targetKey) { mutableStateOf(true) }
    var errorMessage by remember(targetKey) { mutableStateOf<String?>(null) }
    var matchFilterMode by remember { mutableIntStateOf(0) } // 0: 全部, 1: 已匹配, 2: 未匹配
    var retryTrigger by remember { mutableIntStateOf(0) }
    var inPlaylistSearchQuery by remember(targetKey) { mutableStateOf("") }
    var isPlaylistSearchActive by remember(targetKey) { mutableStateOf(false) }

    // 歌手详情页状态
    var artistTab by rememberSaveable(targetKey) { mutableIntStateOf(0) } // 0: 单曲, 1: 专辑
    var artistSongOffset by remember(targetKey) { mutableIntStateOf(0) }
    var hasMoreArtistSongs by remember(targetKey) { mutableStateOf(false) }
    var isLoadingMoreSongs by remember(targetKey) { mutableStateOf(false) }

    var artistAlbums by remember(targetKey) { mutableStateOf<List<NcmAlbum>>(emptyList()) }
    var isAlbumsLoading by remember(targetKey) { mutableStateOf(false) }
    var albumsError by remember(targetKey) { mutableStateOf<String?>(null) }
    var artistAlbumOffset by remember(targetKey) { mutableIntStateOf(0) }
    var hasMoreArtistAlbums by remember(targetKey) { mutableStateOf(false) }
    var isLoadingMoreAlbums by remember(targetKey) { mutableStateOf(false) }
    var albumsLoadedOnce by remember(targetKey) { mutableStateOf(false) }
    var albumRetryTrigger by remember(targetKey) { mutableIntStateOf(0) }

    val customPlaylists by playlistManager.playlists.collectAsState()
    val isCollected = remember(resolvedId, target.isDailyRecommend, target.isArtist, customPlaylists) {
        if (target.isDailyRecommend || target.isArtist || resolvedId <= 0L) false
        else customPlaylists.any { it.ncmPlaylistId == resolvedId }
    }

    LaunchedEffect(targetKey, retryTrigger) {
        isLoading = true
        errorMessage = null
        try {
            var activeId = target.id
            if (activeId <= 0L && !target.isDailyRecommend) {
                if (target.isArtist) {
                    val keyword = target.creatorName.ifBlank { target.title.removeSuffix(" - 热门曲目").trim() }
                    val foundArtists = ncmApi.searchArtists(keyword, limit = 10, offset = 0)
                    val matched = foundArtists.firstOrNull { it.name.equals(keyword, ignoreCase = true) }
                        ?: foundArtists.firstOrNull()
                    if (matched != null) {
                        activeId = matched.id
                        resolvedId = matched.id
                        playlistMeta = playlistMeta.copy(
                            id = matched.id,
                            coverImgUrl = if (playlistMeta.coverImgUrl.isBlank()) matched.picUrl ?: "" else playlistMeta.coverImgUrl,
                            creatorName = if (playlistMeta.creatorName.isBlank()) matched.name else playlistMeta.creatorName
                        )
                    } else {
                        errorMessage = "未找到歌手“$keyword”"
                    }
                } else if (target.isAlbum) {
                    val keyword = target.title.ifBlank { target.creatorName }
                    val foundAlbums = ncmApi.searchAlbums(keyword, limit = 10, offset = 0)
                    val matched = foundAlbums.firstOrNull {
                        it.name.equals(target.title, ignoreCase = true) &&
                            (target.creatorName.isBlank() || it.artistName.contains(target.creatorName, ignoreCase = true))
                    } ?: foundAlbums.firstOrNull { it.name.equals(target.title, ignoreCase = true) }
                        ?: foundAlbums.firstOrNull()
                    if (matched != null) {
                        activeId = matched.id
                        resolvedId = matched.id
                        playlistMeta = playlistMeta.copy(
                            id = matched.id,
                            coverImgUrl = if (playlistMeta.coverImgUrl.isBlank()) matched.picUrl ?: "" else playlistMeta.coverImgUrl,
                            creatorName = if (playlistMeta.creatorName.isBlank()) matched.artistName else playlistMeta.creatorName
                        )
                    } else {
                        errorMessage = "未找到专辑“$keyword”"
                    }
                }
            }

            if (errorMessage == null) {
                if (target.isDailyRecommend) {
                    val songs = ncmApi.getDailyRecommendSongs()
                    if (songs.isNotEmpty()) {
                        tracks = songs
                        playlistMeta = playlistMeta.copy(
                            trackCount = songs.size,
                            coverImgUrl = if (playlistMeta.coverImgUrl.isBlank()) songs.first().coverUrl ?: "" else playlistMeta.coverImgUrl
                        )
                    } else {
                        errorMessage = "暂无每日推荐歌曲，请检查是否已登录网易云账号"
                    }
                } else if (target.isAlbum) {
                    val albumSongs = ncmApi.getAlbumTracks(activeId)
                    if (albumSongs.isNotEmpty()) {
                        tracks = albumSongs
                        playlistMeta = playlistMeta.copy(
                            trackCount = albumSongs.size,
                            coverImgUrl = if (playlistMeta.coverImgUrl.isBlank()) albumSongs.first().coverUrl ?: "" else playlistMeta.coverImgUrl
                        )
                    } else {
                        errorMessage = "未获取到专辑曲目，请检查网络或稍后重试"
                    }
                } else if (target.isArtist) {
                    val (artistSongs, hasMore) = ncmApi.getArtistSongs(activeId, limit = 50, offset = 0)
                    val finalSongs = if (artistSongs.isNotEmpty()) artistSongs else ncmApi.getArtistTopSongs(activeId)
                    if (finalSongs.isNotEmpty()) {
                        tracks = finalSongs
                        artistSongOffset = finalSongs.size
                        hasMoreArtistSongs = artistSongs.isNotEmpty() && hasMore
                        playlistMeta = playlistMeta.copy(
                            trackCount = maxOf(playlistMeta.trackCount, finalSongs.size),
                            coverImgUrl = if (playlistMeta.coverImgUrl.isBlank()) finalSongs.first().coverUrl ?: "" else playlistMeta.coverImgUrl
                        )
                    } else {
                        errorMessage = "未获取到歌手曲目，请检查网络或稍后重试"
                    }
                } else {
                    val result = ncmApi.getPlaylistDetailAndTracks(activeId, target.trackCount)
                    if (result.tracks.isNotEmpty()) {
                        tracks = result.tracks
                        val p = result.playlist
                        if (p != null) {
                            playlistMeta = playlistMeta.copy(
                                title = if (playlistMeta.title.isBlank()) p.name else playlistMeta.title,
                                coverImgUrl = if (playlistMeta.coverImgUrl.isBlank()) p.coverImgUrl else playlistMeta.coverImgUrl,
                                creatorName = if (playlistMeta.creatorName.isBlank()) p.creatorName else playlistMeta.creatorName,
                                description = if (playlistMeta.description.isBlank()) p.description else playlistMeta.description,
                                trackCount = if (p.trackCount > 0) p.trackCount else result.tracks.size,
                                playCount = if (p.playCount > 0) p.playCount else playlistMeta.playCount
                            )
                        } else {
                            playlistMeta = playlistMeta.copy(trackCount = result.tracks.size)
                        }
                    } else {
                        errorMessage = "未获取到歌单曲目，请检查网络或稍后重试"
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            errorMessage = "加载失败: ${e.localizedMessage ?: "未知错误"}"
        } finally {
            isLoading = false
        }
    }

    // 歌手专辑列表按需加载
    LaunchedEffect(target.isArtist, artistTab, resolvedId, albumRetryTrigger) {
        if (target.isArtist && artistTab == 1 && resolvedId > 0L && (!albumsLoadedOnce || albumRetryTrigger > 0)) {
            isAlbumsLoading = true
            albumsError = null
            try {
                val (albums, hasMore) = ncmApi.getArtistAlbums(resolvedId, limit = 30, offset = 0)
                artistAlbums = albums
                artistAlbumOffset = albums.size
                hasMoreArtistAlbums = hasMore
                albumsLoadedOnce = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                albumsError = "加载专辑失败: ${e.localizedMessage ?: "未知错误"}"
            } finally {
                isAlbumsLoading = false
            }
        }
    }

    // 歌手单曲触底分页
    if (target.isArtist && artistTab == 0) {
        val shouldLoadMoreSongs by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                totalItems > 0 && lastVisibleItem >= totalItems - 3
            }
        }
        LaunchedEffect(shouldLoadMoreSongs) {
            if (shouldLoadMoreSongs && !isLoading && !isLoadingMoreSongs && hasMoreArtistSongs &&
                inPlaylistSearchQuery.isBlank() && matchFilterMode == 0 && resolvedId > 0L
            ) {
                isLoadingMoreSongs = true
                try {
                    val (moreSongs, hasMore) = ncmApi.getArtistSongs(resolvedId, limit = 50, offset = artistSongOffset)
                    val existing = tracks ?: emptyList()
                    val existingIds = existing.mapTo(HashSet()) { it.id }
                    val uniqueMore = moreSongs.filter { it.id !in existingIds }
                    tracks = existing + uniqueMore
                    artistSongOffset += moreSongs.size
                    hasMoreArtistSongs = hasMore && moreSongs.isNotEmpty()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                } finally {
                    isLoadingMoreSongs = false
                }
            }
        }
    }

    // 歌手专辑触底分页
    if (target.isArtist && artistTab == 1) {
        val shouldLoadMoreAlbums by remember {
            derivedStateOf {
                val layoutInfo = albumsListState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                totalItems > 0 && lastVisibleItem >= totalItems - 3
            }
        }
        LaunchedEffect(shouldLoadMoreAlbums) {
            if (shouldLoadMoreAlbums && !isAlbumsLoading && !isLoadingMoreAlbums && hasMoreArtistAlbums && resolvedId > 0L) {
                isLoadingMoreAlbums = true
                try {
                    val (moreAlbums, hasMore) = ncmApi.getArtistAlbums(resolvedId, limit = 30, offset = artistAlbumOffset)
                    val existingIds = artistAlbums.mapTo(HashSet()) { it.id }
                    val uniqueMore = moreAlbums.filter { it.id !in existingIds }
                    artistAlbums = artistAlbums + uniqueMore
                    artistAlbumOffset += moreAlbums.size
                    hasMoreArtistAlbums = hasMore && moreAlbums.isNotEmpty()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                } finally {
                    isLoadingMoreAlbums = false
                }
            }
        }
    }

    val currentTracks = tracks ?: emptyList()
    val searchFilteredTracks = remember(currentTracks, inPlaylistSearchQuery) {
        if (inPlaylistSearchQuery.isBlank()) {
            currentTracks
        } else {
            val q = inPlaylistSearchQuery.trim().lowercase()
            currentTracks.filter { track ->
                track.title.lowercase().contains(q) ||
                    track.artist.lowercase().contains(q) ||
                    track.album.lowercase().contains(q)
            }
        }
    }

    val songMatchTriples = remember(searchFilteredTracks, customPlaylists) {
        searchFilteredTracks.mapIndexed { index, onlineMedia ->
            val localMatch = playlistManager.findLocalMatch(onlineMedia)
            Triple(index, onlineMedia, localMatch != null)
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

    val currentPlaylistSourceType by playerEngine.playlistSourceType.collectAsState()
    val isCurrentFromOnline = currentPlaylistSourceType == PlaylistSourceType.ONLINE
    val isCurrentMediaInThisOnlinePlaylist = remember(currentTracks, currentMedia, isCurrentFromOnline) {
        isCurrentFromOnline && currentMedia != null && currentTracks.any { it.id == currentMedia.id }
    }

    val renderTopBar: @Composable () -> Unit = {
        OnlinePlaylistTopBar(
            target = target,
            playlistMeta = playlistMeta,
            resolvedId = resolvedId,
            currentTracks = currentTracks,
            isCollected = isCollected,
            showSearchToggle = (!target.isArtist || artistTab == 0) && currentTracks.isNotEmpty(),
            isPlaylistSearchActive = isPlaylistSearchActive,
            onToggleSearch = {
                isPlaylistSearchActive = !isPlaylistSearchActive
                if (!isPlaylistSearchActive) inPlaylistSearchQuery = ""
            },
            onCollectClick = {
                if (isCollected) {
                    Toast.makeText(context, "已在我的歌单中", Toast.LENGTH_SHORT).show()
                } else {
                    playlistManager.saveOrUpdateSyncedPlaylist(
                        ncmPlaylistId = resolvedId,
                        name = playlistMeta.title.ifBlank { "在线歌单" },
                        coverImgUrl = playlistMeta.coverImgUrl,
                        songs = currentTracks
                    )
                    Toast.makeText(context, "已收藏至我的歌单", Toast.LENGTH_SHORT).show()
                }
            },
            onBack = onBack
        )
    }

    val renderHeaderCard: @Composable () -> Unit = {
        OnlinePlaylistHeaderCard(
            target = target,
            playlistMeta = playlistMeta,
            loadedTrackCount = currentTracks.size
        )
    }

    val renderArtistTabs: @Composable () -> Unit = {
        if (target.isArtist) {
            PrimaryTabRow(
                selectedTabIndex = artistTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Tab(
                    selected = artistTab == 0,
                    onClick = { artistTab = 0 },
                    text = {
                        Text(
                            "单曲",
                            fontWeight = if (artistTab == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier.testTag("artist_tab_songs")
                )
                Tab(
                    selected = artistTab == 1,
                    onClick = { artistTab = 1 },
                    text = {
                        Text(
                            "专辑",
                            fontWeight = if (artistTab == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    modifier = Modifier.testTag("artist_tab_albums")
                )
            }
        }
    }

    val renderInPlaylistSearch: @Composable () -> Unit = {
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
                        "在歌单内搜索歌曲/歌手/专辑...",
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
    }

    val headerItemCount = if (target.isArtist) 5 else 4
    val renderActionRow: @Composable () -> Unit = {
        OnlinePlaylistActionRow(
            searchFilteredTracks = searchFilteredTracks,
            inPlaylistSearchQuery = inPlaylistSearchQuery,
            showLocateButton = isCurrentMediaInThisOnlinePlaylist,
            showMatchStatusPreference = showMatchStatusPreference && currentTracks.isNotEmpty(),
            matchFilterMode = matchFilterMode,
            matchedCount = matchedCount,
            unmatchedCount = unmatchedCount,
            onPlayAll = {
                if (searchFilteredTracks.isNotEmpty()) {
                    val playableList = searchFilteredTracks.map { onlineMedia ->
                        playlistManager.findLocalMatch(onlineMedia) ?: onlineMedia
                    }
                    playerEngine.loadPlaylist(playableList, 0, PlaylistSourceType.ONLINE)
                    playerEngine.play()
                }
            },
            onLocate = {
                scope.launch {
                    if (inPlaylistSearchQuery.isNotBlank() && displayedSongs.none { it.second.id == currentMedia?.id }) {
                        inPlaylistSearchQuery = ""
                    }
                    if (matchFilterMode != 0 && displayedSongs.none { it.second.id == currentMedia?.id }) {
                        matchFilterMode = 0
                    }
                    val targetIdx = displayedSongs.indexOfFirst { it.second.id == currentMedia?.id }
                    if (targetIdx >= 0) {
                        listState.animateScrollToItem(headerItemCount + targetIdx)
                    }
                }
            },
            onFilterModeChange = { matchFilterMode = it }
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 840.dp)
        ) {
        if (target.isArtist && artistTab == 1) {
            // 歌手专辑标签页
            if (isAlbumsLoading || albumsError != null || artistAlbums.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    renderTopBar()
                    renderHeaderCard()
                    renderArtistTabs()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isAlbumsLoading) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "正在获取歌手专辑...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else if (albumsError != null) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Rounded.CloudOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = albumsError ?: "加载失败",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                OutlinedButton(onClick = { albumRetryTrigger++ }) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("重试")
                                }
                            }
                        } else {
                            Text(
                                text = "暂无歌手专辑",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = albumsListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    item(key = "top_bar") { renderTopBar() }
                    item(key = "header_card") { renderHeaderCard() }
                    item(key = "artist_tabs") { renderArtistTabs() }
                    items(artistAlbums, key = { "artist_album_${it.id}" }) { album ->
                        ArtistAlbumRowItem(
                            album = album,
                            onClick = {
                                onOpenPlaylistTarget?.invoke(
                                    OnlinePlaylistTarget(
                                        id = album.id,
                                        title = album.name,
                                        coverImgUrl = album.picUrl ?: "",
                                        trackCount = album.songCount,
                                        creatorName = album.artistName,
                                        description = "专辑 · ${album.artistName}",
                                        isAlbum = true
                                    )
                                )
                            }
                        )
                    }
                    item(key = "albums_footer") {
                        ArtistPaginationFooter(
                            isLoadingMore = isLoadingMoreAlbums,
                            hasMore = hasMoreArtistAlbums
                        )
                    }
                }
            }
        } else {
            // 歌曲列表（歌单 / 专辑 / 每日推荐 / 歌手单曲）
            if (isLoading || errorMessage != null || displayedSongs.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    renderTopBar()
                    renderHeaderCard()
                    if (target.isArtist) {
                        renderArtistTabs()
                    }
                    if (!isLoading && errorMessage == null) {
                        renderInPlaylistSearch()
                        renderActionRow()
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = when {
                                        target.isDailyRecommend -> "正在获取每日推荐歌曲..."
                                        target.isArtist -> "正在获取歌手曲目..."
                                        target.isAlbum -> "正在获取专辑曲目..."
                                        else -> "正在获取歌单曲目..."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else if (errorMessage != null) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Rounded.CloudOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = errorMessage ?: "加载失败",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                OutlinedButton(onClick = { retryTrigger++ }) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("重试")
                                }
                            }
                        } else {
                            Text(
                                text = if (inPlaylistSearchQuery.isNotBlank()) "未在歌单内找到匹配歌曲"
                                else if (matchFilterMode == 1) "暂无已匹配的歌曲"
                                else if (matchFilterMode == 2) "暂无未匹配的歌曲"
                                else "歌单内暂无曲目",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    item(key = "top_bar") { renderTopBar() }
                    item(key = "header_card") { renderHeaderCard() }
                    if (target.isArtist) {
                        item(key = "artist_tabs") { renderArtistTabs() }
                    }
                    item(key = "in_playlist_search") { renderInPlaylistSearch() }
                    item(key = "action_row") { renderActionRow() }
                    items(displayedSongs, key = { "${it.first}_${it.second.id}" }) { (_, onlineMedia, isMatched) ->
                        SongItem(
                            media = onlineMedia,
                            isCurrentTrack = currentMedia?.id == onlineMedia.id,
                            isPlaying = isPlaying && currentMedia?.id == onlineMedia.id,
                            isLocalMatch = isMatched,
                            onClick = {
                                val songsSource = if (inPlaylistSearchQuery.isNotBlank() || matchFilterMode == 0) {
                                    currentTracks
                                } else {
                                    displayedSongs.map { it.second }
                                }
                                val playableList = songsSource.map { m ->
                                    playlistManager.findLocalMatch(m) ?: m
                                }
                                val targetIndex = playableList.indexOfFirst { it.id == onlineMedia.id }.coerceAtLeast(0)
                                playerEngine.loadPlaylist(playableList, targetIndex, PlaylistSourceType.ONLINE)
                                playerEngine.play()
                            },
                            onActionClick = {
                                onSongAction(onlineMedia, null)
                            }
                        )
                    }
                    if (target.isArtist) {
                        item(key = "songs_footer") {
                            ArtistPaginationFooter(
                                isLoadingMore = isLoadingMoreSongs,
                                hasMore = hasMoreArtistSongs
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
private fun OnlinePlaylistTopBar(
    target: OnlinePlaylistTarget,
    playlistMeta: OnlinePlaylistTarget,
    resolvedId: Long,
    currentTracks: List<PlayableMedia.Online>,
    isCollected: Boolean,
    showSearchToggle: Boolean,
    isPlaylistSearchActive: Boolean,
    onToggleSearch: () -> Unit,
    onCollectClick: () -> Unit,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = when {
                target.isDailyRecommend -> "每日推荐"
                target.isAlbum -> "专辑详情"
                target.isArtist -> "歌手详情"
                else -> "歌单详情"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        )

        if (showSearchToggle) {
            IconButton(onClick = onToggleSearch) {
                Icon(
                    if (isPlaylistSearchActive) Icons.Rounded.SearchOff else Icons.Rounded.Search,
                    contentDescription = "歌单内搜索",
                    tint = if (isPlaylistSearchActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (!target.isDailyRecommend && !target.isArtist && resolvedId > 0L && currentTracks.isNotEmpty()) {
            if (isCollected) {
                FilledTonalButton(
                    onClick = onCollectClick,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 36.dp)
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("已收藏", fontSize = 13.sp)
                }
            } else {
                Button(
                    onClick = onCollectClick,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 36.dp)
                ) {
                    Icon(
                        Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (target.isAlbum) "收藏专辑" else "收藏歌单", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun OnlinePlaylistHeaderCard(
    target: OnlinePlaylistTarget,
    playlistMeta: OnlinePlaylistTarget,
    loadedTrackCount: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(108.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (playlistMeta.coverImgUrl.isNotBlank()) {
                    AsyncImage(
                        model = playlistMeta.coverImgUrl,
                        contentDescription = playlistMeta.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = Akari.NCM.player.R.drawable.ic_ame_status),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                if (playlistMeta.playCount > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Text(
                            text = "▶ ${formatPlayCount(playlistMeta.playCount)}",
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp)
            ) {
                Text(
                    text = playlistMeta.title.ifBlank {
                        when {
                            target.isDailyRecommend -> "每日推荐"
                            target.isAlbum -> "专辑详情"
                            target.isArtist -> "歌手详情"
                            else -> "歌单详情"
                        }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (playlistMeta.creatorName.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = playlistMeta.creatorName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (playlistMeta.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = playlistMeta.description.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                val displayCount = if (loadedTrackCount > 0) loadedTrackCount else playlistMeta.trackCount
                Text(
                    text = "$displayCount 首歌曲",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun OnlinePlaylistActionRow(
    searchFilteredTracks: List<PlayableMedia.Online>,
    inPlaylistSearchQuery: String,
    showLocateButton: Boolean,
    showMatchStatusPreference: Boolean,
    matchFilterMode: Int,
    matchedCount: Int,
    unmatchedCount: Int,
    onPlayAll: () -> Unit,
    onLocate: () -> Unit,
    onFilterModeChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = onPlayAll,
            enabled = searchFilteredTracks.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (searchFilteredTracks.isNotEmpty()) {
                    if (inPlaylistSearchQuery.isNotBlank()) "播放筛选 (${searchFilteredTracks.size})"
                    else "全部播放 (${searchFilteredTracks.size})"
                } else "全部播放"
            )
        }

        if (showLocateButton) {
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onLocate,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("btn_locate_online_track")
            ) {
                Icon(
                    imageVector = Icons.Rounded.MyLocation,
                    contentDescription = "定位正在播放的音乐",
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
                    onClick = { onFilterModeChange(0) },
                    label = { Text("全部", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = matchFilterMode == 1,
                    onClick = { onFilterModeChange(1) },
                    label = { Text("已匹配 ($matchedCount)", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = matchFilterMode == 2,
                    onClick = { onFilterModeChange(2) },
                    label = { Text("未匹配 ($unmatchedCount)", fontSize = 12.sp) }
                )
            }
        }
    }
}

@Composable
private fun ArtistAlbumRowItem(
    album: NcmAlbum,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!album.picUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = album.picUrl,
                        contentDescription = album.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Rounded.Album,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                val displayName = buildString {
                    append(album.name)
                    val sub = album.transNames.firstOrNull { it.isNotBlank() } ?: album.alias.firstOrNull { it.isNotBlank() }
                    if (!sub.isNullOrBlank() && !album.name.contains(sub)) {
                        append(" (").append(sub).append(")")
                    }
                }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${album.artistName}${if (album.songCount > 0) " · ${album.songCount}首" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ArtistPaginationFooter(isLoadingMore: Boolean, hasMore: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isLoadingMore) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    "正在加载更多...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (!hasMore) {
            Text(
                "已加载全部内容",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

private fun formatPlayCount(count: Long): String {
    return when {
        count >= 100_000_000 -> String.format("%.1f亿", count / 100_000_000.0)
        count >= 10_000 -> String.format("%.1f万", count / 10_000.0)
        count > 0 -> count.toString()
        else -> ""
    }
}
