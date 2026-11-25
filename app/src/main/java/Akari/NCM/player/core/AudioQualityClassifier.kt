package Akari.NCM.player.core

/**
 * 统一的歌曲音质判定与展示工具。
 *
 * 设计约束：
 * - 在线歌曲优先依据服务端返回的 `qualityLevel` 直接映射；
 * - 本地歌曲基于保存文件的实际音频信息（编码格式、声道数、无损属性、位深、采样率、比特率）判定，
 *   所有本地音质判定条件严格两两互斥（无任何重合判定特征）：
 *   1. 杜比全景音：杜比编码（E-AC-3 / E-AC-3 JOC / AC-3 / AC-4）
 *   2. 沉浸环绕音：非杜比编码 且 多声道（channels > 2）
 *   3. 超清母带  ：非杜比编码 且 非多声道（channels <= 2）且 无损格式 且 位深 >= 24-bit 且 采样率 >= 176.4 kHz
 *   4. Hi-Res    ：非杜比编码 且 非多声道（channels <= 2）且 无损格式 且 位深 >= 24-bit 且 采样率 < 176.4 kHz
 *   5. 无损      ：非杜比编码 且 非多声道（channels <= 2）且 无损格式 且 位深 < 24-bit（如 16-bit FLAC / WAV / ALAC）
 *   6. 极高      ：非杜比编码 且 非多声道（channels <= 2）且 有损格式 且 比特率 >= 256 kbps
 *   7. 较高      ：非杜比编码 且 非多声道（channels <= 2）且 有损格式 且 比特率 in 160..255 kbps
 *   8. 标准      ：非杜比编码 且 非多声道（channels <= 2）且 有损格式 且 比特率 in 1..159 kbps
 */
enum class AudioQualityBadge(
    val key: String,
    val shortLabel: String,
    val fullLabel: String
) {
    STANDARD("standard", "标准", "标准音质"),
    HIGHER("higher", "较高", "较高音质"),
    EXHIGH("exhigh", "极高", "极高音质"),
    LOSSLESS("lossless", "无损", "无损音质"),
    HIRES("hires", "Hi-Res", "Hi-Res 高解析"),
    JYEFFECT("jyeffect", "臻音全景音", "臻音全景音"),
    DOLBY("dolby", "杜比全景音", "杜比全景音"),
    SKY("sky", "沉浸环绕音", "沉浸环绕音"),
    JYMASTER("jymaster", "超清母带", "超清母带")
}

data class LocalAudioFeatureSnapshot(
    val isDolbyCodec: Boolean,
    val isLosslessFormat: Boolean,
    val isKnownFormat: Boolean,
    val bitDepthBits: Int,
    val sampleRateHz: Int,
    val channels: Int,
    val bitrateKbps: Int
)

data class LocalQualityRule(
    val badge: AudioQualityBadge,
    val matches: (LocalAudioFeatureSnapshot) -> Boolean
)

object AudioQualityClassifier {

    const val MASTER_MIN_SAMPLE_RATE_HZ = 176_400
    const val HIRES_MIN_BIT_DEPTH = 24
    const val EXHIGH_MIN_BITRATE_KBPS = 256
    const val HIGHER_MIN_BITRATE_KBPS = 160

    /**
     * 本地音频音质判定规则表：所有规则谓词严格两两互斥，任意一份合法音频特征最多命中 1 条规则。
     */
    val localRules: List<LocalQualityRule> = listOf(
        LocalQualityRule(AudioQualityBadge.DOLBY) { f ->
            f.isDolbyCodec
        },
        LocalQualityRule(AudioQualityBadge.SKY) { f ->
            !f.isDolbyCodec && f.channels > 2
        },
        LocalQualityRule(AudioQualityBadge.JYMASTER) { f ->
            !f.isDolbyCodec &&
                f.channels <= 2 &&
                f.isLosslessFormat &&
                f.bitDepthBits >= HIRES_MIN_BIT_DEPTH &&
                f.sampleRateHz >= MASTER_MIN_SAMPLE_RATE_HZ
        },
        LocalQualityRule(AudioQualityBadge.HIRES) { f ->
            !f.isDolbyCodec &&
                f.channels <= 2 &&
                f.isLosslessFormat &&
                f.bitDepthBits >= HIRES_MIN_BIT_DEPTH &&
                f.sampleRateHz < MASTER_MIN_SAMPLE_RATE_HZ
        },
        LocalQualityRule(AudioQualityBadge.LOSSLESS) { f ->
            !f.isDolbyCodec &&
                f.channels <= 2 &&
                f.isLosslessFormat &&
                f.bitDepthBits < HIRES_MIN_BIT_DEPTH
        },
        LocalQualityRule(AudioQualityBadge.EXHIGH) { f ->
            !f.isDolbyCodec &&
                f.channels <= 2 &&
                !f.isLosslessFormat &&
                f.isKnownFormat &&
                f.bitrateKbps >= EXHIGH_MIN_BITRATE_KBPS
        },
        LocalQualityRule(AudioQualityBadge.HIGHER) { f ->
            !f.isDolbyCodec &&
                f.channels <= 2 &&
                !f.isLosslessFormat &&
                f.isKnownFormat &&
                f.bitrateKbps in HIGHER_MIN_BITRATE_KBPS until EXHIGH_MIN_BITRATE_KBPS
        },
        LocalQualityRule(AudioQualityBadge.STANDARD) { f ->
            !f.isDolbyCodec &&
                f.channels <= 2 &&
                !f.isLosslessFormat &&
                f.isKnownFormat &&
                f.bitrateKbps in 1 until HIGHER_MIN_BITRATE_KBPS
        }
    )

