package Akari.NCM.player.ui.theme

import Akari.NCM.player.data.ColorVariant
import Akari.NCM.player.data.ContrastLevel
import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 计算两个颜色之间的 WCAG 2.1 对比度 (1.0 .. 21.0)
 */
fun contrastRatio(c1: Color, c2: Color): Float {
    val l1 = c1.luminance()
    val l2 = c2.luminance()
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * 根据背景亮度返回纯黑或纯白（用于未播放歌词或极致对比前景）
 */
fun pureBlackOrWhiteOn(background: Color): Color {
    return if (background.luminance() > 0.38f) Color.Black else Color.White
}

fun contrastOn(color: Color): Color {
    return pureBlackOrWhiteOn(color)
}

fun blend(fg: Color, bg: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    val r = fg.red + (bg.red - fg.red) * f
    val g = fg.green + (bg.green - fg.green) * f
    val b = fg.blue + (bg.blue - fg.blue) * f
    val a = fg.alpha + (bg.alpha - fg.alpha) * f
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), a.coerceIn(0f, 1f))
}

fun lighten(color: Color, fraction: Float): Color = blend(color, Color.White, fraction)
fun darken(color: Color, fraction: Float): Color = blend(color, Color.Black, fraction)

/**
 * 确保前景色 [foreground] 与背景色 [background] 至少达到 [minRatio] 的对比度。
 * 优先保持 [foreground] 的色相与饱和度，通过调节明度 L 来满足对比度要求。
 */
fun ensureContrast(
    foreground: Color,
    background: Color,
    minRatio: Float = 4.5f,
    preferChroma: Boolean = true
): Color {
    if (contrastRatio(foreground, background) >= minRatio) {
        return foreground
    }

    val hsl = colorToHsl(foreground)
    val bgLum = background.luminance()
    val whiteRatio = contrastRatio(Color.White, background)
    val blackRatio = contrastRatio(Color.Black, background)

    // 决定向亮端还是暗端调整：优先依据背景明暗，若该方向最大对比度不足则自动切换另一方向
    val goLighter = if (bgLum <= 0.38f) {
        whiteRatio >= minRatio || whiteRatio >= blackRatio
    } else {
        blackRatio < minRatio && whiteRatio > blackRatio
    }

    val effectiveSat = if (preferChroma && hsl.s > 8f) hsl.s.coerceIn(35f, 92f) else hsl.s

    var bestColor = if (goLighter) Color.White else Color.Black
    if (goLighter) {
        var low = hsl.l.coerceIn(0f, 100f)
        var high = 100f
        for (step in 0 until 14) {
            val mid = (low + high) * 0.5f
            val candidate = hslToColor(hsl.h, effectiveSat, mid)
            if (contrastRatio(candidate, background) >= minRatio) {
                bestColor = candidate
                high = mid
            } else {
                low = mid
            }
        }
        if (contrastRatio(bestColor, background) < minRatio) {
            // 若高饱和度限制了最高亮度，适度降低饱和度以进一步提亮
            for (sFactor in listOf(0.8f, 0.6f, 0.4f, 0.2f, 0f)) {
                val cand = hslToColor(hsl.h, effectiveSat * sFactor, 96f)
                if (contrastRatio(cand, background) >= minRatio) {
                    bestColor = cand
                    break
                }
            }
        }
    } else {
        var low = 0f
        var high = hsl.l.coerceIn(0f, 100f)
        for (step in 0 until 14) {
            val mid = (low + high) * 0.5f
            val candidate = hslToColor(hsl.h, effectiveSat, mid)
            if (contrastRatio(candidate, background) >= minRatio) {
                bestColor = candidate
                low = mid
            } else {
                high = mid
            }
        }
        if (contrastRatio(bestColor, background) < minRatio) {
            for (sFactor in listOf(0.8f, 0.6f, 0.4f, 0.2f, 0f)) {
                val cand = hslToColor(hsl.h, effectiveSat * sFactor, 6f)
                if (contrastRatio(cand, background) >= minRatio) {
                    bestColor = cand
                    break
                }
            }
        }
    }

    return bestColor
}

