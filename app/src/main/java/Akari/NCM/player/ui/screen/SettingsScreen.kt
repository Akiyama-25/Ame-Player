package Akari.NCM.player.ui.screen

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.QualityLevel
import Akari.NCM.player.core.QrStatus
import Akari.NCM.player.core.UserProfile
import Akari.NCM.player.data.UserSessionManager
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*

import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import Akari.NCM.player.data.ThemePrefs


import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    sessionManager: UserSessionManager,
    ncmApi: NcmApi,
    localFolderManager: Akari.NCM.player.data.LocalFolderManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val apiBaseUrl by sessionManager.apiBaseUrlState.collectAsState()
    val qualityLevel by sessionManager.qualityState.collectAsState()
    val userProfile by sessionManager.userProfileState.collectAsState()
    val cookie by sessionManager.cookieState.collectAsState()
    val isLoggedIn = remember(cookie) { sessionManager.isLoggedIn() }
    val homepageMode by sessionManager.homepageModeState.collectAsState()
    val hasApi = apiBaseUrl.isNotBlank()

    val scope = rememberCoroutineScope()
    var isRefreshingProfile by remember { mutableStateOf(false) }

    var folderDirsList by remember { mutableStateOf(localFolderManager.getDirectories().toList()) }
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                localFolderManager.addDirectory(uri.toString())
                folderDirsList = localFolderManager.getDirectories().toList()
                android.widget.Toast.makeText(context, "已添加文件夹，正在扫描...", android.widget.Toast.LENGTH_SHORT).show()
                localFolderManager.scanAllFolders()
                android.widget.Toast.makeText(context, "扫描完成", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    var showApiDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showCustomAccentDialog by remember { mutableStateOf(false) }
    var showThemeModeDialog by remember { mutableStateOf(false) }
    var showProfileDetailDialog by remember { mutableStateOf(false) }
    var showHomepageModeDialog by remember { mutableStateOf(false) }
    var showFoldersDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        
        
        
        val isMonet by ThemePrefs.monetFlow.collectAsState()
        val accentColorLong by ThemePrefs.accentColorFlow.collectAsState()
        
        val settingsCols = when (androidx.compose.material3.adaptive.currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass) {
            androidx.window.core.layout.WindowWidthSizeClass.COMPACT -> 1
            else -> 2
        }
            
        LazyVerticalGrid(
            columns = GridCells.Fixed(settingsCols),

            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── 账号与网易云 API 服务 ──
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("网易云账号 & API 服务") }

            // API 域名设置
            item {
                SettingItem(
                    icon = Icons.Rounded.Dns,
                    title = "API 服务地址",
                    subtitle = if (hasApi) apiBaseUrl else "未配置 (点击配置解锁在线功能)",
                    onClick = { showApiDialog = true }
                )
            }

            if (hasApi) {
                // 登录状态卡片 (精简为仅展示头像、名称、VIP 标志与跳转箭头)
                item(span = { GridItemSpan(maxLineSpan) }) { Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clickable {
                            if (isLoggedIn) {
                                showProfileDetailDialog = true
                            } else {
                                showQrDialog = true
                            }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isLoggedIn) {
                            if (!userProfile?.avatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = userProfile?.avatarUrl,
                                    contentDescription = "Avatar",
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.AccountCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    userProfile?.nickname?.takeIf { it.isNotBlank() } ?: "已登录网易云用户",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if ((userProfile?.vipType ?: 0) > 0) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    SuggestionChip(
                                        onClick = { showProfileDetailDialog = true },
                                        label = { Text("VIP", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                        modifier = Modifier.height(24.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    if (!isRefreshingProfile) {
                                        isRefreshingProfile = true
                                        scope.launch {
                                            val profile = ncmApi.getUserProfile()
                                            if (profile != null) {
                                                sessionManager.saveUserProfile(profile)
                                                Toast.makeText(context, "用户信息已更新", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "获取用户信息失败", Toast.LENGTH_SHORT).show()
                                            }
                                            isRefreshingProfile = false
                                        }
                                    }
                                }
                            ) {
                                if (isRefreshingProfile) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(
                                        Icons.Rounded.Refresh,
                                        contentDescription = "重新获取用户信息",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = "查看个人信息",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.AccountCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "未登录网易云账号",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "登录后解锁无损/VIP音质及个人歌单",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { showQrDialog = true },
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text("扫码登录")
                            }
                        }
                    }
                }
            }

            item {
                val modeDesc = when(homepageMode) {
                    Akari.NCM.player.core.HomepageMode.APP_LIKE -> "类官方 APP 首页体验"
                    Akari.NCM.player.core.HomepageMode.DAILY_ONLY -> "仅获取每日推荐歌单"
                    Akari.NCM.player.core.HomepageMode.HIGH_QUALITY_ONLY -> "仅获取精品歌单 / 网友精选碟"
                }
                SettingItem(
                    icon = Icons.Rounded.Home,
                    title = "首页展示模式",
                    subtitle = modeDesc,
                    onClick = { showHomepageModeDialog = true }
                )
            }
            } // End of if (hasApi)

            item(span = { GridItemSpan(maxLineSpan) }) { HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp)) }

            // ── 本地音乐扫描 ──
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("本地音乐扫描") }

            item {
                SettingItem(
                    icon = Icons.Rounded.CreateNewFolder,
                    title = "添加本地文件夹",
                    subtitle = "选择目录并自动匹配网络元数据",
                    onClick = { folderPickerLauncher.launch(null) }
                )
            }

            item {
                SettingItem(
                    icon = Icons.Rounded.Refresh,
                    title = "重新扫描本地文件",
                    subtitle = "更新本地增删变动，重新匹配网易云信息",
                    onClick = {
                        scope.launch {
                            android.widget.Toast.makeText(context, "正在重新扫描...", android.widget.Toast.LENGTH_SHORT).show()
                            localFolderManager.scanAllFolders()
                            android.widget.Toast.makeText(context, "扫描完成", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            item {
                SettingItem(
                    icon = Icons.Rounded.Folder,
                    title = "管理已添加的文件夹",
                    subtitle = "点击查看或移除",
                    onClick = { showFoldersDialog = true }
                )
            }

            
            

            item(span = { GridItemSpan(maxLineSpan) }) { HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp)) }

            // ── 关于 (精简纯粹) ──
            
            
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("主题与外观") }
            
            item {
                val themeMode by ThemePrefs.themeFlow.collectAsState()
                val themeModeDesc = when(themeMode) {
                    ThemePrefs.THEME_LIGHT -> "浅色模式"
                    ThemePrefs.THEME_DARK -> "深色模式"
                    else -> "跟随系统"
                }
                SettingItem(
                    icon = Icons.Rounded.DarkMode,
                    title = "应用主题",
                    subtitle = themeModeDesc,
                    onClick = { showThemeModeDialog = true }
                )
            }
            
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { ThemePrefs.useMonetColors = !isMonet }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.ColorLens,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("动态取色 (Material You)", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        Text(if (isMonet) "基于壁纸提取动态颜色" else "使用自定义颜色", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val currentScheme = MaterialTheme.colorScheme
    Switch(checked = isMonet, onCheckedChange = { newValue ->
        if (!newValue) {
            ThemePrefs.customAccentColor = currentScheme.primary.toArgb().toLong()
            ThemePrefs.customLightBgColor = currentScheme.surface.toArgb().toLong()
            ThemePrefs.customDarkBgColor = currentScheme.surface.toArgb().toLong()
        }
        ThemePrefs.useMonetColors = newValue
    })
                }
            }
            
            item(span = { GridItemSpan(maxLineSpan) }) {
                AnimatedVisibility(
                    visible = !isMonet,
                    enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                ) {
                    Column {
                        Text("强调色", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 56.dp, top = 8.dp, bottom = 4.dp))
                        
                        val accentOptions = listOf(
                            Color(0xFF388E3C), // Green
                            Color(0xFF1976D2), // Blue
                            Color(0xFF7B1FA2), // Purple
                            Color(0xFFD32F2F), // Red
                            Color(0xFFF57C00), // Orange
                            Color(0xFF0097A7), // Cyan
                            Color(0xFFC2185B), // Pink
                            Color(0xFF303F9F)  // Indigo
                        )
                        
                        ColorPickerRow(
                            colors = accentOptions,
                            selectedColor = Color(accentColorLong.toInt()),
                            onColorSelected = { 
                                val hsl = Akari.NCM.player.ui.theme.colorToHsl(it)
                                ThemePrefs.customAccentH = hsl.h
                                ThemePrefs.customAccentS = hsl.s
                                ThemePrefs.customAccentL = hsl.l
                                ThemePrefs.customAccentColor = it.toArgb().toLong()
                            },
                            onCustomClick = { showCustomAccentDialog = true }
                        )
                    }
                }
            }
            
            item(span = { GridItemSpan(maxLineSpan) }) { HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp)) }
