# Ame Player 歌词系统架构与解析逻辑文档 (Lyric.md)

本文档记录 Ame Player (NCM 播放器) 的歌词提取、在线抓取、双语合并对齐、来源识别及 UI 呈现的完整实现逻辑。

---

## 1. 歌词加载策略 (Lyrics Hierarchy & Search Fallback)

歌词解析由 [`LyricsManager.kt`](file:///C:/Users/jocsu/.claude/ame-player/app/src/main/java/Akari/NCM/player/data/LyricsManager.kt) 统一调度，按优先级依次检索：

```
                              [加载歌词请求]
                                    │
                    ┌───────────────┴───────────────┐
                    ▼                               ▼
             [本地 NCM / 音频文件]              [在线歌曲]
                    │                               │
       ┌────────────┴────────────┐                  │
       ▼                         ▼                  │
 1. 读取内嵌歌词             2. 读取同目录            │
(METADATA_KEY_LYRIC)        同名 .lrc 文件           │
       │                         │                  │
       ├─ (有歌词) ──> [返回]    ├─ (有歌词) ──> [返回] │
       │                         │                  │
       └─ (无歌词) ──────────────┴─ (无歌词) ────────┤
                                                    │
                                                    ▼
                                    3. 网易云在线 API 抓取
                                     ├─ NCM 文件: 使用内置 musicId
                                     └─ 非 NCM/普通 MP3: 自动以 "歌名 + 歌手" 搜索获取在线 songId
                                                    │
                                                    ▼
                                    4. 抓取 lrc (原文) + tlyric (译文)
                                                    │
                                                    ▼
                                    5. 时间戳精准对齐合并双语歌词
```

### 来源类型标识 (`LyricSource`)
- `EMBEDDED`: 从音频文件 ID3v2 / Metadata 中提取的内嵌歌词。
- `LOCAL_LRC`: 从文件同级目录下读取的同名 `.lrc` 文件。
- `ONLINE`: 通过网易云 API 抓取的在线歌词（含普通本地音频模糊匹配搜索到的在线歌词）。
- `NONE`: 未找到可用歌词。

---

## 2. 双语歌词解析与对齐算法 (`LrcParser.kt`)

1. **LRC 时间戳提取**：
   通过正则表达式 `\[(\d{2}):(\d{2})(?:[\.\:](\d{2,3}))?\]` 解析分、秒、毫秒并统一换算为 `timeMs: Long`。
2. **双语对齐匹配 (`mergeWithTranslation`)**：
   - 提取主歌词 `lrc` 与翻译歌词 `tlyric`。
   - 对主歌词列表按时间戳在翻译列表中匹配：
     1. 优先精细匹配相同 `timeMs`。
     2. 容错匹配 `abs(transTime - mainTime) <= 500ms` 的偏移行。
   - 匹配成功的译文写入 `LyricLine.translation` 属性，组成带翻译的双语歌词模型。

---

## 3. 非 NCM 音频文件通用支持

对于普通 MP3 / FLAC / WAV 等本地文件：
- 当内嵌歌词与同目录 `.lrc` 均不存在时，`LyricsManager` 自动提取 `media.title` 与 `media.artist`，调用 `ncmApi.searchSongs("$title $artist", limit = 1)` 在线匹配关联的网易云曲库歌曲 ID。
- 获取 ID 后自动无缝加载其在线歌词及翻译。

---

## 4. Salt Player 风格全屏歌词 UI 交互 (`PlayerScreen.kt`)

- **3 行封面小预览**：封面模式下展示上一句、当前句加粗放大（带翻译小字）及下一句。
- **全屏歌词流与隔离布局**：
  - 将歌词滚动列表完全隔离包含在 `Column(weight(1f))` 的独立 ViewBox 内，底部 `词 ONLINE` 与 `译` 按钮独立在容器下方，彻底解决文字与控制按钮的交叉覆盖碰撞问题。
  - **Pure Alpha Mask (`CompositingStrategy.Offscreen`)**：使用了 Compose 的 `CompositingStrategy.Offscreen` 离屏渲染层配合 `BlendMode.DstOut` 擦除掩码（渐显/渐隐高度为 `120.dp`，动态换算适配全分辨率屏幕）。彻底解决特定硬件/系统下擦除失效的问题，只切除歌词文字本身的 Alpha 透明度，背景完全无阴影或黑块。
- **播放上下文与播放模式重构**：
  - **播放范围严格上下文限定**：当歌曲在自建歌单 A 中被点开时，自动清空旧列表并填入歌单 A 的所有曲目；当歌曲在“全部曲目”主列表中被点开时，播放范围自动设为全部曲目。
  - **移除列表循环模式**：仅保留【顺序播放】➜【单曲循环】➜【随机播放】三态循环。
