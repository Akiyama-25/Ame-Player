package Akari.NCM.player.data

import Akari.NCM.player.core.HomepageMode
import Akari.NCM.player.core.QualityLevel
import Akari.NCM.player.core.UserProfile
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
    private val json = Json { ignoreUnknownKeys = true }

    private val _cookieState = MutableStateFlow(getCookie())
    val cookieState: StateFlow<String> = _cookieState.asStateFlow()

    private val _qualityState = MutableStateFlow(getQualityLevel())
    val qualityState: StateFlow<QualityLevel> = _qualityState.asStateFlow()

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

    fun saveQualityLevel(level: QualityLevel) {
        prefs.edit().putString(KEY_QUALITY, level.value).apply()
        _qualityState.value = level
    }

    fun getUserProfile(): UserProfile? {
        val jsonStr = prefs.getString(KEY_USER_PROFILE, null) ?: return null
        return try {
            json.decodeFromString<UserProfile>(jsonStr)
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
        return prefs.getString(KEY_API_BASE_URL, DEFAULT_API_BASE_URL) ?: DEFAULT_API_BASE_URL
    }

    fun saveApiBaseUrl(url: String) {
        val formatted = url.trim().removeSuffix("/")
        prefs.edit().putString(KEY_API_BASE_URL, formatted).apply()
        _apiBaseUrlState.value = formatted
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
        private const val KEY_USER_PROFILE = "user_profile"
        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_HOMEPAGE_MODE = "homepage_mode"
        const val DEFAULT_API_BASE_URL = ""

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
