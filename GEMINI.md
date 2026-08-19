# GEMINI.md - AmePlayer 项目说明与架构记录

> **无论有无项目级 CLAUDE.md / GEMINI.md，都必须查看用户在 `.claude` 或 `.gemini` 目录下的全局提示词。**

---

## 项目概述
AmePlayer 是一款基于 Jetpack Compose 与 ExoPlayer (Media3) 开发的高品质 Android 网易云 / 本地 / NCM 格式音乐播放器。支持无缝播放、NCM 解密、USB 声卡独占直通与网易云云端歌单同步，并深度集成 Material 3 Adaptive 自适应大屏/横屏架构与响应式网格布局。

---

## 核心架构与设计规范
- **音频引擎**：[`AmePlayerEngine.kt`](file:///c:/Users/jocsu/.claude/ame-player/app/src/main/java/Akari/NCM/player/player/AmePlayerEngine.kt)，集成 `ResolvingDataSource` 拦截在线 URL；支持 USB DAC 设备绑定与 Bit-Perfect 直通；
- **歌单与持久化**：[`PlaylistManager.kt`](file:///c:/Users/jocsu/.claude/ame-player/app/src/main/java/Akari/NCM/player/data/PlaylistManager.kt)，管理本地与网易云云端歌单；
- **API 服务**：[`NcmApi.kt`](file:///c:/Users/jocsu/.claude/ame-player/app/src/main/java/Akari/NCM/player/api/NcmApi.kt)，基于 Ktor Client，对接 NeteaseCloudMusicApi；
- **多语言（i18n）规范**：严格使用 **SC/TC**（如 `zh-SC`，目录为 `values-zh-rSC/`；`zh-TC`，目录为 `values-zh-rTC/`），**禁止使用 CN/TW**；
- **全局自适应导航 (Material 3 Adaptive)**：
  - 基于 `NavigationSuiteScaffold` 替代传统的 `TabRow`，根据当前窗口尺寸类别（Window Size Class）自动切换导航布局形态：
    - **紧凑型屏幕 Compact**（宽度 < 600dp，常规竖屏手机）：在屏幕底部渲染标准 `NavigationBar`；
    - **中等/扩展型屏幕 Medium / Expanded**（宽度 >= 600dp，横屏手机、折叠屏、平板）：在屏幕左侧渲染 `NavigationRail`；
  - **嵌套 Scaffold 与 MiniPlayerBar 停靠**：在 Compact 模式下 `MiniPlayerBar` 停靠在底部导航栏上方；在 Medium/Expanded 模式下 `MiniPlayerBar` 停靠在主内容区域底部；
  - `PlayerScreen` 全屏播放页与 `SettingsScreen` 设置页保持独立全屏沉浸式展示，不受导航外壳干扰。
- **首页响应式网格与多列布局 (`UserHomepageView.kt`)**：
  - `PlaylistGrid` 采用 `GridCells.Adaptive(minSize = minItemSize)` 动态多列排版：
    - Compact 模式：`minSize = 110.dp`（呈现 2-3 列）；
    - Medium 模式：`minSize = 130.dp`（呈现 4-5 列）；
    - Expanded 模式：`minSize = 140.dp`（呈现 6-8 列）；
  - 使用 `BoxWithConstraints` 解析局部容器宽度，卡片尺寸根据窗口断点动态缩放：
    - `DragonBallCard` 龙珠卡片尺寸：120x160dp (Compact) -> 150x175dp (Medium) -> 180x195dp (Expanded)；
    - `BlockCreativeCard` 封面卡片宽度：110dp (Compact) -> 135dp (Medium) -> 160dp (Expanded)；
  - 专辑封面容器严格使用 `aspectRatio(1f)` 保持 1:1 正方形比例；
  - 维护内存级 `HomepageCache`，保证屏幕旋转和 Tab 切换时瞬时渲染，无需重复触发网络加载。
- **自动化 Compose UI 测试体系 (Robolectric)**：
  - 基于 Robolectric 4.14.1 与 `androidx.compose.ui:ui-test-junit4` 在本地 JVM 环境下执行高覆盖率自动化测试：
    - `AdaptiveNavigationTest.kt`：模拟 Compact (`w400dp-h800dp`)、Medium (`w700dp-h900dp`) 与 Expanded (`w1024dp-h768dp`) 环境，断言 `NavigationBar`（底部水平）与 `NavigationRail`（左侧垂直）几何坐标节点及 Tab 导航切换；
    - `HomepageAdaptiveGridTest.kt`：断言 `PlaylistGrid` 在不同窗口断点下的 3/4/6 列几何排布、卡片缩放、空状态与即时缓存渲染；
    - `HomepageCacheTest.kt`：测试首页内存缓存的读写、数据清除、并发安全性与登录状态联动。

---

## 已完成核心特性
1. **Material 3 Adaptive 全局导航与自适应首页**：引入 `material3-adaptive-navigation-suite`，实现 Compact 底部 `NavigationBar` 与 Medium/Expanded 左侧 `NavigationRail` 自动切换，以及 `UserHomepageView` 动态多列响应式网格排版；
2. **USB DAC 独占模式**：在播放控制与快捷弹窗中集成【独占 USB 通道】入口，硬件直通绕过 AudioFlinger SRC 重采样，拔出设备自动暂停保护；
3. **歌曲操作菜单 (`SongActionBottomSheet.kt`)**：替换红减号图标为 `MoreVert` 三点菜单。支持下一首播放、网易云云端喜欢/取消喜欢同步、云端歌单增删同步（在“我喜欢的音乐”中置灰“移出当前歌单”）；
4. **Logcat 优化**：彻底清除加载成百上千首大歌单时的逐首冗余日志；
5. **设置界面**：账号卡片旁增加【刷新/更新】按钮，重新向 API 获取 Profile；
6. **用户首页 (`UserHomepageView.kt`)**：支持类官方 APP 首页体验 (Block 混合呈现与 Dragon Ball 入口)、仅获取每日推荐歌单、仅获取精品歌单三种模式切换，点击直接拉取数据并装载至播放引擎；
7. **首页 UI 现代化与加载优化**：将旧版“龙珠”圆形入口重构为动态渐变卡片 (`DragonBallCard`)，无缝映射至“心动模式”等官方新入口并抓取个性化推荐封面作为卡片背景；同时在 `UserHomepageView` 中引入内存级 `HomepageCache` 状态缓存，消除了切换页面时的重新请求与加载动画，实现秒切冷启动级别的流畅体验。
8. **本地文件夹匹配与扫描**：在设置页中添加了 `LocalFolderManager`，支持使用系统选择器 (`ACTION_OPEN_DOCUMENT_TREE`) 选取多个本地目录，并在应用启动时自动扫描内部音频。对于缺失元数据或未解密的 NCM 文件，会自动回退通过网易云 API 获取信息。所有匹配文件会汇总到统一的“本地扫描文件夹”中。


9. **Material 3 颜色回退与设置管理完善**：修复了关闭 Material You (动态取色) 时，强调色丢失导致文字变白不可见的问题。现在关闭开关会瞬间提取并固化当前的系统色调。将本地文件夹管理抽离成单独的 Dialog 组件，方便直观地移除对应路径。并且在设置底部独立加入了项目 GitHub 仓库的链接跳转。
10. **全层级过渡动画注入**：移除了生硬的 if/else UI 切换，采用 AnimatedContent 实现了全局动画覆盖。包括：播放界面上拉入场与下拉退出、设置界面右侧滑入与划出、主页与歌单 Tab 的交叉淡入淡出溶解、以及歌单列表到详情页的水平推进景深过渡。
---

## 已废弃与设计变更说明：播放音质全面标记为跟随网易云账户最高权限

### 1. 变更说明
鉴于网易云 CDN 服务端携带 VIP 凭证的请求会自动强升并返回 FLAC 无损流，APP 放弃手动限制/降级音质的设计，彻底**移除 APP 设置中的音质手动切换菜单**，将默认播放音质设定为**“跟随网易云账户最高权限 (自动匹配)”**。

### 2. 引擎行为
- `NcmApi.getSongUrl` 自动发起最高音质重试链 (`hires` -> `lossless` -> `exhigh` -> `standard`)，由网易云 API 与用户的网易云 VIP/标准账户权限自动协商决定最佳音频流格式。

---

## 网易云 API 依赖功能清单

当前项目中依赖网易云音乐 API (`NcmApi`) 的功能模块记录。
强依赖 **“API + 登录账号”** 才能完整运作的功能标注了 🔒 **[API+Login]** 标签。后续新增或改动 API 功能后，需同步更新此处。

### 1. 播放与基础信息类
- **在线音频播放流获取**：解析网络歌曲真实的 MP3/FLAC 播放链接。未登录仅获取标准音质，🔒 **[API+Login]** 解锁最高至 Hi-Res 的音质。
- **智能歌词匹配与拉取**：根据本地音频元数据检索网易云歌曲并拉取双语歌词（无需登录）。

### 2. 首页与发现类
- **用户首页聚合数据**：拉取首页圆形图标 (`/homepage/dragon/ball`) 与混合推荐区块 (`/homepage/block/page`)，拉取网友精选精品歌单 (`/top/playlist/highquality`)。
- **每日推荐内容**：拉取每日推荐歌曲 (`/recommend/songs`) 与每日推荐歌单 (`/recommend/resource`)。🔒 **[API+Login]**

### 3. 账号与个人数据类
- **扫码授权登录**：完整的网易云音乐扫码授权流程。
- **用户信息获取与刷新**：拉取并更新网易云用户的昵称、头像、VIP 等级和个性签名。🔒 **[API+Login]**

### 4. 云端资产与交互类 (双向同步)
- **喜欢 / 取消喜欢音乐**：点击红心时同步加入/移出网易云的“我喜欢的音乐”。🔒 **[API+Login]**
- **拉取云端歌单列表**：拉取用户创建和收藏的网易云歌单，用于本地导入。🔒 **[API+Login]**
- **同步/更新云端歌单曲目明细**：分页拉取指定网易云歌单的所有歌曲数据并持久化到本地。拉取公开歌单无需登录，拉取私人歌单需 🔒 **[API+Login]**。
- **云端歌单增删曲目双向绑定**：本地向映射歌单增删曲目时，实时同步修改网易云远端歌单数据。🔒 **[API+Login]**

### 5. UI 渲染备忘录
- **横屏/折叠屏模式下歌词不可见**：根因是横屏下 ControlsBlock 和 ToolbarBlock 的尺寸被反直觉地放大（如 playBgSize=80dp、btnSize=56dp），导致右侧 Column 内固定控件总高度（约 330dp）超过横屏可用垂直高度（约 280-310dp），歌词容器 Box(weight=1f) 被压缩为 0dp 高度，CurrentLyricBlock 和 SaltFullLyricsView 均不可见且无法响应点击。修复方案：移除横屏放大逻辑，统一使用竖屏尺寸，并减少外层 padding。同时移除了之前的 `Arrangement.Center` 和缩减固定 Spacer 间距。
- **全屏歌词渐隐（CompositingStrategy.Offscreen 兼容性）**：原本为实现歌词列表顶部和底部的渐隐效果（Fading Edge），SaltFullLyricsView 中 Modifier.graphicsLayer 使用 compositingStrategy = CompositingStrategy.Offscreen 和 BlendMode.DstOut。在部分 Android 模拟器和设备（如 Pixel 10 Pro Fold API 34+）上会导致渲染异常和透明度问题，已移除这些渲染指令以保证全屏歌词的可视性。
- **全屏歌词垂直居中与展开跳动问题**：原逻辑 `listState.scrollToItem(currentIndex - 2)` 在开启翻译（行高翻倍）时会导致当前歌词严重偏下，且开关翻译时画面会跳动。修复方案：使用 `BoxWithConstraints` 获取 `maxHeight / 2` 作为 `LazyColumn` 的上下 `contentPadding`，并直接 `animateScrollToItem(currentIndex)`。这样能确保当前歌词永远绝对锚定在屏幕正中央，彻底解决高度变化导致的垂直偏移跳动问题。

### 6. ExoPlayer (Media3) 已知问题与规避策略
- **MP3 (MPEG) 在线流媒体断流挂起 (Stall) 问题**：当 ExoPlayer 缓存在本地的在线 MP3 文件因网络中断或接口返回异常而意外残缺（如 4 分钟的歌只有前 55 秒数据）时，底层 Mp3Extractor 无法找到有效帧且 MediaCodec 软件解码器不会抛出 EOF。这会导致 AudioTrack 饥饿 (device stall)，音频突然无声，但播放器引擎未进入 Error 或 EOF 状态，使得**进度条继续空跑**。
  **规避与应对方案**：切勿为了修正跳秒而盲目为 Mp3Extractor 添加 FLAG_ENABLE_INDEX_SEEKING 或禁用 AUDIO_OFFLOAD_MODE_ENABLED，这会导致更严重的挂起。正确的处理方式是引导用户在应用内或系统设置中**清除应用缓存 (Clear Cache)**，丢弃残缺的 .exo 缓存碎片文件，重新拉取完整音频流。

---

## 待解决问题 (TODO)
- **本地文件夹扫描曲目无法播放**：当前已实现本地目录的扫描、元数据提取（含 API 兜底匹配）并能够成功展示在播放列表中。但尝试点击播放这些 `content://` URI 或通过 `DocumentFile` 解析出的文件时，播放器引擎 (`AmePlayerEngine` / `ExoPlayer` / `NcmDataSource`) 无法正常加载流并播放（可能由于 URI 权限丢失、`ResolvingDataSource` 解析报错或是引擎对外部 SAF 路径的支持存在缺陷），目前已将该问题挂起，等待后续修复。
- **MPEG/MP3 本地翻唱与电台曲目匹配局限**：当本地音频文件（非 NCM）实际上是网易云的“播客/电台/用户上传”等未被收录进官方单曲库的音频时，`/search` (type=1) 接口无法检索到准确歌曲。当前已优化为：**不强行乱用第一首错误搜索结果的封面与歌词**，转而优先提取本地 MP3 文件的内嵌 ID3 封面；但受限于 API 机制，对于毫无信息的翻唱音频仍可能面临获取不到正确歌词的情况。