item(span = { GridItemSpan(maxLineSpan) }) {
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                uriHandler.openUri("https://github.com/Akiyama-25/Ame-Player")
                            } catch (_: Exception) {}
                        }
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "AmePlayer",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "github.com/Akiyama-25/Ame-Player",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

        }
    }

    
    if (showThemeModeDialog) {
        val currentThemeMode = ThemePrefs.themeFlow.collectAsState().value
        AlertDialog(
            onDismissRequest = { showThemeModeDialog = false },
            title = { Text("选择应用主题", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    listOf(
                        ThemePrefs.THEME_SYSTEM to "跟随系统",
                        ThemePrefs.THEME_LIGHT to "浅色模式",
                        ThemePrefs.THEME_DARK to "深色模式"
                    ).forEach { (mode, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ThemePrefs.themeMode = mode
                                    showThemeModeDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentThemeMode == mode,
                                onClick = {
                                    ThemePrefs.themeMode = mode
                                    showThemeModeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeModeDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showCustomAccentDialog) {
        var sliderH by remember { mutableFloatStateOf(ThemePrefs.customAccentH) }
        var sliderS by remember { mutableFloatStateOf(ThemePrefs.customAccentS) }
        var sliderL by remember { mutableFloatStateOf(ThemePrefs.customAccentL) }
        
        AlertDialog(
            onDismissRequest = { showCustomAccentDialog = false },
            title = { Text("自定义强调色", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 16.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Akari.NCM.player.ui.theme.hslToColor(sliderH, sliderS, sliderL))
                    )
                    
                    Text("色相", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = sliderH,
                        onValueChange = { sliderH = it },
                        valueRange = 0f..360f
                    )
                    
                    Text("饱和度", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = sliderS,
                        onValueChange = { sliderS = it },
                        valueRange = 0f..100f
                    )
                    
                    Text("明度", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(
                        value = sliderL,
                        onValueChange = { sliderL = it },
                        valueRange = 0f..100f
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        ThemePrefs.customAccentH = sliderH
                        ThemePrefs.customAccentS = sliderS
                        ThemePrefs.customAccentL = sliderL
                        ThemePrefs.customAccentColor = Akari.NCM.player.ui.theme.hslToArgbLong(sliderH, sliderS, sliderL)
                        showCustomAccentDialog = false
                    }
                ) {
                    Text("应用")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomAccentDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 独立个人信息页面对话框
    if (showProfileDetailDialog && userProfile != null) {
        UserProfileDetailDialog(
            userProfile = userProfile!!,
            onDismiss = { showProfileDetailDialog = false },
            onLogout = {
                sessionManager.clearSession()
                showProfileDetailDialog = false
                Toast.makeText(context, "已退出登录", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // 首页展示模式对话框
if (showFoldersDialog) {
        AlertDialog(
            onDismissRequest = { showFoldersDialog = false },
            title = { Text("管理本地文件夹", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                if (folderDirsList.isEmpty()) {
                    Text("暂无已添加的文件夹", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(folderDirsList) { dir ->
                            val pathName = android.net.Uri.parse(dir).lastPathSegment ?: dir
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = android.net.Uri.decode(pathName),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        localFolderManager.removeDirectory(dir)
                                        folderDirsList = localFolderManager.getDirectories().toList()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "移除",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFoldersDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    if (showHomepageModeDialog) {
        AlertDialog(
            onDismissRequest = { showHomepageModeDialog = false },
            title = { Text("选择首页展示模式", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    val modes = listOf(
                        Akari.NCM.player.core.HomepageMode.APP_LIKE to "类官方 APP 首页体验",
                        Akari.NCM.player.core.HomepageMode.DAILY_ONLY to "仅获取每日推荐歌单 (需登录)",
                        Akari.NCM.player.core.HomepageMode.HIGH_QUALITY_ONLY to "仅获取精品歌单 / 网友精选碟"
                    )
                    modes.forEach { (mode, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sessionManager.saveHomepageMode(mode)
                                    showHomepageModeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = homepageMode == mode,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(desc)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHomepageModeDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // API 地址设置对话框
    if (showApiDialog) {
        ApiBaseUrlDialog(
            currentUrl = apiBaseUrl,
            onDismiss = { showApiDialog = false },
            onSave = { newUrl ->
                sessionManager.saveApiBaseUrl(newUrl)
                showApiDialog = false
                Toast.makeText(context, "API 地址已保存", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // 扫码登录对话框
    if (showQrDialog) {
        QrLoginDialog(
            ncmApi = ncmApi,
            onDismiss = { showQrDialog = false },
            onLoginSuccess = {
                showQrDialog = false
                Toast.makeText(context, "网易云账号登录成功！", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UserProfileDetailDialog(
    userProfile: UserProfile,
    onDismiss: () -> Unit,
    onLogout: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("个人信息", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (userProfile.avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = userProfile.avatarUrl,
                        contentDescription = "User Avatar",
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        userProfile.nickname.ifBlank { "网易云用户" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (userProfile.vipType > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        SuggestionChip(
                            onClick = {},
                            label = { Text("VIP", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("用户 ID", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(userProfile.userId.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        if (userProfile.signature.isNotBlank()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                            Text("个性签名", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(userProfile.signature, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedButton(
                    onClick = onLogout,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("退出登录")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun ApiBaseUrlDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义 API 服务地址") },
        text = {
            Column {
                Text(
                    "请输入 NeteaseCloudMusicApi 服务的 Base URL",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(text) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun QualitySelectionDialog(
    currentQuality: QualityLevel,
    onDismiss: () -> Unit,
    onSelect: (QualityLevel) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择默认播放音质") },
        text = {
            Column {
                QualityLevel.entries.forEach { quality ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(quality) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = quality == currentQuality,
                            onClick = { onSelect(quality) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = quality.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (quality == currentQuality) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun QrLoginDialog(
    ncmApi: NcmApi,
    onDismiss: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var qrKey by remember { mutableStateOf<String?>(null) }
    var qrImageBase64 by remember { mutableStateOf<String?>(null) }
    var qrLoginUrl by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var qrStatus by remember { mutableStateOf(QrStatus.WAITING) }
    var statusMessage by remember { mutableStateOf("正在生成二维码...") }
    var isLoading by remember { mutableStateOf(true) }

    fun refreshQr() {
        scope.launch {
            isLoading = true
            statusMessage = "正在生成二维码..."
            val key = ncmApi.getQrKey()
            if (key != null) {
                qrKey = key
                val qrResult = ncmApi.createQrUrl(key)
                qrImageBase64 = qrResult?.first
                qrLoginUrl = qrResult?.second ?: ("https://music.163.com/login?codekey=" + key)
                qrStatus = QrStatus.WAITING
                statusMessage = "请使用网易云音乐 App 扫码登录\n(或点击二维码直接拉起授权)"
            } else {
                statusMessage = "二维码生成失败，请检查网络或 API 服务地址"
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshQr()
    }

    LaunchedEffect(qrKey) {
        val key = qrKey ?: return@LaunchedEffect
        while (isActive && qrStatus != QrStatus.SUCCESS && qrStatus != QrStatus.EXPIRED) {
            delay(2000)
            val result = ncmApi.checkQrStatus(key)
            qrStatus = result.status
            when (result.status) {
                QrStatus.WAITING -> statusMessage = "请使用网易云音乐 App 扫码登录"
                QrStatus.SCANNING -> statusMessage = "已扫码，请在手机上确认登录"
                QrStatus.EXPIRED -> statusMessage = "二维码已过期，请点击重新获取"
                QrStatus.SUCCESS -> {
                    statusMessage = "登录成功！"
                    onLoginSuccess()
                    break
                }
                QrStatus.UNKNOWN -> {}
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网易云扫码登录", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator()
                    } else if (!qrImageBase64.isNullOrBlank()) {
                        val bitmap = remember(qrImageBase64) { parseQrBitmap(qrImageBase64!!) }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "QR Code",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable {
                                        qrLoginUrl?.let { url ->
                                            try {
                                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                android.widget.Toast.makeText(context, "无法拉起浏览器或应用", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                            )
                        } else {
                            Text("二维码解析失败", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Text("无二维码数据", color = MaterialTheme.colorScheme.error)
                    }

                    if (qrStatus == QrStatus.EXPIRED) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.7f))
                                .clickable { refreshQr() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("二维码已过期\n点击刷新", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            if (qrStatus == QrStatus.EXPIRED) {
                Button(onClick = { refreshQr() }) {
                    Text("刷新二维码")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun parseQrBitmap(dataUriOrBase64: String): ImageBitmap? {
    return try {
        val base64Str = if (dataUriOrBase64.contains(",")) {
            dataUriOrBase64.substringAfter(",")
        } else {
            dataUriOrBase64
        }
        val bytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
        val androidBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        androidBitmap?.asImageBitmap()
    } catch (e: Exception) {
        android.util.Log.e("AME_QR_DEBUG", "parseQrBitmap failed", e)
        null
    }
}

@Composable
private fun ColorPickerRow(
    colors: List<Color>,
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    onCustomClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 56.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        colors.forEach { color ->
            val isSelected = selectedColor.toArgb() == color.toArgb()
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        }
                    )
                    .clickable { onColorSelected(color) },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    Brush.sweepGradient(
                        listOf(
                            Color.Red, Color.Yellow, Color.Cyan,
                            Color.Blue, Color.Magenta, Color.Red
                        )
                    )
                )
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable { onCustomClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
