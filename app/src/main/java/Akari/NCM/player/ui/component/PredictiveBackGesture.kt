package Akari.NCM.player.ui.component

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * AOSP Material 3 预见性返回标准阻尼曲线
 */
val PredictiveBackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/**
 * 封装 AOSP 预见性返回手势的实时状态，支持跨页面与页内组件的跟手缩放、圆角形变与边缘位移。
 */
@Stable
class PredictiveBackState {
    var progress by mutableFloatStateOf(0f)
        internal set

    var isActive by mutableStateOf(false)
        internal set

    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        internal set

    var touchX by mutableFloatStateOf(0f)
        internal set

    var touchY by mutableFloatStateOf(0f)
        internal set

    val isVisibleInBackground: Boolean
        get() = isActive || progress > 0.001f

    fun onGestureStarted() {
        isActive = true
        progress = 0f
    }

    fun onGestureProgress(
        rawProgress: Float,
        edge: Int,
        x: Float,
        y: Float
    ) {
        isActive = true
        swipeEdge = edge
        touchX = x
        touchY = y
        progress = rawProgress.coerceIn(0f, 1f)
    }

    suspend fun animateCancelSpringBack() {
        withContext(NonCancellable) {
            val startProgress = progress
            if (startProgress > 0.001f && coroutineContext[MonotonicFrameClock] != null) {
                runCatching {
                    animate(
                        initialValue = startProgress,
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) { value, _ ->
                        progress = value
                    }
                }
            }
            reset()
        }
    }

    fun reset() {
        progress = 0f
        isActive = false
        touchX = 0f
        touchY = 0f
    }
}

/**
 * 注册并记忆一个 AOSP 预见性返回状态处理器。
 *
 * - 手势滑动中：实时同步更新 [PredictiveBackState.progress] (0f..1f)、[PredictiveBackState.swipeEdge] 与触点坐标
 * - 手势中途取消：通过物理弹簧动画平滑回弹至初始状态
 * - 手势松手确认：触发 [onBack] 回调并重置手势状态
 */
@Composable
fun rememberPredictiveBackState(
    enabled: Boolean,
    onBack: () -> Unit
): PredictiveBackState {
    val state = remember { PredictiveBackState() }
    val currentOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(enabled) {
        if (!enabled) {
            state.reset()
        }
    }

    PredictiveBackHandler(enabled = enabled) { backEvents: Flow<BackEventCompat> ->
        try {
            state.onGestureStarted()
            backEvents.collect { event ->
                state.onGestureProgress(
                    rawProgress = event.progress,
                    edge = event.swipeEdge,
                    x = event.touchX,
                    y = event.touchY
                )
            }
            currentOnBack()
            state.reset()
        } catch (e: CancellationException) {
            state.animateCancelSpringBack()
            throw e
        }
    }

    return state
}

/**
 * 为前景视图应用 AOSP 预见性返回的跟手缩放、动态圆角、阴影与边缘位移。
 *
 * @param state 预见性返回状态
 * @param pullDownDp 纵向附加下移距离（如全屏播放页返回底部迷你播放条时可设较大值）
 */
fun Modifier.predictiveBackForeground(
    state: PredictiveBackState,
    pullDownDp: Float = 12f
): Modifier = this.graphicsLayer {
    val rawP = state.progress.coerceIn(0f, 1f)
    if (rawP <= 0.0005f && !state.isActive) {
        scaleX = 1f
        scaleY = 1f
        translationX = 0f
        translationY = 0f
        alpha = 1f
        clip = false
        shadowElevation = 0f
        return@graphicsLayer
    }

    val eased = PredictiveBackEasing.transform(rawP)
    val scale = 1f - (0.14f * eased)
    scaleX = scale
    scaleY = scale

    // 根据左右滑动边缘向屏幕内侧偏移
    val horizontalDirection = if (state.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
    val maxHorizontalShiftPx = (size.width * 0.06f).coerceAtMost(28.dp.toPx())
    translationX = horizontalDirection * maxHorizontalShiftPx * eased

    // 纵向偏移：结合触点相对屏幕中心的偏移与预设下移量
    val verticalTouchBias = if (size.height > 0f && state.touchY > 0f) {
        ((state.touchY / size.height) - 0.5f).coerceIn(-0.5f, 0.5f) * 24.dp.toPx()
    } else {
        0f
    }
    translationY = (pullDownDp.dp.toPx() * eased) + (verticalTouchBias * eased)

    // 动态圆角：手势前 30% 快速形成 28.dp 圆角以贴合设备屏幕边缘
    val cornerFraction = (eased / 0.30f).coerceIn(0f, 1f)
    val cornerRadius = (28f * cornerFraction).dp
    shape = RoundedCornerShape(cornerRadius)
    clip = cornerFraction > 0.01f
    shadowElevation = (20f * eased).dp.toPx()
    alpha = 1f - (0.08f * eased)
}

/**
 * 为底层透视视图应用 AOSP 预见性返回的景深视差恢复效果（从 0.94x 缩放与微移平滑恢复至 1.0x）。
 */
fun Modifier.predictiveBackBackground(
    progress: Float,
    swipeEdge: Int = BackEventCompat.EDGE_LEFT
): Modifier = this.graphicsLayer {
    val rawP = progress.coerceIn(0f, 1f)
    if (rawP <= 0.0005f) {
        scaleX = 1f
        scaleY = 1f
        translationX = 0f
        return@graphicsLayer
    }

    val eased = PredictiveBackEasing.transform(rawP)
    val scale = 0.94f + (0.06f * eased)
    scaleX = scale
    scaleY = scale

    val horizontalDirection = if (swipeEdge == BackEventCompat.EDGE_RIGHT) 1f else -1f
    val maxParallaxPx = 14.dp.toPx()
    translationX = horizontalDirection * maxParallaxPx * (1f - eased)
}
