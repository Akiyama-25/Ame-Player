package Akari.NCM.player.ui.screen

import Akari.NCM.player.BuildConfig
import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.LogLevel
import Akari.NCM.player.core.QrStatus
import Akari.NCM.player.core.UserProfile
import Akari.NCM.player.data.AppPackageVariant
import Akari.NCM.player.data.AppUpdateManager
import Akari.NCM.player.data.ColorVariant
import Akari.NCM.player.data.ContrastLevel
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.data.UpdateChannel
import Akari.NCM.player.data.UpdateCheckResult
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.ui.component.AdaptiveButtonGroup
import Akari.NCM.player.ui.component.AdaptiveButtonSpec
import Akari.NCM.player.ui.component.ExpressiveButtonStyle
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@UnstableApi
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    sessionManager: UserSessionManager,
    ncmApi: NcmApi,
    localFolderManager: Akari.NCM.player.data.LocalFolderManager,
    onOpenLog: () -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val apiBaseUrl by sessionManager.apiBaseUrlState.collectAsState()
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
                Toast.makeText(context, "已添加文件夹，正在扫描...", Toast.LENGTH_SHORT).show()
                localFolderManager.scanAllFolders()
                Toast.makeText(context, "扫描完成", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var showQrDialog by remember { mutableStateOf(false) }
    var showCustomAccentDialog by remember { mutableStateOf(false) }
    var showThemeModeDialog by remember { mutableStateOf(false) }
    var showExpressiveStyleDialog by remember { mutableStateOf(false) }
    var showProfileDetailDialog by remember { mutableStateOf(false) }
    var showHomepageModeDialog by remember { mutableStateOf(false) }
    var showFoldersDialog by remember { mutableStateOf(false) }
    var showLogLevelDialog by remember { mutableStateOf(false) }
    var showLogSizeDialog by remember { mutableStateOf(false) }
    var showLoudnessDialog by remember { mutableStateOf(false) }

    val updateChannel by ThemePrefs.updateChannelFlow.collectAsState()
    var showUpdateChannelDialog by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var showUpdateResultDialog by remember { mutableStateOf(false) }

    val volumeNormalizationEnabled by ThemePrefs.volumeNormalizationFlow.collectAsState()
    val targetLoudnessLufs by ThemePrefs.targetLoudnessFlow.collectAsState()
    val loudnessPeakLimitEnabled by ThemePrefs.loudnessPeakLimitFlow.collectAsState()
    val loudnessNegativeGainOnly by ThemePrefs.loudnessNegativeGainOnlyFlow.collectAsState()
    val playbackFadeEnabled by ThemePrefs.playbackFadeEnabledFlow.collectAsState()

    val isMonet by ThemePrefs.monetFlow.collectAsState()
    val colorVariant by ThemePrefs.colorVariantFlow.collectAsState()
    val contrastLevel by ThemePrefs.contrastLevelFlow.collectAsState()
    val useMaterialBg by ThemePrefs.useMaterialBgFlow.collectAsState()
    val playerBlurCoverBg by ThemePrefs.playerBlurCoverBgFlow.collectAsState()
    val accentColorLong by ThemePrefs.accentColorFlow.collectAsState()

    val currentLogLevel by AppLogger.currentLevel.collectAsState()
    val maxFileSizeKb by AppLogger.maxFileSizeKb.collectAsState()
    val showMatchStatus by ThemePrefs.showMatchStatusFlow.collectAsState()
    val liveIslandEnabled by ThemePrefs.liveIslandEnabledFlow.collectAsState()
    val islandManager = remember { Akari.NCM.player.player.island.IslandLiveNotificationManager(context) }
    var canPostPromoted by remember { mutableStateOf(islandManager.canPostLiveUpdates()) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                canPostPromoted = islandManager.canPostLiveUpdates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "需要开启通知权限以展示灵动岛与实况歌词", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        val settingsCols = when (androidx.compose.material3.adaptive.currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass) {
            androidx.window.core.layout.WindowWidthSizeClass.COMPACT -> 1
            else -> 2
        }
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        LazyVerticalGrid(
            columns = GridCells.Fixed(settingsCols),
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = navBarBottom + 32.dp)
        ) {
            // ── 账号与网易云 API 服务 ──
            if (hasApi) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader("网易云账号")
                        SettingsGroupCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isLoggedIn) {
                                            showProfileDetailDialog = true
                                        } else {
                                            showQrDialog = true
                                        }
                                    }
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isLoggedIn) {
                                    if (!userProfile?.avatarUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = userProfile?.avatarUrl,
                                            contentDescription = "用户头像",
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primaryContainer),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primaryContainer),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = (userProfile?.nickname?.firstOrNull() ?: 'U').toString(),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
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
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "未登录网易云账号",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            "登录后解锁无损/VIP音质及个人歌单",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Button(
                                        onClick = { showQrDialog = true },
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Text("扫码登录", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            SettingsInnerDivider()

                            val modeDesc = when (homepageMode) {
                                Akari.NCM.player.core.HomepageMode.APP_LIKE -> "类官方 APP 首页体验"
                                Akari.NCM.player.core.HomepageMode.DAILY_ONLY -> "仅获取每日推荐歌单"
                                Akari.NCM.player.core.HomepageMode.HIGH_QUALITY_ONLY -> "仅获取精品歌单 / 网友精选碟"
                            }
                            SettingItem(
                                title = "首页展示模式",
                                subtitle = modeDesc,
                                onClick = { showHomepageModeDialog = true }
                            )
                        }
                    }
                }
            }

            // ── 本地音乐扫描 ──
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader("本地音乐扫描")
                    SettingsGroupCard {
                        SettingItem(
                            title = "添加本地文件夹",
                            subtitle = "选择目录并自动匹配网络元数据",
                            onClick = { folderPickerLauncher.launch(null) }
                        )
                        SettingsInnerDivider()
                        SettingItem(
                            title = "重新扫描本地文件",
                            subtitle = "更新本地增删变动，重新匹配网易云信息",
                            onClick = {
                                scope.launch {
                                    Toast.makeText(context, "正在重新扫描...", Toast.LENGTH_SHORT).show()
                                    localFolderManager.scanAllFolders()
                                    Toast.makeText(context, "扫描完成", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        SettingsInnerDivider()
                        SettingItem(
                            title = "管理已添加的文件夹",
                            subtitle = "点击查看或移除",
                            onClick = { showFoldersDialog = true }
                        )
                    }
                }
            }

            // ── 主题与外观 (Material 3 Expressive) ──
            item(span = { GridItemSpan(maxLineSpan) }) {
                val themeMode by ThemePrefs.themeFlow.collectAsState()
                val themeModeDesc = when (themeMode) {
                    ThemePrefs.THEME_LIGHT -> "浅色模式"
                    ThemePrefs.THEME_DARK -> "深色模式"
                    else -> "跟随系统"
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader("主题与外观")
                    SettingsGroupCard {
                        SettingItem(
                            title = "应用主题",
                            subtitle = themeModeDesc,
                            onClick = { showThemeModeDialog = true }
                        )

                        SettingsInnerDivider()

                        SettingItem(
                            title = "色彩变体与对比度级别",
                            subtitle = "${colorVariant.label} · ${contrastLevel.label}",
                            onClick = { showExpressiveStyleDialog = true },
                            modifier = Modifier.testTag("setting_color_variant_contrast")
                        )

                        SettingsInnerDivider()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { ThemePrefs.useMonetColors = !isMonet }
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "动态取色 (Material You)",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    if (isMonet) "非播放页使用系统壁纸取色，播放页基于歌曲封面取色"
                                    else "非播放页使用自定义颜色，播放页基于歌曲封面取色",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            val currentScheme = MaterialTheme.colorScheme
                            Switch(
                                checked = isMonet,
                                onCheckedChange = { newValue ->
                                    if (!newValue) {
                                        ThemePrefs.customAccentColor = currentScheme.primary.toArgb().toLong()
                                        ThemePrefs.customLightBgColor = currentScheme.surface.toArgb().toLong()
                                        ThemePrefs.customDarkBgColor = currentScheme.surface.toArgb().toLong()
                                    }
                                    ThemePrefs.useMonetColors = newValue
                                }
                            )
                        }

                        AnimatedVisibility(
                            visible = !isMonet,
                            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                        ) {
                            Column {
                                Text(
                                    "强调色",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 18.dp, top = 4.dp, bottom = 2.dp)
                                )

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

                        SettingsInnerDivider()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { ThemePrefs.useMaterialBackground = !useMaterialBg }
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                                .testTag("setting_use_material_bg"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Material 取色背景色",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    if (useMaterialBg) "已开启 · APP 背景使用 Material 取色的背景色显示"
                                    else "已关闭 · APP 背景色显示为纯黑或纯白色，跟随浅色/深色模式",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = useMaterialBg,
                                onCheckedChange = { ThemePrefs.useMaterialBackground = it },
                                modifier = Modifier.testTag("switch_use_material_bg")
                            )
                        }

                        SettingsInnerDivider()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { ThemePrefs.playerBlurCoverBackground = !playerBlurCoverBg }
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                                .testTag("setting_player_blur_cover_bg"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "播放界面模糊封面背景",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    if (playerBlurCoverBg) "已开启 · 播放界面以模糊处理后的歌曲封面作为背景"
                                    else "已关闭 · 播放界面使用应用背景色（取色背景或纯黑/纯白）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = playerBlurCoverBg,
                                onCheckedChange = { ThemePrefs.playerBlurCoverBackground = it },
                                modifier = Modifier.testTag("switch_setting_player_blur_cover_bg")
                            )
                        }
                    }
                }
            }

            // ── 音频与声音设置 ──
            item(span = { GridItemSpan(maxLineSpan) }) {
                val enableJyEffect by ThemePrefs.enableJyEffectFlow.collectAsState()
                Column(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader("音频与声音")
                    SettingsGroupCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { ThemePrefs.enableJyEffect = !enableJyEffect }
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                                .testTag("setting_enable_jy_effect"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "高清臻音 / 超清母带",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (enableJyEffect) "已启用 · 优先尝试匹配高清臻音 (JYEffect) 与超清母带音质"
                                    else "已关闭 · 最高匹配至 Hi-Res 高解析与无损 FLAC 音质",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = enableJyEffect,
                                onCheckedChange = { ThemePrefs.enableJyEffect = it },
                                modifier = Modifier.testTag("switch_enable_jy_effect")
                            )
                        }

                        SettingsInnerDivider()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showLoudnessDialog = true }
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                                .testTag("setting_volume_normalization"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "音量均衡 (EBU R128 / ITU-R BS.1770)",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val targetDesc = when (targetLoudnessLufs) {
                                    -11.0f -> "响亮 (-11.0 LUFS)"
                                    -16.0f -> "柔和 (-16.0 LUFS)"
                                    -23.0f -> "广电参考 (-23.0 LUFS)"
                                    else -> "标准 (-14.0 LUFS)"
                                }
                                val negTag = if (loudnessNegativeGainOnly) " · 仅降不升" else ""
                                Text(
                                    text = if (volumeNormalizationEnabled) "已启用 · $targetDesc$negTag · -1.0 dBTP 限幅"
                                    else "云端元数据 + 11×3s 稀疏抽样 + 实时前瞻 AGC 三级响度均衡，自动拉平曲目音量差异",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = volumeNormalizationEnabled,
                                onCheckedChange = { ThemePrefs.volumeNormalizationEnabled = it },
                                modifier = Modifier.testTag("switch_volume_normalization")
                            )
                        }

                        SettingsInnerDivider()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { ThemePrefs.playbackFadeEnabled = !playbackFadeEnabled }
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                                .testTag("setting_playback_fade"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "播放淡入与淡出",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (playbackFadeEnabled) "已启用 · 播放、暂停与切歌时平滑过渡音量，消除突兀爆音"
                                    else "已关闭 · 播放与暂停立即响应，不做音量渐变",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = playbackFadeEnabled,
                                onCheckedChange = { ThemePrefs.playbackFadeEnabled = it },
                                modifier = Modifier.testTag("switch_playback_fade")
                            )
                        }
                    }
                }
            }

            // ── 灵动岛与实时播放控制窗 ──
            item(span = { GridItemSpan(maxLineSpan) }) {
                val toggleLiveIsland: (Boolean) -> Unit = { enabled ->
                    ThemePrefs.liveIslandEnabled = enabled
                    if (enabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.POST_NOTIFICATIONS
                            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
                val triggerTestIsland: () -> Unit = {
                    if (android.os.Build.VERSION.SDK_INT >= 36 && !islandManager.canPostLiveUpdates()) {
                        Toast.makeText(context, "未开启实况通知权限，点击前往开启「实况通知」", Toast.LENGTH_SHORT).show()
                        islandManager.openPromotedNotificationSettings()
                    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        Toast.makeText(context, "正在模拟离开前台并发送实况通知，点击胶囊可返回应用", Toast.LENGTH_SHORT).show()
                        val activity = context as? android.app.Activity
                        activity?.moveTaskToBack(true)
                        islandManager.postTestIslandNotification()
                        scope.launch {
                            delay(400)
                            islandManager.postTestIslandNotification()
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader("灵动岛与实时播放控制")
                    SettingsGroupCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { toggleLiveIsland(!liveIslandEnabled) }
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                                .testTag("setting_live_island"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "启用播放状态与快捷控制实时窗",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "通过标准 MediaSession 实时同步高清专辑封面、播放进度条与上一首/暂停/下一首快捷控制（适配澎湃焦点岛、流体云、原子岛等）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = liveIslandEnabled,
                                onCheckedChange = toggleLiveIsland,
                                modifier = Modifier.testTag("switch_live_island")
                            )
                        }

                        SettingsInnerDivider()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { triggerTestIsland() }
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                                .testTag("setting_check_island_permission"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "发送上岛测试通知",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val subtitleText = if (android.os.Build.VERSION.SDK_INT >= 36 && !canPostPromoted) {
                                    "未开启实况通知权限，点击前往系统设置开启「实况通知 / 流体云」开关"
                                } else {
                                    "模拟离开前台退至后台状态，触发一条实况通知以检测灵动岛/流体云/原子岛效果"
                                }
                                Text(
                                    text = subtitleText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (android.os.Build.VERSION.SDK_INT >= 36 && !canPostPromoted) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            FilledTonalButton(
                                onClick = { triggerTestIsland() },
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("btn_check_island_permission")
                            ) {
                                Text(
                                    text = if (android.os.Build.VERSION.SDK_INT >= 36 && !canPostPromoted) "去开启" else "发送测试",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // ── 软件更新 ──
            item(span = { GridItemSpan(maxLineSpan) }) {
                val currentPkgVariant = remember { AppPackageVariant.fromContext(context) }
                val currentVersion = remember {
                    runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: BuildConfig.VERSION_NAME
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader("软件更新")
                    SettingsGroupCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showUpdateChannelDialog = true }
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                                .testTag("setting_update_channel"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "更新通道",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "当前通道: ${updateChannel.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            FilledTonalButton(
                                onClick = { showUpdateChannelDialog = true },
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("btn_switch_update_channel")
                            ) {
                                Text("切换", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        SettingsInnerDivider()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!isCheckingUpdate) {
                                        isCheckingUpdate = true
                                        scope.launch {
                                            val res = AppUpdateManager.checkUpdate(context, updateChannel)
                                            isCheckingUpdate = false
                                            updateResult = res
                                            showUpdateResultDialog = true
                                        }
                                    }
                                }
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                                .testTag("setting_check_update"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "检查更新",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "$currentVersion · ${currentPkgVariant.displayName} (${context.packageName})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                FilledTonalButton(
                                    onClick = {
                                        isCheckingUpdate = true
                                        scope.launch {
                                            val res = AppUpdateManager.checkUpdate(context, updateChannel)
                                            isCheckingUpdate = false
                                            updateResult = res
                                            showUpdateResultDialog = true
                                        }
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                    modifier = Modifier.testTag("btn_check_update")
                                ) {
                                    Text("检查", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // ── 调试功能 ──
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader("调试功能")
                    SettingsGroupCard {
                        val levelLabel = if (currentLogLevel == LogLevel.ERROR) {
                            "${currentLogLevel.label} (默认)"
                        } else {
                            currentLogLevel.label
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenLog() }
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                                .testTag("setting_log_record"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "运行日志",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "等级: $levelLabel · 上限: ${maxFileSizeKb} KB（点击进入独立页面查看与导出）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            FilledTonalButton(
                                onClick = onOpenLog,
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("btn_open_log_screen")
                            ) {
                                Text("查看", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        SettingsInnerDivider()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { ThemePrefs.showMatchStatus = !showMatchStatus }
                                .padding(horizontal = 18.dp, vertical = 12.dp)
                                .testTag("setting_show_match_status"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "歌曲匹配列表查看",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "开启后在歌单详情页快速过滤已匹配/未匹配本地源的歌曲",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = showMatchStatus,
                                onCheckedChange = { ThemePrefs.showMatchStatus = it },
                                modifier = Modifier.testTag("switch_show_match_status")
                            )
                        }
                    }
                }
            }

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
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "AmePlayer",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.ExtraBold
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

    // ── Material 3 Expressive 色彩变体与对比度级别配置弹窗 ──
    if (showExpressiveStyleDialog) {
        AlertDialog(
            onDismissRequest = { showExpressiveStyleDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "色彩变体与对比度级别",
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "对比度级别 (Contrast Level)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // 自适应按钮组：在竖屏手机上自动纵向堆叠，避免文字变形
                    AdaptiveButtonGroup(
                        buttons = ContrastLevel.entries.map { level ->
                            val isSelected = contrastLevel == level
                            AdaptiveButtonSpec(
                                text = level.label,
                                onClick = { ThemePrefs.contrastLevel = level },
                                icon = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.Contrast,
                                style = if (isSelected) ExpressiveButtonStyle.FILLED else ExpressiveButtonStyle.TONAL,
                                testTag = "contrast_option_${level.key}"
                            )
                        }
                    )

                    Text(
                        text = contrastLevel.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "Material 3 色彩变体 (Color Variant)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    ColorVariant.entries.forEach { variant ->
                        val isSelected = colorVariant == variant
                        Surface(
                            onClick = { ThemePrefs.colorVariant = variant },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("color_variant_${variant.key}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { ThemePrefs.colorVariant = variant }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = variant.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = variant.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant
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
                            text = "完成",
                            onClick = { showExpressiveStyleDialog = false },
                            style = ExpressiveButtonStyle.FILLED,
                            testTag = "btn_close_expressive_style_dialog"
                        )
                    )
                )
            }
        )
    }

    if (showLogLevelDialog) {
        AlertDialog(
            onDismissRequest = { showLogLevelDialog = false },
            title = { Text("日志记录等级", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
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
                                    showLogLevelDialog = false
                                }
                                .padding(vertical = 10.dp, horizontal = 8.dp)
                                .testTag("log_level_option_${level.name}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentLogLevel == level,
                                onClick = {
                                    AppLogger.setLogLevel(context, level)
                                    showLogLevelDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = displayLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (currentLogLevel == level) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                AdaptiveButtonGroup(
                    buttons = listOf(
                        AdaptiveButtonSpec(
                            text = "取消",
                            onClick = { showLogLevelDialog = false },
                            style = ExpressiveButtonStyle.TONAL
                        )
                    )
                )
            }
        )
    }

    if (showLogSizeDialog) {
        var selectedSizeKb by remember { mutableStateOf(maxFileSizeKb) }
        val presets = listOf(64L, 256L, 512L, 1024L, 2048L, 4096L)
        AlertDialog(
            onDismissRequest = { showLogSizeDialog = false },
            title = { Text("自定义日志大小上限", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "当前设置上限: $selectedSizeKb KB（最小 64 KB，默认 1024 KB）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Slider(
                        value = selectedSizeKb.toFloat().coerceIn(64f, 4096f),
                        onValueChange = { selectedSizeKb = (it.toLong() / 64L).coerceAtLeast(1L) * 64L },
                        valueRange = 64f..4096f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_log_max_size_kb")
                    )
                    presets.forEach { preset ->
                        val label = if (preset == 1024L) "$preset KB (默认)" else "$preset KB"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedSizeKb = preset }
                                .padding(vertical = 6.dp, horizontal = 8.dp)
                                .testTag("log_size_option_$preset"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedSizeKb == preset,
                                onClick = { selectedSizeKb = preset }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selectedSizeKb == preset) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                AdaptiveButtonGroup(
                    buttons = listOf(
                        AdaptiveButtonSpec(
                            text = "保存",
                            onClick = {
                                AppLogger.setMaxFileSizeKb(context, selectedSizeKb)
                                Toast.makeText(context, "日志上限已设为 ${AppLogger.maxFileSizeKb.value} KB", Toast.LENGTH_SHORT).show()
                                showLogSizeDialog = false
                            },
                            style = ExpressiveButtonStyle.FILLED,
                            testTag = "btn_save_log_size"
                        ),
                        AdaptiveButtonSpec(
                            text = "取消",
                            onClick = { showLogSizeDialog = false },
                            style = ExpressiveButtonStyle.TONAL
                        )
                    )
                )
            }
        )
    }

    if (showLoudnessDialog) {
        AlertDialog(
            onDismissRequest = { showLoudnessDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("音量均衡设置", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "采用三级渐进式响度引擎（P0 本地持久化缓存 → 云端 gain/vd → 11×3s 稀疏抽样 → 实时前瞻 AGC 与 Freeze Gate 兜底），全链路配备末端 4x 过采样真峰值限幅器；启用 USB DAC 硬件独占模式时自动旁路所有响度 DSP。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "目标综合响度 (Target Loudness)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val targets = listOf(
                        Triple(-14.0f, "标准 (-14.0 LUFS)", "默认推荐，与主流流媒体标准一致，兼顾响度与动态"),
                        Triple(-16.0f, "柔和 (-16.0 LUFS)", "温和听感，保留更多安静细节与动态起伏"),
                        Triple(-11.0f, "响亮 (-11.0 LUFS)", "更高响度输出，适合通勤或外放环境"),
                        Triple(-23.0f, "广电参考 (-23.0 LUFS)", "EBU R128 广电参考电平，最大动态余量")
                    )

                    targets.forEach { (lufs, title, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    ThemePrefs.targetLoudnessLufs = lufs
                                }
                                .padding(vertical = 4.dp)
                                .testTag("loudness_target_${lufs.toInt()}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = targetLoudnessLufs == lufs,
                                onClick = { ThemePrefs.targetLoudnessLufs = lufs }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { ThemePrefs.loudnessNegativeGainOnly = !loudnessNegativeGainOnly }
                            .testTag("setting_loudness_negative_gain_only"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("仅降不升模式 (Negative Gain Only)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text("仅衰减高于目标响度的曲目，不对低响度/大动态曲目施加正向增益，杜绝任何动态压缩感", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = loudnessNegativeGainOnly,
                            onCheckedChange = { ThemePrefs.loudnessNegativeGainOnly = it },
                            modifier = Modifier.testTag("switch_loudness_negative_gain_only")
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { ThemePrefs.loudnessPeakLimitEnabled = !loudnessPeakLimitEnabled },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("末端 4x 过采样真峰值限幅 (-1.0 dBTP)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            Text("在音频管线末端采用 4 倍过采样与 5ms 前瞻限幅，严控采样点间真实峰值不超 -1.0 dBTP", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(10.dp))
                        Switch(
                            checked = loudnessPeakLimitEnabled,
                            onCheckedChange = { ThemePrefs.loudnessPeakLimitEnabled = it },
                            modifier = Modifier.testTag("switch_loudness_peak_limit")
                        )
                    }
                }
            },
            confirmButton = {
                AdaptiveButtonGroup(
                    buttons = listOf(
                        AdaptiveButtonSpec(
                            text = "完成",
                            onClick = { showLoudnessDialog = false },
                            style = ExpressiveButtonStyle.FILLED
                        )
                    )
                )
            }
        )
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
                AdaptiveButtonGroup(
                    buttons = listOf(
                        AdaptiveButtonSpec(
                            text = "取消",
                            onClick = { showThemeModeDialog = false },
                            style = ExpressiveButtonStyle.TONAL
                        )
                    )
                )
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
                AdaptiveButtonGroup(
                    buttons = listOf(
                        AdaptiveButtonSpec(
                            text = "应用",
                            onClick = {
                                ThemePrefs.customAccentH = sliderH
                                ThemePrefs.customAccentS = sliderS
                                ThemePrefs.customAccentL = sliderL
                                ThemePrefs.customAccentColor = Akari.NCM.player.ui.theme.hslToArgbLong(sliderH, sliderS, sliderL)
                                showCustomAccentDialog = false
                            },
                            style = ExpressiveButtonStyle.FILLED
                        ),
                        AdaptiveButtonSpec(
                            text = "取消",
                            onClick = { showCustomAccentDialog = false },
                            style = ExpressiveButtonStyle.TONAL
                        )
                    )
                )
            }
        )
    }

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
                AdaptiveButtonGroup(
                    buttons = listOf(
                        AdaptiveButtonSpec(
                            text = "关闭",
                            onClick = { showFoldersDialog = false },
                            style = ExpressiveButtonStyle.TONAL
                        )
                    )
                )
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
                AdaptiveButtonGroup(
                    buttons = listOf(
                        AdaptiveButtonSpec(
                            text = "取消",
                            onClick = { showHomepageModeDialog = false },
                            style = ExpressiveButtonStyle.TONAL
                        )
                    )
                )
            }
        )
    }

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

    if (showUpdateChannelDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateChannelDialog = false },
            title = { Text("选择更新通道", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "选择您偏好的更新接收通道。更新将始终绑定当前包名 (${AppPackageVariant.fromContext(context).packageName})。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    UpdateChannel.entries.forEach { ch ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ThemePrefs.updateChannel = ch
                                    showUpdateChannelDialog = false
                                    Toast.makeText(context, "已切换至 ${ch.label}", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = updateChannel == ch,
                                onClick = {
                                    ThemePrefs.updateChannel = ch
                                    showUpdateChannelDialog = false
                                    Toast.makeText(context, "已切换至 ${ch.label}", Toast.LENGTH_SHORT).show()
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = ch.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(
                                    text = if (ch == UpdateChannel.RELEASE) "稳定正式版本，直接下载官方发布的完整安装包" else "自动化构建产物，包含最新提交的开发测试特性",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            text = "完成",
                            onClick = { showUpdateChannelDialog = false },
                            style = ExpressiveButtonStyle.FILLED
                        )
                    )
                )
            }
        )
    }

    if (showUpdateResultDialog && updateResult != null) {
        when (val res = updateResult!!) {
            is UpdateCheckResult.UpdateAvailable -> {
                AlertDialog(
                    onDismissRequest = { showUpdateResultDialog = false },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.NewReleases, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (res.channel == UpdateChannel.RELEASE) "发现新版本" else "发现新测试构建", fontWeight = FontWeight.Bold)
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
                            if (res.publishDate.isNotBlank()) {
                                val sizeText = if (res.fileSize > 0) " · 大小: ${"%.1f".format(res.fileSize / 1024f / 1024f)} MB" else ""
                                Text(
                                    text = "发布时间: ${res.publishDate}$sizeText",
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
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
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
                                        showUpdateResultDialog = false
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(res.downloadUrl)).apply {
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
                                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(res.webUrl)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                    },
                                    style = ExpressiveButtonStyle.TONAL
                                ),
                                AdaptiveButtonSpec(
                                    text = "稍后",
                                    onClick = { showUpdateResultDialog = false },
                                    style = ExpressiveButtonStyle.TEXT
                                )
                            )
                        )
                    }
                )
            }
            is UpdateCheckResult.UpToDate -> {
                AlertDialog(
                    onDismissRequest = { showUpdateResultDialog = false },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("已是最新版本", fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Text(
                            text = "当前应用 (${res.currentVersion} · ${res.variant.displayName})\n包名 [${res.variant.packageName}] 在「${res.channel.label}」通道已是最新，无需更新。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        AdaptiveButtonGroup(
                            buttons = listOf(
                                AdaptiveButtonSpec(
                                    text = "好的",
                                    onClick = { showUpdateResultDialog = false },
                                    style = ExpressiveButtonStyle.FILLED
                                )
                            )
                        )
                    }
                )
            }
            is UpdateCheckResult.Error -> {
                AlertDialog(
                    onDismissRequest = { showUpdateResultDialog = false },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("检查更新失败", fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Text(text = res.message, style = MaterialTheme.typography.bodyMedium)
                    },
                    confirmButton = {
                        AdaptiveButtonGroup(
                            buttons = listOf(
                                AdaptiveButtonSpec(
                                    text = "确定",
                                    onClick = { showUpdateResultDialog = false },
                                    style = ExpressiveButtonStyle.FILLED
                                )
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsGroupCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

@Composable
private fun SettingsInnerDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 18.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    )
}

@Composable
private fun SettingItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
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
                        contentDescription = "用户头像",
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
            }
        },
        confirmButton = {
            AdaptiveButtonGroup(
                buttons = listOf(
                    AdaptiveButtonSpec(
                        text = "退出登录",
                        onClick = onLogout,
                        icon = Icons.Rounded.Logout,
                        style = ExpressiveButtonStyle.OUTLINED,
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    AdaptiveButtonSpec(
                        text = "关闭",
                        onClick = onDismiss,
                        style = ExpressiveButtonStyle.TONAL
                    )
                )
            )
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
                        .clip(RoundedCornerShape(16.dp))
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
                                contentDescription = "登录二维码",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable {
                                        qrLoginUrl?.let { url ->
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "无法拉起浏览器或应用", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                            )
                        } else {
                            Text("二维码解析失败", color = MaterialTheme.colorScheme.error)
                        }
                    } else if (!qrLoginUrl.isNullOrBlank()) {
                        val bitmap = remember(qrLoginUrl) { generateQrCodeBitmap(qrLoginUrl!!) }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "登录二维码",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable {
                                        qrLoginUrl?.let { url ->
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "无法拉起浏览器或应用", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                            )
                        } else {
                            Text("二维码生成失败", color = MaterialTheme.colorScheme.error)
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
            val specs = buildList {
                if (qrStatus == QrStatus.EXPIRED) {
                    add(
                        AdaptiveButtonSpec(
                            text = "刷新二维码",
                            onClick = { refreshQr() },
                            style = ExpressiveButtonStyle.FILLED
                        )
                    )
                }
                add(
                    AdaptiveButtonSpec(
                        text = "取消",
                        onClick = onDismiss,
                        style = ExpressiveButtonStyle.TONAL
                    )
                )
            }
            AdaptiveButtonGroup(buttons = specs)
        }
    )
}

private fun generateQrCodeBitmap(content: String, size: Int = 512): ImageBitmap? {
    return try {
        val bitMatrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            content,
            com.google.zxing.BarcodeFormat.QR_CODE,
            size,
            size
        )
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.asImageBitmap()
    } catch (e: Exception) {
        android.util.Log.e("AME_QR_DEBUG", "generateQrCodeBitmap failed", e)
        null
    }
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
            .padding(horizontal = 18.dp, vertical = 8.dp),
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
