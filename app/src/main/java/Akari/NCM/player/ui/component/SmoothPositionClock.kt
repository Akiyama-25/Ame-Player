package Akari.NCM.player.ui.component

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.isActive

/**
 * 高帧率平滑播放时钟插值器 (60fps ~ 120fps)
 *
 * 在 ExoPlayer 底层 ~50ms 轮询更新间隔之间，借助屏幕垂直同步与 [withFrameMillis]
 * 实时插值计算流逝毫秒，驱动逐字歌词达到硬件刷新率级别的丝滑流光扫度；
 * 遇到 Seek 拖拽、暂停或切歌时即时吸附同步，避免累积漂移。
 */
@Composable
fun rememberSmoothPosition(
    positionMs: Long,
    isPlaying: Boolean,
    durationMs: Long
): State<Long> {
    val smoothPosition = remember { mutableLongStateOf(positionMs) }

    LaunchedEffect(positionMs, isPlaying) {
        val syncBasePosition = positionMs
        val syncBaseTime = SystemClock.uptimeMillis()
        smoothPosition.longValue = positionMs

        if (!isPlaying) return@LaunchedEffect

        while (isActive) {
            withFrameMillis {
                val now = SystemClock.uptimeMillis()
                val elapsed = now - syncBaseTime
                val estimated = syncBasePosition + elapsed
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