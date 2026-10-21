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
 * 负责管理：
 * 1. 方案一：Android 16+ 原生 Live Updates (适配 ColorOS 16 流体云 / One UI 8 Now Bar / Pixel / HyperOS 3)
 * 2. 方案二：vivo OriginOS 原子岛本地 notification.superx.* 扩展协议
 * 3. 方案二：Meizu Flyme 及类原生系统的状态栏/实况胶囊 Ticker 歌词协议
 */
class IslandLiveNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "ame_live_island_channel"
        // 注意：不要与 Media3 DefaultMediaNotificationProvider 的 ID (2001 / 1001) 冲突
        const val NOTIFICATION_ID = 2002

        // vivo 原子岛限制单个活动刷新不能过快，对纯进度更新做节流（歌词切换则立即刷新）
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
    private var lastLyricLine: String = ""

    private val isVivoDevice: Boolean
        get() = Build.MANUFACTURER.equals("vivo", ignoreCase = true) ||
            Build.BRAND.equals("vivo", ignoreCase = true) ||
            Build.BRAND.equals("iQOO", ignoreCase = true)

    init {
        createSilentPromotableChannel()
    }

    /**
     * 创建符合 Android 16 晋升要求的静音通知渠道
     * 【关键限制】：Live Updates 要求渠道重要性必须 >= IMPORTANCE_DEFAULT（不能是 MIN 或 LOW），
     * 因此必须显式关闭渠道的声音和震动，防止切歌或刷新歌词时打断音乐播放。
     */
    private fun createSilentPromotableChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "实况胶囊与灵动岛歌词",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "用于在状态栏胶囊、流体云、原子岛及 Now Bar 上实时显示歌词与播放进度"
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
     * 引导用户前往系统设置开启本应用的「实况通知 / 流体云」开关（针对 Android 16+）
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
     * 创建或更新灵动岛实况通知
     *
     * @param songTitle 歌曲名
     * @param artist 歌手名
     * @param currentLyric 当前行主歌词（若无则传空字符串）
     * @param currentTranslation 当前行翻译歌词（可选）
     * @param currentPositionMs 当前播放进度（毫秒）
     * @param durationMs 歌曲总时长（毫秒）
     * @param coverBitmap 当前歌曲封面 Bitmap（可选，用于 vivo 原子岛图标）
     * @param contentIntent 点击胶囊跳转回 App 播放页的 PendingIntent
     */
    fun postOrUpdateIsland(
        songTitle: String,
        artist: String,
        currentLyric: String,
        currentTranslation: String?,
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
        val isLyricChanged = currentLyric != lastLyricLine
        if (isVivoDevice && isIslandActive && !isLyricChanged &&
            (now - lastVivoUpdateTime < VIVO_MIN_UPDATE_INTERVAL_MS)
        ) {
            return
        }
        lastLyricLine = currentLyric
        lastVivoUpdateTime = now

        val displayTitle = currentLyric.ifBlank { songTitle }
        val displaySubtitle = buildString {
            if (!currentTranslation.isNullOrBlank()) {
                append(currentTranslation).append(" · ")
            }
            append(songTitle).append(" - ").append(artist)
        }
        // 状态栏胶囊内显示的短文本（建议截断在 10~12 个字符以内以保证各家胶囊完全展示）
        val chipText = currentLyric.ifBlank { songTitle }.take(12)

        val maxProgress = durationMs.coerceAtLeast(1L).toInt()
        val currentProgress = currentPositionMs.coerceIn(0L, durationMs.coerceAtLeast(1L)).toInt()

        // 1. 构建符合 Android 16 Live Updates 严苛要求的 NotificationCompat
        // 注意：严禁调用 setColorized(true)、setGroupSummary(true) 或使用 RemoteViews！
        val liveUpdateExtras = Bundle().apply {
            // 【核心】状态栏灵动岛/流体云胶囊内直接展示的短文本 (Notification.EXTRA_SHORT_CRITICAL_TEXT)
            putCharSequence(EXTRA_SHORT_CRITICAL_TEXT, chipText)
            // 【核心】请求晋升为 Android 16 实况通知 (Notification.EXTRA_REQUEST_PROMOTED_ONGOING)
            putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
            // 注入 ProgressStyle 标记，兼容 Android 16 Live Updates 进度条渲染
            putBoolean("android.styledByProgress", false)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(displayTitle)
            .setContentText(displaySubtitle)
            .setSubText(chipText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setProgress(maxProgress, currentProgress, false)
            .addExtras(liveUpdateExtras)

        if (coverBitmap != null) {
            builder.setLargeIcon(coverBitmap)
        }

        // 2. 注入 vivo OriginOS 原子岛本地扩展参数 (notification.superx.*)
        if (isVivoDevice) {
            val vivoOperation = if (!isIslandActive) 0 else 1 // 0:创建, 1:更新
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

        // 3. 注入魅族 Flyme 及类原生系统的 Ticker 实况歌词 Flag
        FlymeTickerLyricHelper.applyTickerLyrics(
            notification = notification,
            currentLyric = currentLyric,
            isLyricOnlyUpdate = isIslandActive
        )

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        isIslandActive = true
    }

    /**
     * 暂停或停止播放时关闭实况岛通知
     */
    fun cancelIsland() {
        if (!isIslandActive) return

        if (isVivoDevice) {
            // 发送 vivo 原子岛结束指令 (operation = 2)
            val endExtras = Bundle().apply {
                putInt("notification.superx.operation", 2)
            }
            val endNotification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .addExtras(endExtras)
                .build()
            runCatching {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, endNotification)
            }
        }

        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        isIslandActive = false
        lastLyricLine = ""
    }

    /**
     * 构建 vivo OriginOS 本地原子岛扩展 Bundle
     * 若当前设备不支持或未开启原子岛权限，showNotify = true 会确保其平滑回退或由系统忽略
     */
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
                Icon.createWithResource(context, R.drawable.ic_launcher_foreground)
            }
            putParcelable("notification.superx.baseInfos.icon", icon)
            putCharSequence("notification.superx.baseInfos.title", title.take(20))
            putCharSequence("notification.superx.baseInfos.content", content.take(40))
        }

        return Bundle().apply {
            putInt("notification.superx.operation", operation) // 0:创建, 1:更新, 2:结束
            putBoolean("notification.superx.showNotify", true)
            putInt("notification.superx.template", 1)
            putParcelable("notification.superx.clickResp", clickIntent)
            putBundle("notification.superx.baseInfos", baseInfo)
        }
    }
}
