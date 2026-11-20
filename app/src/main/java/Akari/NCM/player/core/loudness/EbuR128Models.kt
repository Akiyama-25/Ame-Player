package Akari.NCM.player.core.loudness

import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable

/**
 * 音频响度特征来源（三级渐进式音量均衡架构）
 */
enum class LoudnessSource(val label: String, val priority: Int) {
    NETEASE_ONLINE("云端特征", 4),           // Tier 1: /song/url/v1 有效非零 gain/peak
    NETEASE_VD("云端音质特征", 4),           // Tier 1: /song/detail 或 /song/music/detail 分音质 vd
    REPLAYGAIN_TAG("本地标签", 4),           // Tier 1: 本地音频内嵌 ReplayGain / R128 / SoundCheck 标签
    SPARSE_RANGE_SCAN("11×3s 稀疏抽样", 5),  // Tier 2: 11段×3秒等距稀疏抽样 + 双重门控 + P75 锚点锁定
    BS1770_SCAN("BS.1770 全曲锁定", 5),      // Tier 2: 短曲或本地全曲完整 ITU-R BS.1770-4 门控积分锁定
    FAST_PREVIEW_SCAN("极速初判", 3),        // Tier 2 过渡: 20%/40%/60%/80% 中段优先初判
    REALTIME_AGC("实时流式兜底", 2),         // Tier 3: 保守正向增益上限 + Look-ahead 非对称 AGC + Freeze Gate
    INTRO_PREVIEW_SCAN("前奏初判", 2),       // 兼容早期流式初判来源
    QUEUE_AVERAGE("队列均值预补偿", 1),       // 未来队列均值先验参考
    DEFAULT_FALLBACK("基准兜底", 0);         // 默认平准电平

    val isKnownValid: Boolean
        get() = this != DEFAULT_FALLBACK

    val isFinalLocked: Boolean
        get() = this == NETEASE_ONLINE ||
            this == NETEASE_VD ||
            this == REPLAYGAIN_TAG ||
            this == SPARSE_RANGE_SCAN ||
            this == BS1770_SCAN

    val isMultiPointOrLocked: Boolean
        get() = isFinalLocked || this == FAST_PREVIEW_SCAN

    val tierLabel: String
        get() = when (this) {
            NETEASE_ONLINE, NETEASE_VD, REPLAYGAIN_TAG -> "Tier 1 · 元数据直读"
            SPARSE_RANGE_SCAN, BS1770_SCAN, FAST_PREVIEW_SCAN -> "Tier 2 · 稀疏/离线扫描"
            REALTIME_AGC, INTRO_PREVIEW_SCAN, QUEUE_AVERAGE, DEFAULT_FALLBACK -> "Tier 3 · 实时流式兜底"
        }
}

/**
 * 单曲综合响度与峰值特征数据
 */
@Serializable
@Stable
data class LoudnessData(
    val integratedLufs: Float,                                // 综合响度 (LUFS / LKFS)
    val truePeak: Float = 1.0f,                               // 线性真实峰值 (1.0 = 0 dBTP, 4x 过采样可 > 1.0)
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
    val isInterTrackWaiting: Boolean = false,
    val isNegativeGainOnly: Boolean = false,
    val isBypassedForUsbExclusive: Boolean = false
) {
    val statusSummary: String
        get() = if (!isEnabled) {
            "未启用"
        } else if (isBypassedForUsbExclusive) {
            "USB 独占源码旁路 (Bit-Perfect)"
        } else if (isInterTrackWaiting) {
            if (sourceLoudnessLufs != null) {
                String.format("曲间等候 (1s) · 已就绪 %.1f LUFS", sourceLoudnessLufs)
            } else {
                "曲间等候 (1s) · 响度计算中..."
            }
        } else if (sourceLoudnessLufs == null) {
            if (source == LoudnessSource.REALTIME_AGC) {
                val gainSign = if (appliedGainDb >= 0) "+" else ""
                String.format("实时兜底 · 增益: %s%.1f dB", gainSign, appliedGainDb)
            } else if (upcomingAverageLufs != null) {
                String.format("队列均值预热 (%.1f LUFS)", upcomingAverageLufs)
            } else {
                "标准电平 (0.0 dB)"
            }
        } else {
            val gainSign = if (appliedGainDb >= 0) "+" else ""
            val peakTag = if (isPeakLimited) " · 防过载压制" else ""
            val negOnlyTag = if (isNegativeGainOnly && appliedGainDb >= -0.05f) " · 仅降不升" else ""
            val stageTag = when (source) {
                LoudnessSource.FAST_PREVIEW_SCAN,
                LoudnessSource.INTRO_PREVIEW_SCAN -> " · 初判"
                LoudnessSource.REALTIME_AGC -> " · 实时兜底"
                LoudnessSource.SPARSE_RANGE_SCAN -> " · 11×3s"
                LoudnessSource.QUEUE_AVERAGE -> " · 队列均值"
                else -> ""
            }
            String.format("%.1f LUFS -> 增益: %s%.1f dB%s%s%s", sourceLoudnessLufs, gainSign, appliedGainDb, peakTag, negOnlyTag, stageTag)
        }
}