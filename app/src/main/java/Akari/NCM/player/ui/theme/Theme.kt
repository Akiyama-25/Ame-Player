package Akari.NCM.player.ui.theme

import Akari.NCM.player.data.CustomFontManager
import Akari.NCM.player.data.ThemePrefs
import android.app.Activity
import android.app.WallpaperManager
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

data class SystemBarAppearanceOverride(
    val key: String,
    val isLightStatusBars: Boolean,
    val isLightNavigationBars: Boolean
)

/**
 * 统一管理系统状态栏/导航栏图标明暗覆写栈，避免子页面（如播放页）与主题层互相覆盖或退出时误还原。
 */
class SystemBarAppearanceController {
    private val overrides = mutableStateListOf<SystemBarAppearanceOverride>()

    fun setOverride(
        key: String,
        isLightStatusBars: Boolean,
        isLightNavigationBars: Boolean
    ) {
        val next = SystemBarAppearanceOverride(
            key = key,
            isLightStatusBars = isLightStatusBars,
            isLightNavigationBars = isLightNavigationBars
        )
        val existingIdx = overrides.indexOfFirst { it.key == key }
        if (existingIdx >= 0) {
            if (overrides[existingIdx] != next) {
                overrides[existingIdx] = next
            }
        } else {
            overrides.add(next)
        }
    }

    fun clearOverride(key: String) {
        val idx = overrides.indexOfFirst { it.key == key }
        if (idx >= 0) {
            overrides.removeAt(idx)
        }
    }

    fun activeOverrideOrNull(): SystemBarAppearanceOverride? = overrides.lastOrNull()
}

val LocalSystemBarAppearanceController = staticCompositionLocalOf<SystemBarAppearanceController?> { null }

val AmeExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

val AmeExpressiveTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 30.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp
    )
)

fun Typography.withFontFamily(fontFamily: FontFamily?): Typography {
    if (fontFamily == null) return this
    return copy(
        displayLarge = displayLarge.copy(fontFamily = fontFamily),
        displayMedium = displayMedium.copy(fontFamily = fontFamily),
        displaySmall = displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = titleLarge.copy(fontFamily = fontFamily),
        titleMedium = titleMedium.copy(fontFamily = fontFamily),
        titleSmall = titleSmall.copy(fontFamily = fontFamily),
        bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = bodySmall.copy(fontFamily = fontFamily),
        labelLarge = labelLarge.copy(fontFamily = fontFamily),
        labelMedium = labelMedium.copy(fontFamily = fontFamily),
        labelSmall = labelSmall.copy(fontFamily = fontFamily)
    )
}

@Composable
fun rememberIsDarkTheme(): Boolean {
    val themeMode by ThemePrefs.themeFlow.collectAsState()
    val isSystemDark = isSystemInDarkTheme()
    return when (themeMode) {
        ThemePrefs.THEME_DARK -> true
        ThemePrefs.THEME_LIGHT -> false
        else -> isSystemDark
    }
}

@Composable
private fun SystemBarAppearanceEffect(
    controller: SystemBarAppearanceController,
    baseLightStatusBars: Boolean,
    baseLightNavigationBars: Boolean
) {
    val view = LocalView.current
    val activeOverride = controller.activeOverrideOrNull()
    val effectiveLightStatusBars = activeOverride?.isLightStatusBars ?: baseLightStatusBars
    val effectiveLightNavigationBars = activeOverride?.isLightNavigationBars ?: baseLightNavigationBars

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val desiredCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                if (window.attributes.layoutInDisplayCutoutMode != desiredCutoutMode) {
                    window.attributes = window.attributes.apply {
                        layoutInDisplayCutoutMode = desiredCutoutMode
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = effectiveLightStatusBars
            insetsController.isAppearanceLightNavigationBars = effectiveLightNavigationBars
        }
    }
}

@Composable
fun AmePlayerTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    remember(context) { CustomFontManager.init(context) }
    val customFontFamily by CustomFontManager.currentFontFamilyFlow.collectAsState()
    val typography = remember(customFontFamily) {
        AmeExpressiveTypography.withFontFamily(customFontFamily)
    }

    val darkTheme = rememberIsDarkTheme()
    val useMonet by ThemePrefs.monetFlow.collectAsState()
    val colorVariant by ThemePrefs.colorVariantFlow.collectAsState()
    val contrastLevel by ThemePrefs.contrastLevelFlow.collectAsState()
    val useMaterialBg by ThemePrefs.useMaterialBgFlow.collectAsState()
    val accentColor by ThemePrefs.accentColorFlow.collectAsState()

    // 非播放页面：开启壁纸动态取色时从系统壁纸提取种子色，否则使用自定义强调色
    val wallpaperSeedColor = remember(context, darkTheme, useMonet, accentColor) {
        if (useMonet) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dynScheme = runCatching {
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                }.getOrNull()
                if (dynScheme != null) {
                    dynScheme.primary
                } else {
                    Color(accentColor.toInt())
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val wpColors = runCatching {
                    WallpaperManager.getInstance(context)
                        .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                }.getOrNull()
                val primaryArgb = wpColors?.primaryColor?.toArgb()
                if (primaryArgb != null) Color(primaryArgb) else Color(accentColor.toInt())
            } else {
                Color(accentColor.toInt())
            }
        } else {
            Color(accentColor.toInt())
        }
    }

    val colorScheme = remember(
        wallpaperSeedColor,
        darkTheme,
        colorVariant,
        contrastLevel,
        useMaterialBg
    ) {
        buildExpressiveColorScheme(
            seedColor = wallpaperSeedColor,
            darkTheme = darkTheme,
            variant = colorVariant,
            contrastLevel = contrastLevel,
            useMaterialBackground = useMaterialBg
        )
    }

    val systemBarController = remember { SystemBarAppearanceController() }
    val baseLightStatusBars = shouldUseDarkSystemBarIcons(colorScheme.background)
    val baseLightNavigationBars = shouldUseDarkSystemBarIcons(colorScheme.surfaceContainer)
    SystemBarAppearanceEffect(
        controller = systemBarController,
        baseLightStatusBars = baseLightStatusBars,
        baseLightNavigationBars = baseLightNavigationBars
    )

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AmeExpressiveShapes,
        typography = typography
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colorScheme.onBackground,
            LocalSystemBarAppearanceController provides systemBarController,
            content = content
        )
    }
}
