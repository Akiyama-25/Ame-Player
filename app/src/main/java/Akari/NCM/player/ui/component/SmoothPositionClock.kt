package Akari.NCM.player.ui.component

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * 按需驱动的高帧率平滑播放时钟插值器
 *
 * - 仅在 [isPlaying] 且 [enabled] 为 true（例如存在可见歌词需要逐字流光扫度）时才挂起屏幕 VSync；
 * - 无歌词或暂停时零帧回调、零额外重组；
 * - 不将高频变化的 [positionMs] 作为 LaunchedEffect key，避免每 50~100ms 反复取消与重建协程，
 *   遇到 Seek、切歌或漂移 > 120ms 时在帧内平滑吸附同步。
 */
@Composable
fun rememberSmoothPosition(
    positionMs: Long,
    isPlaying: Boolean,
    durationMs: Long,
    enabled: Boolean = true
): State<Long> {
    val smoothPosition = remember { mutableLongStateOf(positionMs) }
    val latestPositionMs by rememberUpdatedState(positionMs)

    if (!isPlaying || !enabled) {
        smoothPosition.longValue = positionMs
    }

    LaunchedEffect(isPlaying, enabled, durationMs) {
        var syncBasePosition = latestPositionMs
        var syncBaseTime = SystemClock.uptimeMillis()
        var lastObservedEnginePos = latestPositionMs
        smoothPosition.longValue = syncBasePosition

        if (!isPlaying || !enabled) return@LaunchedEffect

        while (isActive) {
            withFrameMillis {
                val now = SystemClock.uptimeMillis()
                val enginePos = latestPositionMs
                val predicted = syncBasePosition + (now - syncBaseTime)
                if (enginePos != lastObservedEnginePos) {
                    lastObservedEnginePos = enginePos
                    // 发生 Seek/切歌跳转或累计时钟漂移超过 120ms 时重新锚定基准，避免频繁抖动
                    if (abs(enginePos - predicted) > 120L) {
                        syncBasePosition = enginePos
                        syncBaseTime = now
                    }
                }
                val estimated = syncBasePosition + (now - syncBaseTime)
                smoothPosition.longValue = if (durationMs > 0) {
                    estimated.coerceIn(0L, durationMs)
                } else {
                    estimated.coerceAtLeast(0L)
                }
            }
        }
    }

    return smoothPosition
}