    fun fromOnlineLevel(rawLevel: String?): AudioQualityBadge? {
        return when (rawLevel?.trim()?.lowercase()) {
            "standard" -> AudioQualityBadge.STANDARD
            "higher" -> AudioQualityBadge.HIGHER
            "exhigh" -> AudioQualityBadge.EXHIGH
            "lossless" -> AudioQualityBadge.LOSSLESS
            "hires" -> AudioQualityBadge.HIRES
            "jyeffect" -> AudioQualityBadge.JYEFFECT
            "dolby" -> AudioQualityBadge.DOLBY
            "sky" -> AudioQualityBadge.SKY
            "jymaster" -> AudioQualityBadge.JYMASTER
            else -> null
        }
    }

    fun buildLocalFeatureSnapshot(
        formatInfo: AudioFormatInfo,
        fallbackFormat: AudioFormat? = null,
        uriOrName: String? = null
    ): LocalAudioFeatureSnapshot {
        val mime = formatInfo.mimeType?.trim()?.lowercase().orEmpty()
        val ext = extractLowerExtension(uriOrName)

        val isDolby = isDolbyMime(mime) || ext in DOLBY_EXTENSIONS

        val mimeLossless: Boolean? = when {
            isDolby -> false
            mime.isEmpty() -> null
            isLosslessMime(mime) -> true
            isLossyMime(mime) -> false
            else -> null
        }

        val extLossless: Boolean? = when {
            isDolby -> false
            ext in LOSSLESS_EXTENSIONS -> true
            ext in LOSSY_EXTENSIONS -> false
            else -> null
        }

        val fallbackLossless: Boolean? = when (fallbackFormat) {
            AudioFormat.FLAC -> true
            AudioFormat.MP3 -> false
            null -> null
        }

        val resolvedLossless = mimeLossless ?: extLossless ?: fallbackLossless ?: false
        val isKnownFormat = isDolby || mimeLossless != null || extLossless != null || fallbackLossless != null

        val safeChannels = if (formatInfo.channels > 0) formatInfo.channels else 2
        val safeBitDepth = if (formatInfo.bitDepthBits > 0) formatInfo.bitDepthBits else 16
        val safeSampleRate = formatInfo.sampleRateHz.coerceAtLeast(0)
        val safeBitrate = formatInfo.bitrateKbps.coerceAtLeast(0)

        return LocalAudioFeatureSnapshot(
            isDolbyCodec = isDolby,
            isLosslessFormat = resolvedLossless,
            isKnownFormat = isKnownFormat,
            bitDepthBits = safeBitDepth,
            sampleRateHz = safeSampleRate,
            channels = safeChannels,
            bitrateKbps = safeBitrate
        )
    }

    fun classifyLocal(
        formatInfo: AudioFormatInfo,
        fallbackFormat: AudioFormat? = null,
        uriOrName: String? = null
    ): AudioQualityBadge? {
        val snapshot = buildLocalFeatureSnapshot(
            formatInfo = formatInfo,
            fallbackFormat = fallbackFormat,
            uriOrName = uriOrName
        )
        return classifySnapshot(snapshot)
    }

    fun classifySnapshot(snapshot: LocalAudioFeatureSnapshot): AudioQualityBadge? {
        return localRules.firstOrNull { it.matches(snapshot) }?.badge
    }

    fun classify(
        media: PlayableMedia?,
        formatInfo: AudioFormatInfo
    ): AudioQualityBadge? {
        if (media == null) return null
        return when (media) {
            is PlayableMedia.Online -> {
                fromOnlineLevel(formatInfo.qualityLevel)
                    ?: classifyLocal(formatInfo = formatInfo, fallbackFormat = null, uriOrName = null)
            }
            is PlayableMedia.LocalNcm -> {
                classifyLocal(
                    formatInfo = formatInfo,
                    fallbackFormat = media.format,
                    uriOrName = media.uri
                )
            }
        }
    }

    private fun isDolbyMime(mime: String): Boolean {
        return mime.contains("eac3") ||
            mime.contains("ec-3") ||
            mime.contains("ac3") ||
            mime.contains("ac-3") ||
            mime.contains("ac4") ||
            mime.contains("ac-4") ||
            mime.contains("dolby")
    }

    private fun isLosslessMime(mime: String): Boolean {
        return mime.contains("flac") ||
            mime.contains("alac") ||
            mime.contains("wav") ||
            mime.contains("aiff") ||
            mime.contains("audio/raw") ||
            mime.contains("pcm") ||
            mime.contains("ape") ||
            mime.contains("dsd") ||
            mime.contains("dsf")
    }

    private fun isLossyMime(mime: String): Boolean {
        return mime.contains("mpeg") ||
            mime.contains("mp3") ||
            mime.contains("mp4a") ||
            mime.contains("aac") ||
            mime.contains("ogg") ||
            mime.contains("opus") ||
            mime.contains("vorbis") ||
            mime.contains("mp4") ||
            mime.contains("m4a") ||
            mime.contains("wma")
    }

    private fun extractLowerExtension(uriOrName: String?): String {
        if (uriOrName.isNullOrBlank()) return ""
        val decoded = runCatching { android.net.Uri.decode(uriOrName) }.getOrDefault(uriOrName)
        val clean = decoded.substringBefore('?').substringBefore('#')
        val leaf = clean.substringAfterLast('/').substringAfterLast(':')
        val dot = leaf.lastIndexOf('.')
        if (dot < 0 || dot == leaf.lastIndex) return ""
        return leaf.substring(dot + 1).lowercase()
    }

    private val DOLBY_EXTENSIONS = setOf("ec3", "eac3", "ac3", "ac4")
    private val LOSSLESS_EXTENSIONS = setOf("flac", "wav", "alac", "aiff", "aif", "ape", "dsf", "dff")
    private val LOSSY_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wma")
}
