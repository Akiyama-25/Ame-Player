package Akari.NCM.player.ui.component

import Akari.NCM.player.core.LyricLine
import Akari.NCM.player.core.WordInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 逐字/逐词高精度卡拉OK流光歌词组件
 *
 * 基于 [WordInfo] 词级起止时间与毫秒进度 [currentPositionMs]，在每个音节上实时绘制
 * 渐变光晕扫色（Horizontal Gradient Sweep），保持字重与尺寸完全一致，仅展现半透明至不透明的播放位置平滑过渡。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordByWordLyricView(
    line: LyricLine,
    currentPositionMs: Long,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 22.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    lineHeight: TextUnit = 28.sp,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.35f),
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    hasWordTimestamps: Boolean = line.words.isNotEmpty()
) {
    if (!hasWordTimestamps || line.words.isEmpty()) {
        Text(
            text = line.text.trim(),
            fontSize = fontSize,
            fontWeight = fontWeight,
            color = activeColor,
            lineHeight = lineHeight,
            textAlign = if (horizontalAlignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start,
            modifier = modifier
        )
        return
    }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = if (horizontalAlignment == Alignment.CenterHorizontally) Arrangement.Center else Arrangement.Start,
        verticalArrangement = Arrangement.Center
    ) {
        line.words.forEach { word ->
            WordItem(
                word = word,
                lineTimeMs = line.timeMs,
                currentPositionMs = currentPositionMs,
                fontSize = fontSize,
                fontWeight = fontWeight,
                lineHeight = lineHeight,
                activeColor = activeColor,
                inactiveColor = inactiveColor
            )
        }
    }
}

@Composable
private fun WordItem(
    word: WordInfo,
    lineTimeMs: Long,
    currentPositionMs: Long,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    lineHeight: TextUnit,
    activeColor: Color,
    inactiveColor: Color
) {
    val wordStart = lineTimeMs + word.startOffsetMs
    val wordDuration = word.durationMs.coerceAtLeast(1L)
    val wordEnd = wordStart + wordDuration

    when {
        currentPositionMs < wordStart -> {
            // 未发音：半透明常驻，字重与尺寸严格统一，无缩放
            Text(
                text = word.text,
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = inactiveColor,
                lineHeight = lineHeight
            )
        }
        currentPositionMs >= wordEnd -> {
            // 已唱过：不透明常驻
            Text(
                text = word.text,
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = activeColor,
                lineHeight = lineHeight
            )
        }
        else -> {
            // 正在发音：自左向右横向流光渐变扫色（仅保留半透明到不透明的播放位置平滑渐变）
            val progress = ((currentPositionMs - wordStart).toFloat() / wordDuration).coerceIn(0f, 1f)
            val p = progress.coerceIn(0.001f, 0.999f)

            val sweepBrush = remember(p, activeColor, inactiveColor) {
                Brush.horizontalGradient(
                    0.0f to activeColor,
                    (p * 0.96f).coerceIn(0f, 1f) to activeColor,
                    p to inactiveColor,
                    1.0f to inactiveColor
                )
            }

            Text(
                text = word.text,
                fontSize = fontSize,
                fontWeight = fontWeight,
                style = TextStyle(brush = sweepBrush),
                lineHeight = lineHeight
            )
        }
    }
}