/**
 * 从歌曲封面 Bitmap 中提取最具代表性的鲜明主题种子色 (Seed Color)
 */
fun extractSeedColorFromBitmap(
    bitmap: Bitmap?,
    fallback: Color = Color(0xFF6750A4)
): Color {
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) {
        return fallback
    }

    val width = bitmap.width
    val height = bitmap.height
    val stepX = (width / 24).coerceAtLeast(1)
    val stepY = (height / 24).coerceAtLeast(1)

    val hueBinWeights = FloatArray(36)
    val hueBinH = FloatArray(36)
    val hueBinS = FloatArray(36)
    val hueBinL = FloatArray(36)

    var totalPixels = 0
    var fallbackSumR = 0f
    var fallbackSumG = 0f
    var fallbackSumB = 0f

    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = bitmap.getPixel(x, y)
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha >= 128) {
                val r = ((pixel ushr 16) and 0xFF) / 255f
                val g = ((pixel ushr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f

                fallbackSumR += r
                fallbackSumG += g
                fallbackSumB += b
                totalPixels++

                val c = Color(r, g, b, 1f)
                val hsl = colorToHsl(c)
                // 过滤接近纯黑或纯白的噪点像素，聚焦有色彩表现力的区域
                if (hsl.l in 10f..92f && hsl.s >= 10f) {
                    val bin = ((hsl.h / 10f).toInt()).coerceIn(0, 35)
                    // Material 3 Expressive 倾向于挑选具有较高色度与适中明度的颜色
                    val chromaScore = (hsl.s / 100f) * 1.6f + 0.2f
                    val lightnessScore = 1f - (abs(hsl.l - 52f) / 60f).coerceIn(0f, 0.7f)
                    val weight = chromaScore * lightnessScore

                    hueBinWeights[bin] += weight
                    hueBinH[bin] += hsl.h * weight
                    hueBinS[bin] += hsl.s * weight
                    hueBinL[bin] += hsl.l * weight
                }
            }
            x += stepX
        }
        y += stepY
    }

    var bestBin = -1
    var maxWeight = 0f
    for (i in 0 until 36) {
        if (hueBinWeights[i] > maxWeight) {
            maxWeight = hueBinWeights[i]
            bestBin = i
        }
    }

    if (bestBin >= 0 && maxWeight > 0f) {
        val avgH = (hueBinH[bestBin] / maxWeight) % 360f
        val avgS = (hueBinS[bestBin] / maxWeight).coerceIn(36f, 90f)
        val avgL = (hueBinL[bestBin] / maxWeight).coerceIn(38f, 65f)
        return hslToColor(avgH, avgS, avgL)
    }

    if (totalPixels > 0) {
        val avgColor = Color(
            (fallbackSumR / totalPixels).coerceIn(0f, 1f),
            (fallbackSumG / totalPixels).coerceIn(0f, 1f),
            (fallbackSumB / totalPixels).coerceIn(0f, 1f)
        )
        val hsl = colorToHsl(avgColor)
        if (hsl.s >= 5f) {
            return hslToColor(hsl.h, hsl.s.coerceIn(28f, 75f), hsl.l.coerceIn(40f, 62f))
        }
    }

    return fallback
}

/**
 * 计算封面 Bitmap 的平均背景基底色（用于评估模糊封面背景的实际明暗与对比度）
 */
fun extractAverageColorFromBitmap(
    bitmap: Bitmap?,
    fallback: Color = Color(0xFF18181C)
): Color {
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) {
        return fallback
    }
    val width = bitmap.width
    val height = bitmap.height
    val stepX = (width / 16).coerceAtLeast(1)
    val stepY = (height / 16).coerceAtLeast(1)

    var sumR = 0f
    var sumG = 0f
    var sumB = 0f
    var count = 0

    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = bitmap.getPixel(x, y)
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha >= 64) {
                sumR += ((pixel ushr 16) and 0xFF) / 255f
                sumG += ((pixel ushr 8) and 0xFF) / 255f
                sumB += (pixel and 0xFF) / 255f
                count++
            }
            x += stepX
        }
        y += stepY
    }

    if (count == 0) return fallback
    return Color(
        red = (sumR / count).coerceIn(0f, 1f),
        green = (sumG / count).coerceIn(0f, 1f),
        blue = (sumB / count).coerceIn(0f, 1f),
        alpha = 1f
    )
}

