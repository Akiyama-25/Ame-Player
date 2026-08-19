# 无论有无项目级 CLAUDE.md / GEMINI.md，都必须查看用户在 .claude 或 .gemini 目录下的全局提示词。

# AmePlayer

Android 音乐播放器，核心功能是播放网易云音乐 NCM 加密格式文件，同时支持在线音乐搜索与播放，并全面集成 Material 3 Adaptive 自适应大屏/横屏架构。
详细进度与架构汇总参阅根目录下的 [STATUS.md](file:///C:/Users/jocsu/.claude/ame-player/STATUS.md) 与 [Lyric.md](file:///C:/Users/jocsu/.claude/ame-player/Lyric.md)。

## 参考与借鉴项目

- **[InnerTune](https://github.com/z-huang/innertune)**：独立播放器界面 (PlayerScreen)、大圆角封面卡片、质感控制盘与沉浸式歌词无缝切换设计的核心参考来源。
- **[ncm-converter-android](https://github.com/.../ncm-converter-android)**：设置页面布局设计参考。

## 技术栈

| 层面 | 技术 |
|------|------|
| UI | Jetpack Compose + Material3 + Material 3 Adaptive (NavigationSuite) |
| DI | Hilt (KSP) |
| 播放器 | Media3 ExoPlayer |
| 网络 | Ktor (OkHttp) |
| 图片加载 | Coil Compose |
| 序列化 | KotlinX Serialization |
| 异步 | Kotlin Coroutines + Flow |
| 测试 | Robolectric 4.14.1 + Compose UI Test (JUnit4) |
| minSdk / targetSdk / compileSdk | 26 / 35 / 37 |
| JVM Target | 21 |

## 多语言（i18n）规范
涉及 Android/i18n 多语言资源时，简体中文和繁体中文必须严格使用 **SC/TC**（Script subtag）区分，**禁止**使用 CN/TW（Region subtag）：
- 简体中文：`zh-SC`，Android 资源目录 `values-zh-rSC/`
- 繁体中文：`zh-TC`，Android 资源目录 `values-zh-rTC/`

## 项目结构

```
app/src/main/java/Akari/NCM/player/
├── AmeApp.kt              # @HiltAndroidApp Application
├── MainActivity.kt        # 单 Activity，注入 AmePlayerEngine，拦截返回键切后台
├── core/
│   ├── Models.kt          # PlayableMedia (LocalNcm / Online)、AudioFormat、NcmMetadata
│   ├── NcmModels.kt       # NcmUserPlaylist、UserProfile、QrStatus、HomepageBlock 数据模型
│   ├── PlayerState.kt     # PlayerState 接口 (StateFlow)、PlayerController 接口
│   ├── Command.kt         # PlayerCommand sealed interface + CommandQueue (Channel)
│   └── LyricLine.kt       # 歌词行数据模型
├── api/
│   └── NcmApi.kt          # 网易云 API 客户端 (搜索 / 二维码登录 / 自动音质降级 getSongUrl / 歌单与日推全量分页拉取 / 歌词 / Block与DragonBall)
├── data/
│   ├── NcmCrypto.kt       # AES-ECB 解密 + RC4 KeyBox 构建
│   ├── NcmDataSource.kt   # ExoPlayer DataSource：NCM 文件解析、流式按需解密、内嵌封面提取
│   ├── NcmFileScanner.kt  # 文件扫描：识别 NCM / 普通音频，提取元数据与封面缓存
│   ├── LrcParser.kt       # LRC 歌词解析器
│   ├── LyricsManager.kt   # 内嵌歌词、本地 .lrc 文件与在线 Api 多级歌词管理
│   ├── PlaylistManager.kt # 自建歌单增删改查与网易云歌单同步/重新同步(Re-sync)持久化管理
│   ├── UsbAudioManager.kt # USB DAC 声卡热插拔监听与独占通道管理
│   └── UserSessionManager.kt # 用户登录 Cookie 持久化、Cookie Clean 净化拦截、登录状态管理与偏好配置
├── player/
│   ├── AmePlayerEngine.kt # 播放引擎核心：ResolvingDataSource 在线 URL 路由、DefaultLoadControl、USB DAC 设备直通
│   └── AmePlaybackService.kt  # MediaSessionService 前台播放服务 (共享 AmePlayerEngine 的 ExoPlayer)
├── di/
│   └── AppModule.kt       # Hilt 模块：提供 UserSessionManager + NcmApi + AmePlayerEngine 单例
└── ui/
    ├── theme/Theme.kt     # Material3 主题 (支持 Dynamic Color)
    ├── component/
    │   ├── AddToPlaylistDialog.kt # “添加到歌单”选择与新建对话框
    │   ├── SongActionBottomSheet.kt # 歌曲操作三点弹窗 (下一首播放 / 喜欢 / 移出歌单 / 加入歌单)
    │   ├── MiniPlayerBar.kt # 底部悬浮 Mini 播放控制条
    │   └── QuickFunctionsDialog.kt # 快捷功能弹窗 (USB 独占通道 / 播放状态)
    └── screen/
        ├── MainScreen.kt  # 主界面：Material 3 NavigationSuiteScaffold 自适应顶层导航 (Compact 底部 NavigationBar / Medium+Expanded 左侧 NavigationRail) + 嵌套 Scaffold MiniPlayerBar 停靠
        ├── UserHomepageView.kt # 自适应网格首页：GridCells.Adaptive 响应式多列排版 + DragonBallCard / BlockCreativeCard 动态尺寸缩放 + HomepageCache 瞬时渲染
        ├── PlayerScreen.kt # Salt/InnerTune 风格独立全屏播放页面 (0.5s 封面/歌词物理平移缩放 + 歌词垂直居中 + 沉浸式高斯模糊背景 + SaltPlayQueueView 队列视差推退动效)
        └── SettingsScreen.kt # 设置界面：设计借鉴 ncm-converter-android (网易云账号/扫码登录/精简卡片与独立 UserProfileDetailDialog/自定义API节点/最高音质自动匹配)
```

## 架构要点

- **单 Activity 架构**：MainActivity 直接注入 AmePlayerEngine，无 ViewModel 层
- **Material 3 Adaptive 自适应导航架构**：
  - 使用 `NavigationSuiteScaffold` 替代顶层 `TabRow`；
  - 竖屏手机（Compact，宽度 < 600dp）在底部渲染 `NavigationBar`；
  - 横屏手机、折叠屏、平板（Medium / Expanded，宽度 >= 600dp）在左侧渲染 `NavigationRail`；
  - 嵌套 `Scaffold` 管理 `MiniPlayerBar`：Compact 下停靠在底部导航上方，Medium/Expanded 下停靠在主内容区域底部；
  - `PlayerScreen` 与 `SettingsScreen` 全屏沉浸独立渲染。
- **UserHomepageView 响应式多列排版**：
  - `PlaylistGrid` 采用 `GridCells.Adaptive(minSize = minItemSize)`（Compact: 110dp 呈 2-3 列；Medium: 130dp 呈 4-5 列；Expanded: 140dp 呈 6-8 列）；
  - `DiscoverBlocksHomepage` 与 `DragonBallCard` 基于 `BoxWithConstraints` 实现根据屏幕断点缩放卡片大小，封面统一 `aspectRatio(1f)`；
  - 维护内存级 `HomepageCache`，切页与旋转无需重复网络请求。
- **播放状态驱动 UI**：AmePlayerEngine 实现 PlayerState 接口，暴露 StateFlow，Compose 通过 collectAsState 订阅
- **DataSource 路由**：AmePlayerEngine 内部根据 media 类型自动路由到 NcmDataSource (NCM 解密) 或 ContentDataSource (本地 content://)，在线音频走 CacheDataSource (500MB LRU 缓存) + `ResolvingDataSource` (根据 MediaItem key/uri 拦截请求并实时调用 `ncmApi.getSongUrl`)
- **DefaultLoadControl**：配置 minBuffer=15s / maxBuffer=50s / bufferForPlayback=5s，适配高码率 FLAC（96kHz 立体声 ≈ 3Mbps）
- **仅音频渲染与硬件直通**：ExoPlayer 配置为 AudioOnly RenderersFactory，启用 AudioOffload；支持 Android 14+ USB DAC Bit-Perfect 独占直通与拔出自动暂停保护。

## NCM 文件格式

NCM 是网易云音乐的加密音频格式。解密流程：
1. 验证魔数 `CTENFDAM`
2. 读取加密 Key Block → XOR 0x64 → AES-ECB 解密 → 取 RC4 Key → 构建 KeyBox
3. 读取加密 Meta Block → XOR 0x63 → Base64 → AES-ECB 解密 → JSON 元数据
4. 读取 Image Block → 提取原始 JPEG/PNG 嵌入封面保存为本地 URI
5. 剩余数据为加密音频，使用 FileChannel 流式按需 XOR KeyBox 解密（零全量内存占用）

## 构建与测试

```bash
# 运行全部单元与 Compose UI 自动化测试
.\gradle-9-bin\gradle-9.5.0\bin\gradle.bat testDebugUnitTest

# 构建 Debug APK
.\gradle-9-bin\gradle-9.5.0\bin\gradle.bat assembleDebug
```

## 已完成与近期变更

- **Material 3 Adaptive 全局导航与自适应首页**：
  - 使用 `NavigationSuiteScaffold` 替代顶层 `TabRow` 导航，支持 Compact 底部 `NavigationBar` 与 Medium/Expanded 左侧 `NavigationRail` 自动切换；
  - 嵌套 `Scaffold` 在 Compact 下将 `MiniPlayerBar` 停靠在底部导航上方，在 Medium/Expanded 下停靠在内容底部；
  - 首页 `UserHomepageView` 引入 `GridCells.Adaptive` 多列自适应网格与 `DragonBallCard` / `BlockCreativeCard` 响应式尺寸缩放；
  - 配备完整的 Robolectric Compose UI 测试套件（`AdaptiveNavigationTest.kt`, `HomepageAdaptiveGridTest.kt`, `HomepageCacheTest.kt`）。
- **网易云歌单同步与持久化**：新增 `SyncCloudPlaylistsDialog` 及歌单单条 Re-sync（重新同步）。`NcmApi.getPlaylistTracks` 支持分页全量抓取，单页 100 首轮询，加入 10s 超时防护；支持保存 `ncmPlaylistId`、`coverImgUrl` 与 `lastSyncedAt` 时间戳。
- **UI 精致化布局重构**：
  - 移除了歌单内容区重复的 "My Playlists" 文本；
  - 将【同步云端】与【新建本地】按钮简化为仅保留图标的精简图标按钮（`FilledTonalIconButton`），统一靠右对齐展示；
  - 将 `[云端]` 标识 Chip 移到了第二行 `1066 songs  ·  [云端]`，释放第一行标题区域。
- **在线音频播放连环跳歌修复**：
  - `DefaultHttpDataSource.Factory()` 启用 `setAllowCrossProtocolRedirects(true)`，解决网易云 CDN 在 HTTP 与 HTTPS 之间跨协议重定向失败的问题；
  - `AmePlayerEngine` 增加 `resolvedUrlCache` 内存缓存，避免 ExoPlayer 在分段缓冲和 Seek 时重复调 API 解析 URL；
  - 调整 `onPlayerError` 错误处理逻辑，不再在遇到网络/解析错误时自动盲目 `seekToNext()` 导致连环切歌；
  - `NcmApi.getSongUrl` 增加对 `/song/url?id=...&br=320000` 兼容接口的兜底逻辑。
- **独立播放列表页面 (PLAY QUEUE)**：
  - 将 `PlayerScreen` 底部工具栏的播放列表按钮改造成在播放界面内部展开的独立 `SaltPlayQueueView` 页面；
  - 配备顶部当前播放曲目 Mini 卡片、`1/N` 曲目计数、`播放队列` 居中 Header 以及 `CLEAR` 按钮；
  - 视差缩放与层级推退动效 (Apple Music / Salt Player 风格)；
  - 切歌封面预加载与平滑无缝缓冲 (Zero Blank Cover Transition)；
  - 快捷功能菜单与实时播放状态 (Quick Functions & Playback Status)。
- **独占 USB 音频通道支持 (Exclusive USB Audio Mode)**：
  - 新增 `UsbAudioManager` 单例，管理外接 USB DAC/耳放硬件设备动态扫描与独占开关状态；
  - `AmePlayerEngine` 适配 `setPreferredAudioDevice` 绑定硬件声卡并为 Android 14+ 开启 Bit-Perfect 直通模式；
  - 支持拔出保护：当在独占模式下拔出 USB DAC 时，引擎自动触发 `pause()` 保护耳朵与设备。
- **设置界面刷新用户信息功能**：在 APP 设置页面的账号卡片旁新增【刷新/更新】按钮，重新向 API 获取 Profile。
- **歌曲操作 (Song Action Sheet) 弹窗与图标重构**：
  - 将歌单与播放队列曲目旁的删除减号按钮移除，替换为标准的三个点图标 (`Icons.Rounded.MoreVert`)；
  - 新增网易云风格的 `SongActionBottomSheet.kt` 弹窗；
  - 【移出当前歌单】在“我喜欢的音乐”歌单中置灰禁用，在普通云端歌单中正常开放使用；
  - 联动网易云 API 实现云端喜欢、移出歌单、添加歌单双向同步；
  - 播放音质全面标记为跟随网易云账户最高权限。
- **Logcat 日志刷屏优化与等级调优**：清除加载大歌单时的逐首冗余日志，降低缓冲拦截日志等级。
- **全面简体中文化 (zh-SC)**：
  - 标准 `values-zh-rSC/strings.xml` 资源目录；
  - 全界面文本与图标 contentDescription 完成中文化。
