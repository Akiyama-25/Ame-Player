package Akari.NCM.player.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import Akari.NCM.player.data.ThemePrefs

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF23005C),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1E192B),
    tertiary = Color(0xFF7E5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E3),
    onTertiaryContainer = Color(0xFF370B1E),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EB),
    onSurfaceVariant = Color(0xFF49454E)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF3A1D72),
    primaryContainer = Color(0xFF5234A3),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFCBC2DB),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF4A2532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD9E3),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E6),
    surfaceVariant = Color(0xFF49454E),
    onSurfaceVariant = Color(0xFFCAC4CF)
)

@Composable
fun AmePlayerTheme(
    content: @Composable () -> Unit
) {
    val themeMode by ThemePrefs.themeFlow.collectAsState()
    val useMonet by ThemePrefs.monetFlow.collectAsState()
    val accentColor by ThemePrefs.accentColorFlow.collectAsState()
    val lightBg by ThemePrefs.lightBgColorFlow.collectAsState()
    val darkBg by ThemePrefs.darkBgColorFlow.collectAsState()
    
    val isSystemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemePrefs.THEME_DARK -> true
        ThemePrefs.THEME_LIGHT -> false
        else -> isSystemDark
    }

    val bg = Color((if (darkTheme) darkBg else lightBg).toInt())
    val accent = Color(accentColor.toInt())

    val baseScheme = when {
        useMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val colorScheme = baseScheme.applyUserColors(darkTheme, accent, bg, useMonet)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

