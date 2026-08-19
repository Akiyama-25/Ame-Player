package Akari.NCM.player.ui.component

import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.data.CustomPlaylist
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongActionBottomSheet(
    media: PlayableMedia,
    currentPlaylist: CustomPlaylist? = null,
    isFavorite: Boolean,
    showFavoriteAction: Boolean = true,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromCurrentPlaylist: (() -> Unit)? = null
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // ── 歌曲 Header 卡片 ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (!media.coverUrl.isNullOrBlank()) {
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
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "歌曲 : ${media.title}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = media.artist.ifBlank { "未知歌手" } + if (media.album.isNotBlank()) " · ${media.album}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // ── 1. 下一首播放 ──────────────────────────────────────────
            SongActionItem(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                title = "下一首播放",
                onClick = {
                    onPlayNext()
                    onDismiss()
                }
            )

            // ── 2. 喜欢 / 取消喜欢 ─────────────────────────────────────
            if (showFavoriteAction) {
                SongActionItem(
                    icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    iconTint = if (isFavorite) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurface,
                    title = if (isFavorite) "取消喜欢" else "添加喜欢",
                    onClick = {
                        onToggleFavorite()
                        onDismiss()
                    }
                )
            }

            // ── 3. 收藏到歌单 / 加入歌单 ──────────────────────────────
            SongActionItem(
                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                title = "收藏到歌单",
                onClick = {
                    onDismiss()
                    onAddToPlaylist()
                }
            )

            // ── 4. 移出当前歌单 (在“我喜欢的音乐”歌单内禁用) ───────────────
            val isFavoritePlaylist = currentPlaylist?.let {
                it.name.contains("喜欢的音乐") || it.name.contains("My Favorite") || it.name.contains("红心歌单")
            } ?: false
            val isCanRemove = onRemoveFromCurrentPlaylist != null && !isFavoritePlaylist

            SongActionItem(
                icon = Icons.Rounded.DeleteOutline,
                title = if (isFavoritePlaylist) "移出当前歌单 (在喜欢歌单中禁用)" else "移出当前歌单",
                enabled = isCanRemove,
                onClick = {
                    if (isCanRemove) {
                        onRemoveFromCurrentPlaylist?.invoke()
                        onDismiss()
                    }
                }
            )
        }
    }
}

@Composable
private fun SongActionItem(
    icon: ImageVector,
    title: String,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) iconTint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                modifier = Modifier.size(24.dp)
            )
        },
        headlineContent = {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        }
    )
}
