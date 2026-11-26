package Akari.NCM.player.ui.component

import Akari.NCM.player.data.UpdateChannel
import Akari.NCM.player.data.UpdateCheckResult
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun UpdateResultDialog(
    result: UpdateCheckResult,
    onDismiss: () -> Unit
) {
    when (result) {
        is UpdateCheckResult.UpdateAvailable -> {
            UpdateAvailableDialog(res = result, onDismiss = onDismiss)
        }
        is UpdateCheckResult.UpToDate -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("已是最新版本", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Text(
                        text = "当前应用 (${result.currentVersion} · ${result.variant.displayName})\n包名 [${result.variant.packageName}] 在「${result.channel.label}」通道已是最新，无需更新。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    AdaptiveButtonGroup(
                        buttons = listOf(
                            AdaptiveButtonSpec(
                                text = "好的",
                                onClick = onDismiss,
                                style = ExpressiveButtonStyle.FILLED
                            )
                        )
                    )
                }
            )
        }
        is UpdateCheckResult.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("检查更新失败", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Text(text = result.message, style = MaterialTheme.typography.bodyMedium)
                },
                confirmButton = {
                    AdaptiveButtonGroup(
                        buttons = listOf(
                            AdaptiveButtonSpec(
                                text = "确定",
                                onClick = onDismiss,
                                style = ExpressiveButtonStyle.FILLED
                            )
                        )
                    )
                }
            )
        }
    }
}

@Composable
fun UpdateAvailableDialog(
    res: UpdateCheckResult.UpdateAvailable,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.NewReleases,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (res.channel == UpdateChannel.RELEASE) "发现新版本" else "发现新测试构建",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "绑定包名: ${res.variant.packageName} (${res.variant.displayName})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "当前版本: ${res.currentVersion}  →  最新版本: ${res.newVersion}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                val metaParts = buildList {
                    if (res.publishDate.isNotBlank()) add("发布时间: ${res.publishDate}")
                    if (res.fileSize > 0) add("大小: ${"%.1f".format(res.fileSize / 1024f / 1024f)} MB")
                    if (res.buildBranch.isNotBlank()) add("分支: ${res.buildBranch}")
                }
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "更新说明:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = res.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            AdaptiveButtonGroup(
                buttons = listOf(
                    AdaptiveButtonSpec(
                        text = "下载更新",
                        onClick = {
                            onDismiss()
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(res.downloadUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "无法打开浏览器下载: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        style = ExpressiveButtonStyle.FILLED
                    ),
                    AdaptiveButtonSpec(
                        text = "查看网页",
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(res.webUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        style = ExpressiveButtonStyle.TONAL
                    ),
                    AdaptiveButtonSpec(
                        text = "稍后",
                        onClick = onDismiss,
                        style = ExpressiveButtonStyle.TEXT
                    )
                )
            )
        }
    )
}
