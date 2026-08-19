# Ame Player (NCM 播放器) 项目全量进度汇总文档 (STATUS.md)

更新时间：2026-07-31  
项目版本：AmePlayer 1.0 (Debug/Release Build Pass)

---

## 目录
1. [项目简介](#1-项目简介)
2. [已完成的核心功能列表](#2-已完成的核心功能列表)
3. [核心架构与技术创新](#3-核心架构与技术创新)
4. [重大 Bug 攻克与诊断记录](#4-重大-bug-攻克与诊断记录)
5. [文件索引与模块职责](#5-文件索引与模块职责)

---

## 1. 项目简介

Ame Player (`Akari.NCM.player`) 是一款高度优雅、性能优异的 Android 现代音乐播放器。支持播放网易云 `.ncm` 专属加密格式音频（支持 FLAC / MP3 高码率按需流式解密），同时支持本地常规音频（MP3、FLAC、WAV、AAC 等）以及网易云在线音乐搜索与播放。界面深度借鉴 **Salt Player (椒盐音乐)** 与 **InnerTune** 的现代极致 UI 审美。

---

## 2. 已完成的核心功能列表

### 🎵 播放与解密核心
- **NCM 文件流式零 OOM 按需解密**：基于 `FileChannel` 建立流式 XOR KeyBox 解密机制。面对 >129MB 超大无损 NCM 文件实现零全量内存占用与秒开 Seek。
- **FLAC 元数据防崩溃处理**：实现 `FlacMetadataSkipExtractor`，完美跳过 FLAC 文件中包含的大量 PICTURE / VORBIS_COMMENT 元数据块，消除 "First frame does not start with sync code" 异常。
- **播放范围与上下文严格限定**：
  - 从【歌单 A】中点击歌曲：自动清空前次播放列表并填入【歌单 A】全部曲目。
  - 从【全部曲目】主列表中点击歌曲：播放范围自动设为全部曲目。

### 🎨 Salt Player (椒盐音乐) 沉浸播放界面 (`PlayerScreen.kt`)
- **高斯模糊氛围背景**：提取封面色调配合 60dp 模糊与暗色渐变罩层。
- **0.5 秒封面/歌词物理平移缩放动画**：点击歌词时，大封面卡片在 0.5 秒内顺滑缩放并平移至标题/歌手左侧变为 52dp 迷你封面，歌词全屏显示；再次点击迷你封面，逆向平滑恢复大封面。采用对占位元素进行 `onGloballyPositioned` 坐标量测结合 `absoluteOffset` 插值平移，保证真实物理轨迹。
- **当前播放歌词精确匹配与居中布局**：封面视图下方精确同步显示当前正在播放的歌词行（`currentLyricLine`）及双语译文。歌词容器在**封面底部与进度条顶部**之间的剩余空间中实现绝对垂直居中布局。
- **离屏 Alpha 渐隐 Mask (CompositingStrategy.Offscreen)**：采用 Compose 离屏画布与 `BlendMode.DstOut` 擦除掩码（渐显/渐隐高度为 `120.dp`）。歌词向上/向下滑动时以整列歌词高度自然淡隐，**背景 100% 保持清晰透亮，绝对零阴影黑块**。
- **恒定显示 Slider 滑块头**：自定义 16dp 白色圆点浮雕滑块头，禁用 M3 默认的 `thumbTrackGapSize` 轨头间隙与 `drawStopIndicator` 端点隐去逻辑。进度条滑块从 `00:00` 开始全过程 100% 稳定清晰显现。

### 📜 双语歌词系统 (`LyricsManager.kt` & `LrcParser.kt`)
- **多级歌词检索**：按【音频内嵌歌词 (`EMBEDDED`)】➜【同目录同名 `.lrc` (`LOCAL_LRC`)】➜【网易云在线抓取 (`ONLINE`)】自动 fallback 检索。
- **非 NCM 普通音频在线匹配**：普通 MP3/FLAC 无内嵌歌词时，自动以“歌名 + 歌手”在线搜索网易云曲库，无缝抓取歌词与翻译。
- **双语对齐算法**：精准解析并合并 `lrc` (原文) 与 `tlyric` (译文) 在 ±500ms 内的时间戳，支持在界面中一键切换显示/隐藏翻译 (`译` 按钮)。

### 📂 歌单管理系统 (`PlaylistManager.kt`)
- 支持创建、重命名、删除自建歌单。
- 支持往歌单中添加/移除歌曲，并在 `context.filesDir/custom_playlists.json` 中自动持久化。

### 🔔 后台保活与媒体会话整合
- **单实例 ExoPlayer**：`AmePlaybackService` 注入 `AmePlayerEngine` Hilt 单例，共用其内部 ExoPlayer 构建 `MediaSession`，彻底消除历史双实例问题。
- **前台通知保活**：`MainActivity.onCreate()` 调用 `startService` 将服务启动为前台，Media3 `DefaultMediaNotificationProvider` 自动管理播放通知条，保证进程在后台不被系统回收。
- **应用级返回手势拦截**：`MainActivity` 根级拦截系统返回键为 `moveTaskToBack(true)`，退回到桌面时不销毁 Activity。
- **独立播放页返回手势导航**：在 `MainScreen` 中配置 `BackHandler(enabled = showPlayerScreen)`。在全屏独立播放器页面（不论在封面视图还是歌词视图），触发系统返回手势均优雅收起播放页返回 APP 主页面，而非退出 App。
- **`singleTop` 启动模式**：`MainActivity` 配置 `launchMode="singleTop"`，点击通知返回 App 时触发 `onNewIntent` 而非重建 Activity。

---

## 3. 核心架构与技术创新

```
Ame Player Architecture
┌─────────────────────────────────────────────────────────────┐
│ MainActivity (Single Activity)                              │
│  └── AmePlayerEngine (PlayerState + PlayerController Impl)   │
└──────────────────────────────┬──────────────────────────────┘
                               │
       ┌───────────────────────┼───────────────────────┐
       ▼                       ▼                       ▼
 [MainScreen UI]       [PlayerScreen UI]       [AmePlaybackService]
 (Queue/Playlists)     (Salt Player UI)        (MediaSessionService)
       │                       │                       │
       └───────────────────────┼───────────────────────┘
                               ▼
 ┌───────────────────────────────────────────────────────────┐
 │ ExoPlayer (Audio Only + Offload)                          │
 │  ├── RoutingDataSource (NCM / Content / Cache)            │
 │  │    ├── NcmDataSource (FileChannel Streaming XOR)      │
 │  │    └── CacheDataSource (512MB LRU Disk Cache)          │
 │  └── FlacMetadataSkipExtractor                            │
 └───────────────────────────────────────────────────────────┘
```

---

## 4. 重大 Bug 攻克与诊断记录

### 🐛 1. NCM 嵌入封面 `BitmapFactory returned a null bitmap` 崩溃修复
- **原因**：部分 NCM 的 `Image Block` 提取出的字节包含非标准图片头数据，存为本地文件后 Coil 解码失败报 null，并屏蔽了在线 `albumPic`。
- **解决**：在 `NcmFileScanner.kt` 中引入 `isValidImageBytes`（校验 JPEG `FF D8 FF` / PNG `89 50 4E 47` 等魔数），不合法自动 Fallback 至网络 `albumPic` (`?param=500y500`)。

### 🐛 2. 歌词掩码“一团阴影/黑块”问题修复
- **原因**：之前使用了带颜色的实体背景色块 Box 遮挡。
- **解决**：改用 Compose 离屏渲染层 `compositingStrategy = CompositingStrategy.Offscreen` 配合 `BlendMode.DstOut` 擦除掩码，仅对歌词文字自身的 Alpha 透明度做渐隐，背景 100% 无阴影黑块。

### 🐛 3. 进度条滑块头“突然显现/隐去”修复
- **原因**：Material3 默认 `Slider` 的 `thumbTrackGapSize` 间隙与 `drawStopIndicator` 端点隐藏动作所致。
- **解决**：自定义 `thumb` 渲染恒定 16dp 圆点，设置 `thumbTrackGapSize = 0.dp` 与 `drawStopIndicator = null`。

---

## 5. 文件索引与模块职责

- [`CLAUDE.md`](file:///C:/Users/jocsu/.claude/ame-player/CLAUDE.md)：项目全局配置规范与技术栈总结。
- [`Lyric.md`](file:///C:/Users/jocsu/.claude/ame-player/Lyric.md)：歌词系统架构、对齐算法与 UI 交互文档。
- [`PlayerScreen.kt`](file:///C:/Users/jocsu/.claude/ame-player/app/src/main/java/Akari/NCM/player/ui/screen/PlayerScreen.kt)：Salt Player 沉浸独立播放界面全量实现。
- [`MainScreen.kt`](file:///C:/Users/jocsu/.claude/ame-player/app/src/main/java/Akari/NCM/player/ui/screen/MainScreen.kt)：播放队列、自建歌单与 MiniPlayer 主页面。
- [`NcmDataSource.kt`](file:///C:/Users/jocsu/.claude/ame-player/app/src/main/java/Akari/NCM/player/data/NcmDataSource.kt)：NCM 流式解密与 Block 解析。
- [`LyricsManager.kt`](file:///C:/Users/jocsu/.claude/ame-player/app/src/main/java/Akari/NCM/player/data/LyricsManager.kt)：多源歌词加载与非 NCM 在线匹配调度器。
