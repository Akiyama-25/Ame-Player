# Ame Player 调试与匹配功能开发进度

## 概述
为 Ame Player 设置界面与播放体验引入调试与匹配诊断支持，包括：
1. 日志记录与导出系统（可调整等级、限制大小、支持导出为 .log、.md、.zip）。
2. 歌单歌曲匹配快速查看功能（开关控制、快捷分类查看已匹配与未匹配歌曲）。

---

## 一、已执行的修改 (Executed Changes)

### 1. 核心日志框架实现 (`app/src/main/java/Akari/NCM/player/core/AppLogger.kt`)
- **日志层级设计**：
  - 定义枚举 `LogLevel`：`VERBOSE` (2), `DEBUG` (3), `INFO` (4), `WARN` (5), `ERROR` (6)。
  - 默认层级设定为 `ERROR`。
  - 支持通过 `setLogLevel(context, level)` 实时切换并持久化保存。
- **文件大小限制与滚动**：
  - 默认文件大小上限为 `1024 KB`。
  - 支持动态配置 `maxFileSizeKb`，写入前检测文件大小，超出限制时自动滚动备份至 `app_log.1.txt`，防止日志无节制增长占用磁盘。
  - 使用独立协程作用域（`Dispatchers.IO + SupervisorJob`）以非阻塞方式异步写入磁盘，保证高并发日志不阻塞 UI 渲染。
- **全局异常拦截**：
  - 接入 `Thread.setDefaultUncaughtExceptionHandler`，应用发生崩溃异常时自动抓取完整堆栈并记录至 `AppLogger.e`。
- **多格式导出能力**：
  - 提供 `exportToFormat(context, format)` 函数：
    - `.log`：原始日志纯文本格式。
    - `.md`：包含导出时间、设置等级、大小上限及 Markdown 代码块格式包裹的格式化文档。
    - `.zip`：使用标准 `ZipOutputStream` 压缩打包生成的 Zip 文件。

### 2. 应用入口初始化与 FileProvider 配置
- **`app/src/main/java/Akari/NCM/player/AmeApp.kt`**：
  - 在 `onCreate` 时调用 `AppLogger.init(this)` 完成环境初始化。
- **`app/src/main/AndroidManifest.xml`**：
  - 声明 `androidx.core.content.FileProvider`，用于安全导出日志到外部应用（授权目标应用临时读取文件 URI）。
- **`app/src/main/res/xml/file_paths.xml`**：
  - 配置 FileProvider 路径映射（`cache-path` 与 `files-path`）。

### 3. 设置偏好持久化 (`app/src/main/java/Akari/NCM/player/data/ThemePrefs.kt`)
- 新增 `KEY_SHOW_MATCH_STATUS = "show_match_status"`。
- 新增 `showMatchStatusFlow: StateFlow<Boolean>` 与 `showMatchStatus` 属性，默认值为 `false`（选项默认关闭）。

### 4. 歌单内歌曲匹配快速查看 (`app/src/main/java/Akari/NCM/player/ui/screen/MainScreen.kt`)
- 接入 `ThemePrefs.showMatchStatusFlow` 状态。
- 当开关开启时，歌单详情顶部渲染 FilterChip 筛选行：
  - **全部 (总数)**
  - **已匹配 (已匹配数)**
  - **未匹配 (未匹配数)**
- 列表支持实时筛选展示，且保持点击播放全部或指定歌曲时能正确获取原始对应索引及本地/在线歌曲匹配资源。

---

## 二、未执行的修改 (Pending Changes)

### 1. 设置页（SettingsScreen.kt）调试功能 UI
- **调试功能分组**：
  - 在设置网格列表中新增 `SectionHeader("调试功能")`。
- **日志记录设置项**：
  - 单击打开层级调节弹窗（单选 `VERBOSE` / `DEBUG` / `INFO` / `WARN` / `ERROR`，显示当前层级与默认 `ERROR` 提示）。
  - 长按选项弹出自定义大小限制输入弹窗（输入 KB 数，支持保存并即时生效）。
  - 导出入口：提供导出为 `.log`、`.md`、`.zip` 的操作菜单，生成后调用系统 `Intent.ACTION_SEND` 及 `Intent.createChooser` 进行导出与分享。
- **歌曲匹配列表查看开关项**：
  - 增加带有 Switch 的设置行，绑定 `ThemePrefs.showMatchStatus`，提供明确的功能说明（“开启后在歌单详情页快速过滤已匹配/未匹配本地源的歌曲”），默认关闭。

### 2. 自动化测试与验证
- 针对 `AppLogger` 的写入、日志轮转（超过大小限制滚动）及导出归档编写单元测试。
- 校验设置项开关在进程重启后的持久化状态与歌单界面的实时联动。