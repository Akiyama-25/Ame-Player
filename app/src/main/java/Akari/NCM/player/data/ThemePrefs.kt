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
    private const val KEY_COLOR_VARIANT = "color_variant"
    private const val KEY_CONTRAST_LEVEL = "contrast_level"
    private const val KEY_USE_MATERIAL_BG = "use_material_bg"
    private const val KEY_PLAYER_BLUR_COVER_BG = "player_blur_cover_bg"
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
    private const val KEY_LOUDNESS_NEGATIVE_GAIN_ONLY = "loudness_negative_gain_only"
    private const val KEY_USB_EXCLUSIVE_MODE = "usb_exclusive_mode"
    private const val KEY_PLAYBACK_FADE_ENABLED = "playback_fade_enabled"
    private const val KEY_PLAYBACK_FADE_DURATION_MS = "playback_fade_duration_ms"
    private const val KEY_UPDATE_CHANNEL = "update_channel"
    private const val KEY_AUTO_CHECK_UPDATE = "auto_check_update"
    private const val KEY_LAST_PLAYBACK_PLAYLIST_JSON = "last_playback_playlist_json"
    private const val KEY_LAST_PLAYBACK_INDEX = "last_playback_index"
    private const val KEY_LAST_PLAYBACK_SOURCE_TYPE = "last_playback_source_type"

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

    private val _colorVariantFlow = MutableStateFlow(ColorVariant.EXPRESSIVE)
    val colorVariantFlow: StateFlow<ColorVariant> = _colorVariantFlow.asStateFlow()

    private val _contrastLevelFlow = MutableStateFlow(ContrastLevel.STANDARD)
    val contrastLevelFlow: StateFlow<ContrastLevel> = _contrastLevelFlow.asStateFlow()

    private val _useMaterialBgFlow = MutableStateFlow(true)
    val useMaterialBgFlow: StateFlow<Boolean> = _useMaterialBgFlow.asStateFlow()

    private val _playerBlurCoverBgFlow = MutableStateFlow(true)
    val playerBlurCoverBgFlow: StateFlow<Boolean> = _playerBlurCoverBgFlow.asStateFlow()

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

    private val _liveIslandEnabledFlow = MutableStateFlow(false)
    val liveIslandEnabledFlow: StateFlow<Boolean> = _liveIslandEnabledFlow.asStateFlow()

    private val _volumeNormalizationFlow = MutableStateFlow(true)
    val volumeNormalizationFlow: StateFlow<Boolean> = _volumeNormalizationFlow.asStateFlow()

    private val _targetLoudnessFlow = MutableStateFlow(-14.0f)
    val targetLoudnessFlow: StateFlow<Float> = _targetLoudnessFlow.asStateFlow()

    private val _loudnessPeakLimitFlow = MutableStateFlow(true)
    val loudnessPeakLimitFlow: StateFlow<Boolean> = _loudnessPeakLimitFlow.asStateFlow()

    private val _loudnessNegativeGainOnlyFlow = MutableStateFlow(false)
    val loudnessNegativeGainOnlyFlow: StateFlow<Boolean> = _loudnessNegativeGainOnlyFlow.asStateFlow()

    private val _usbExclusiveModeFlow = MutableStateFlow(false)
    val usbExclusiveModeFlow: StateFlow<Boolean> = _usbExclusiveModeFlow.asStateFlow()

    private val _playbackFadeEnabledFlow = MutableStateFlow(true)
    val playbackFadeEnabledFlow: StateFlow<Boolean> = _playbackFadeEnabledFlow.asStateFlow()

    private val _playbackFadeDurationMsFlow = MutableStateFlow(750)
    val playbackFadeDurationMsFlow: StateFlow<Int> = _playbackFadeDurationMsFlow.asStateFlow()

    private val _updateChannelFlow = MutableStateFlow(UpdateChannel.RELEASE)
    val updateChannelFlow: StateFlow<UpdateChannel> = _updateChannelFlow.asStateFlow()

    private val _autoCheckUpdateFlow = MutableStateFlow(true)
    val autoCheckUpdateFlow: StateFlow<Boolean> = _autoCheckUpdateFlow.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        _themeFlow.value = themeMode
        _monetFlow.value = useMonetColors
        _colorVariantFlow.value = colorVariant
        _contrastLevelFlow.value = contrastLevel
        _useMaterialBgFlow.value = useMaterialBackground
        _playerBlurCoverBgFlow.value = playerBlurCoverBackground
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
        _loudnessNegativeGainOnlyFlow.value = loudnessNegativeGainOnly
        _usbExclusiveModeFlow.value = usbExclusiveMode
        _playbackFadeEnabledFlow.value = playbackFadeEnabled
        _playbackFadeDurationMsFlow.value = playbackFadeDurationMs
        _updateChannelFlow.value = updateChannel
        _autoCheckUpdateFlow.value = autoCheckUpdate
    }

    var themeMode: String
        get() = if (::prefs.isInitialized) prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM else _themeFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putString(KEY_THEME_MODE, value).apply()
            }
            _themeFlow.value = value
        }

    var useMonetColors: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_USE_MONET_COLORS, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) else _monetFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_USE_MONET_COLORS, value).apply()
            }
            _monetFlow.value = value
        }

    var colorVariant: ColorVariant
        get() = if (::prefs.isInitialized) {
            ColorVariant.fromKey(prefs.getString(KEY_COLOR_VARIANT, ColorVariant.EXPRESSIVE.key) ?: ColorVariant.EXPRESSIVE.key)
        } else _colorVariantFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putString(KEY_COLOR_VARIANT, value.key).apply()
            }
            _colorVariantFlow.value = value
        }

    var contrastLevel: ContrastLevel
        get() = if (::prefs.isInitialized) {
            ContrastLevel.fromKey(prefs.getString(KEY_CONTRAST_LEVEL, ContrastLevel.STANDARD.key) ?: ContrastLevel.STANDARD.key)
        } else _contrastLevelFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putString(KEY_CONTRAST_LEVEL, value.key).apply()
            }
            _contrastLevelFlow.value = value
        }

    var useMaterialBackground: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_USE_MATERIAL_BG, true) else _useMaterialBgFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_USE_MATERIAL_BG, value).apply()
            }
            _useMaterialBgFlow.value = value
        }

    var playerBlurCoverBackground: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_PLAYER_BLUR_COVER_BG, true) else _playerBlurCoverBgFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_PLAYER_BLUR_COVER_BG, value).apply()
            }
            _playerBlurCoverBgFlow.value = value
        }

    var customAccentColor: Long
        get() = if (::prefs.isInitialized) prefs.getLong(KEY_CUSTOM_ACCENT_COLOR, DEFAULT_ACCENT_COLOR) else _accentColorFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putLong(KEY_CUSTOM_ACCENT_COLOR, value).apply()
            }
            _accentColorFlow.value = value
        }

    var customLightBgColor: Long
        get() = if (::prefs.isInitialized) prefs.getLong(KEY_CUSTOM_LIGHT_BG_COLOR, DEFAULT_LIGHT_BG_COLOR) else _lightBgColorFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putLong(KEY_CUSTOM_LIGHT_BG_COLOR, value).apply()
            }
            _lightBgColorFlow.value = value
        }

    var customDarkBgColor: Long
        get() = if (::prefs.isInitialized) prefs.getLong(KEY_CUSTOM_DARK_BG_COLOR, DEFAULT_DARK_BG_COLOR) else _darkBgColorFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putLong(KEY_CUSTOM_DARK_BG_COLOR, value).apply()
            }
            _darkBgColorFlow.value = value
        }

    var customAccentH: Float
        get() = if (::prefs.isInitialized) prefs.getFloat(KEY_CUSTOM_ACCENT_H, 256f) else _accentHFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putFloat(KEY_CUSTOM_ACCENT_H, value).apply()
            }
            _accentHFlow.value = value
        }

    var customAccentS: Float
        get() = if (::prefs.isInitialized) prefs.getFloat(KEY_CUSTOM_ACCENT_S, 34f) else _accentSFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putFloat(KEY_CUSTOM_ACCENT_S, value).apply()
            }
            _accentSFlow.value = value
        }

    var customAccentL: Float
        get() = if (::prefs.isInitialized) prefs.getFloat(KEY_CUSTOM_ACCENT_L, 48f) else _accentLFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putFloat(KEY_CUSTOM_ACCENT_L, value).apply()
            }
            _accentLFlow.value = value
        }

    var customBgH: Float
        get() = if (::prefs.isInitialized) prefs.getFloat(KEY_CUSTOM_BG_H, 0f) else _bgHFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putFloat(KEY_CUSTOM_BG_H, value).apply()
            }
            _bgHFlow.value = value
        }

    var customBgS: Float
        get() = if (::prefs.isInitialized) prefs.getFloat(KEY_CUSTOM_BG_S, 0f) else _bgSFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putFloat(KEY_CUSTOM_BG_S, value).apply()
            }
            _bgSFlow.value = value
        }

    var customBgL: Float
        get() = if (::prefs.isInitialized) prefs.getFloat(KEY_CUSTOM_BG_L, 98f) else _bgLFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putFloat(KEY_CUSTOM_BG_L, value).apply()
            }
            _bgLFlow.value = value
        }

    var gridColumns: Int
        get() = if (::prefs.isInitialized) prefs.getInt(KEY_GRID_COLUMNS, 2) else _gridColumnsFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putInt(KEY_GRID_COLUMNS, value).apply()
            }
            _gridColumnsFlow.value = value
        }

    var showMatchStatus: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_SHOW_MATCH_STATUS, false) else _showMatchStatusFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_SHOW_MATCH_STATUS, value).apply()
            }
            _showMatchStatusFlow.value = value
        }

    var liveIslandEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_LIVE_ISLAND_ENABLED, false) else _liveIslandEnabledFlow.value
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

    var loudnessNegativeGainOnly: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_LOUDNESS_NEGATIVE_GAIN_ONLY, false) else _loudnessNegativeGainOnlyFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_LOUDNESS_NEGATIVE_GAIN_ONLY, value).apply()
            }
            _loudnessNegativeGainOnlyFlow.value = value
        }

    var usbExclusiveMode: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_USB_EXCLUSIVE_MODE, false) else _usbExclusiveModeFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_USB_EXCLUSIVE_MODE, value).apply()
            }
            _usbExclusiveModeFlow.value = value
        }

    var playbackFadeEnabled: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_PLAYBACK_FADE_ENABLED, true) else _playbackFadeEnabledFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_PLAYBACK_FADE_ENABLED, value).apply()
            }
            _playbackFadeEnabledFlow.value = value
        }

    var playbackFadeDurationMs: Int
        get() = if (::prefs.isInitialized) prefs.getInt(KEY_PLAYBACK_FADE_DURATION_MS, 750) else _playbackFadeDurationMsFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putInt(KEY_PLAYBACK_FADE_DURATION_MS, value).apply()
            }
            _playbackFadeDurationMsFlow.value = value
        }

    var updateChannel: UpdateChannel
        get() = if (::prefs.isInitialized) {
            UpdateChannel.fromKey(prefs.getString(KEY_UPDATE_CHANNEL, UpdateChannel.RELEASE.key) ?: UpdateChannel.RELEASE.key)
        } else _updateChannelFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putString(KEY_UPDATE_CHANNEL, value.key).apply()
            }
            _updateChannelFlow.value = value
        }

    var autoCheckUpdate: Boolean
        get() = if (::prefs.isInitialized) prefs.getBoolean(KEY_AUTO_CHECK_UPDATE, true) else _autoCheckUpdateFlow.value
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putBoolean(KEY_AUTO_CHECK_UPDATE, value).apply()
            }
            _autoCheckUpdateFlow.value = value
        }

    var lastPlaybackPlaylistJson: String
        get() = if (::prefs.isInitialized) prefs.getString(KEY_LAST_PLAYBACK_PLAYLIST_JSON, "") ?: "" else ""
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putString(KEY_LAST_PLAYBACK_PLAYLIST_JSON, value).apply()
            }
        }

    var lastPlaybackIndex: Int
        get() = if (::prefs.isInitialized) prefs.getInt(KEY_LAST_PLAYBACK_INDEX, 0) else 0
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putInt(KEY_LAST_PLAYBACK_INDEX, value).apply()
            }
        }

    var lastPlaybackSourceType: String
        get() = if (::prefs.isInitialized) prefs.getString(KEY_LAST_PLAYBACK_SOURCE_TYPE, "NONE") ?: "NONE" else "NONE"
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putString(KEY_LAST_PLAYBACK_SOURCE_TYPE, value).apply()
            }
        }
}