/**
 * 构建 Material 3 Expressive 动态/自定义配色方案
 *
 * @param seedColor 从系统壁纸（非播放页）或自定义色盘提取的种子色
 * @param darkTheme 是否为深色模式
 * @param variant 用户选择的色彩变体 (Expressive / TonalSpot / Vibrant / Fidelity / Content / Neutral / Monochrome)
 * @param contrastLevel 用户选择的对比度级别 (Standard / Medium / High)
 * @param useMaterialBackground 是否启用 Material 取色背景色（关闭时使用跟随深浅色模式的纯黑/纯白背景）
 */
fun buildExpressiveColorScheme(
    seedColor: Color,
    darkTheme: Boolean,
    variant: ColorVariant = ColorVariant.EXPRESSIVE,
    contrastLevel: ContrastLevel = ContrastLevel.STANDARD,
    useMaterialBackground: Boolean = true
): ColorScheme {
    val seedHsl = colorToHsl(seedColor)
    val h = seedHsl.h

    // 根据不同色彩变体计算 Primary / Secondary / Tertiary / Neutral 的色相与饱和度
    val primaryH: Float
    val primaryS: Float
    val secondaryH: Float
    val secondaryS: Float
    val tertiaryH: Float
    val tertiaryS: Float
    val neutralH: Float
    val neutralS: Float

    when (variant) {
        ColorVariant.EXPRESSIVE -> {
            // M3 Expressive 标志性的多色相撞色与高表现力饱和度
            primaryH = (h + 8f) % 360f
            primaryS = seedHsl.s.coerceIn(64f, 88f)
            secondaryH = (h + 48f) % 360f
            secondaryS = (seedHsl.s * 0.72f).coerceIn(42f, 66f)
            tertiaryH = (h + 135f) % 360f
            tertiaryS = (seedHsl.s * 0.90f).coerceIn(58f, 86f)
            neutralH = (h + 12f) % 360f
            neutralS = 20f
        }
        ColorVariant.TONAL_SPOT -> {
            primaryH = h
            primaryS = seedHsl.s.coerceIn(42f, 60f)
            secondaryH = h
            secondaryS = 26f
            tertiaryH = (h + 60f) % 360f
            tertiaryS = 42f
            neutralH = h
            neutralS = 12f
        }
        ColorVariant.VIBRANT -> {
            primaryH = h
            primaryS = seedHsl.s.coerceIn(80f, 96f)
            secondaryH = (h + 24f) % 360f
            secondaryS = 62f
            tertiaryH = (h + 85f) % 360f
            tertiaryS = 78f
            neutralH = h
            neutralS = 26f
        }
        ColorVariant.FIDELITY -> {
            primaryH = h
            primaryS = seedHsl.s.coerceIn(28f, 92f)
            secondaryH = h
            secondaryS = (seedHsl.s * 0.55f).coerceIn(18f, 52f)
            tertiaryH = (h + 32f) % 360f
            tertiaryS = (seedHsl.s * 0.75f).coerceIn(26f, 68f)
            neutralH = h
            neutralS = (seedHsl.s * 0.22f).coerceIn(8f, 22f)
        }
        ColorVariant.CONTENT -> {
            primaryH = h
            primaryS = seedHsl.s.coerceIn(36f, 84f)
            secondaryH = (h + 14f) % 360f
            secondaryS = (seedHsl.s * 0.65f).coerceIn(24f, 58f)
            tertiaryH = (h + 340f) % 360f
            tertiaryS = (seedHsl.s * 0.75f).coerceIn(30f, 66f)
            neutralH = h
            neutralS = (seedHsl.s * 0.25f).coerceIn(10f, 24f)
        }
        ColorVariant.NEUTRAL -> {
            primaryH = h
            primaryS = 14f
            secondaryH = h
            secondaryS = 8f
            tertiaryH = (h + 30f) % 360f
            tertiaryS = 16f
            neutralH = h
            neutralS = 4f
        }
        ColorVariant.MONOCHROME -> {
            primaryH = 0f
            primaryS = 0f
            secondaryH = 0f
            secondaryS = 0f
            tertiaryH = 0f
            tertiaryS = 0f
            neutralH = 0f
            neutralS = 0f
        }
    }

    val cLevel = contrastLevel.levelValue // 0.0f, 0.5f, 1.0f
    val minTextRatio = contrastLevel.minTextContrast
    val minControlRatio = contrastLevel.minControlContrast

    // 1. 计算背景与表面色
    val background: Color
    val surface: Color
    val surfaceVariant: Color
    val surfaceContainerLowest: Color
    val surfaceContainerLow: Color
    val surfaceContainer: Color
    val surfaceContainerHigh: Color
    val surfaceContainerHighest: Color

    if (!useMaterialBackground) {
        // 用户关闭 Material 取色背景开关：使用纯黑或纯白背景，严格跟随深色/浅色模式
        if (darkTheme) {
            background = Color.Black
            surface = Color.Black
            surfaceContainerLowest = Color.Black
            surfaceContainerLow = hslToColor(neutralH, neutralS * 0.4f, 6f + cLevel * 2f)
            surfaceContainer = hslToColor(neutralH, neutralS * 0.5f, 10f + cLevel * 3f)
            surfaceContainerHigh = hslToColor(neutralH, neutralS * 0.5f, 15f + cLevel * 4f)
            surfaceContainerHighest = hslToColor(neutralH, neutralS * 0.6f, 20f + cLevel * 5f)
            surfaceVariant = hslToColor(neutralH, neutralS * 0.7f, 18f + cLevel * 5f)
        } else {
            background = Color.White
            surface = Color.White
            surfaceContainerLowest = Color.White
            surfaceContainerLow = hslToColor(neutralH, neutralS * 0.4f, 96f - cLevel * 2f)
            surfaceContainer = hslToColor(neutralH, neutralS * 0.5f, 93f - cLevel * 3f)
            surfaceContainerHigh = hslToColor(neutralH, neutralS * 0.5f, 89f - cLevel * 4f)
            surfaceContainerHighest = hslToColor(neutralH, neutralS * 0.6f, 85f - cLevel * 5f)
            surfaceVariant = hslToColor(neutralH, neutralS * 0.7f, 88f - cLevel * 5f)
        }
    } else {
        // 用户开启 Material 取色背景开关：使用 Material 取色背景色
        if (darkTheme) {
            val baseBgL = (8f - cLevel * 3f).coerceIn(3f, 12f)
            background = hslToColor(neutralH, neutralS, baseBgL)
            surface = hslToColor(neutralH, neutralS, baseBgL)
            surfaceContainerLowest = hslToColor(neutralH, neutralS, (baseBgL - 3f).coerceAtLeast(2f))
            surfaceContainerLow = hslToColor(neutralH, neutralS, baseBgL + 4f + cLevel * 1.5f)
            surfaceContainer = hslToColor(neutralH, neutralS, baseBgL + 7f + cLevel * 2.5f)
            surfaceContainerHigh = hslToColor(neutralH, neutralS, baseBgL + 11f + cLevel * 3.5f)
            surfaceContainerHighest = hslToColor(neutralH, neutralS, baseBgL + 16f + cLevel * 4.5f)
            surfaceVariant = hslToColor(neutralH, (neutralS * 1.4f).coerceAtMost(36f), baseBgL + 15f + cLevel * 4f)
        } else {
            val baseBgL = (97f + cLevel * 1.5f).coerceIn(94f, 99f)
            background = hslToColor(neutralH, neutralS, baseBgL)
            surface = hslToColor(neutralH, neutralS, baseBgL)
            surfaceContainerLowest = Color.White
            surfaceContainerLow = hslToColor(neutralH, neutralS, baseBgL - 3f - cLevel * 1.5f)
            surfaceContainer = hslToColor(neutralH, neutralS, baseBgL - 6f - cLevel * 2.5f)
            surfaceContainerHigh = hslToColor(neutralH, neutralS, baseBgL - 9f - cLevel * 3.5f)
            surfaceContainerHighest = hslToColor(neutralH, neutralS, baseBgL - 13f - cLevel * 4.5f)
            surfaceVariant = hslToColor(neutralH, (neutralS * 1.4f).coerceAtMost(38f), baseBgL - 10f - cLevel * 4f)
        }
    }

    // 2. 计算主色、次色、第三色及其容器色，并通过 ensureContrast 严格校验对比度
    if (darkTheme) {
        val rawPrimary = hslToColor(primaryH, primaryS, 76f + cLevel * 8f)
        val primary = ensureContrast(rawPrimary, background, max(minTextRatio, 4.5f))
        val onPrimary = ensureContrast(hslToColor(primaryH, primaryS * 0.8f, 14f - cLevel * 6f), primary, minTextRatio)

        val rawPrimaryContainer = hslToColor(primaryH, (primaryS * 0.85f).coerceAtMost(82f), 28f + cLevel * 8f)
        val primaryContainer = ensureContrast(rawPrimaryContainer, background, minControlRatio * 0.75f)
        val onPrimaryContainer = ensureContrast(hslToColor(primaryH, primaryS, 90f + cLevel * 5f), primaryContainer, minTextRatio)

        val rawSecondary = hslToColor(secondaryH, secondaryS, 74f + cLevel * 8f)
        val secondary = ensureContrast(rawSecondary, background, max(minControlRatio, 3.8f))
        val onSecondary = ensureContrast(hslToColor(secondaryH, secondaryS * 0.8f, 16f - cLevel * 6f), secondary, minTextRatio)

        val secondaryContainer = hslToColor(secondaryH, secondaryS * 0.8f, 26f + cLevel * 6f)
        val onSecondaryContainer = ensureContrast(hslToColor(secondaryH, secondaryS, 90f + cLevel * 5f), secondaryContainer, minTextRatio)

        val rawTertiary = hslToColor(tertiaryH, tertiaryS, 76f + cLevel * 8f)
        val tertiary = ensureContrast(rawTertiary, background, max(minControlRatio, 3.8f))
        val onTertiary = ensureContrast(hslToColor(tertiaryH, tertiaryS * 0.8f, 15f - cLevel * 6f), tertiary, minTextRatio)

        val tertiaryContainer = hslToColor(tertiaryH, tertiaryS * 0.85f, 28f + cLevel * 6f)
        val onTertiaryContainer = ensureContrast(hslToColor(tertiaryH, tertiaryS, 91f + cLevel * 5f), tertiaryContainer, minTextRatio)

        val onBackground = ensureContrast(hslToColor(neutralH, neutralS * 0.4f, 92f + cLevel * 5f), background, minTextRatio)
        val onSurface = ensureContrast(hslToColor(neutralH, neutralS * 0.4f, 92f + cLevel * 5f), surface, minTextRatio)
        val onSurfaceVariant = ensureContrast(hslToColor(neutralH, neutralS * 0.6f, 76f + cLevel * 10f), surfaceVariant, minTextRatio * 0.88f)
        val outline = ensureContrast(hslToColor(neutralH, neutralS * 0.6f, 56f + cLevel * 16f), background, minControlRatio)
        val outlineVariant = hslToColor(neutralH, neutralS * 0.5f, 28f + cLevel * 10f)

        return darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            outline = outline,
            outlineVariant = outlineVariant
        )
    } else {
        val rawPrimary = hslToColor(primaryH, primaryS, 40f - cLevel * 10f)
        val primary = ensureContrast(rawPrimary, background, max(minTextRatio, 4.5f))
        val onPrimary = ensureContrast(Color.White, primary, minTextRatio)

        val rawPrimaryContainer = hslToColor(primaryH, (primaryS * 0.85f).coerceAtMost(86f), 88f - cLevel * 8f)
        val primaryContainer = ensureContrast(rawPrimaryContainer, background, 1.15f + cLevel * 0.35f)
        val onPrimaryContainer = ensureContrast(hslToColor(primaryH, primaryS, 15f - cLevel * 7f), primaryContainer, minTextRatio)

        val rawSecondary = hslToColor(secondaryH, secondaryS, 42f - cLevel * 10f)
        val secondary = ensureContrast(rawSecondary, background, max(minControlRatio, 3.8f))
        val onSecondary = ensureContrast(Color.White, secondary, minTextRatio)

        val secondaryContainer = hslToColor(secondaryH, secondaryS * 0.85f, 88f - cLevel * 7f)
        val onSecondaryContainer = ensureContrast(hslToColor(secondaryH, secondaryS, 16f - cLevel * 7f), secondaryContainer, minTextRatio)

        val rawTertiary = hslToColor(tertiaryH, tertiaryS, 40f - cLevel * 10f)
        val tertiary = ensureContrast(rawTertiary, background, max(minControlRatio, 3.8f))
        val onTertiary = ensureContrast(Color.White, tertiary, minTextRatio)

        val tertiaryContainer = hslToColor(tertiaryH, tertiaryS * 0.85f, 89f - cLevel * 7f)
        val onTertiaryContainer = ensureContrast(hslToColor(tertiaryH, tertiaryS, 15f - cLevel * 7f), tertiaryContainer, minTextRatio)

        val onBackground = ensureContrast(hslToColor(neutralH, neutralS * 0.4f, 11f - cLevel * 6f), background, minTextRatio)
        val onSurface = ensureContrast(hslToColor(neutralH, neutralS * 0.4f, 11f - cLevel * 6f), surface, minTextRatio)
        val onSurfaceVariant = ensureContrast(hslToColor(neutralH, neutralS * 0.6f, 30f - cLevel * 10f), surfaceVariant, minTextRatio * 0.88f)
        val outline = ensureContrast(hslToColor(neutralH, neutralS * 0.6f, 46f - cLevel * 14f), background, minControlRatio)
        val outlineVariant = hslToColor(neutralH, neutralS * 0.5f, 78f - cLevel * 10f)

        return lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            outline = outline,
            outlineVariant = outlineVariant
        )
    }
}

