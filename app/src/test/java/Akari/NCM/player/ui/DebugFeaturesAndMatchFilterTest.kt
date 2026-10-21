package Akari.NCM.player.ui

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.LogLevel
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.data.LocalFolderManager
import Akari.NCM.player.data.PlaylistManager
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.data.UsbAudioManager
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.player.AmePlayerEngine
import Akari.NCM.player.ui.screen.MainScreen
import Akari.NCM.player.ui.screen.SettingsScreen
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugFeaturesAndMatchFilterTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var sessionManager: UserSessionManager
    private lateinit var playlistManager: PlaylistManager
    private lateinit var ncmApi: NcmApi
    private lateinit var localFolderManager: LocalFolderManager
    private lateinit var usbAudioManager: UsbAudioManager
    private lateinit var playerEngine: AmePlayerEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ame_theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("app_logger_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("local_folders", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "custom_playlists.json").delete()

        AppLogger.init(context)
        ThemePrefs.init(context)

        sessionManager = UserSessionManager(context)
        playlistManager = PlaylistManager(context)
        ncmApi = NcmApi(sessionManager)
        localFolderManager = LocalFolderManager(context, sessionManager, playlistManager, ncmApi)
        usbAudioManager = UsbAudioManager(context)
        playerEngine = AmePlayerEngine(context, ncmApi, usbAudioManager, sessionManager)
    }

    @Test
    @Config(qualifiers = "w400dp-h1200dp")
    fun testSettingsScreen_debugSection_rendersAllElements() {
        composeTestRule.setContent {
            SettingsScreen(
                sessionManager = sessionManager,
                ncmApi = ncmApi,
                localFolderManager = localFolderManager,
                onBack = {}
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("调试功能").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setting_log_record").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setting_show_match_status").assertIsDisplayed()
        composeTestRule.onNodeWithText("开启后在歌单详情页快速过滤已匹配/未匹配本地源的歌曲").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_export_log").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w400dp-h1200dp")
    fun testSettingsScreen_logLevelDialog_selectLevel() {
        composeTestRule.setContent {
            SettingsScreen(
                sessionManager = sessionManager,
                ncmApi = ncmApi,
                localFolderManager = localFolderManager,
                onBack = {}
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("setting_log_record").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("日志记录等级").assertIsDisplayed()
        composeTestRule.onNodeWithText("ERROR (默认)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("log_level_option_DEBUG").performClick()
        composeTestRule.waitForIdle()

        assertEquals(LogLevel.DEBUG, AppLogger.currentLevel.value)
    }

    @Test
    @Config(qualifiers = "w400dp-h1200dp")
    fun testSettingsScreen_exportMenu_displaysOptions() {
        composeTestRule.setContent {
            SettingsScreen(
                sessionManager = sessionManager,
                ncmApi = ncmApi,
                localFolderManager = localFolderManager,
                onBack = {}
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("btn_export_log").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("export_format_log").assertIsDisplayed()
        composeTestRule.onNodeWithTag("export_format_md").assertIsDisplayed()
        composeTestRule.onNodeWithTag("export_format_zip").assertIsDisplayed()

        // Dismiss menu
        composeTestRule.onNodeWithTag("export_format_log").performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w400dp-h1200dp")
    fun testSettingsScreen_matchStatusSwitch_togglesAndPersists() {
        composeTestRule.setContent {
            SettingsScreen(
                sessionManager = sessionManager,
                ncmApi = ncmApi,
                localFolderManager = localFolderManager,
                onBack = {}
            )
        }
        composeTestRule.waitForIdle()

        assertFalse(ThemePrefs.showMatchStatus)
        composeTestRule.onNodeWithTag("switch_show_match_status").performClick()
        composeTestRule.waitForIdle()
        assertTrue(ThemePrefs.showMatchStatus)

        // Verify persistence across restart
        ThemePrefs.init(context)
        assertTrue(ThemePrefs.showMatchStatus)
        assertTrue(ThemePrefs.showMatchStatusFlow.value)
    }

    @Test
    @Config(qualifiers = "w400dp-h1200dp")
    fun testSettingsScreen_logSizeDialog_saveMaxFileSize() {
        composeTestRule.setContent {
            SettingsScreen(
                sessionManager = sessionManager,
                ncmApi = ncmApi,
                localFolderManager = localFolderManager,
                onBack = {}
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("setting_log_record").performSemanticsAction(SemanticsActions.OnLongClick)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("自定义日志大小上限").assertIsDisplayed()
        composeTestRule.onNodeWithTag("input_log_max_size_kb").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_save_log_size").assertIsDisplayed()

        // Directly test setMaxFileSizeKb and persistence to avoid infinite cursor animation idling issue in Robolectric
        AppLogger.setMaxFileSizeKb(context, 256L)
        assertEquals(256L, AppLogger.maxFileSizeKb.value)

        AppLogger.init(context)
        assertEquals(256L, AppLogger.maxFileSizeKb.value)
    }

    @Test
    @Config(qualifiers = "w400dp-h1000dp")
    fun testMatchStatusSwitch_persistenceAndPlaylistDetailLinkage() {
        // Prepare local scanned playlist with 1 local song
        val localSong = PlayableMedia.LocalNcm(
            id = "10001",
            title = "Matched Song Title",
            artist = "Artist One",
            album = "Album One",
            uri = "file:///storage/emulated/0/Music/Matched.flac",
            format = AudioFormat.FLAC,
            isNcm = true
        )
        playlistManager.saveOrUpdateSyncedPlaylist(
            ncmPlaylistId = -9999L,
            name = "本地扫描文件夹",
            coverImgUrl = null,
            songs = listOf(localSong)
        )

        // Prepare cloud synced playlist with 1 matched song and 1 unmatched song
        val onlineMatched = PlayableMedia.Online(
            id = "10001",
            title = "Matched Song Title",
            artist = "Artist One",
            album = "Album One"
        )
        val onlineUnmatched = PlayableMedia.Online(
            id = "20002",
            title = "Unmatched Song Title",
            artist = "Artist Two",
            album = "Album Two"
        )
        playlistManager.saveOrUpdateSyncedPlaylist(
            ncmPlaylistId = 8888L,
            name = "测试混合歌单",
            coverImgUrl = null,
            songs = listOf(onlineMatched, onlineUnmatched)
        )

        // Default: showMatchStatus is false
        assertFalse(ThemePrefs.showMatchStatus)

        composeTestRule.setContent {
            MainScreen(playerEngine = playerEngine)
        }
        composeTestRule.waitForIdle()

        // Navigate to Playlists tab and open "测试混合歌单"
        composeTestRule.onNodeWithText("歌单", substring = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("测试混合歌单").performClick()
        composeTestRule.waitForIdle()

        // Both songs are displayed, but filter chips are hidden when switch is off
        composeTestRule.onNodeWithText("Matched Song Title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unmatched Song Title").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("全部 (2)").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("已匹配 (1)").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("未匹配 (1)").assertCountEquals(0)

        // Enable showMatchStatus and simulate process restart re-init
        ThemePrefs.showMatchStatus = true
        ThemePrefs.init(context)
        composeTestRule.waitForIdle()

        // Filter chips appear immediately in playlist detail view
        composeTestRule.onNodeWithText("全部 (2)").assertIsDisplayed()
        composeTestRule.onNodeWithText("已匹配 (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("未匹配 (1)").assertIsDisplayed()

        // Click "已匹配 (1)" -> only Matched Song Title is shown
        composeTestRule.onNodeWithText("已匹配 (1)").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Matched Song Title").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Unmatched Song Title").assertCountEquals(0)

        // Click "未匹配 (1)" -> only Unmatched Song Title is shown
        composeTestRule.onNodeWithText("未匹配 (1)").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Unmatched Song Title").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Matched Song Title").assertCountEquals(0)

        // Turning off showMatchStatus hides filter chips and restores full song list
        ThemePrefs.showMatchStatus = false
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Matched Song Title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Unmatched Song Title").assertIsDisplayed()
    }
}
