package Akari.NCM.player.data

import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.AudioFormat
import android.net.Uri

object NcmFileScanner {

    fun scanUri(uri: Uri, displayName: String): PlayableMedia? {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val isNcm = ext == "ncm"
        val format = when (ext) {
            "flac" -> AudioFormat.FLAC
            "mp3", "wav", "ogg", "opus", "aac", "m4a", "ncm" -> AudioFormat.MP3
            else -> return null
        }

        val baseName = displayName.substringBeforeLast('.')
        // 清理文件名开头的序号 (如 "01. " 或 "12 "), 避免它污染歌手名或曲名导致搜索全军覆没
        val cleanBaseName = baseName.replace(Regex("^\\d+\\.?\\s*"), "").trim()
        
        var artist = "Unknown"
        var title = cleanBaseName

        val parts = cleanBaseName.split(" - ", limit = 2)
        if (parts.size == 2) {
            // 恢复原始空格，不强转为逗号，避免破坏欧美歌手(如 Taylor Swift)或带空格的名字(如 kamome sano)
            artist = parts[0].trim()
            title = parts[1].trim()
        }

        return PlayableMedia.LocalNcm(
            id = uri.toString().hashCode().toString(),
            title = title,
            artist = artist,
            album = "",
            coverUrl = null,
            durationMs = 0L,
            uri = uri.toString(),
            format = format,
            isNcm = isNcm
        )
    }
}