/**
 * 播放页专属的 Material 3 Expressive 色彩与高对比度令牌集
 *
 * - 以当前歌曲封面为基础动态提取主题色
 * - 应用于播放界面按钮控件与歌词播放进度显示
 * - 已播放歌词（前序歌词行 + 当前行已唱进度）使用经过对比度校验的强调色 [playedLyricColor]
 * - 未播放歌词（后续歌词行 + 当前行未唱部分）严格使用纯黑或纯白 [unplayedLyricColor]
 * - 所有按钮容器与按钮文字/图标均经过与背景色的 WCAG 对比度检验
 */
data class PlayerExpressivePalette(
    val seedColor: Color,
    val effectiveBackgroundColor: Color,
    val isLightBackground: Boolean,
    val primaryButtonContainer: Color,
    val onPrimaryButton: Color,
    val secondaryButtonContainer: Color,
    val onSecondaryButton: Color,
    val tertiaryAccentContainer: Color,
    val onTertiaryAccent: Color,
    val playedLyricColor: Color,
    val unplayedLyricColor: Color,
    val primaryTextColor: Color,
    val secondaryTextColor: Color,
    val sliderActiveColor: Color,
    val sliderInactiveColor: Color,
    val cardSurfaceColor: Color,
    val cardOutlineColor: Color
)