enum class ColorVariant(
    val key: String,
    val label: String,
    val description: String
) {
    NEUTRAL("neutral", "Neutral", "克制、低彩度的界面基调"),
    TONAL_SPOT("tonal_spot", "Tonal Spot", "均衡的 Material 默认风格"),
    VIBRANT("vibrant", "Vibrant", "保留色相并显著提高彩度"),
    EXPRESSIVE("expressive", "Expressive", "旋转色相，产生更强个性"),
    MONOCHROME("monochrome", "Monochrome", "纯黑白，最大限度削弱色彩");

    companion object {
        fun fromKey(key: String): ColorVariant =
            entries.find { it.key.equals(key, ignoreCase = true) } ?: EXPRESSIVE
    }
}

enum class ContrastLevel(
    val key: String,
    val label: String,
    val shortLabel: String,
    val description: String,
    val levelValue: Float,
    val minTextContrast: Float,
    val minControlContrast: Float
) {
    STANDARD(
        key = "standard",
        label = "标准对比度 (0.0)",
        shortLabel = "标准",
        description = "平衡柔和的色阶过渡，符合 WCAG AA 阅读标准",
        levelValue = 0.0f,
        minTextContrast = 4.5f,
        minControlContrast = 3.0f
    ),
    MEDIUM(
        key = "medium",
        label = "中等对比度 (+0.5)",
        shortLabel = "中等",
        description = "增强按钮容器与背景的明暗反差，提升户外与歌词辨识度",
        levelValue = 0.5f,
        minTextContrast = 6.0f,
        minControlContrast = 3.8f
    ),
    HIGH(
        key = "high",
        label = "高对比度 (+1.0)",
        shortLabel = "高对比",
        description = "最大化前景与背景的对比度（WCAG AAA 级），轮廓鲜明锐利",
        levelValue = 1.0f,
        minTextContrast = 7.5f,
        minControlContrast = 4.5f
    );

    companion object {
        fun fromKey(key: String): ContrastLevel =
            entries.find { it.key.equals(key, ignoreCase = true) } ?: STANDARD
    }
}

enum class UpdateChannel(val key: String, val label: String) {
    RELEASE("release", "Release (正式发布)"),
    ACTION("action", "Action (测试构建)");

    companion object {
        fun fromKey(key: String): UpdateChannel =
            entries.find { it.key.equals(key, ignoreCase = true) } ?: RELEASE
    }
}
