package Akari.NCM.player.ui.screen

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.NcmAlbum
import Akari.NCM.player.core.NcmArtist
import Akari.NCM.player.core.NcmMixedSearchResult
import Akari.NCM.player.core.OnlinePlaylistTarget
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.PlayerController
import Akari.NCM.player.core.PlayerState
import Akari.NCM.player.data.CustomPlaylist
import Akari.NCM.player.data.PlaylistManager
import Akari.NCM.player.data.SearchHistoryManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索类别定义：
 * 默认 MIXED (综合/混合模式)，其他为单类别分页非限制模式。
 */
enum class SearchCategory(val label: String) {
    MIXED("综合"),
    SONGS("单曲"),
    ALBUMS("专辑"),
    ARTISTS("歌手"),
    LYRICS("歌词")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    playerState: PlayerState,
    playerController: PlayerController,
    ncmApi: NcmApi,
    playlistManager: PlaylistManager,
    searchHistoryManager: SearchHistoryManager,
    initialQuery: String = "",
    onBack: () -> Unit,
    onSongActionClick: (PlayableMedia, CustomPlaylist?) -> Unit,
    onOpenPlaylistTarget: ((OnlinePlaylistTarget) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var searchQuery by remember { mutableStateOf(initialQuery) }
    var selectedCategory by remember { mutableStateOf(SearchCategory.MIXED) }

    // 各模式结果状态
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var hasSearched by remember { mutableStateOf(false) }

    // 混合模式结果 (单曲<=10, 专辑<=5, 歌手按名称匹配)
    var mixedResult by remember { mutableStateOf(NcmMixedSearchResult()) }

    // 非混合模式分页状态
    var categorySongs by remember { mutableStateOf<List<PlayableMedia.Online>>(emptyList()) }
    var categoryAlbums by remember { mutableStateOf<List<NcmAlbum>>(emptyList()) }
    var categoryArtists by remember { mutableStateOf<List<NcmArtist>>(emptyList()) }
    var categoryLyrics by remember { mutableStateOf<List<PlayableMedia.Online>>(emptyList()) }

    var songOffset by remember { mutableIntStateOf(0) }
    var albumOffset by remember { mutableIntStateOf(0) }
    var artistOffset by remember { mutableIntStateOf(0) }
    var lyricOffset by remember { mutableIntStateOf(0) }

    var hasMoreSongs by remember { mutableStateOf(true) }
    var hasMoreAlbums by remember { mutableStateOf(true) }
    var hasMoreArtists by remember { mutableStateOf(true) }
    var hasMoreLyrics by remember { mutableStateOf(true) }

    var isLoadingMore by remember { mutableStateOf(false) }

    val currentMedia by playerState.currentMedia.collectAsState()
    val isPlaying by playerState.isPlaying.collectAsState()
    val searchHistory by searchHistoryManager.history.collectAsState()

    val listState = rememberLazyListState()

    // 执行当前模式下的搜索
    val executeSearch: (String, SearchCategory, Boolean) -> Unit = { query, category, isLoadMore ->
        val trimmed = query.trim()
        if (trimmed.isNotBlank()) {
            if (!isLoadMore) {
                keyboardController?.hide()
                searchHistoryManager.addSearchQuery(trimmed)
                isSearching = true
                hasSearched = true
                searchError = null
            } else {
                isLoadingMore = true
            }

            scope.launch {
                try {
                    when (category) {
                        SearchCategory.MIXED -> {
                            val result = withContext(Dispatchers.IO) {
                                ncmApi.searchMixed(trimmed)
                            }
                            mixedResult = result
                        }
                        SearchCategory.SONGS -> {
                            val currentOff = if (isLoadMore) songOffset else 0
                            val list = withContext(Dispatchers.IO) {
                                ncmApi.searchSongs(trimmed, limit = 30, offset = currentOff)
                            }
                            if (isLoadMore) {
                                categorySongs = categorySongs + list
                            } else {
                                categorySongs = list
                            }
                            songOffset = currentOff + list.size
                            hasMoreSongs = list.size >= 30
                        }
                        SearchCategory.ALBUMS -> {
                            val currentOff = if (isLoadMore) albumOffset else 0
                            val list = withContext(Dispatchers.IO) {
                                ncmApi.searchAlbums(trimmed, limit = 30, offset = currentOff)
                            }
                            if (isLoadMore) {
                                categoryAlbums = categoryAlbums + list
                            } else {
                                categoryAlbums = list
                            }
                            albumOffset = currentOff + list.size
                            hasMoreAlbums = list.size >= 30
                        }
                        SearchCategory.ARTISTS -> {
                            val currentOff = if (isLoadMore) artistOffset else 0
                            val list = withContext(Dispatchers.IO) {
                                ncmApi.searchArtists(trimmed, limit = 30, offset = currentOff)
                            }
                            if (isLoadMore) {
                                categoryArtists = categoryArtists + list
                            } else {
                                categoryArtists = list
                            }
                            artistOffset = currentOff + list.size
                            hasMoreArtists = list.size >= 30
                        }
                        SearchCategory.LYRICS -> {
                            val currentOff = if (isLoadMore) lyricOffset else 0
                            val list = withContext(Dispatchers.IO) {
                                ncmApi.searchLyrics(trimmed, limit = 30, offset = currentOff)
                            }
                            if (isLoadMore) {
                                categoryLyrics = categoryLyrics + list
                            } else {
                                categoryLyrics = list
                            }
                            lyricOffset = currentOff + list.size
                            hasMoreLyrics = list.size >= 30
                        }
                    }
                } catch (e: Exception) {
                    if (!isLoadMore) {
                        searchError = e.message ?: "搜索失败，请检查网络"
                    }
                } finally {
                    isSearching = false
                    isLoadingMore = false
                }
            }
        }
    }

    // 分类切换时触发重新搜索
    LaunchedEffect(selectedCategory) {
        if (searchQuery.isNotBlank() && hasSearched) {
            when (selectedCategory) {
                SearchCategory.MIXED -> if (mixedResult.songs.isEmpty() && mixedResult.albums.isEmpty()) executeSearch(searchQuery, selectedCategory, false)
                SearchCategory.SONGS -> if (categorySongs.isEmpty()) executeSearch(searchQuery, selectedCategory, false)
                SearchCategory.ALBUMS -> if (categoryAlbums.isEmpty()) executeSearch(searchQuery, selectedCategory, false)
                SearchCategory.ARTISTS -> if (categoryArtists.isEmpty()) executeSearch(searchQuery, selectedCategory, false)
                SearchCategory.LYRICS -> if (categoryLyrics.isEmpty()) executeSearch(searchQuery, selectedCategory, false)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (initialQuery.isNotBlank()) {
            executeSearch(initialQuery, SearchCategory.MIXED, false)
        } else {
            runCatching { focusRequester.requestFocus() }
        }
    }

    // 非混合模式滑动到底部自动触底加载更多
    if (selectedCategory != SearchCategory.MIXED) {
        val shouldLoadMore by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                totalItems > 0 && lastVisibleItem >= totalItems - 3
            }
        }

        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore && !isLoadingMore && !isSearching) {
                val hasMore = when (selectedCategory) {
                    SearchCategory.SONGS -> hasMoreSongs
                    SearchCategory.ALBUMS -> hasMoreAlbums
                    SearchCategory.ARTISTS -> hasMoreArtists
                    SearchCategory.LYRICS -> hasMoreLyrics
                    else -> false
                }
                if (hasMore && searchQuery.isNotBlank()) {
                    executeSearch(searchQuery, selectedCategory, true)
                }
            }
        }
    }

    // 点击专辑打开曲目详情
    val handleAlbumClick: (NcmAlbum) -> Unit = { album ->
        scope.launch {
            Toast.makeText(context, "正在获取专辑《${album.name}》...", Toast.LENGTH_SHORT).show()
            try {
                val target = OnlinePlaylistTarget(
                    id = album.id,
                    title = album.name,
                    coverImgUrl = album.picUrl ?: "",
                    trackCount = album.songCount,
                    creatorName = album.artistName,
                    description = "专辑 · ${album.artistName}",
                    isAlbum = true
                )
                onOpenPlaylistTarget?.invoke(target)
            } catch (e: Exception) {
                Toast.makeText(context, "无法获取专辑信息: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 点击歌手打开热门歌曲
    val handleArtistClick: (NcmArtist) -> Unit = { artist ->
        scope.launch {
            Toast.makeText(context, "正在获取歌手《${artist.name}》热门曲目...", Toast.LENGTH_SHORT).show()
            try {
                val target = OnlinePlaylistTarget(
                    id = artist.id,
                    title = "${artist.name} - 热门曲目",
                    coverImgUrl = artist.picUrl ?: "",
                    trackCount = artist.songCount,
                    creatorName = artist.name,
                    description = "歌手精选 · ${artist.name}",
                    isAlbum = false,
                    isArtist = true
                )
                onOpenPlaylistTarget?.invoke(target)
            } catch (e: Exception) {
                Toast.makeText(context, "无法获取歌手歌曲: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
            ) {
                // Top Search Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        placeholder = {
                            Text(
                                "搜索歌曲、专辑、歌手、歌词...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    hasSearched = false
                                    mixedResult = NcmMixedSearchResult()
                                    categorySongs = emptyList()
                                    categoryAlbums = emptyList()
                                    categoryArtists = emptyList()
                                    categoryLyrics = emptyList()
                                }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "清空")
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            executeSearch(searchQuery, selectedCategory, false)
                        }),
                        shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = { executeSearch(searchQuery, selectedCategory, false) },
                        enabled = searchQuery.isNotBlank()
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = "检索",
                            tint = if (searchQuery.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // Search Category Tabs
                PrimaryTabRow(
                    selectedTabIndex = selectedCategory.ordinal,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    divider = {}
                ) {
                    SearchCategory.entries.forEach { category ->
                        Tab(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            text = {
                                Text(
                                    category.label,
                                    fontWeight = if (selectedCategory == category) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = 840.dp)
        ) {
            // Search History Chips (Shown when input is blank)
            if (searchQuery.isBlank() && searchHistory.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "搜索历史",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { searchHistoryManager.clearHistory() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("清空", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        searchHistory.forEach { keyword ->
                            SuggestionChip(
                                onClick = {
                                    searchQuery = keyword
                                    executeSearch(keyword, selectedCategory, false)
                                },
                                label = {
                                    Text(
                                        keyword,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                icon = {
                                    Icon(
                                        Icons.Rounded.History,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                shape = RoundedCornerShape(16.dp)
                            )
                        }
                    }
                }
            }

            // Loading state
            if (isSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "正在检索网易云曲库...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (searchError != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = searchError ?: "搜索失败",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { executeSearch(searchQuery, selectedCategory, false) }) {
                            Text("重试")
                        }
                    }
                }
            } else if (hasSearched) {
                when (selectedCategory) {
                    SearchCategory.MIXED -> {
                        // --- 综合/混合搜索模式 ---
                        val isAllEmpty = mixedResult.songs.isEmpty() && mixedResult.albums.isEmpty() && mixedResult.artists.isEmpty()
                        if (isAllEmpty) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "未在网易云曲库中找到匹配结果",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 32.dp)
                            ) {
                                // 1. 歌手模块（仅在名称匹配时展示）
                                if (mixedResult.artists.isNotEmpty()) {
                                    item {
                                        Text(
                                            "歌手",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp)
                                        )
                                    }
                                    items(mixedResult.artists, key = { "artist_${it.id}" }) { artist ->
                                        ArtistCardItem(artist = artist, onClick = { handleArtistClick(artist) })
                                    }
                                }

                                // 2. 单曲模块（限制展示 10 首，含歌词命中优先歌曲名）
                                if (mixedResult.songs.isNotEmpty()) {
                                    item {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "单曲",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                FilledTonalButton(
                                                    onClick = {
                                                        val playableList = mixedResult.songs.map { m ->
                                                            playlistManager.findLocalMatch(m) ?: m
                                                        }
                                                        playerController.loadPlaylist(playableList, 0)
                                                        playerController.play()
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.PlayArrow,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("播放全部", fontSize = 12.sp)
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                TextButton(
                                                    onClick = { selectedCategory = SearchCategory.SONGS },
                                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                                ) {
                                                    Text("查看更多", fontSize = 12.sp)
                                                    Icon(
                                                        Icons.Rounded.ChevronRight,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    itemsIndexed(mixedResult.songs, key = { _, s -> "song_${s.id}" }) { index, onlineMedia ->
                                        val localMatch = remember(onlineMedia.id) {
                                            playlistManager.findLocalMatch(onlineMedia)
                                        }
                                        val targetMedia = localMatch ?: onlineMedia
                                        val isCurrent = currentMedia?.id == onlineMedia.id
                                        SongItem(
                                            media = targetMedia,
                                            isCurrentTrack = isCurrent,
                                            isPlaying = isPlaying && isCurrent,
                                            isLocalMatch = localMatch != null,
                                            matchedLyric = onlineMedia.matchedLyric,
                                            onClick = {
                                                val playableList = mixedResult.songs.map { m ->
                                                    playlistManager.findLocalMatch(m) ?: m
                                                }
                                                playerController.loadPlaylist(playableList, index)
                                                playerController.play()
                                            },
                                            onActionClick = {
                                                onSongActionClick(targetMedia, null)
                                            }
                                        )
                                    }
                                }

                                // 3. 专辑模块（限制展示 5 个）
                                if (mixedResult.albums.isNotEmpty()) {
                                    item {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "专辑",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            TextButton(
                                                onClick = { selectedCategory = SearchCategory.ALBUMS },
                                                contentPadding = PaddingValues(horizontal = 4.dp)
                                            ) {
                                                Text("查看更多", fontSize = 12.sp)
                                                Icon(
                                                    Icons.Rounded.ChevronRight,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                    items(mixedResult.albums, key = { "album_${it.id}" }) { album ->
                                        AlbumRowItem(album = album, onClick = { handleAlbumClick(album) })
                                    }
                                }
                            }
                        }
                    }

                    SearchCategory.SONGS -> {
                        // --- 非混合模式：单曲 (随滑动加载) ---
                        if (categorySongs.isEmpty()) {
                            EmptySearchResultText()
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 32.dp)
                            ) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "已加载 ${categorySongs.size} 首单曲",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        FilledTonalButton(
                                            onClick = {
                                                val playableList = categorySongs.map { m ->
                                                    playlistManager.findLocalMatch(m) ?: m
                                                }
                                                playerController.loadPlaylist(playableList, 0)
                                                playerController.play()
                                            },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Rounded.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("全部播放", fontSize = 12.sp)
                                        }
                                    }
                                }
                                itemsIndexed(categorySongs, key = { index, s -> "${s.id}_$index" }) { index, onlineMedia ->
                                    val localMatch = remember(onlineMedia.id) {
                                        playlistManager.findLocalMatch(onlineMedia)
                                    }
                                    val targetMedia = localMatch ?: onlineMedia
                                    val isCurrent = currentMedia?.id == onlineMedia.id
                                    SongItem(
                                        media = targetMedia,
                                        isCurrentTrack = isCurrent,
                                        isPlaying = isPlaying && isCurrent,
                                        isLocalMatch = localMatch != null,
                                        onClick = {
                                            val playableList = categorySongs.map { m ->
                                                playlistManager.findLocalMatch(m) ?: m
                                            }
                                            playerController.loadPlaylist(playableList, index)
                                            playerController.play()
                                        },
                                        onActionClick = {
                                            onSongActionClick(targetMedia, null)
                                        }
                                    )
                                }
                                item {
                                    PaginationFooter(isLoadingMore = isLoadingMore, hasMore = hasMoreSongs)
                                }
                            }
                        }
                    }

                    SearchCategory.ALBUMS -> {
                        // --- 非混合模式：专辑 (随滑动加载) ---
                        if (categoryAlbums.isEmpty()) {
                            EmptySearchResultText()
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 32.dp)
                            ) {
                                items(categoryAlbums, key = { it.id }) { album ->
                                    AlbumRowItem(album = album, onClick = { handleAlbumClick(album) })
                                }
                                item {
                                    PaginationFooter(isLoadingMore = isLoadingMore, hasMore = hasMoreAlbums)
                                }
                            }
                        }
                    }

                    SearchCategory.ARTISTS -> {
                        // --- 非混合模式：歌手 (随滑动加载) ---
                        if (categoryArtists.isEmpty()) {
                            EmptySearchResultText()
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 32.dp)
                            ) {
                                items(categoryArtists, key = { it.id }) { artist ->
                                    ArtistCardItem(artist = artist, onClick = { handleArtistClick(artist) })
                                }
                                item {
                                    PaginationFooter(isLoadingMore = isLoadingMore, hasMore = hasMoreArtists)
                                }
                            }
                        }
                    }

                    SearchCategory.LYRICS -> {
                        // --- 非混合模式：歌词搜索 (随滑动加载) ---
                        if (categoryLyrics.isEmpty()) {
                            EmptySearchResultText()
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 32.dp)
                            ) {
                                item {
                                    Text(
                                        "按歌词命中共检索到 ${categoryLyrics.size} 首歌曲",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                                itemsIndexed(categoryLyrics, key = { index, s -> "${s.id}_$index" }) { index, onlineMedia ->
                                    val localMatch = remember(onlineMedia.id) {
                                        playlistManager.findLocalMatch(onlineMedia)
                                    }
                                    val targetMedia = localMatch ?: onlineMedia
                                    val isCurrent = currentMedia?.id == onlineMedia.id
                                    SongItem(
                                        media = targetMedia,
                                        isCurrentTrack = isCurrent,
                                        isPlaying = isPlaying && isCurrent,
                                        isLocalMatch = localMatch != null,
                                        matchedLyric = onlineMedia.matchedLyric,
                                        onClick = {
                                            val playableList = categoryLyrics.map { m ->
                                                playlistManager.findLocalMatch(m) ?: m
                                            }
                                            playerController.loadPlaylist(playableList, index)
                                            playerController.play()
                                        },
                                        onActionClick = {
                                            onSongActionClick(targetMedia, null)
                                        }
                                    )
                                }
                                item {
                                    PaginationFooter(isLoadingMore = isLoadingMore, hasMore = hasMoreLyrics)
                                }
                            }
                        }
                    }
                }
            } else if (searchQuery.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                        Text(
                            "输入关键词搜索网易云千万在线曲库",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistCardItem(
    artist: NcmArtist,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!artist.picUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = artist.picUrl,
                        contentDescription = artist.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Rounded.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                val displayName = buildString {
                    append(artist.name)
                    val sub = artist.trans?.takeIf { it.isNotBlank() } ?: artist.alias.firstOrNull { it.isNotBlank() }
                    if (!sub.isNullOrBlank() && !artist.name.contains(sub)) {
                        append(" (").append(sub).append(")")
                    }
                }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "歌手 · ${if (artist.songCount > 0) "${artist.songCount}首单曲" else ""}${if (artist.albumCount > 0) " · ${artist.albumCount}张专辑" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
private fun AlbumRowItem(
    album: NcmAlbum,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
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
private fun EmptySearchResultText() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "未检索到匹配结果",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PaginationFooter(isLoadingMore: Boolean, hasMore: Boolean) {
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
