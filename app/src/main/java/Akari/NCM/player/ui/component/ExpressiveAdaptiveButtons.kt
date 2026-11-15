package Akari.NCM.player.ui.component

import Akari.NCM.player.ui.theme.ensureContrast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ExpressiveButtonStyle {
    FILLED,
    TONAL,
    OUTLINED,
    TEXT
}

data class AdaptiveButtonSpec(
    val text: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val style: ExpressiveButtonStyle = ExpressiveButtonStyle.TONAL,
    val enabled: Boolean = true,
    val testTag: String? = null,
    val containerColor: Color? = null,
    val contentColor: Color? = null
)

/**
 * 判断当前设备是否处于纵向长度远大于水平宽度的形态（如手机竖屏模式）
 */
@Composable
fun rememberIsPortraitTallScreen(): Boolean {
    val config = LocalConfiguration.current
    val w = config.screenWidthDp.coerceAtLeast(1)
    val h = config.screenHeightDp.coerceAtLeast(1)
    return h > w * 1.15f || w < 480
}

/**
 * Material 3 Expressive 自适应按钮组件
 * - 不固定宽度，支持多字数文案自然延展，防止文字挤压变形
 * - 自动执行前景/容器对比度校验
 */
@Composable
fun ExpressiveAdaptiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: ExpressiveButtonStyle = ExpressiveButtonStyle.TONAL,
    enabled: Boolean = true,
    containerColor: Color? = null,
    contentColor: Color? = null,
    backgroundForContrast: Color = MaterialTheme.colorScheme.surface,
    minContrastRatio: Float = 4.5f,
    cornerRadius: Dp = 20.dp,
    testTag: String? = null
) {
    val scheme = MaterialTheme.colorScheme
    val finalModifier = modifier
        .heightIn(min = 48.dp)
        .let { if (!testTag.isNullOrBlank()) it.testTag(testTag) else it }

    val shape = RoundedCornerShape(cornerRadius)
    val contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)

    when (style) {
        ExpressiveButtonStyle.FILLED -> {
            val rawBg = containerColor ?: scheme.primary
            val safeBg = ensureContrast(rawBg, backgroundForContrast, 3.0f)
            val rawFg = contentColor ?: scheme.onPrimary
            val safeFg = ensureContrast(rawFg, safeBg, minContrastRatio)
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                contentPadding = contentPadding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = safeBg,
                    contentColor = safeFg
                ),
                modifier = finalModifier
            ) {
                ButtonInnerContent(icon = icon, text = text)
            }
        }
        ExpressiveButtonStyle.TONAL -> {
            val rawBg = containerColor ?: scheme.secondaryContainer
            val safeBg = ensureContrast(rawBg, backgroundForContrast, 1.6f)
            val rawFg = contentColor ?: scheme.onSecondaryContainer
            val safeFg = ensureContrast(rawFg, safeBg, minContrastRatio)
            FilledTonalButton(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                contentPadding = contentPadding,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = safeBg,
                    contentColor = safeFg
                ),
                modifier = finalModifier
            ) {
                ButtonInnerContent(icon = icon, text = text)
            }
        }
        ExpressiveButtonStyle.OUTLINED -> {
            val rawFg = contentColor ?: scheme.primary
            val safeFg = ensureContrast(rawFg, backgroundForContrast, minContrastRatio)
            val borderColor = ensureContrast(scheme.outline, backgroundForContrast, 2.5f)
            OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                contentPadding = contentPadding,
                border = BorderStroke(1.2.dp, borderColor.copy(alpha = 0.75f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = safeFg
                ),
                modifier = finalModifier
            ) {
                ButtonInnerContent(icon = icon, text = text)
            }
        }
        ExpressiveButtonStyle.TEXT -> {
            val rawFg = contentColor ?: scheme.primary
            val safeFg = ensureContrast(rawFg, backgroundForContrast, minContrastRatio)
            TextButton(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = safeFg
                ),
                modifier = finalModifier
            ) {
                ButtonInnerContent(icon = icon, text = text)
            }
        }
    }
}

@Composable
private fun ButtonInnerContent(
    icon: ImageVector?,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 自适应按钮组：
 * - 在手机竖屏（纵向长度远大于水平宽度）或容器宽度较窄、或按钮文字较长时，优先使用纵向堆叠排列 (Column)
 * - 在横屏/宽屏平板且文字可舒适排布时，使用自适应流式水平排列 (FlowRow)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdaptiveButtonGroup(
    buttons: List<AdaptiveButtonSpec>,
    modifier: Modifier = Modifier,
    forceVerticalInPortrait: Boolean = true,
    backgroundForContrast: Color = MaterialTheme.colorScheme.surface,
    verticalSpacing: Dp = 10.dp,
    horizontalSpacing: Dp = 10.dp
) {
    if (buttons.isEmpty()) return
    val isPortraitTall = rememberIsPortraitTallScreen()
    val totalChars = buttons.sumOf { it.text.length }
    val hasLongButtonText = buttons.any { it.text.length >= 8 }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val isNarrowContainer = maxWidth < 440.dp
        val shouldStackVertically = (forceVerticalInPortrait && isPortraitTall) ||
            isNarrowContainer ||
            (buttons.size >= 2 && totalChars > 16) ||
            (buttons.size >= 3 && hasLongButtonText)

        if (shouldStackVertically) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(verticalSpacing)
            ) {
                buttons.forEach { spec ->
                    ExpressiveAdaptiveButton(
                        text = spec.text,
                        onClick = spec.onClick,
                        icon = spec.icon,
                        style = spec.style,
                        enabled = spec.enabled,
                        containerColor = spec.containerColor,
                        contentColor = spec.contentColor,
                        backgroundForContrast = backgroundForContrast,
                        testTag = spec.testTag,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(verticalSpacing)
            ) {
                buttons.forEach { spec ->
                    ExpressiveAdaptiveButton(
                        text = spec.text,
                        onClick = spec.onClick,
                        icon = spec.icon,
                        style = spec.style,
                        enabled = spec.enabled,
                        containerColor = spec.containerColor,
                        contentColor = spec.contentColor,
                        backgroundForContrast = backgroundForContrast,
                        testTag = spec.testTag,
                        modifier = Modifier.weight(1f, fill = true)
                    )
                }
            }
        }
    }
}
