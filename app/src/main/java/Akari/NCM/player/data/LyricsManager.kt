package Akari.NCM.player.data

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.LyricLine
import Akari.NCM.player.core.LyricSource
import Akari.NCM.player.core.PlayableMedia
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class LyricResult(
    val lines: List<LyricLine>,
    val source: LyricSource
)

class LyricsManager(
    private val context: Context,
    private val ncmApi: NcmApi
) {

    suspend fun loadLyrics(media: PlayableMedia): LyricResult = withContext(Dispatchers.IO) {
        // Strategy 1: Try local .lrc file or embedded metadata if LocalNcm
        if (media is PlayableMedia.LocalNcm) {
            val uri = Uri.parse(media.uri)

            // Try embedded lyrics via MediaMetadataRetriever
            val embeddedLrc = extractEmbeddedLyrics(uri)
            if (!embeddedLrc.isNullOrBlank()) {
                val parsed = LrcParser.parse(embeddedLrc)
                if (parsed.isNotEmpty()) {
                    Log.d("LyricsManager", "Found embedded lyrics for ${media.title}")
                    return@withContext LyricResult(parsed, LyricSource.EMBEDDED)
                }
            }

            // Try local matching .lrc file (if file:// or content:// has file path)
            val lrcFileContent = findLocalLrcFile(uri)
            if (!lrcFileContent.isNullOrBlank()) {
                val parsed = LrcParser.parse(lrcFileContent)
                if (parsed.isNotEmpty()) {
                    Log.d("LyricsManager", "Found local .lrc file for ${media.title}")
                    return@withContext LyricResult(parsed, LyricSource.LOCAL_LRC)
                }
            }
        }

        // Strategy 2: Fetch online lyrics via NcmApi
        var onlineSongId: String? = when (media) {
            is PlayableMedia.Online -> media.id
            is PlayableMedia.LocalNcm -> {
                // If it's an NCM file, check if we can scan NCM metadata for musicId
                val ncmMeta = NcmDataSource.scanMetadata(context.contentResolver, Uri.parse(media.uri))
                val musicId = ncmMeta?.first?.musicId
                if (musicId != null && musicId > 0) musicId.toString() else null
            }
        }

        suspend fun searchAndFetchLyric(title: String, artist: String): LyricResult? {
            if (title.isBlank()) return null
            val rawFirstArtist = artist.split(",", "/").firstOrNull()?.trim() ?: ""
            val searchArtist = rawFirstArtist.split(Regex("\\s+")).firstOrNull()?.trim() ?: ""
            val cleanTitle = title.replace(Regex("^\\d+\\.?\\s*"), "").trim()
            val query = if (searchArtist.isNotBlank() && searchArtist != "Unknown") {
                "$searchArtist $cleanTitle"
            } else {
                cleanTitle
            }
            val searchResults = ncmApi.searchSongs(query.trim(), limit = 5)
            
            // 将搜索结果智能排序：
            // 1. 优先尝试歌手名匹配的
            // 2. 其次尝试歌名完全一样的 (这样即使歌手名不匹配，比如用户听的是翻唱，也能拿到原唱的歌词)
            val prioritizedResults = searchResults.sortedByDescending {
                when {
                    it.artist.contains(searchArtist, ignoreCase = true) -> 2
                    it.title.equals(cleanTitle, ignoreCase = true) -> 1
                    else -> 0
                }
            }

            // 逐个尝试获取歌词，只要找到一个非纯音乐（有实质歌词）的，立刻返回！
            for (song in prioritizedResults) {
                val (m, t) = ncmApi.getLyric(song.id) ?: Pair(null, null)
                if (!m.isNullOrBlank() || !t.isNullOrBlank()) {
                    val mP = LrcParser.parse(m)
                    val tP = LrcParser.parse(t)
                    val merged = LrcParser.mergeWithTranslation(mP, tP)
                    // 必须有实质性的歌词，不能是空的或者纯音乐标识
                    if (merged.isNotEmpty() && merged.any { it.text.isNotBlank() && !it.text.contains("纯音乐") }) {
                        android.util.Log.d("LyricsManager", "Fetched fallback online lyrics from ${song.title} - ${song.artist}")
                        return LyricResult(merged, LyricSource.ONLINE)
                    }
                }
            }
            return null
        }

        // Try exact ID first (for Online or NCM with musicId)
        if (!onlineSongId.isNullOrBlank()) {
            val (mainLrc, transLrc) = ncmApi.getLyric(onlineSongId) ?: Pair(null, null)
            if (!mainLrc.isNullOrBlank() || !transLrc.isNullOrBlank()) {
                val mainParsed = LrcParser.parse(mainLrc)
                val transParsed = LrcParser.parse(transLrc)
                val merged = LrcParser.mergeWithTranslation(mainParsed, transParsed)
                if (merged.isNotEmpty()) {
                    Log.d("LyricsManager", "Fetched online lyrics by exact ID for ${media.title}")
                    return@withContext LyricResult(merged, LyricSource.ONLINE)
                }
            }
        }

        // If exact ID failed (e.g. Cloud Drive song without lyrics) or no ID, fallback to text search
        val fallbackResult = searchAndFetchLyric(media.title, media.artist)
        if (fallbackResult != null) {
            return@withContext fallbackResult
        }

        LyricResult(emptyList(), LyricSource.NONE)
    }

    private fun extractEmbeddedLyrics(uri: Uri): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            // Key 1000 corresponds to METADATA_KEY_LYRIC in MediaMetadataRetriever
            retriever.extractMetadata(1000)
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun findLocalLrcFile(uri: Uri): String? {
        return try {
            val path = uri.path ?: return null
            val audioFile = File(path)
            if (audioFile.exists()) {
                val lrcFile = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc")
                if (lrcFile.exists()) {
                    return lrcFile.readText()
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
