package Akari.NCM.player.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import java.io.ByteArrayOutputStream
import kotlin.math.max

object MediaMetadataHelper {

    private const val MAX_ARTWORK_DIMENSION = 512
    private const val JPEG_QUALITY = 85

    /**
     * 构建标准化的 MediaMetadata，确保各厂商灵动岛均能正常读取封面与歌曲信息
     *
     * @param title 歌曲标题
     * @param artist 歌手名
     * @param album 专辑名
     * @param durationMs 歌曲时长（毫秒，部分厂商岛进度条依赖此字段）
     * @param remoteArtworkUrl 在线封面 URL（或 NCM 匹配到的云端封面 URL）
     * @param localArtworkBytes 本地 .ncm 解密提取出的封面字节数组，或本地音频内嵌 ID3 封面字节
     */
    fun buildTrackMediaMetadata(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long? = null,
        remoteArtworkUrl: String? = null,
        localArtworkBytes: ByteArray? = null
    ): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album ?: "")
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)

        if (durationMs != null && durationMs > 0) {
            builder.setDurationMs(durationMs)
        }

        // 优先处理本地 .ncm 解密出的封面字节流（确保离线状态下也能在灵动岛显示封面）
        if (localArtworkBytes != null && localArtworkBytes.isNotEmpty()) {
            val safeBytes = compressArtworkBytes(localArtworkBytes)
            if (safeBytes != null) {
                builder.setArtworkData(safeBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
        }

        // 写入在线封面 URI（附加网易云 CDN 尺寸限制参数，防止大图引发 Android 16 StrictMode 警告）
        if (!remoteArtworkUrl.isNullOrBlank()) {
            val optimizedUrl = optimizeNeteaseCoverUrl(remoteArtworkUrl)
            builder.setArtworkUri(Uri.parse(optimizedUrl))
        }

        return builder.build()
    }

    /**
     * 当 .ncm 或本地歌曲通过网易云 API 异步补全封面/元数据后，原地更新正在播放的 MediaItem，
     * 触发系统 MediaSession 与灵动岛刷新封面，且不打断当前播放进度。
     */
    fun updateCurrentPlayingMetadata(
        player: ExoPlayer,
        mediaId: String,
        updatedMetadata: MediaMetadata
    ) {
        val currentIndex = player.currentMediaItemIndex
        val currentItem = player.currentMediaItem ?: return
        if (currentItem.mediaId != mediaId) return

        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(updatedMetadata)
            .build()

        player.replaceMediaItem(currentIndex, updatedItem)
    }

    /**
     * 为网易云音乐封面 URL 追加缩略图裁剪参数
     */
    fun optimizeNeteaseCoverUrl(url: String): String {
        return if (url.contains("music.126.net") && !url.contains("param=")) {
            val separator = if (url.contains("?")) "&" else "?"
            "${url}${separator}param=${MAX_ARTWORK_DIMENSION}y=${MAX_ARTWORK_DIMENSION}".replace("y=", "y")
        } else {
            url
        }
    }

    /**
     * 将本地 .ncm 解密封面压缩至 512x512 以内，防止跨进程 IPC 传输崩溃
     */
    fun compressArtworkBytes(rawBytes: ByteArray): ByteArray? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)

            val maxDim = max(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > MAX_ARTWORK_DIMENSION) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
                ?: return null

            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                bitmap.recycle()
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将封面字节解码为不超过 512x512 的 Bitmap（用于实况通知与原子岛图标）
     */
    fun decodeSafeBitmap(rawBytes: ByteArray): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
            val maxDim = max(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > MAX_ARTWORK_DIMENSION) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
        } catch (_: Exception) {
            null
        }
    }
}
