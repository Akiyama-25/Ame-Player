package Akari.NCM.player.data

import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.AudioFormat
import android.content.ContentResolver
import android.net.Uri

object NcmFileScanner {

    fun scanUri(
        uri: Uri,
        displayName: String,
        contentResolver: ContentResolver? = null
    ): PlayableMedia? {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val isNcm = ext == "ncm"
        var format = when (ext) {
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

        var musicIdStr: String? = null
        var coverUrl: String? = null
        var album = ""
        var durationMs = 0L

        // 优先提取 NCM 文件内嵌的网易云歌曲 ID 与元数据
        if (isNcm && contentResolver != null) {
            try {
                val scanResult = NcmDataSource.scanMetadata(contentResolver, uri)
                if (scanResult != null) {
                    val meta = scanResult.first
                    if (meta.musicId > 0L) {
                        musicIdStr = meta.musicId.toString()
                    }
                    format = scanResult.second
                    if (meta.albumPic.isNotBlank()) {
                        coverUrl = meta.albumPic
                    }
                    if (meta.album.isNotBlank()) {
                        album = meta.album
                    }
                    if (meta.duration > 0L) {
                        durationMs = meta.duration
                    }
                    if (artist == "Unknown" && meta.artists.isNotEmpty()) {
                        artist = meta.artists.joinToString("/")
                    }
                    if (title.isBlank() && meta.musicName.isNotBlank() && meta.musicName != "Unknown") {
                        title = meta.musicName
                    }
                }
            } catch (_: Exception) {}
        }

        return PlayableMedia.LocalNcm(
            id = musicIdStr ?: uri.toString().hashCode().toString(),
            title = title,
            artist = artist,
            album = album,
            coverUrl = coverUrl,
            durationMs = durationMs,
            uri = uri.toString(),
            format = format,
            isNcm = isNcm
        )
    }
}