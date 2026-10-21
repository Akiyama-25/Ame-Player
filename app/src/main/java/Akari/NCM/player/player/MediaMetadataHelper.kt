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
     * 构建标准化的 MediaMetadata，确保各厂商媒体实时窗/灵动岛（HyperOS 超级岛 / ColorOS 流体云 /
     * OriginOS 原子岛 / One UI Now Bar）均能正常读取封面、时长与曲目信息。
     *
     * @param title 歌曲标题
     * @param artist 歌手名
     * @param album 专辑名
     * @param durationMs 歌曲时长（毫秒，媒体实时窗进度条依赖此字段）
     * @param remoteArtworkUrl 在线封面 URL（或 NCM 匹配到的云端封面 URL）
     * @param localArtworkBytes 封面字节数组（本地 .ncm 解密封面、ID3 封面或已缓存的在线封面字节）
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
            .setDisplayTitle(title)
            .setSubtitle(artist)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)

        if (durationMs != null && durationMs > 0) {
            builder.setDurationMs(durationMs)
        }

        // 优先写入压缩后的封面字节流（确保各定制系统 SystemUI 100% 拿到封面并提取实时窗背景动态色）
        if (localArtworkBytes != null && localArtworkBytes.isNotEmpty()) {
            val safeBytes = compressArtworkBytes(localArtworkBytes)
            if (safeBytes != null) {
                builder.setArtworkData(safeBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
        }

        // 写入在线封面 URI（附加网易云 CDN 尺寸限制参数，防止大图引发降采样耗时与内存问题）
        if (!remoteArtworkUrl.isNullOrBlank()) {
            val optimizedUrl = optimizeNeteaseCoverUrl(remoteArtworkUrl)
            builder.setArtworkUri(Uri.parse(optimizedUrl))
        }

        return builder.build()
    }

    /**
     * 当歌曲异步获取到封面 Bitmap/字节流或真实时长后，原地更新正在播放的 MediaItem，
     * 触发系统 MediaSession 与媒体控制实时窗刷新封面与进度条，且不打断当前播放进度。
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
            "${url}${separator}param=${MAX_ARTWORK_DIMENSION}y${MAX_ARTWORK_DIMENSION}"
        } else {
            url
        }
    }

    /**
     * 将封面字节流压缩至 512x512 以内，防止跨进程 IPC 传输崩溃
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
     * 将已加载的 Bitmap 压缩为安全的 JPEG 字节数组（用于写入 MediaMetadata.artworkData）
     */
    fun bitmapToCompressedBytes(bitmap: Bitmap): ByteArray? {
        return try {
            val maxDim = max(bitmap.width, bitmap.height)
            val targetBitmap = if (maxDim > MAX_ARTWORK_DIMENSION && maxDim > 0) {
                val scale = MAX_ARTWORK_DIMENSION.toFloat() / maxDim
                val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            } else {
                bitmap
            }
            ByteArrayOutputStream().use { out ->
                targetBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 将封面字节解码为不超过 512x512 的 Bitmap
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