fun buildPlayerExpressivePalette(
    coverSeedColor: Color,
    coverAverageColor: Color? = null,
    darkTheme: Boolean = true,
    variant: ColorVariant = ColorVariant.EXPRESSIVE,
    contrastLevel: ContrastLevel = ContrastLevel.STANDARD,
    blurCoverBackground: Boolean = true,
    useMaterialBackground: Boolean = true
): PlayerExpressivePalette {
    val scheme = buildExpressiveColorScheme(
        seedColor = coverSeedColor,
        darkTheme = if (blurCoverBackground) true else darkTheme,
        variant = variant,
        contrastLevel = contrastLevel,
        useMaterialBackground = useMaterialBackground
    )

    // 1. 确定播放页的实际背景基准色，用于严格的对比度检验
    val effectiveBg: Color = if (blurCoverBackground) {
        val rawCoverAvg = coverAverageColor ?: darken(coverSeedColor, 0.55f)
        // 模糊封面叠加半透明深色遮罩后的实际感知背景色
        val scrimOverlay = Color(0xFF0A0A0E)
        blend(rawCoverAvg, scrimOverlay, 0.58f)
    } else {
        if (useMaterialBackground) {
            scheme.background
        } else {
            if (darkTheme) Color.Black else Color.White
        }
    }

    val isLightBg = effectiveBg.luminance() > 0.38f
    val minTextRatio = contrastLevel.minTextContrast
    val minControlRatio = contrastLevel.minControlContrast

    // 2. 未播放部分的歌词：严格使用纯黑或纯白显示
    val unplayedLyricColor = if (isLightBg) Color.Black else Color.White

    // 3. 已播放部分的歌词（前序歌词与当前行已唱进度）：使用封面取色强调色，并校验与背景的对比度
    val seedHsl = colorToHsl(coverSeedColor)
    val accentHue = when (variant) {
        ColorVariant.EXPRESSIVE -> (seedHsl.h + 10f) % 360f
        ColorVariant.VIBRANT -> seedHsl.h
        else -> seedHsl.h
    }
    // 保证强调色具有鲜明色度，以便与纯黑/纯白未播放歌词形成清晰区分
    val accentSat = when (variant) {
        ColorVariant.MONOCHROME -> 52f
        ColorVariant.NEUTRAL -> 56f
        ColorVariant.VIBRANT, ColorVariant.EXPRESSIVE -> seedHsl.s.coerceIn(72f, 96f)
        else -> seedHsl.s.coerceIn(62f, 90f)
    }
    val initialAccentLightness = if (isLightBg) 34f else 72f
    val rawPlayedLyricColor = hslToColor(accentHue, accentSat, initialAccentLightness)
    val playedLyricColor = ensureContrast(
        foreground = rawPlayedLyricColor,
        background = effectiveBg,
        minRatio = max(minTextRatio, 4.5f),
        preferChroma = true
    )

    // 4. 播放界面主按钮（播放/暂停等）与次级按钮控件：确保与背景具有足够的对比度
    val rawPrimaryBtn = if (isLightBg) {
        hslToColor(accentHue, accentSat, 36f - contrastLevel.levelValue * 8f)
    } else {
        hslToColor(accentHue, accentSat, 74f + contrastLevel.levelValue * 8f)
    }
    val primaryButtonContainer = ensureContrast(
        foreground = rawPrimaryBtn,
        background = effectiveBg,
        minRatio = max(minControlRatio, 3.2f),
        preferChroma = true
    )
    val onPrimaryButton = ensureContrast(
        foreground = pureBlackOrWhiteOn(primaryButtonContainer),
        background = primaryButtonContainer,
        minRatio = max(minTextRatio, 4.5f),
        preferChroma = false
    )

    // 次级按钮容器（上一曲/下一曲/模式/列表/背景切换等）
    val secondaryHue = when (variant) {
        ColorVariant.EXPRESSIVE -> (accentHue + 38f) % 360f
        else -> accentHue
    }
    val rawSecondaryBtn = if (isLightBg) {
        hslToColor(secondaryHue, (accentSat * 0.65f).coerceIn(28f, 68f), 82f - contrastLevel.levelValue * 8f)
    } else {
        hslToColor(secondaryHue, (accentSat * 0.60f).coerceIn(26f, 64f), 26f + contrastLevel.levelValue * 8f)
    }
    val secondaryButtonContainer = ensureContrast(
        foreground = rawSecondaryBtn,
        background = effectiveBg,
        minRatio = (minControlRatio * 0.65f).coerceAtLeast(1.85f),
        preferChroma = true
    )
    val onSecondaryButton = ensureContrast(
        foreground = if (secondaryButtonContainer.luminance() > 0.38f) {
            hslToColor(secondaryHue, accentSat * 0.8f, 12f)
        } else {
            hslToColor(secondaryHue, accentSat * 0.8f, 92f)
        },
        background = secondaryButtonContainer,
        minRatio = max(minTextRatio, 4.5f),
        preferChroma = true
    )

    val tertiaryHue = (accentHue + 125f) % 360f
    val rawTertiaryContainer = if (isLightBg) {
        hslToColor(tertiaryHue, (accentSat * 0.75f).coerceIn(36f, 78f), 84f - contrastLevel.levelValue * 6f)
    } else {
        hslToColor(tertiaryHue, (accentSat * 0.75f).coerceIn(36f, 78f), 28f + contrastLevel.levelValue * 7f)
    }
    val tertiaryAccentContainer = ensureContrast(
        foreground = rawTertiaryContainer,
        background = effectiveBg,
        minRatio = 1.85f,
        preferChroma = true
    )
    val onTertiaryAccent = ensureContrast(
        foreground = pureBlackOrWhiteOn(tertiaryAccentContainer),
        background = tertiaryAccentContainer,
        minRatio = max(minTextRatio, 4.5f),
        preferChroma = false
    )

    val primaryTextColor = ensureContrast(
        foreground = if (isLightBg) Color(0xFF121216) else Color(0xFFF6F4F9),
        background = effectiveBg,
        minRatio = max(minTextRatio, 4.5f),
        preferChroma = false
    )
    val secondaryTextColor = ensureContrast(
        foreground = if (isLightBg) Color(0xFF424048) else Color(0xFFD2CED8),
        background = effectiveBg,
        minRatio = max(minTextRatio * 0.85f, 4.5f),
        preferChroma = false
    )

    val cardSurfaceColor = if (isLightBg) {
        hslToColor(accentHue, (accentSat * 0.25f).coerceAtMost(24f), 93f - contrastLevel.levelValue * 3f)
    } else {
        hslToColor(accentHue, (accentSat * 0.25f).coerceAtMost(24f), 15f + contrastLevel.levelValue * 4f)
    }
    val cardOutlineColor = ensureContrast(
        foreground = hslToColor(accentHue, accentSat * 0.5f, if (isLightBg) 55f else 48f),
        background = effectiveBg,
        minRatio = minControlRatio * 0.8f,
        preferChroma = true
    )

    return PlayerExpressivePalette(
        seedColor = coverSeedColor,
        effectiveBackgroundColor = effectiveBg,
        isLightBackground = isLightBg,
        primaryButtonContainer = primaryButtonContainer,
        onPrimaryButton = onPrimaryButton,
        secondaryButtonContainer = secondaryButtonContainer,
        onSecondaryButton = onSecondaryButton,
        tertiaryAccentContainer = tertiaryAccentContainer,
        onTertiaryAccent = onTertiaryAccent,
        playedLyricColor = playedLyricColor,
        unplayedLyricColor = unplayedLyricColor,
        primaryTextColor = primaryTextColor,
        secondaryTextColor = secondaryTextColor,
        sliderActiveColor = playedLyricColor,
        sliderInactiveColor = unplayedLyricColor.copy(alpha = if (isLightBg) 0.24f else 0.28f),
        cardSurfaceColor = cardSurfaceColor,
        cardOutlineColor = cardOutlineColor
    )
}

fun ColorScheme.applyUserColors(
    darkTheme: Boolean,
    accent: Color,
    bg: Color,
    monetEnabled: Boolean,
    variant: ColorVariant = ColorVariant.EXPRESSIVE,
    contrastLevel: ContrastLevel = ContrastLevel.STANDARD,
    useMaterialBackground: Boolean = true
): ColorScheme {
    val seed = if (monetEnabled) this.primary else accent
    return buildExpressiveColorScheme(
        seedColor = seed,
        darkTheme = darkTheme,
        variant = variant,
        contrastLevel = contrastLevel,
        useMaterialBackground = useMaterialBackground
    )
}
