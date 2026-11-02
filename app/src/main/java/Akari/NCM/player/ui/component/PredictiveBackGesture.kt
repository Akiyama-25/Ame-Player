package Akari.NCM.player.ui.component

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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

    var isCommitting by mutableStateOf(false)
        internal set

    var commitProgress by mutableFloatStateOf(0f)
        internal set

    var swipeEdge by mutableIntStateOf(BackEventCompat.EDGE_LEFT)
        internal set

    var touchX by mutableFloatStateOf(0f)
        internal set

    var touchY by mutableFloatStateOf(0f)
        internal set

    val isVisibleInBackground: Boolean
        get() = isActive || isCommitting || progress > 0.001f

    fun onGestureStarted() {
        isActive = true
        isCommitting = false
        commitProgress = 0f
        progress = 0f
    }

    fun onGestureProgress(
        rawProgress: Float,
        edge: Int,
        x: Float,
        y: Float
    ) {
        isActive = true
        isCommitting = false
        swipeEdge = edge
        touchX = x
        touchY = y
        progress = rawProgress.coerceIn(0f, 1f)
    }

    suspend fun animateCommit() {
        withContext(NonCancellable) {
            isCommitting = true
            val startProgress = progress
            if (coroutineContext[MonotonicFrameClock] != null) {
                runCatching {
                    animate(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = 240,
                            easing = PredictiveBackEasing
                        )
                    ) { value, _ ->
                        commitProgress = value
                        progress = startProgress + (1f - startProgress) * value
                    }
                }
            }
            commitProgress = 1f
            progress = 1f
        }
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
        isCommitting = false
        commitProgress = 0f
        touchX = 0f
        touchY = 0f
    }
}

/**
 * 注册并记忆一个 AOSP 预见性返回状态处理器。
 *
 * - 手势滑动中：实时同步更新 [PredictiveBackState.progress] (0f..1f)、[PredictiveBackState.swipeEdge] 与触点坐标
 * - 手势松手确认：平滑执行提交退场过渡，随后调用 [onBack] 回调并重置手势状态
 * - 手势中途取消：通过物理弹簧动画平滑回弹至初始状态
 */
@Composable
fun rememberPredictiveBackState(
    enabled: Boolean,
    onBack: () -> Unit
): PredictiveBackState {
    val state = remember { PredictiveBackState() }
    val currentOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(enabled) {
        if (!enabled && !state.isCommitting) {
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
            state.animateCommit()
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
 * 为前景视图应用 AOSP 预见性返回的跟手缩放、动态圆角、阴影与边缘位移，并在提交退场时保持连续平滑出场。
 *
 * @param state 预见性返回状态
 * @param pullDownDp 纵向附加下移距离（如全屏播放页返回底部迷你播放条时设较大值，>=32dp 时走纵向下沉退场）
 */
fun Modifier.predictiveBackForeground(
    state: PredictiveBackState,
    pullDownDp: Float = 12f
): Modifier = this.graphicsLayer {
    val rawP = state.progress.coerceIn(0f, 1f)
    if (rawP <= 0.0005f && !state.isActive && !state.isCommitting) {
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
    val gestureScale = 1f - (0.14f * eased)

    // 动态圆角：手势前 30% 快速形成 28.dp 圆角以贴合设备屏幕边缘
    val cornerFraction = (eased / 0.30f).coerceIn(0f, 1f)
    val cornerRadius = (28f * cornerFraction).dp
    shape = RoundedCornerShape(cornerRadius)
    clip = cornerFraction > 0.01f
    shadowElevation = (20f * eased).dp.toPx()

    val horizontalDirection = if (state.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
    val maxHorizontalShiftPx = (size.width * 0.06f).coerceAtMost(28.dp.toPx())
    val baseTranslationX = horizontalDirection * maxHorizontalShiftPx * eased

    val verticalTouchBias = if (size.height > 0f && state.touchY > 0f) {
        ((state.touchY / size.height) - 0.5f).coerceIn(-0.5f, 0.5f) * 24.dp.toPx()
    } else {
        0f
    }
    val baseTranslationY = (pullDownDp.dp.toPx() * eased) + (verticalTouchBias * eased)

    if (!state.isCommitting) {
        // --- 拖拽跟手阶段 ---
        scaleX = gestureScale
        scaleY = gestureScale
        translationX = baseTranslationX
        translationY = baseTranslationY
        alpha = 1f - (0.08f * eased)
    } else {
        // --- 松手确认提交退场阶段（无缝接续拖拽当前缩放、圆角与位移，平滑滑离屏幕） ---
        val cEased = state.commitProgress.coerceIn(0f, 1f)

        if (pullDownDp >= 32f) {
            // 纵向沉底退场（播放页、播放队列）
            val exitDistanceY = if (size.height > 0f) size.height else 800.dp.toPx()
            translationY = baseTranslationY + (exitDistanceY * cEased)
            translationX = baseTranslationX * (1f - cEased)
            scaleX = gestureScale * (1f - (0.06f * cEased))
            scaleY = gestureScale * (1f - (0.06f * cEased))
            alpha = ((1f - (0.08f * eased)) * (1f - cEased)).coerceIn(0f, 1f)
        } else {
            // 横向侧滑退场（搜索、设置、日志、歌单详情）
            val exitDistanceX = if (size.width > 0f) size.width else 400.dp.toPx()
            translationX = baseTranslationX + (horizontalDirection * exitDistanceX * cEased)
            translationY = baseTranslationY * (1f - cEased)
            scaleX = gestureScale * (1f - (0.06f * cEased))
            scaleY = gestureScale * (1f - (0.06f * cEased))
            alpha = ((1f - (0.08f * eased)) * (1f - cEased)).coerceIn(0f, 1f)
        }
    }
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
