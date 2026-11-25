package Akari.NCM.player.ui.component

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

/**
 * 音频下载前的目标音质询问弹窗。
 * 默认预选用户的「默认下载音质」，允许自由切换当前账号权益内支持的任意音质并确认开始下载。
 */
@Composable
fun DownloadQualitySelectionDialog(
    song: PlayableMedia,
    defaultQuality: QualityLevel,
    userVipTier: VipTier,
    isAlreadyDownloaded: Boolean = false,
    onConfirmDownload: (QualityLevel) -> Unit,
    onDismiss: () -> Unit
) {
    val initialQuality = remember(defaultQuality, userVipTier) {
        if (defaultQuality.requiredVipTier.level <= userVipTier.level) {
            defaultQuality
        } else {
            when (userVipTier) {
                VipTier.SVIP -> QualityLevel.JYMASTER
                VipTier.VIP -> QualityLevel.HIRES
                VipTier.NONE -> QualityLevel.EXHIGH
            }
        }
    }
    var selectedQuality by remember(song.id, initialQuality) { mutableStateOf(initialQuality) }

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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                QualityLevel.entries.forEach { quality ->
                    val isUnlocked = quality.requiredVipTier.level <= userVipTier.level
                    val isSelected = selectedQuality == quality
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
                                Text(
                                    text = if (isUnlocked) {
                                        quality.description
                                    } else {
                                        "需要${quality.requiredVipTier.label}解锁 · ${quality.description}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
                        onClick = { onConfirmDownload(selectedQuality) },
                        style = ExpressiveButtonStyle.FILLED,
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
