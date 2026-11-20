package Akari.NCM.player.data

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.LyricLine
import Akari.NCM.player.core.LyricSource
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.PlaylistSourceType
import Akari.NCM.player.data.ttml.AmllLyricsClient
import Akari.NCM.player.data.ttml.LyricsCache
import Akari.NCM.player.data.ttml.TtmlLyricParser
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LyricResult(
    val lines: List<LyricLine>,
    val source: LyricSource
) {
    /**
     * 当前歌曲是否包含逐字播放时间轴（非仅单句时间轴）
     */
    val hasWordTimestamps: Boolean
        get() = lines.any { it.words.isNotEmpty() }
}

class LyricsManager(
    private val context: Context,
    private val ncmApi: NcmApi,
    private val amllLyricsClient: AmllLyricsClient = AmllLyricsClient(LyricsCache(context.cacheDir))
) {

    /**
     * 按播放歌单来源加载歌词：
     * - 本地歌单（PlaylistSourceType.LOCAL）：仅通过音频文件名匹配同目录 .ttml / .lrc 文件，不读内嵌、不联网搜索或拉取云端歌词。
     * - 云端歌单（PlaylistSourceType.ONLINE）：
     *   1. 若匹配到本地文件，优先读取本地文件自身的内嵌歌词元数据；
     *   2. 其次按精确云端歌曲 ID 读取云端歌词（AMLL TTML -> 网易云 YRC -> LRC + 翻译）；
     *   3. 严禁通过歌曲名搜索歌词。
     */
    suspend fun loadLyrics(
        media: PlayableMedia,
        sourceType: PlaylistSourceType = PlaylistSourceType.NONE
    ): LyricResult = withContext(Dispatchers.IO) {
        val effectiveSource = when (sourceType) {
            PlaylistSourceType.ONLINE -> PlaylistSourceType.ONLINE
            PlaylistSourceType.LOCAL -> PlaylistSourceType.LOCAL
            PlaylistSourceType.NONE -> if (media is PlayableMedia.Online) PlaylistSourceType.ONLINE else PlaylistSourceType.LOCAL
        }

        // === 分支 1：本地歌单播放 ===
        // 仅通过文件名匹配本地同目录歌词文件（.ttml 优先，其次 .lrc），不查云端、不搜歌名
        if (effectiveSource == PlaylistSourceType.LOCAL) {
            if (media is PlayableMedia.LocalNcm) {
                val uri = Uri.parse(media.uri)
                val sibling = LocalMediaMetadataHelper.findSiblingLyrics(context, uri, media.title)
                if (sibling != null) {
                    val (content, src) = sibling
                    val parsed = if (src == LyricSource.TTML) {
                        TtmlLyricParser.parse(content)
                    } else {
                        LrcParser.parse(content)
                    }
                    if (parsed.isNotEmpty()) {
                        AppLogger.d("LyricsManager", "Local playlist matched sibling ${src.name} by filename for ${media.title}")
                        return@withContext LyricResult(parsed, src)
                    }
                }
            }
            return@withContext LyricResult(emptyList(), LyricSource.NONE)
        }

        // === 分支 2：云端歌单播放 ===
        // 第 1 优先：若存在本地文件，优先读取本地文件自身内嵌歌词元数据
        if (media is PlayableMedia.LocalNcm) {
            val uri = Uri.parse(media.uri)
            val embeddedRaw = LocalMediaMetadataHelper.extractEmbeddedLyrics(context, uri, media.isNcm)
            if (!embeddedRaw.isNullOrBlank()) {
                val parsed = if (embeddedRaw.contains("<tt", ignoreCase = true) && embeddedRaw.contains("<p", ignoreCase = true)) {
                    TtmlLyricParser.parse(embeddedRaw)
                } else {
                    LrcParser.parse(embeddedRaw)
                }
                if (parsed.isNotEmpty()) {
                    AppLogger.d("LyricsManager", "Cloud playlist loaded local embedded lyrics for ${media.title}")
                    return@withContext LyricResult(parsed, LyricSource.EMBEDDED)
                }
            }
        }

        // 第 2 优先：按精确云端歌曲 ID 读取云端歌词（严禁按歌曲名搜索）
        val onlineSongId: String? = when (media) {
            is PlayableMedia.Online -> media.id
            is PlayableMedia.LocalNcm -> {
                val idAsLong = media.id.toLongOrNull()
                if (idAsLong != null && idAsLong > 0L) {
                    media.id
                } else if (media.isNcm) {
                    val ncmMeta = NcmDataSource.scanMetadata(context.contentResolver, Uri.parse(media.uri))
                    ncmMeta?.first?.musicId?.takeIf { it > 0L }?.toString()
                } else {
                    null
                }
            }
        }

        if (!onlineSongId.isNullOrBlank()) {
            val songIdLong = onlineSongId.toLongOrNull()
            if (songIdLong != null && songIdLong > 0L) {
                val ttmlXml = amllLyricsClient.fetch(songIdLong)
                if (!ttmlXml.isNullOrBlank()) {
                    val parsed = TtmlLyricParser.parse(ttmlXml)
                    if (parsed.isNotEmpty()) {
                        AppLogger.d("LyricsManager", "Fetched AMLL TTML lyrics by exact ID for ${media.title} (id=$songIdLong)")
                        return@withContext LyricResult(parsed, LyricSource.TTML)
                    }
                }
            }

            val lyricData = ncmApi.getLyric(onlineSongId)
            val yrc = lyricData?.yrc
            val mainLrc = lyricData?.lrc
            val transLrc = lyricData?.tlyric

            if (!yrc.isNullOrBlank()) {
                val yrcParsed = YrcLyricParser.parse(yrc)
                if (yrcParsed.isNotEmpty()) {
                    val transParsed = LrcParser.parse(transLrc)
                    val merged = LrcParser.mergeWithTranslation(yrcParsed, transParsed)
                    AppLogger.d("LyricsManager", "Fetched online YRC lyrics by exact ID for ${media.title}")
                    return@withContext LyricResult(merged, LyricSource.YRC)
                }
            }

            if (!mainLrc.isNullOrBlank() || !transLrc.isNullOrBlank()) {
                val mainParsed = LrcParser.parse(mainLrc)
                val transParsed = LrcParser.parse(transLrc)
                val merged = LrcParser.mergeWithTranslation(mainParsed, transParsed)
                if (merged.isNotEmpty()) {
                    AppLogger.d("LyricsManager", "Fetched online lyrics by exact ID for ${media.title}")
                    return@withContext LyricResult(merged, LyricSource.ONLINE)
                }
            }
        }

        // 不通过歌曲名搜索歌词，精确 ID 未命中直接返回无歌词
        LyricResult(emptyList(), LyricSource.NONE)
    }
}