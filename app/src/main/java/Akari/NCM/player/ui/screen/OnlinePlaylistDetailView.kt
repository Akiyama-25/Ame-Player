package Akari.NCM.player.ui.screen

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.OnlinePlaylistTarget
import Akari.NCM.player.core.PlayableMedia
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
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
import coil.compose.AsyncImage

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
    onSongAction: (media: PlayableMedia, playlistContext: CustomPlaylist?) -> Unit
) {
    val context = LocalContext.current
    var tracks by remember(target.id, target.isDailyRecommend) { mutableStateOf<List<PlayableMedia.Online>?>(null) }
    var playlistMeta by remember(target.id, target.isDailyRecommend) { mutableStateOf(target) }
    var isLoading by remember(target.id, target.isDailyRecommend) { mutableStateOf(true) }
    var errorMessage by remember(target.id, target.isDailyRecommend) { mutableStateOf<String?>(null) }
    var matchFilterMode by remember { mutableIntStateOf(0) } // 0: 全部, 1: 已匹配, 2: 未匹配
    var retryTrigger by remember { mutableIntStateOf(0) }
    var inPlaylistSearchQuery by remember(target.id) { mutableStateOf("") }
    var isPlaylistSearchActive by remember(target.id) { mutableStateOf(false) }

    val customPlaylists by playlistManager.playlists.collectAsState()
    val isCollected = remember(target.id, target.isDailyRecommend, customPlaylists) {
        if (target.isDailyRecommend) false
        else customPlaylists.any { it.ncmPlaylistId == target.id }
    }

    LaunchedEffect(target.id, target.isDailyRecommend, retryTrigger) {
        isLoading = true
        errorMessage = null
        try {
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
                val albumSongs = ncmApi.getAlbumTracks(target.id)
                if (albumSongs.isNotEmpty()) {
                    tracks = albumSongs
                    playlistMeta = playlistMeta.copy(
                        trackCount = albumSongs.size,
                        coverImgUrl = if (playlistMeta.coverImgUrl.isBlank()) albumSongs.first().coverUrl ?: "" else playlistMeta.coverImgUrl
                    )
                } else {
                    errorMessage = "未获取到专辑曲目，请检查网络或稍后重试"
                }
            } else {
                val result = ncmApi.getPlaylistDetailAndTracks(target.id, target.trackCount)
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
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            errorMessage = "加载失败: ${e.localizedMessage ?: "未知错误"}"
        } finally {
            isLoading = false
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 840.dp)
    ) {
        // Top Navigation Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = if (target.isDailyRecommend) "每日推荐" else if (target.isAlbum) "专辑详情" else "歌单详情",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )

            if (currentTracks.isNotEmpty()) {
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

            if (!target.isDailyRecommend && currentTracks.isNotEmpty()) {
                if (isCollected) {
                    FilledTonalButton(
                        onClick = {
                            Toast.makeText(context, "已在我的歌单中", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
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
                        onClick = {
                            playlistManager.saveOrUpdateSyncedPlaylist(
                                ncmPlaylistId = target.id,
                                name = playlistMeta.title.ifBlank { "在线歌单" },
                                coverImgUrl = playlistMeta.coverImgUrl,
                                songs = currentTracks
                            )
                            Toast.makeText(context, "已收藏至我的歌单", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
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

        // Header Card (Cover + Metadata)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cover Art
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

                    // Play count badge if available
                    if (playlistMeta.playCount > 0) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
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

                // Metadata Column
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = playlistMeta.title.ifBlank { if (target.isDailyRecommend) "每日推荐" else if (target.isAlbum) "专辑详情" else "歌单详情" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
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
                    Text(
                        text = "${if (currentTracks.isNotEmpty()) currentTracks.size else playlistMeta.trackCount} 首歌曲",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

        // Action Row: Play All + Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = {
                    if (searchFilteredTracks.isNotEmpty()) {
                        val playableList = searchFilteredTracks.map { onlineMedia ->
                            playlistManager.findLocalMatch(onlineMedia) ?: onlineMedia
                        }
                        playerEngine.loadPlaylist(playableList, 0)
                        playerEngine.play()
                    }
                },
                enabled = searchFilteredTracks.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (searchFilteredTracks.isNotEmpty()) "全部播放 (${searchFilteredTracks.size})" else "全部播放"
                )
            }

            if (showMatchStatusPreference && currentTracks.isNotEmpty()) {
                Spacer(modifier = Modifier.width(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = matchFilterMode == 0,
                        onClick = { matchFilterMode = 0 },
                        label = { Text("全部", fontSize = 12.sp) }
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

        // Tracklist or Loading/Error State
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (target.isDailyRecommend) "正在获取每日推荐歌曲..." else "正在获取歌单曲目...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (errorMessage != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
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
            } else if (displayedSongs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (inPlaylistSearchQuery.isNotBlank()) "未在歌单内找到匹配歌曲"
                        else if (matchFilterMode == 1) "暂无已匹配的歌曲"
                        else if (matchFilterMode == 2) "暂无未匹配的歌曲"
                        else "歌单内暂无曲目",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    items(displayedSongs, key = { it.second.id }) { (origIndex, onlineMedia, isMatched) ->
                        SongItem(
                            media = onlineMedia,
                            isCurrentTrack = currentMedia?.id == onlineMedia.id,
                            isPlaying = isPlaying && currentMedia?.id == onlineMedia.id,
                            isLocalMatch = isMatched,
                            onClick = {
                                val playableList = searchFilteredTracks.map { m ->
                                    playlistManager.findLocalMatch(m) ?: m
                                }
                                playerEngine.loadPlaylist(playableList, origIndex)
                                playerEngine.play()
                            },
                            onActionClick = {
                                onSongAction(onlineMedia, null)
                            }
                        )
                    }
                }
            }
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
