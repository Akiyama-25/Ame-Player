package Akari.NCM.player.ui

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.data.PlaylistManager
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.data.UsbAudioManager
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.player.AmePlayerEngine
import Akari.NCM.player.ui.component.PredictiveBackEasing
import Akari.NCM.player.ui.component.PredictiveBackState
import Akari.NCM.player.ui.screen.MainScreen
import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
class PredictiveBackTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var sessionManager: UserSessionManager
    private lateinit var playlistManager: PlaylistManager
    private lateinit var ncmApi: NcmApi
    private lateinit var usbAudioManager: UsbAudioManager
    private lateinit var playerEngine: AmePlayerEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ame_theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "custom_playlists.json").delete()
        ThemePrefs.init(context)

        sessionManager = UserSessionManager(context)
        playlistManager = PlaylistManager(context)
        ncmApi = NcmApi(sessionManager)
        usbAudioManager = UsbAudioManager(context)
        playerEngine = AmePlayerEngine(context, ncmApi, usbAudioManager, sessionManager)
    }

    @Test
    fun testPredictiveBackState_progressAndSpringBackLifecycle() = runBlocking {
        val state = PredictiveBackState()
        assertFalse(state.isActive)
        assertFalse(state.isVisibleInBackground)
        assertEquals(0f, state.progress, 0.001f)

        state.onGestureStarted()
        assertTrue(state.isActive)
        assertTrue(state.isVisibleInBackground)

        state.onGestureProgress(
            rawProgress = 0.45f,
            edge = BackEventCompat.EDGE_RIGHT,
            x = 980f,
            y = 640f
        )
        assertEquals(0.45f, state.progress, 0.001f)
        assertEquals(BackEventCompat.EDGE_RIGHT, state.swipeEdge)
        assertEquals(980f, state.touchX, 0.01f)
        assertEquals(640f, state.touchY, 0.01f)
        assertTrue(PredictiveBackEasing.transform(state.progress) > 0.45f)

        state.animateCancelSpringBack()
        assertFalse(state.isActive)
        assertFalse(state.isVisibleInBackground)
        assertEquals(0f, state.progress, 0.001f)
    }

    @Test
    @Config(qualifiers = "w400dp-h1200dp")
    fun testPredictiveBack_settingsAndPlaylistDetailAndRootHierarchy() {
        val sampleSong = PlayableMedia.LocalNcm(
            id = "9001",
            title = "Predictive Back Track",
            artist = "Test Artist",
            album = "Test Album",
            uri = "file:///storage/emulated/0/Music/Track.flac",
            format = AudioFormat.FLAC,
            isNcm = false
        )
        playlistManager.saveOrUpdateSyncedPlaylist(
            ncmPlaylistId = 7777L,
            name = "预见性测试歌单",
            coverImgUrl = null,
            songs = listOf(sampleSong)
        )

        composeTestRule.setContent {
            MainScreen(playerEngine = playerEngine)
        }
        composeTestRule.waitForIdle()

        val dispatcher = composeTestRule.activity.onBackPressedDispatcher

        // 1. At root MainScreen, no in-app PredictiveBackHandler is enabled, allowing AOSP Back-to-Home
        assertFalse(
            "Root MainScreen should not intercept back so AOSP Back-to-Home preview works",
            dispatcher.hasEnabledCallbacks()
        )

        // 2. Open SettingsScreen -> PredictiveBackHandler becomes enabled
        composeTestRule.onNodeWithContentDescription("设置").performClick()
        composeTestRule.waitForIdle()
        assertTrue("SettingsScreen should enable PredictiveBackHandler", dispatcher.hasEnabledCallbacks())

        // Dispatch predictive back progress -> cancel -> verify SettingsScreen stays open
        composeTestRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 410f, 0.5f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackCancelled()
        }
        composeTestRule.waitForIdle()
        assertTrue("SettingsScreen should remain open after cancelled predictive back", dispatcher.hasEnabledCallbacks())
        composeTestRule.onNodeWithContentDescription("返回").assertIsDisplayed()

        // Dispatch predictive back progress -> commit -> verify return to MainScreen
        composeTestRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(10f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(180f, 420f, 0.75f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        assertFalse("After returning to MainScreen, no in-app back handler should be enabled", dispatcher.hasEnabledCallbacks())

        // 3. Navigate to Playlists tab and open playlist detail
        composeTestRule.onNodeWithText("歌单", substring = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("预见性测试歌单").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Predictive Back Track").assertIsDisplayed()
        assertTrue("Playlist detail should enable PredictiveBackHandler", dispatcher.hasEnabledCallbacks())

        // Commit predictive back from playlist detail -> returns to playlists list
        composeTestRule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(390f, 500f, 0f, BackEventCompat.EDGE_RIGHT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(220f, 500f, 0.6f, BackEventCompat.EDGE_RIGHT))
            dispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("预见性测试歌单").assertIsDisplayed()
        assertFalse("Playlists list root should hand back control to AOSP Back-to-Home", dispatcher.hasEnabledCallbacks())
    }
}
