package Akari.NCM.player.core

import kotlinx.serialization.Serializable

enum class LyricSource {
    EMBEDDED,   // 音频文件嵌入歌词
    LOCAL_LRC,  // 本地 .lrc 文件歌词
    ONLINE,     // 网易云在线 API 抓取歌词
    TTML,       // AMLL TTML 逐词歌词
    NONE        // 无歌词
}

// 单个字符/单词的耗时元数据
@Serializable
data class WordInfo(
    val text: String,
    val startOffsetMs: Long,  // 相对于整行歌词起始时间的偏移毫秒数
    val durationMs: Long      // 该字/词的持续发音毫秒数
)

// TTML/AMLL 对唱对齐：首位 agent 靠左，其余靠右
enum class LyricAlignment { START, END }

@Serializable
data class LyricLine(
    val timeMs: Long,
    val durationMs: Long = 0,
    val text: String,
    val translation: String? = null,
    val roma: String? = null,
    val words: List<WordInfo> = emptyList(),
    val alignment: LyricAlignment = LyricAlignment.START,
    val backgroundLine: LyricLine? = null
)