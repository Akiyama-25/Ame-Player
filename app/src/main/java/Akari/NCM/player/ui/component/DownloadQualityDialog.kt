package Akari.NCM.player.ui.component

import Akari.NCM.player.api.AvailableSongQuality
import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.QualityLevel
import Akari.NCM.player.core.VipTier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 音频下载前的目标音质询问弹窗：
 * 先向云端请求该歌曲所有实际可用的音质档位（含文件大小/采样率/比特率），
 * 获取后再向用户展示可用音质列表并预选最匹配用户「默认下载音质」的已解锁档位。
 */
@Composable
fun DownloadQualitySelectionDialog(
    song: PlayableMedia,
    defaultQuality: QualityLevel,
    userVipTier: VipTier,
    isAlreadyDownloaded: Boolean = false,
    loadAvailableQualities: suspend () -> List<AvailableSongQuality>,
    onConfirmDownload: (QualityLevel) -> Unit,
    onDismiss: () -> Unit
) {
    var retryCounter by remember(song.id) { mutableIntStateOf(0) }
    var isLoading by remember(song.id) { mutableStateOf(true) }
    var loadError by remember(song.id) { mutableStateOf<String?>(null) }
    var availableQualities by remember(song.id) { mutableStateOf<List<AvailableSongQuality>>(emptyList()) }
    var selectedQuality by remember(song.id) { mutableStateOf<QualityLevel?>(null) }

    LaunchedEffect(song.id, retryCounter, defaultQuality, userVipTier) {
        isLoading = true
        loadError = null
        try {
            val fetched = withContext(Dispatchers.IO) { loadAvailableQualities() }
            availableQualities = fetched
            if (fetched.isEmpty()) {
                selectedQuality = null
                loadError = "未能从云端获取到该歌曲的可用音质，请检查网络后重试"
            } else {
                selectedQuality = pickInitialDownloadQuality(
                    available = fetched,
                    preferredDefault = defaultQuality,
                    userVipTier = userVipTier
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            availableQualities = emptyList()
            selectedQuality = null
            loadError = "获取云端可用音质失败：${e.message ?: "请检查网络连接"}"
        } finally {
            isLoading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("download_quality_dialog"),
        title = {
            Column {
                Text(
                    text = if (isAlreadyDownloaded) "选择重新下载音质" else "选择下载音质",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(song.title)
                        if (song.artist.isNotBlank()) append(" - ${song.artist}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "当前账号权益: ${userVipTier.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                            .testTag("download_quality_loading"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                        Text(
                            text = "正在向云端查询该歌曲所有可用音质...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                loadError != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                            .testTag("download_quality_error"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = loadError ?: "获取可用音质失败",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        FilledTonalButton(
                            onClick = { retryCounter++ },
                            modifier = Modifier.testTag("btn_retry_download_qualities")
                        ) {
                            Text("重新查询")
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        availableQualities.forEach { item ->
                            val quality = item.quality
                            val isUnlocked = quality.requiredVipTier.level <= userVipTier.level
                            val isSelected = selectedQuality == quality
                            val cloudSpecSummary = formatAvailableQualitySpec(item)
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                } else {
                                    Color.Transparent
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (isUnlocked) 1f else 0.48f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable(enabled = isUnlocked) { selectedQuality = quality }
                                    .testTag("download_quality_option_${quality.value}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = if (isUnlocked) ({ selectedQuality = quality }) else null,
                                        enabled = isUnlocked
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = quality.label,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (quality.requiredVipTier != VipTier.NONE) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = if (quality.requiredVipTier == VipTier.SVIP) {
                                                        MaterialTheme.colorScheme.tertiaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.secondaryContainer
                                                    }
                                                ) {
                                                    Text(
                                                        text = quality.requiredVipTier.badgeText,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (quality.requiredVipTier == VipTier.SVIP) {
                                                            MaterialTheme.colorScheme.onTertiaryContainer
                                                        } else {
                                                            MaterialTheme.colorScheme.onSecondaryContainer
                                                        },
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val detailLine = buildString {
                                            if (!isUnlocked) {
                                                append("需要${quality.requiredVipTier.label}解锁 · ")
                                            }
                                            if (cloudSpecSummary.isNotBlank()) {
                                                append(cloudSpecSummary)
                                                append(" · ")
                                            }
                                            append(quality.description)
                                        }
                                        Text(
                                            text = detailLine,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        if (selectedQuality == null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "当前账号权益暂无可下载的音质档位",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            AdaptiveButtonGroup(
                buttons = listOf(
                    AdaptiveButtonSpec(
                        text = if (isAlreadyDownloaded) "重新下载" else "开始下载",
                        onClick = { selectedQuality?.let(onConfirmDownload) },
                        style = ExpressiveButtonStyle.FILLED,
                        enabled = !isLoading && selectedQuality != null,
                        testTag = "btn_confirm_download_quality"
                    ),
                    AdaptiveButtonSpec(
                        text = "取消",
                        onClick = onDismiss,
                        style = ExpressiveButtonStyle.TONAL,
                        testTag = "btn_cancel_download_quality"
                    )
                )
            )
        }
    )
}

internal fun pickInitialDownloadQuality(
    available: List<AvailableSongQuality>,
    preferredDefault: QualityLevel,
    userVipTier: VipTier
): QualityLevel? {
    val unlockedAvailable = available
        .map { it.quality }
        .filter { it.requiredVipTier.level <= userVipTier.level }
    if (unlockedAvailable.isEmpty()) return null

    val fallbackOrder = NcmApi.qualityFallbackOrder(preferredDefault)
    for (candidate in fallbackOrder) {
        if (candidate in unlockedAvailable) {
            return candidate
        }
    }
    return unlockedAvailable.last()
}

private fun formatAvailableQualitySpec(item: AvailableSongQuality): String {
    val parts = mutableListOf<String>()
    if (item.fileSizeBytes > 0L) {
        val mb = item.fileSizeBytes / (1024f * 1024f)
        parts.add(
            if (mb >= 1.0f) String.format(java.util.Locale.US, "%.1f MB", mb)
            else String.format(java.util.Locale.US, "%.0f KB", item.fileSizeBytes / 1024f)
        )
    }
    if (item.sampleRateHz > 0) {
        parts.add(String.format(java.util.Locale.US, "%.1f kHz", item.sampleRateHz / 1000f))
    }
    if (item.bitrateKbps > 0) {
        parts.add("${item.bitrateKbps} kbps")
    }
    return parts.joinToString(" · ")
}
