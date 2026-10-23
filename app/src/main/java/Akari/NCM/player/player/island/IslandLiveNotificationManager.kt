package Akari.NCM.player.player.island

import Akari.NCM.player.R
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 负责辅助管理播放状态实况通知权限检查与多厂商扩展参数封装。
 * 核心播放状态展示与快捷控制实时窗由 [Akari.NCM.player.player.AmePlaybackService] 的
 * Media3 [androidx.media3.session.MediaSession] 与 MediaStyle 通知统一驱动。
 */
class IslandLiveNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "ame_live_island_channel"
        const val NOTIFICATION_ID = 2002

        const val VIVO_MIN_UPDATE_INTERVAL_MS = 2500L

        const val ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS =
            "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS"
        const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
        const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    var isIslandActive = false
        private set

    private var lastVivoUpdateTime = 0L
    private var lastTrackKey: String = ""

    private val isVivoDevice: Boolean
        get() = Build.MANUFACTURER.equals("vivo", ignoreCase = true) ||
            Build.BRAND.equals("vivo", ignoreCase = true) ||
            Build.BRAND.equals("iQOO", ignoreCase = true)

    init {
        // 清理旧版本可能残留的独立歌词横幅通知，避免遮挡系统原生媒体控制实时窗
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
        createSilentPromotableChannel()
    }

    private fun createSilentPromotableChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "播放状态实况窗",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "用于在系统灵动岛、流体云、超级岛及实时窗展示播放状态与快捷控制"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 检查当前设备（Android 16+）是否允许本应用发送晋升实况通知
     */
    fun canPostLiveUpdates(): Boolean {
        return if (Build.VERSION.SDK_INT >= 36) {
            runCatching { notificationManager.canPostPromotedNotifications() }.getOrDefault(true)
        } else {
            true
        }
    }

    /**
     * 引导用户前往系统设置开启本应用的「实况通知 / 流体云 / 超级岛」开关
     */
    fun openPromotedNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 36) {
            val intent = Intent(ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    /**
     * 构建或更新纯播放状态实况通知（不含歌词文本，仅展示歌曲标题、歌手与播放进度）
     */
    fun postOrUpdateIsland(
        songTitle: String,
        artist: String,
        currentLyric: String = "",
        currentTranslation: String? = null,
        currentPositionMs: Long,
        durationMs: Long,
        coverBitmap: Bitmap?,
        contentIntent: PendingIntent
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        val trackKey = "$songTitle|$artist"
        val isTrackChanged = trackKey != lastTrackKey
        if (isVivoDevice && isIslandActive && !isTrackChanged &&
            (now - lastVivoUpdateTime < VIVO_MIN_UPDATE_INTERVAL_MS)
        ) {
            return
        }
        lastTrackKey = trackKey
        lastVivoUpdateTime = now

        val displayTitle = songTitle
        val displaySubtitle = artist.ifBlank { "正在播放" }
        val chipText = songTitle.take(12)

        val maxProgress = durationMs.coerceAtLeast(1L).toInt()
        val currentProgress = currentPositionMs.coerceIn(0L, durationMs.coerceAtLeast(1L)).toInt()

        val liveUpdateExtras = Bundle().apply {
            putCharSequence(EXTRA_SHORT_CRITICAL_TEXT, chipText)
            putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
            putBoolean("android.styledByProgress", false)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ame_status)
            .setContentTitle(displayTitle)
            .setContentText(displaySubtitle)
            .setSubText(chipText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setProgress(maxProgress, currentProgress, false)
            .addExtras(liveUpdateExtras)

        if (coverBitmap != null) {
            builder.setLargeIcon(coverBitmap)
        }

        if (isVivoDevice) {
            val vivoOperation = if (!isIslandActive) 0 else 1
            val vivoExtras = buildVivoSuperxExtras(
                operation = vivoOperation,
                title = displayTitle,
                content = displaySubtitle,
                coverBitmap = coverBitmap,
                clickIntent = contentIntent
            )
            builder.addExtras(vivoExtras)
        }

        val notification = builder.build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        isIslandActive = true
    }

    /**
     * 关闭附加实况通知
     */
    fun cancelIsland() {
        if (!isIslandActive) {
            runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
            return
        }

        if (isVivoDevice) {
            val endExtras = Bundle().apply {
                putInt("notification.superx.operation", 2)
            }
            val endNotification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ame_status)
                .setSilent(true)
                .addExtras(endExtras)
                .build()
            runCatching {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, endNotification)
            }
        }

        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        isIslandActive = false
        lastTrackKey = ""
    }

    internal fun buildVivoSuperxExtras(
        operation: Int,
        title: String,
        content: String,
        coverBitmap: Bitmap?,
        clickIntent: PendingIntent
    ): Bundle {
        val baseInfo = Bundle().apply {
            val icon = if (coverBitmap != null) {
                Icon.createWithBitmap(coverBitmap)
            } else {
                Icon.createWithResource(context, R.drawable.ic_ame_status)
            }
            putParcelable("notification.superx.baseInfos.icon", icon)
            putCharSequence("notification.superx.baseInfos.title", title.take(20))
            putCharSequence("notification.superx.baseInfos.content", content.take(40))
        }

        return Bundle().apply {
            putInt("notification.superx.operation", operation)
            putBoolean("notification.superx.showNotify", true)
            putInt("notification.superx.template", 1)
            putParcelable("notification.superx.clickResp", clickIntent)
            putBundle("notification.superx.baseInfos", baseInfo)
        }
    }
}
