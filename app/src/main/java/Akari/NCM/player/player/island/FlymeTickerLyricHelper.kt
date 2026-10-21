package Akari.NCM.player.player.island

import android.app.Notification

object FlymeTickerLyricHelper {

    // Flyme 专有状态栏/实况胶囊歌词 Flag
    const val FLAG_ALWAYS_SHOW_TICKER = 0x01000000
    const val FLAG_ONLY_UPDATE_TICKER = 0x02000000

    /**
     * 将当前歌词注入 Notification 的 tickerText，并打上 Flyme 实况歌词标记
     *
     * @param notification 待发送的 Notification 实例
     * @param currentLyric 当前歌词行（为空则清除 Ticker）
     * @param isLyricOnlyUpdate 是否仅为歌词行刷新（避免重复触发普通通知横幅）
     */
    fun applyTickerLyrics(
        notification: Notification,
        currentLyric: String?,
        isLyricOnlyUpdate: Boolean = true
    ) {
        if (currentLyric.isNullOrBlank()) {
            notification.tickerText = null
            notification.flags = notification.flags and FLAG_ALWAYS_SHOW_TICKER.inv()
            notification.flags = notification.flags and FLAG_ONLY_UPDATE_TICKER.inv()
            return
        }

        notification.tickerText = currentLyric
        notification.flags = notification.flags or FLAG_ALWAYS_SHOW_TICKER
        if (isLyricOnlyUpdate) {
            notification.flags = notification.flags or FLAG_ONLY_UPDATE_TICKER
        } else {
            notification.flags = notification.flags and FLAG_ONLY_UPDATE_TICKER.inv()
        }
    }
}
