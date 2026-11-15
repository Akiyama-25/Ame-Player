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
 * 逐字/逐词高精度卡拉OK流光歌词组件 (Material 3 Expressive 色彩增强版)
 *
 * - 已播放部分使用经对比度校验的强调色 [activeColor]
 * - 未播放部分使用纯黑或纯白 [inactiveColor]
 * - 当曲目无原生逐字时间戳时，自动根据行起止时间合成字符级进度，确保已播放与未播放部分严格呈现强调色与纯黑/纯白对比
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordByWordLyricView(
    line: LyricLine,
    currentPositionMs: Long,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 22.sp,
    fontWeight: FontWeight = FontWeight.ExtraBold,
    lineHeight: TextUnit = 30.sp,
    activeColor: Color = Color(0xFFCFBCFF),
    inactiveColor: Color = Color.White,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    hasWordTimestamps: Boolean = line.words.isNotEmpty(),
    fallbackLineDurationMs: Long = 3600L
) {
    val effectiveWords = remember(line, hasWordTimestamps, fallbackLineDurationMs) {
        if (hasWordTimestamps && line.words.isNotEmpty()) {
            line.words
        } else {
            val trimmed = line.text.trim()
            if (trimmed.isEmpty()) {
                emptyList()
            } else {
                val chars = trimmed.map { it.toString() }
                val totalDur = fallbackLineDurationMs.coerceIn(800L, 10000L)
                val step = (totalDur / chars.size.coerceAtLeast(1)).coerceAtLeast(40L)
                chars.mapIndexed { idx, ch ->
                    WordInfo(
                        text = ch,
                        startOffsetMs = idx * step,
                        durationMs = step
                    )
                }
            }
        }
    }

    if (effectiveWords.isEmpty()) {
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
        effectiveWords.forEach { word ->
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
            // 未播放部分：严格使用纯黑或纯白显示
            Text(
                text = word.text,
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = inactiveColor,
                lineHeight = lineHeight
            )
        }
        currentPositionMs >= wordEnd -> {
            // 已播放部分：使用封面取色强调色显示
            Text(
                text = word.text,
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = activeColor,
                lineHeight = lineHeight
            )
        }
        else -> {
            // 正在发音：自左向右横向扫色，左侧已唱为强调色，右侧未唱为纯黑或纯白
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