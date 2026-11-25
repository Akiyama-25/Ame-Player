package Akari.NCM.player.data

import Akari.NCM.player.core.HomepageMode
import Akari.NCM.player.core.QualityLevel
import Akari.NCM.player.core.UserProfile
import Akari.NCM.player.core.VipTier
import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserSessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _cookieState = MutableStateFlow(getCookie())
    val cookieState: StateFlow<String> = _cookieState.asStateFlow()

    private val _qualityState = MutableStateFlow(getQualityLevel())
    val qualityState: StateFlow<QualityLevel> = _qualityState.asStateFlow()

    private val _downloadQualityState = MutableStateFlow(getDownloadQualityLevel())
    val downloadQualityState: StateFlow<QualityLevel> = _downloadQualityState.asStateFlow()

    private val _autoDownloadOnFavoriteState = MutableStateFlow(isAutoDownloadOnFavoriteEnabled())
    val autoDownloadOnFavoriteState: StateFlow<Boolean> = _autoDownloadOnFavoriteState.asStateFlow()

    private val _autoDownloadWifiOnlyState = MutableStateFlow(isAutoDownloadWifiOnlyEnabled())
    val autoDownloadWifiOnlyState: StateFlow<Boolean> = _autoDownloadWifiOnlyState.asStateFlow()

    private val _downloadPathState = MutableStateFlow(getDownloadPath())
    val downloadPathState: StateFlow<String> = _downloadPathState.asStateFlow()

    private val _userProfileState = MutableStateFlow(getUserProfile())
    val userProfileState: StateFlow<UserProfile?> = _userProfileState.asStateFlow()

    private val _apiBaseUrlState = MutableStateFlow(getApiBaseUrl())
    val apiBaseUrlState: StateFlow<String> = _apiBaseUrlState.asStateFlow()

    private val _homepageModeState = MutableStateFlow(getHomepageMode())
    val homepageModeState: StateFlow<HomepageMode> = _homepageModeState.asStateFlow()

    init {
        // 自动净化历史残留的脏 Cookie（移除 Path, Expires, Max-Age 等服务端指示）
        val raw = prefs.getString(KEY_COOKIE, "") ?: ""
        if (raw.isNotBlank()) {
            val cleaned = cleanCookieString(raw)
            if (cleaned != raw) {
                saveCookie(cleaned)
            }
        }
    }

    fun getCookie(): String {
        val raw = prefs.getString(KEY_COOKIE, "") ?: ""
        return cleanCookieString(raw)
    }

    fun saveCookie(cookie: String) {
        val cleaned = cleanCookieString(cookie)
        prefs.edit().putString(KEY_COOKIE, cleaned).apply()
        _cookieState.value = cleaned
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_COOKIE)
            .remove(KEY_USER_PROFILE)
            .apply()
        _cookieState.value = ""
        _userProfileState.value = null
    }

    fun isLoggedIn(): Boolean {
        val cookie = getCookie()
        return cookie.contains("MUSIC_U") || cookie.isNotBlank()
    }

    fun getQualityLevel(): QualityLevel {
        val value = prefs.getString(KEY_QUALITY, QualityLevel.EXHIGH.value) ?: QualityLevel.EXHIGH.value
        return QualityLevel.fromValue(value)
    }

    fun getEffectiveQualityLevel(): QualityLevel {
        return clampQualityByVipTier(getQualityLevel())
    }

    fun saveQualityLevel(level: QualityLevel) {
        prefs.edit().putString(KEY_QUALITY, level.value).apply()
        _qualityState.value = level
    }

    fun getDownloadQualityLevel(): QualityLevel {
        val value = prefs.getString(KEY_DOWNLOAD_QUALITY, QualityLevel.EXHIGH.value) ?: QualityLevel.EXHIGH.value
        return QualityLevel.fromValue(value)
    }

    fun getEffectiveDownloadQualityLevel(): QualityLevel {
        return clampQualityByVipTier(getDownloadQualityLevel())
    }

    fun saveDownloadQualityLevel(level: QualityLevel) {
        prefs.edit().putString(KEY_DOWNLOAD_QUALITY, level.value).apply()
        _downloadQualityState.value = level
    }

    fun isAutoDownloadOnFavoriteEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_DOWNLOAD_ON_FAVORITE, false)
    }

    fun setAutoDownloadOnFavoriteEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_ON_FAVORITE, enabled).apply()
        _autoDownloadOnFavoriteState.value = enabled
    }

    fun isAutoDownloadWifiOnlyEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_DOWNLOAD_WIFI_ONLY, true)
    }

    fun setAutoDownloadWifiOnlyEnabled(wifiOnly: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_WIFI_ONLY, wifiOnly).apply()
        _autoDownloadWifiOnlyState.value = wifiOnly
    }

    fun getDownloadPath(): String {
        val raw = prefs.getString(KEY_DOWNLOAD_PATH, null)
        return normalizeDownloadPath(raw)
    }

    fun saveDownloadPath(path: String) {
        val normalized = normalizeDownloadPath(path)
        prefs.edit().putString(KEY_DOWNLOAD_PATH, normalized).apply()
        _downloadPathState.value = normalized
    }

    fun resetDownloadPath() {
        saveDownloadPath(DEFAULT_DOWNLOAD_PATH)
    }

    private fun clampQualityByVipTier(saved: QualityLevel): QualityLevel {
        val vipTier = if (isLoggedIn()) (getUserProfile()?.vipTier ?: VipTier.NONE) else VipTier.NONE
        if (saved.requiredVipTier.level > vipTier.level) {
            return when (vipTier) {
                VipTier.SVIP -> QualityLevel.JYMASTER
                VipTier.VIP -> QualityLevel.HIRES
                VipTier.NONE -> QualityLevel.EXHIGH
            }
        }
        return saved
    }

    fun getUserProfile(): UserProfile? {
        val jsonStr = prefs.getString(KEY_USER_PROFILE, null) ?: return null
        return try {
            val decoded = json.decodeFromString<UserProfile>(jsonStr)
            val hasVersion = jsonStr.contains("\"entitlementVersion\"")
            if (!hasVersion || decoded.entitlementVersion < UserProfile.CURRENT_ENTITLEMENT_VERSION) {
                decoded.copy(isSvip = false, entitlementVersion = 1)
            } else {
                decoded
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveUserProfile(profile: UserProfile) {
        try {
            val jsonStr = json.encodeToString(profile)
            prefs.edit().putString(KEY_USER_PROFILE, jsonStr).apply()
            _userProfileState.value = profile
        } catch (_: Exception) {}
    }

    fun getApiBaseUrl(): String {
        val saved = prefs.getString(KEY_API_BASE_URL, null)
        return if (saved.isNullOrBlank()) DEFAULT_API_BASE_URL else saved
    }

    fun saveApiBaseUrl(url: String) {
        val trimmed = url.trim().removeSuffix("/")
        val toSave = if (trimmed.isBlank() || trimmed == DEFAULT_API_BASE_URL) DEFAULT_API_BASE_URL else trimmed
        prefs.edit().putString(KEY_API_BASE_URL, toSave).apply()
        _apiBaseUrlState.value = toSave
    }

    fun getHomepageMode(): HomepageMode {
        val value = prefs.getString(KEY_HOMEPAGE_MODE, HomepageMode.APP_LIKE.value) ?: HomepageMode.APP_LIKE.value
        return HomepageMode.fromValue(value)
    }

    fun saveHomepageMode(mode: HomepageMode) {
        prefs.edit().putString(KEY_HOMEPAGE_MODE, mode.value).apply()
        _homepageModeState.value = mode
    }

    companion object {
        private const val PREF_NAME = "ncm_user_session"
        private const val KEY_COOKIE = "user_cookie"
        private const val KEY_QUALITY = "preferred_quality"
        private const val KEY_DOWNLOAD_QUALITY = "preferred_download_quality"
        private const val KEY_AUTO_DOWNLOAD_ON_FAVORITE = "auto_download_on_favorite"
        private const val KEY_AUTO_DOWNLOAD_WIFI_ONLY = "auto_download_wifi_only"
        private const val KEY_DOWNLOAD_PATH = "download_directory_path"
        private const val KEY_USER_PROFILE = "user_profile"
        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_HOMEPAGE_MODE = "homepage_mode"
        const val DEFAULT_API_BASE_URL = "https://interface.music.163.com"
        const val DEFAULT_DOWNLOAD_PATH = "/storage/emulated/0/Download/netease/cloudmusic/Music/"

        fun normalizeDownloadPath(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return DEFAULT_DOWNLOAD_PATH
            if (trimmed.startsWith("content://", ignoreCase = true)) {
                return trimmed
            }
            val normalizedSlashes = trimmed.replace('\\', '/')
            return if (normalizedSlashes.endsWith('/')) normalizedSlashes else "$normalizedSlashes/"
        }

        fun formatDownloadPathForDisplay(rawPath: String): String {
            val normalized = normalizeDownloadPath(rawPath)
            if (!normalized.startsWith("content://", ignoreCase = true)) {
                return normalized
            }
            val decoded = runCatching { android.net.Uri.decode(normalized) }.getOrDefault(normalized)
            val treePart = decoded.substringAfter("/tree/", "")
            if (treePart.startsWith("primary:")) {
                val rel = treePart.removePrefix("primary:").trim('/')
                return if (rel.isEmpty()) "/storage/emulated/0/" else "/storage/emulated/0/$rel/"
            }
            if (treePart.contains(':')) {
                val vol = treePart.substringBefore(':')
                val rel = treePart.substringAfter(':').trim('/')
                return if (rel.isEmpty()) "/storage/$vol/" else "/storage/$vol/$rel/"
            }
            return normalized
        }

        /**
         * 净化 Cookie 字符串：只保留合法的名值对 (key=value)，彻底过滤 Path, Max-Age, Expires 等服务端指令
         */
        fun cleanCookieString(rawCookie: String): String {
            if (rawCookie.isBlank()) return ""
            val ignoredAttributes = setOf(
                "expires", "max-age", "domain", "path", "samesite", "httponly", "secure", "priority"
            )
            val validPairs = mutableMapOf<String, String>()
            val segments = rawCookie.split(Regex("[;\\r\\n]+"))
            for (segment in segments) {
                val trimmed = segment.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    if (key.isNotEmpty() && !ignoredAttributes.contains(key.lowercase())) {
                        validPairs[key] = value
                    }
                }
            }
            return validPairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }
}
