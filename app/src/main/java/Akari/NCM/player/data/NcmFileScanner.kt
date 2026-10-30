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
        val format = when (ext) {
            "flac" -> AudioFormat.FLAC
            "mp3", "wav", "ogg", "opus", "aac", "m4a", "ncm" -> AudioFormat.MP3
            else -> return null
        }

        val rawBaseName = displayName.substringBeforeLast('.').ifBlank { displayName }

        if (!isNcm) {
            // 读取非NCM文件时不做处理，将原始文件名称显示在APP上，不区分艺术家与歌名
            return PlayableMedia.LocalNcm(
                id = uri.toString().hashCode().toString(),
                title = rawBaseName,
                artist = "",
                album = "",
                coverUrl = null,
                durationMs = 0L,
                uri = uri.toString(),
                format = format,
                isNcm = false
            )
        }

        // 仅在读取NCM文件时，通过内嵌数据或歌曲ID获取对应的信息
        var artist = "Unknown"
        var title = rawBaseName
        var musicIdStr: String? = null
        var coverUrl: String? = null
        var album = ""
        var durationMs = 0L
        var resolvedFormat = format

        if (contentResolver != null) {
            try {
                val scanResult = NcmDataSource.scanMetadata(contentResolver, uri)
                if (scanResult != null) {
                    val meta = scanResult.first
                    if (meta.musicId > 0L) {
                        musicIdStr = meta.musicId.toString()
                    }
                    resolvedFormat = scanResult.second
                    if (meta.albumPic.isNotBlank()) {
                        coverUrl = meta.albumPic
                    }
                    if (meta.album.isNotBlank()) {
                        album = meta.album
                    }
                    if (meta.duration > 0L) {
                        durationMs = meta.duration
                    }
                    if (meta.artists.isNotEmpty()) {
                        artist = meta.artists.joinToString("/")
                    }
                    if (meta.musicName.isNotBlank() && meta.musicName != "Unknown") {
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
            format = resolvedFormat,
            isNcm = true
        )
    }
}