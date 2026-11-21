package Akari.NCM.player.ui

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.data.LocalFolderManager
import Akari.NCM.player.data.PlaylistManager
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.ui.screen.NeteaseWebLoginHelper
import Akari.NCM.player.ui.screen.SettingsScreen
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewLoginTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var sessionManager: UserSessionManager
    private lateinit var playlistManager: PlaylistManager
    private lateinit var ncmApi: NcmApi
    private lateinit var localFolderManager: LocalFolderManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ame_theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ncm_user_session", Context.MODE_PRIVATE).edit().clear().commit()
        AppLogger.init(context)
        ThemePrefs.init(context)

        sessionManager = UserSessionManager(context)
        playlistManager = PlaylistManager(context)
        ncmApi = NcmApi(sessionManager)
        localFolderManager = LocalFolderManager(context, sessionManager, playlistManager, ncmApi)
    }

    @Test
    fun extractAuthenticatedCookie_rejectsAnonymousOrEmptyMusicU() {
        assertNull(NeteaseWebLoginHelper.extractAuthenticatedCookie(null, ""))
        assertNull(NeteaseWebLoginHelper.extractAuthenticatedCookie("NMTID=abc123; _ntes_nuid=xyz; MUSIC_A=guest_token"))
        assertNull(NeteaseWebLoginHelper.extractAuthenticatedCookie("MUSIC_U=; __csrf=12345"))
        assertNull(NeteaseWebLoginHelper.extractAuthenticatedCookie("MUSIC_U=deleted; __csrf=12345"))
        assertNull(NeteaseWebLoginHelper.extractAuthenticatedCookie("MUSIC_U=null; NMTID=abc"))
    }

    @Test
    fun extractAuthenticatedCookie_mergesDomainsAndStripsAttributes() {
        val extracted = NeteaseWebLoginHelper.extractAuthenticatedCookie(
            "NMTID=old_nmtid; Path=/; HttpOnly",
            "MUSIC_U=secret_token_value; __csrf=csrf_abc; Max-Age=86400; Domain=.music.163.com",
            "NMTID=new_nmtid"
        )
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("MUSIC_U=secret_token_value"))
        assertTrue(extracted.contains("__csrf=csrf_abc"))
        assertTrue(extracted.contains("NMTID=new_nmtid"))
        assertFalse(extracted.contains("Path="))
        assertFalse(extracted.contains("HttpOnly"))
        assertFalse(extracted.contains("Max-Age="))
    }

    @Test
    fun isAllowedNavigationScheme_blocksAppSchemesAndAllowsHttpHttps() {
        assertTrue(NeteaseWebLoginHelper.isAllowedNavigationScheme("https://y.music.163.com/m/login"))
        assertTrue(NeteaseWebLoginHelper.isAllowedNavigationScheme("http://music.163.com/login"))
        assertFalse(NeteaseWebLoginHelper.isAllowedNavigationScheme("orpheus://login"))
        assertFalse(NeteaseWebLoginHelper.isAllowedNavigationScheme("intent://music.163.com/#Intent;end"))
        assertFalse(NeteaseWebLoginHelper.isAllowedNavigationScheme("market://details?id=com.netease.cloudmusic"))
        assertFalse(NeteaseWebLoginHelper.isAllowedNavigationScheme("javascript:void(0)"))
        assertFalse(NeteaseWebLoginHelper.isAllowedNavigationScheme(null))
        assertFalse(NeteaseWebLoginHelper.isAllowedNavigationScheme("   "))
    }

    @Test
    fun sanitizeUserAgent_removesWebViewToken() {
        val raw = "Mozilla/5.0 (Linux; Android 15; Pixel 9; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36"
        val sanitized = NeteaseWebLoginHelper.sanitizeUserAgent(raw)
        assertFalse(sanitized.contains("; wv"))
        assertTrue(sanitized.contains("Chrome/130.0.0.0"))
        assertEquals(
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36",
            sanitized
        )
    }

    @Test
    fun maskCookieForLog_hidesSensitiveTokenValues() {
        val masked = NeteaseWebLoginHelper.maskCookieForLog("MUSIC_U=super_secret_token_123; __csrf=csrf_456")
        assertFalse(masked.contains("super_secret_token_123"))
        assertFalse(masked.contains("csrf_456"))
        assertTrue(masked.contains("MUSIC_U"))
        assertTrue(masked.contains("__csrf"))
    }

    @Test
    @Config(qualifiers = "w400dp-h2400dp")
    fun settingsScreen_loggedOut_displaysWebLoginButtonAndInvokesCallback() {
        var webLoginOpened = false
        composeTestRule.setContent {
            SettingsScreen(
                sessionManager = sessionManager,
                ncmApi = ncmApi,
                localFolderManager = localFolderManager,
                onOpenWebLogin = { webLoginOpened = true },
                onBack = {}
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("网页登录").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_web_login").performClick()
        composeTestRule.waitForIdle()

        assertTrue(webLoginOpened)
    }
}
