package Akari.NCM.player.player

import Akari.NCM.player.MainActivity
import Akari.NCM.player.R
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 控制中心快捷方式磁贴 (Quick Settings Tile)。
 * 使用基于角色轮廓绘制的 [R.drawable.ic_ame_status] 矢量图标，
 * 支持在系统控制中心一键播放/暂停当前音乐或快速呼出 Ame Player。
 */
@UnstableApi
@AndroidEntryPoint
class AmeQuickSettingsTileService : TileService() {

    @Inject
    lateinit var playerEngine: AmePlayerEngine

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        val currentMedia = playerEngine.currentMedia.value
        if (currentMedia != null) {
            playerEngine.togglePlayPause()
            syncTileState()
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("open_player", true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        val currentMedia = playerEngine.currentMedia.value
        val isPlaying = playerEngine.isPlaying.value
        tile.icon = Icon.createWithResource(this, R.drawable.ic_ame_status)
        if (currentMedia != null) {
            tile.state = if (isPlaying) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = currentMedia.title.ifBlank { getString(R.string.app_name) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = currentMedia.artist.ifBlank {
                    if (isPlaying) "正在播放" else "已暂停"
                }
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.app_name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "点击打开播放器"
            }
        }
        tile.updateTile()
    }
}
