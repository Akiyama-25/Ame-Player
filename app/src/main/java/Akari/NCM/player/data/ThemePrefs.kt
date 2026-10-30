package Akari.NCM.player.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemePrefs {
    private const val NAME = "ame_theme_prefs"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_USE_MONET_COLORS = "use_monet_colors"
    private const val KEY_CUSTOM_ACCENT_COLOR = "custom_accent_color"
    private const val KEY_CUSTOM_LIGHT_BG_COLOR = "custom_light_bg_color"
    private const val KEY_CUSTOM_DARK_BG_COLOR = "custom_dark_bg_color"
    private const val KEY_CUSTOM_ACCENT_H = "custom_accent_h"
    private const val KEY_CUSTOM_ACCENT_S = "custom_accent_s"
    private const val KEY_CUSTOM_ACCENT_L = "custom_accent_l"
    private const val KEY_CUSTOM_BG_H = "custom_bg_h"
    private const val KEY_CUSTOM_BG_S = "custom_bg_s"
    private const val KEY_CUSTOM_BG_L = "custom_bg_l"
    private const val KEY_GRID_COLUMNS = "settings_grid_columns"
    private const val KEY_SHOW_MATCH_STATUS = "show_match_status"
    private const val KEY_LIVE_ISLAND_ENABLED = "live_island_enabled"
    private const val KEY_PLAYBACK_REPEAT_MODE = "playback_repeat_mode"
    private const val KEY_PLAYBACK_SHUFFLE_ENABLED = "playback_shuffle_enabled"
    private const val KEY_VOLUME_NORMALIZATION_ENABLED = "volume_normalization_enabled"
    private const val KEY_TARGET_LOUDNESS_LUFS = "target_loudness_lufs"
    private const val KEY_LOUDNESS_PEAK_LIMIT_ENABLED = "loudness_peak_limit_enabled"

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    private lateinit var prefs: SharedPreferences

    private val _themeFlow = MutableStateFlow(THEME_SYSTEM)
    val themeFlow: StateFlow<String> = _themeFlow.asStateFlow()

    const val DEFAULT_ACCENT_COLOR: Long = 0xFF6750A4
    const val DEFAULT_LIGHT_BG_COLOR: Long = 0xFFFFFBFF
    const val DEFAULT_DARK_BG_COLOR: Long = 0xFF1C1B1F

    private val _monetFlow = MutableStateFlow(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
    val monetFlow: StateFlow<Boolean> = _monetFlow.asStateFlow()

    private val _accentColorFlow = MutableStateFlow(DEFAULT_ACCENT_COLOR)
    val accentColorFlow: StateFlow<Long> = _accentColorFlow.asStateFlow()

    private val _lightBgColorFlow = MutableStateFlow(DEFAULT_LIGHT_BG_COLOR)
    val lightBgColorFlow: StateFlow<Long> = _lightBgColorFlow.asStateFlow()

    private val _darkBgColorFlow = MutableStateFlow(DEFAULT_DARK_BG_COLOR)
    val darkBgColorFlow: StateFlow<Long> = _darkBgColorFlow.asStateFlow()

    private val _accentHFlow = MutableStateFlow(256f)
    val accentHFlow: StateFlow<Float> = _accentHFlow.asStateFlow()

    private val _accentSFlow = MutableStateFlow(34f)
    val accentSFlow: StateFlow<Float> = _accentSFlow.asStateFlow()

    private val _accentLFlow = MutableStateFlow(48f)
    val accentLFlow: StateFlow<Float> = _accentLFlow.asStateFlow()

    private val _bgHFlow = MutableStateFlow(0f)
    val bgHFlow: StateFlow<Float> = _bgHFlow.asStateFlow()

    private val _bgSFlow = MutableStateFlow(0f)
    val bgSFlow: StateFlow<Float> = _bgSFlow.asStateFlow()

    private val _bgLFlow = MutableStateFlow(98f)
    val bgLFlow: StateFlow<Float> = _bgLFlow.asStateFlow()

    private val _gridColumnsFlow = MutableStateFlow(2)
    val gridColumnsFlow: StateFlow<Int> = _gridColumnsFlow.asStateFlow()

    private val _showMatchStatusFlow = MutableStateFlow(false)
    val showMatchStatusFlow: StateFlow<Boolean> = _showMatchStatusFlow.asStateFlow()

    private val _liveIslandEnabledFlow = MutableStateFlow(true)
    val liveIslandEnabledFlow: StateFlow<Boolean> = _liveIslandEnabledFlow.asStateFlow()

    private val _volumeNormalizationFlow = MutableStateFlow(true)
    val volumeNormalizationFlow: StateFlow<Boolean> = _volumeNormalizationFlow.asStateFlow()

    private val _targetLoudnessFlow = MutableStateFlow(-14.0f)
    val targetLoudnessFlow: StateFlow<Float> = _targetLoudnessFlow.asStateFlow()

    private val _loudnessPeakLimitFlow = MutableStateFlow(true)
    val loudnessPeakLimitFlow: StateFlow<Boolean> = _loudnessPeakLimitFlow.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        _themeFlow.value = themeMode
        _monetFlow.value = useMonetColors
        _accentColorFlow.value = customAccentColor
        _lightBgColorFlow.value = customLightBgColor
        _darkBgColorFlow.value = customDarkBgColor
        _accentHFlow.value = customAccentH
        _accentSFlow.value = customAccentS
        _accentLFlow.value = customAccentL
        _bgHFlow.value = customBgH
        _bgSFlow.value = customBgS
        _bgLFlow.value = customBgL
        _gridColumnsFlow.value = gridColumns
        _showMatchStatusFlow.value = showMatchStatus
        _liveIslandEnabledFlow.value = liveIslandEnabled
        _volumeNormalizationFlow.value = volumeNormalizationEnabled
        _targetLoudnessFlow.value = targetLoudnessLufs
        _loudnessPeakLimitFlow.value = loudnessPeakLimitEnabled
    }

    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value).apply()
            _themeFlow.value = value
        }

    var useMonetColors: Boolean
        get() = prefs.getBoolean(KEY_USE_MONET_COLORS, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        set(value) {
            prefs.edit().putBoolean(KEY_USE_MONET_COLORS, value).apply()
            _monetFlow.value = value
        }

    var customAccentColor: Long
        get() = prefs.getLong(KEY_CUSTOM_ACCENT_COLOR, DEFAULT_ACCENT_COLOR)
        set(value) {
            prefs.edit().putLong(KEY_CUSTOM_ACCENT_COLOR, value).apply()
            _accentColorFlow.value = value
        }

    var customLightBgColor: Long
        get() = prefs.getLong(KEY_CUSTOM_LIGHT_BG_COLOR, DEFAULT_LIGHT_BG_COLOR)
        set(value) {
            prefs.edit().putLong(KEY_CUSTOM_LIGHT_BG_COLOR, value).apply()
            _lightBgColorFlow.value = value
        }

    var customDarkBgColor: Long
        get() = prefs.getLong(KEY_CUSTOM_DARK_BG_COLOR, DEFAULT_DARK_BG_COLOR)
        set(value) {
            prefs.edit().putLong(KEY_CUSTOM_DARK_BG_COLOR, value).apply()
            _darkBgColorFlow.value = value
        }

    var customAccentH: Float
        get() = prefs.getFloat(KEY_CUSTOM_ACCENT_H, 256f)
        set(value) {
            prefs.edit().putFloat(KEY_CUSTOM_ACCENT_H, value).apply()
            _accentHFlow.value = value
        }

    var customAccentS: Float
        get() = prefs.getFloat(KEY_CUSTOM_ACCENT_S, 34f)
        set(value) {
            prefs.edit().putFloat(KEY_CUSTOM_ACCENT_S, value).apply()
            _accentSFlow.value = value
        }

    var customAccentL: Float
        get() = prefs.getFloat(KEY_CUSTOM_ACCENT_L, 48f)
        set(value) {
            prefs.edit().putFloat(KEY_CUSTOM_ACCENT_L, value).apply()
            _accentLFlow.value = value
        }

    var customBgH: Float
        get() = prefs.getFloat(KEY_CUSTOM_BG_H, 0f)
        set(value) {
            prefs.edit().putFloat(KEY_CUSTOM_BG_H, value).apply()
            _bgHFlow.value = value
        }

    var customBgS: Float
        get() = prefs.getFloat(KEY_CUSTOM_BG_S, 0f)
        set(value) {
            prefs.edit().putFloat(KEY_CUSTOM_BG_S, value).apply()
            _bgSFlow.value = value
        }

    var customBgL: Float
        get() = prefs.getFloat(KEY_CUSTOM_BG_L, 98f)
        set(value) {
            prefs.edit().putFloat(KEY_CUSTOM_BG_L, value).apply()
            _bgLFlow.value = value
        }

    var gridColumns: Int
        get() = prefs.getInt(KEY_GRID_COLUMNS, 2)
        set(value) {
            prefs.edit().putInt(KEY_GRID_COLUMNS, value).apply()
            _gridColumnsFlow.value = value
        }

    var showMatchStatus: Boolean
        get() = prefs.getBoolean(KEY_SHOW_MATCH_STATUS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHOW_MATCH_STATUS, value).apply()
            _showMatchStatusFlow.value = value
        }

    var liveIslandEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_LIVE_ISLAND_ENABLED, true) else _liveIslandEnabledFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_LIVE_ISLAND_ENABLED, value).apply()
            }
            _liveIslandEnabledFlow.value = value
        }

    var playbackRepeatMode: String
        get() = if (::prefs.isInitialized) prefs.getString(KEY_PLAYBACK_REPEAT_MODE, "OFF") ?: "OFF" else "OFF"
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putString(KEY_PLAYBACK_REPEAT_MODE, value).apply()
            }
        }

    var playbackShuffleEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_PLAYBACK_SHUFFLE_ENABLED, false) else false
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_PLAYBACK_SHUFFLE_ENABLED, value).apply()
            }
        }

    var volumeNormalizationEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_VOLUME_NORMALIZATION_ENABLED, true) else _volumeNormalizationFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_VOLUME_NORMALIZATION_ENABLED, value).apply()
            }
            _volumeNormalizationFlow.value = value
        }

    var targetLoudnessLufs: Float
        get() = if (::prefs.isInitialized) prefs.getFloat(KEY_TARGET_LOUDNESS_LUFS, -14.0f) else _targetLoudnessFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putFloat(KEY_TARGET_LOUDNESS_LUFS, value).apply()
            }
            _targetLoudnessFlow.value = value
        }

    var loudnessPeakLimitEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_LOUDNESS_PEAK_LIMIT_ENABLED, true) else _loudnessPeakLimitFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_LOUDNESS_PEAK_LIMIT_ENABLED, value).apply()
            }
            _loudnessPeakLimitFlow.value = value
        }
}
