package Akari.NCM.player.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class HslColor(val h: Float, val s: Float, val l: Float)

fun hslToColor(h: Float, s: Float, l: Float): Color {
    val hNorm = ((h % 360f) + 360f) % 360f
    val sNorm = s.coerceIn(0f, 100f) / 100f
    val lNorm = l.coerceIn(0f, 100f) / 100f

    val c = (1f - abs(2f * lNorm - 1f)) * sNorm
    val x = c * (1f - abs((hNorm / 60f) % 2f - 1f))
    val m = lNorm - c / 2f

    val (r1, g1, b1) = when {
        hNorm < 60f -> Triple(c, x, 0f)
        hNorm < 120f -> Triple(x, c, 0f)
        hNorm < 180f -> Triple(0f, c, x)
        hNorm < 240f -> Triple(0f, x, c)
        hNorm < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    return Color(red = (r1 + m).coerceIn(0f, 1f), green = (g1 + m).coerceIn(0f, 1f), blue = (b1 + m).coerceIn(0f, 1f))
}

fun colorToHsl(color: Color): HslColor {
    val argb = color.toArgb()
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f

    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC

    val l = (maxC + minC) / 2f

    if (delta == 0f) {
        return HslColor(h = 0f, s = 0f, l = l * 100f)
    }

    val s = if (l < 0.5f) delta / (maxC + minC) else delta / (2f - maxC - minC)

    val h = when (maxC) {
        r -> 60f * (((g - b) / delta) % 6f)
        g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }

    return HslColor(
        h = ((h % 360f) + 360f) % 360f,
        s = s * 100f,
        l = l * 100f
    )
}

fun hslToArgbLong(h: Float, s: Float, l: Float): Long {
    // Wait, toArgb() returns Int. toLong() sign-extends it! 
    // We must use toUInt().toLong() to prevent negative longs if alpha is 0xFF.
    // Let's use (color.toArgb().toULong() and 0xFFFFFFFFUL).toLong()
    // No, toArgb().toLong() is what ncm-converter used.
    // Wait, in Kotlin, Int.toLong() sign-extends. So 0xFF1C1B1F becomes -14867425L.
    // When saved to AppPrefs (Long) and read back, it's still -14867425L.
    // Then Color(accentColor.toInt()) reconstructs the color correctly!
    return hslToColor(h, s, l).toArgb().toLong()
}
