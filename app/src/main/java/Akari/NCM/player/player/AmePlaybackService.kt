package Akari.NCM.player.player

import Akari.NCM.player.MainActivity
import Akari.NCM.player.R
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 前台媒体播放服务。
 * 共用 [AmePlayerEngine] 内部的 ExoPlayer 实例构建 [MediaSession]，
 * 通过 Media3 [DefaultMediaNotificationProvider] 与系统级 [MediaSession] 自动驱动
 * 系统媒体控制实时窗 / 超级岛 / 流体云 / 原子岛 / Now Bar（封面、进度条及上一曲/播放暂停/下一曲快捷控制）。
 */
@UnstableApi
@AndroidEntryPoint
class AmePlaybackService : MediaSessionService() {

    @Inject
    lateinit var playerEngine: AmePlayerEngine

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        val sessionPlayer = object : ForwardingPlayer(playerEngine.player) {
            override fun getAvailableCommands(): Player.Commands {
                val base = super.getAvailableCommands()
                return if (mediaItemCount > 0) {
                    base.buildUpon()
                        .add(Player.COMMAND_PLAY_PAUSE)
                        .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_NEXT)
                        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .build()
                } else {
                    base
                }
            }

            override fun isCommandAvailable(command: Int): Boolean {
                if (mediaItemCount > 0 && (
                        command == Player.COMMAND_PLAY_PAUSE ||
                        command == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM ||
                        command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                        command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                        command == Player.COMMAND_SEEK_TO_NEXT ||
                        command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
                    )
                ) {
                    return true
                }
                return super.isCommandAvailable(command)
            }

            override fun hasPreviousMediaItem(): Boolean =
                mediaItemCount > 0 || super.hasPreviousMediaItem()

            override fun hasNextMediaItem(): Boolean =
                mediaItemCount > 0 || super.hasNextMediaItem()

            override fun seekToNext() {
                playerEngine.skipToNext()
            }

            override fun seekToNextMediaItem() {
                playerEngine.skipToNext()
            }

            override fun seekToPrevious() {
                playerEngine.skipToPrevious()
            }

            override fun seekToPreviousMediaItem() {
                playerEngine.skipToPrevious()
            }
        }

        val session = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("open_player", true)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setBitmapLoader(CoilSessionBitmapLoader(this, serviceScope))
            .build()

        mediaSession = session
        // 显式注册 MediaSession，确保 MediaNotificationManager 与系统媒体控制器（超级岛/流体云/实况窗）立即生效
        addSession(session)

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(CHANNEL_ID)
            .build()
            .apply {
                setSmallIcon(R.drawable.ic_music_note)
            }

        setMediaNotificationProvider(notificationProvider)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        // 只释放 MediaSession，不释放 ExoPlayer（由 AmePlayerEngine 管理）
        mediaSession?.let {
            removeSession(it)
            it.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            // 没有在播放则停止服务（通知也会消失）
            stopSelf()
        }
        // 正在播放时保持服务存活，让用户可以从通知/灵动岛实时窗控制播放
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * 使用 Coil 与本地安全解码器为 MediaSession 加载非硬件封面 Bitmap，
     * 确保各定制系统（Xiaomi HyperOS 超级岛 / ColorOS 流体云 / OriginOS 原子岛 / One UI Now Bar）
     * 均能正常展示右侧高清封面并提取卡片动态背景色。
     */
    private class CoilSessionBitmapLoader(
        private val context: Context,
        private val scope: CoroutineScope
    ) : BitmapLoader {

        override fun supportsMimeType(mimeType: String): Boolean = true

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
            val future = SettableFuture.create<Bitmap>()
            scope.launch {
                val bitmap = MediaMetadataHelper.decodeSafeBitmap(data)
                if (bitmap != null) {
                    future.set(bitmap)
                } else {
                    future.setException(IllegalArgumentException("Failed to decode artwork byte array"))
                }
            }
            return future
        }

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            val future = SettableFuture.create<Bitmap>()
            scope.launch {
                try {
                    val bitmap: Bitmap? = when (uri.scheme) {
                        "file" -> {
                            val file = File(uri.path ?: "")
                            if (file.exists()) {
                                MediaMetadataHelper.decodeSafeBitmap(file.readBytes())
                            } else null
                        }
                        "content" -> {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                MediaMetadataHelper.decodeSafeBitmap(stream.readBytes())
                            }
                        }
                        else -> {
                            val optimizedUrl = MediaMetadataHelper.optimizeNeteaseCoverUrl(uri.toString())
                            val request = ImageRequest.Builder(context)
                                .data(optimizedUrl)
                                .size(Size(512, 512))
                                .allowHardware(false)
                                .build()
                            (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
                        }
                    }
                    if (bitmap != null) {
                        future.set(bitmap)
                    } else {
                        future.setException(IllegalStateException("Null bitmap loaded for $uri"))
                    }
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }
    }

    companion object {
        const val CHANNEL_ID = "ame_playback"
        const val NOTIFICATION_ID = 2001
    }
}
