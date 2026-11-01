package Akari.NCM.player.ui.screen

import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.LogLevel
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var logText by remember { mutableStateOf(AppLogger.getLogContent()) }
    val lines = remember(logText) {
        if (logText == "暂无日志记录") emptyList() else logText.trimEnd().lines()
    }

    val listState = rememberLazyListState()
    var currentLogLevel by remember { mutableStateOf(AppLogger.currentLevel.value) }
    var maxFileSizeKb by remember { mutableStateOf(AppLogger.maxFileSizeKb.value) }

    var showLogLevelDialog by remember { mutableStateOf(false) }
    var showLogSizeDialog by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    // 进入页面时从最底部开始查看（定位至最新日志行）
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.lastIndex)
        }
    }

    fun exportAndShareLog(format: AppLogger.ExportFormat) {
        val file = AppLogger.exportToFormat(context, format)
        if (file != null && file.exists()) {
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = format.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Ame Player 运行日志 (${file.name})")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooserIntent = Intent.createChooser(sendIntent, "导出日志 (.${format.extension})").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooserIntent)
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "日志导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "运行日志",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "等级: ${currentLogLevel.label} · 上限: ${maxFileSizeKb} KB · 共 ${lines.size} 行",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("btn_log_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    // 刷新
                    IconButton(
                        onClick = {
                            logText = AppLogger.getLogContent()
                            Toast.makeText(context, "日志已刷新", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("btn_log_refresh")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "刷新日志"
                        )
                    }

                    // 设置与清空
                    IconButton(
                        onClick = { showLogLevelDialog = true },
                        modifier = Modifier.testTag("btn_log_settings")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = "日志设置"
                        )
                    }

                    // 导出菜单
                    Box {
                        IconButton(
                            onClick = { showExportMenu = true },
                            modifier = Modifier.testTag("btn_export_log_menu")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = "导出日志"
                            )
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("导出为 .log 文件") },
                                onClick = {
                                    showExportMenu = false
                                    exportAndShareLog(AppLogger.ExportFormat.LOG)
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                                modifier = Modifier.testTag("export_format_log")
                            )
                            DropdownMenuItem(
                                text = { Text("导出为 .md (Markdown)") },
                                onClick = {
                                    showExportMenu = false
                                    exportAndShareLog(AppLogger.ExportFormat.MD)
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Article, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                                modifier = Modifier.testTag("export_format_md")
                            )
                            DropdownMenuItem(
                                text = { Text("导出为 .zip 压缩包") },
                                onClick = {
                                    showExportMenu = false
                                    exportAndShareLog(AppLogger.ExportFormat.ZIP)
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Archive, contentDescription = null, modifier = Modifier.size(20.dp))
                                },
                                modifier = Modifier.testTag("export_format_zip")
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("清空当前所有日志", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showExportMenu = false
                                    showClearConfirmDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                },
                                modifier = Modifier.testTag("btn_clear_logs")
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (lines.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Article,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无运行日志记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("list_logs"),
                    contentPadding = PaddingValues(bottom = 72.dp)
                ) {
                    itemsIndexed(lines) { _, line ->
                        val textColor = when {
                            line.contains("[ERROR]") -> MaterialTheme.colorScheme.error
                            line.contains("[WARN]") -> Color(0xFFF57C00)
                            line.contains("[INFO]") -> MaterialTheme.colorScheme.primary
                            line.contains("[DEBUG]") -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(
                            text = line,
                            color = textColor,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 1.dp)
                        )
                    }
                }

                // 快速跳到底部浮动按钮
                val isAtBottom by remember {
                    derivedStateOf {
                        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        lastVisible >= (lines.size - 3).coerceAtLeast(0)
                    }
                }

                if (!isAtBottom && lines.isNotEmpty()) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(lines.lastIndex)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "滚动至底部"
                        )
                    }
                }
            }
        }
    }

    // 日志等级调节弹窗
    if (showLogLevelDialog) {
        AlertDialog(
            onDismissRequest = { showLogLevelDialog = false },
            title = { Text("日志记录等级", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "当前等级: ${currentLogLevel.label}（默认等级: DEBUG）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LogLevel.entries.forEach { level ->
                        val displayLabel = if (level == LogLevel.DEBUG) "${level.label} (默认)" else level.label
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AppLogger.setLogLevel(context, level)
                                    currentLogLevel = level
                                    showLogLevelDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentLogLevel == level,
                                onClick = {
                                    AppLogger.setLogLevel(context, level)
                                    currentLogLevel = level
                                    showLogLevelDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = displayLabel, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogLevelDialog = false
                    showLogSizeDialog = true
                }) {
                    Text("调整大小上限")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogLevelDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // 日志大小上限调节弹窗
    if (showLogSizeDialog) {
        var selectedSizeKb by remember { mutableStateOf(maxFileSizeKb) }
        val presets = listOf(64L, 256L, 512L, 1024L, 2048L, 4096L)
        AlertDialog(
            onDismissRequest = { showLogSizeDialog = false },
            title = { Text("自定义日志大小上限", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "当前设置上限: $selectedSizeKb KB（最小 64 KB，默认 1024 KB）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    presets.chunked(3).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowPresets.forEach { size ->
                                val isSelected = selectedSizeKb == size
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedSizeKb = size },
                                    label = { Text(if (size == 1024L) "1 MB (默认)" else if (size >= 1024L) "${size / 1024} MB" else "$size KB", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppLogger.setMaxFileSizeKb(context, selectedSizeKb)
                        maxFileSizeKb = selectedSizeKb
                        Toast.makeText(context, "日志上限已设为 $selectedSizeKb KB", Toast.LENGTH_SHORT).show()
                        showLogSizeDialog = false
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogSizeDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 清空确认弹窗
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("清空日志", fontWeight = FontWeight.Bold) },
            text = { Text("确定要清空当前的全部运行日志吗？该操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppLogger.clearLogs()
                        logText = AppLogger.getLogContent()
                        showClearConfirmDialog = false
                        Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}