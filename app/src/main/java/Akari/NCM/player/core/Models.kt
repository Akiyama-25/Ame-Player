package Akari.NCM.player.core

import kotlinx.serialization.Serializable

sealed class PlayableMedia {
    abstract val id: String
    abstract val title: String
    abstract val artist: String
    abstract val album: String
    abstract val coverUrl: String?
    abstract val durationMs: Long

    data class LocalNcm(
        override val id: String,
        override val title: String,
        override val artist: String,
        override val album: String,
        override val coverUrl: String? = null,
        override val durationMs: Long = 0L,
        val uri: String,  // content:// or file:// URI
        val format: AudioFormat = AudioFormat.MP3,
        override val isNcm: Boolean = false  // true = needs NCM decryption
    ) : PlayableMedia()

    data class Online(
        override val id: String,
        override val title: String,
        override val artist: String,
        override val album: String,
        override val coverUrl: String? = null,
        override val durationMs: Long = 0L,
        val fee: Int = 0,
        val pl: Int? = null,
        val dl: Int? = null,
        val fl: Int? = null,
        val st: Int? = null,
        val alias: String? = null,
        val transName: String? = null,
        val matchedLyric: String? = null
    ) : PlayableMedia()

    val isLocal: Boolean get() = this is LocalNcm
    val isOnline: Boolean get() = this is Online
    open val isNcm: Boolean get() = false

    /**
     * 带副标题/翻译名的完整展示歌名。
     */
    val displayTitle: String get() {
        val sub = when (this) {
            is Online -> transName?.takeIf { it.isNotBlank() } ?: alias?.takeIf { it.isNotBlank() }
            else -> null
        }
        return if (!sub.isNullOrBlank() && !title.contains(sub)) "$title ($sub)" else title
    }

    fun encodeUri(): String = when (this) {
        is LocalNcm -> uri  // Return the original content:// or file:// URI directly
        is Online -> buildString {
            append(id)
            append("?fee=").append(fee)
            pl?.let { append("&pl=").append(it) }
            dl?.let { append("&dl=").append(it) }
            fl?.let { append("&fl=").append(it) }
            st?.let { append("&st=").append(it) }
        }
    }
}

enum class AudioFormat(val mimeType: String, val extension: String) {
    MP3("audio/mpeg", "mp3"),
    FLAC("audio/flac", "flac")
}

enum class QualityLevel(val value: String, val label: String) {
    STANDARD("standard", "标准音质"),
    EXHIGH("exhigh", "极高音质 (320k)"),
    LOSSLESS("lossless", "无损音质 (FLAC)"),
    HIRES("hires", "Hi-Res 高解析"),
    JYMASTER("jymaster", "超清母带");

    companion object {
        fun fromValue(value: String): QualityLevel {
            return entries.find { it.value.equals(value, ignoreCase = true) } ?: EXHIGH
        }
    }
}


@Serializable
data class NcmMetadata(
    val musicId: Long = 0L,
    val musicName: String = "Unknown",
    val artists: List<String> = emptyList(),
    val album: String = "",
    val albumPic: String = "",
    val format: String = "",
    val duration: Long = 0L
)
