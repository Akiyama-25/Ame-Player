package Akari.NCM.player.player

import Akari.NCM.player.MainActivity
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 前台媒体播放服务。
 * 共用 [AmePlayerEngine] 内部的 ExoPlayer 实例构建 [MediaSession]，
 * 通过 Media3 DefaultMediaNotificationProvider 自动管理前台通知，
 * 保证 App 退到后台后进程持续存活。
 */
@UnstableApi
@AndroidEntryPoint
class AmePlaybackService : MediaSessionService() {

    @Inject
    lateinit var playerEngine: AmePlayerEngine

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // 使用与 AmePlayerEngine 相同的 ExoPlayer 实例，避免双实例问题
        mediaSession = MediaSession.Builder(this, playerEngine.player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .build()
        )
    }

    override fun onDestroy() {
        // 只释放 MediaSession，不释放 ExoPlayer（由 AmePlayerEngine 管理）
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            // 没有在播放则停止服务（通知也会消失）
            stopSelf()
        }
        // 正在播放时保持服务存活，让用户可以从通知控制播放
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    companion object {
        const val CHANNEL_ID = "ame_playback"
        const val NOTIFICATION_ID = 2001
    }
}
