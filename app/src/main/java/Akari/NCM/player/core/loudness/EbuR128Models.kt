package Akari.NCM.player.core.loudness

import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable

/**
 * 音频响度特征来源
 */
enum class LoudnessSource(val label: String) {
    NETEASE_ONLINE("云端特征"),       // 网易云 API 返回的 gain/peak 响度元数据
    REPLAYGAIN_TAG("本地标签"),       // 本地音频内嵌的 ReplayGain / R128 / SoundCheck 标签
    BS1770_SCAN("BS.1770 算法计算"),  // 基于 ITU-R BS.1770-4 K-weighting 双重门控实时/扫描分析
    DEFAULT_FALLBACK("基准兜底")     // 默认平准电平
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
    val source: LoudnessSource = LoudnessSource.DEFAULT_FALLBACK
) {
    val statusSummary: String
        get() = if (!isEnabled) {
            "未启用"
        } else if (sourceLoudnessLufs == null) {
            "标准电平 (0.0 dB)"
        } else {
            val gainSign = if (appliedGainDb >= 0) "+" else ""
            val peakTag = if (isPeakLimited) " · 防过载压制" else ""
            String.format("%.1f LUFS -> 增益: %s%.1f dB%s", sourceLoudnessLufs, gainSign, appliedGainDb, peakTag)
        }
}