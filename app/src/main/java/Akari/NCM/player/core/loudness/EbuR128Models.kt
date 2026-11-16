package Akari.NCM.player.core.loudness

import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable

/**
 * 音频响度特征来源
 */
enum class LoudnessSource(val label: String, val priority: Int) {
    NETEASE_ONLINE("云端特征", 4),         // 网易云 API 返回的有效非零 gain/peak 响度元数据
    REPLAYGAIN_TAG("本地标签", 4),         // 本地音频内嵌的 ReplayGain / R128 / SoundCheck 标签
    BS1770_SCAN("BS.1770 全曲锁定", 5),    // 双阶段第二阶段：基于 ITU-R BS.1770-4 全曲门控积分锁定并缓存
    FAST_PREVIEW_SCAN("极速初判", 3),      // 双阶段第一阶段：多点跳帧极速抽样 (20%/45%/70%)
    INTRO_PREVIEW_SCAN("前奏初判", 2),     // 首段流式或全曲扫描初期 (~1s) 单点初判，不可覆盖多点抽样
    QUEUE_AVERAGE("队列均值预补偿", 1),     // 基于未来 3 首预计算平均响度的先验初始补偿
    DEFAULT_FALLBACK("基准兜底", 0);       // 默认平准电平

    val isKnownValid: Boolean
        get() = this != DEFAULT_FALLBACK

    val isFinalLocked: Boolean
        get() = this == NETEASE_ONLINE || this == REPLAYGAIN_TAG || this == BS1770_SCAN

    val isMultiPointOrLocked: Boolean
        get() = isFinalLocked || this == FAST_PREVIEW_SCAN
}

/**
 * 单曲综合响度与峰值特征数据
 */
@Serializable
@Stable
data class LoudnessData(
    val integratedLufs: Float,                                // 综合响度 (LUFS / LKFS)
    val truePeak: Float = 1.0f,                               // 线性真实峰值 (1.0 = 0 dBFS)
    val source: LoudnessSource = LoudnessSource.DEFAULT_FALLBACK
) {
    val peakDb: Float
        get() = if (truePeak > 0f) (20.0 * kotlin.math.log10(truePeak.toDouble().coerceAtLeast(1e-5))).toFloat() else -100f
}

/**
 * 播放引擎当前生效的音量均衡实时状态
 */
@Stable
data class LoudnessNormalizationState(
    val isEnabled: Boolean = true,
    val targetLufs: Float = -14.0f,
    val currentMediaTitle: String? = null,
    val sourceLoudnessLufs: Float? = null,
    val sourcePeakDb: Float? = null,
    val appliedGainDb: Float = 0.0f,
    val linearMultiplier: Float = 1.0f,
    val isPeakLimited: Boolean = false,
    val source: LoudnessSource = LoudnessSource.DEFAULT_FALLBACK,
    val upcomingAverageLufs: Float? = null,
    val prefetchedCount: Int = 0,
    val prefetchedTotalCount: Int = 0,
    val isInterTrackWaiting: Boolean = false
) {
    val statusSummary: String
        get() = if (!isEnabled) {
            "未启用"
        } else if (isInterTrackWaiting) {
            if (sourceLoudnessLufs != null) {
                String.format("曲间等候 (1s) · 已就绪 %.1f LUFS", sourceLoudnessLufs)
            } else {
                "曲间等候 (1s) · 响度计算中..."
            }
        } else if (sourceLoudnessLufs == null) {
            if (upcomingAverageLufs != null) {
                String.format("队列均值预热 (%.1f LUFS)", upcomingAverageLufs)
            } else {
                "标准电平 (0.0 dB)"
            }
        } else {
            val gainSign = if (appliedGainDb >= 0) "+" else ""
            val peakTag = if (isPeakLimited) " · 防过载压制" else ""
            val stageTag = when (source) {
                LoudnessSource.FAST_PREVIEW_SCAN,
                LoudnessSource.INTRO_PREVIEW_SCAN -> " · 初判"
                LoudnessSource.QUEUE_AVERAGE -> " · 队列均值"
                else -> ""
            }
            String.format("%.1f LUFS -> 增益: %s%.1f dB%s%s", sourceLoudnessLufs, gainSign, appliedGainDb, peakTag, stageTag)
        }
}