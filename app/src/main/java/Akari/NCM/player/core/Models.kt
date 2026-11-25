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
        val matchedLyric: String? = null,
        val albumId: Long = 0L,
        val artistsList: List<NcmArtistBrief> = emptyList()
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

@Serializable
enum class VipTier(val level: Int, val label: String, val badgeText: String) {
    NONE(0, "普通用户", ""),
    VIP(1, "黑胶 VIP", "VIP"),
    SVIP(2, "黑胶 SVIP", "SVIP")
}

@Serializable
data class NcmArtistBrief(
    val id: Long = 0L,
    val name: String
)

fun PlayableMedia.extractArtistCandidates(): List<NcmArtistBrief> {
    if (this is PlayableMedia.Online && artistsList.isNotEmpty()) {
        return artistsList.filter { it.name.isNotBlank() }
    }
    return artist.split('/', ',')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .map { NcmArtistBrief(id = 0L, name = it) }
}

enum class QualityLevel(
    val value: String,
    val label: String,
    val description: String = "",
    val requiredVipTier: VipTier = VipTier.NONE
) {
    STANDARD("standard", "标准音质", "128 kbps MP3 · 极速缓冲与节省流量", VipTier.NONE),
    EXHIGH("exhigh", "极高音质 (320k)", "320 kbps MP3 · 高保真立体声，均衡推荐", VipTier.NONE),
    LOSSLESS("lossless", "无损音质 (FLAC)", "FLAC 16-bit / 44.1 kHz · CD 级无损音质", VipTier.VIP),
    HIRES("hires", "Hi-Res 高解析", "FLAC 24-bit / up to 192 kHz · 高解析度无损音频", VipTier.VIP),
    JYEFFECT("jyeffect", "臻音全景音", "空间环绕臻音 · 沉浸式声场增强", VipTier.SVIP),
    DOLBY("dolby", "杜比全景音", "Dolby Atmos · 三维空间全景音效", VipTier.SVIP),
    SKY("sky", "沉浸环绕音", "多声道沉浸环绕声场体验", VipTier.SVIP),
    JYMASTER("jymaster", "超清母带", "24-bit / 192 kHz · 录音室母带级重制", VipTier.SVIP);

    companion object {
        fun fromValue(value: String): QualityLevel {
            return entries.find { it.value.equals(value, ignoreCase = true) } ?: EXHIGH
        }

        fun displayLabelFor(rawLevel: String?): String {
            return when (rawLevel?.lowercase()) {
                "standard" -> STANDARD.label
                "higher" -> "较高音质 (192k)"
                "exhigh" -> EXHIGH.label
                "lossless" -> LOSSLESS.label
                "hires" -> HIRES.label
                "jyeffect" -> JYEFFECT.label
                "dolby" -> DOLBY.label
                "sky" -> SKY.label
                "jymaster" -> JYMASTER.label
                else -> rawLevel?.takeIf { it.isNotBlank() } ?: EXHIGH.label
            }
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
