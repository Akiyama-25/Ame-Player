package Akari.NCM.player.core

import kotlinx.serialization.Serializable

enum class LyricSource {
    EMBEDDED,   // 音频文件嵌入歌词
    LOCAL_LRC,  // 本地 .lrc 文件歌词
    ONLINE,     // 网易云在线 API 抓取歌词
    NONE        // 无歌词
}

@Serializable
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null
)
