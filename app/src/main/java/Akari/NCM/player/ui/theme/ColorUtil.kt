package Akari.NCM.player.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

fun contrastOn(color: Color): Color {
    return if (color.luminance() > 0.5f) Color.Black else Color.White
}

fun blend(fg: Color, bg: Color, fraction: Float): Color {
    val r = fg.red + (bg.red - fg.red) * fraction
    val g = fg.green + (bg.green - fg.green) * fraction
    val b = fg.blue + (bg.blue - fg.blue) * fraction
    val a = fg.alpha + (bg.alpha - fg.alpha) * fraction
    return Color(r, g, b, a)
}

fun lighten(color: Color, fraction: Float): Color = blend(color, Color.White, fraction)
fun darken(color: Color, fraction: Float): Color = blend(color, Color.Black, fraction)

fun ColorScheme.applyUserColors(
    darkTheme: Boolean,
    accent: Color,
    bg: Color,
    monetEnabled: Boolean
): ColorScheme {
    val onSurfaceVariant = if (darkTheme) Color(0xFFA0A0A0.toInt()) else Color(0xFF666666.toInt())
    val onSurface = if (darkTheme) Color(0xFFE0E0E0.toInt()) else Color(0xFF1C1B1F.toInt())
    val outline = if (darkTheme) Color(0xFF444444.toInt()) else Color(0xFFCCCCCC.toInt())
    val outlineVariant = if (darkTheme) Color(0xFF333333.toInt()) else Color(0xFFDDDDDD.toInt())

    return if (monetEnabled) {
        copy(
            onSurfaceVariant = onSurfaceVariant,
            onSurface = onSurface,
            onBackground = onSurface,
            outline = outline,
            outlineVariant = outlineVariant
        )
    } else {
        val onPrimary = contrastOn(accent)
        val primaryContainer = lighten(accent, 0.8f)
        val onPrimaryContainer = darken(accent, 0.3f)
        val surfaceVariant = if (darkTheme) darken(bg, 0.05f) else lighten(bg, 0.05f)

        copy(
            primary = accent,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            surface = bg,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            background = bg,
            onBackground = onSurface,
            outline = outline,
            outlineVariant = outlineVariant
        )
    }
}
