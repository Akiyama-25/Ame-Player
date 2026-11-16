package Akari.NCM.player.ui

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.data.UsbAudioManager
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.player.AmePlayerEngine
import Akari.NCM.player.ui.screen.PlayerScreen
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerQueuePullDownTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var sessionManager: UserSessionManager
    private lateinit var ncmApi: NcmApi
    private lateinit var usbAudioManager: UsbAudioManager
    private lateinit var playerEngine: AmePlayerEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ame_theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        AppLogger.init(context)
        ThemePrefs.init(context)

        sessionManager = UserSessionManager(context)
        ncmApi = NcmApi(sessionManager)
        usbAudioManager = UsbAudioManager(context)
        playerEngine = AmePlayerEngine(context, ncmApi, usbAudioManager, sessionManager)
    }

    @After
    fun tearDown() {
        playerEngine.release()
    }

    @Test
    fun testExoPlayerDoesNotPauseAtEndOfMediaItems() {
        assertFalse(
            "pauseAtEndOfMediaItems must be false so ExoPlayer advances naturally to next track",
            playerEngine.player.pauseAtEndOfMediaItems
        )
    }

    @Test
    @Config(qualifiers = "w400dp-h1200dp")
    fun testPortraitPlayQueue_pullDownFromTopDismissesQueue() {
        val sampleTracks = listOf(
            PlayableMedia.LocalNcm(
                id = "101",
                title = "Track One",
                artist = "Artist A",
                album = "Album A",
                uri = "file:///storage/emulated/0/Music/one.flac",
                format = AudioFormat.FLAC,
                isNcm = false
            ),
            PlayableMedia.LocalNcm(
                id = "102",
                title = "Track Two",
                artist = "Artist B",
                album = "Album B",
                uri = "file:///storage/emulated/0/Music/two.flac",
                format = AudioFormat.FLAC,
                isNcm = false
            )
        )
        composeTestRule.runOnUiThread {
            playerEngine.loadPlaylist(sampleTracks, startIndex = 0)
        }

        composeTestRule.setContent {
            PlayerScreen(
                playerState = playerEngine,
                playerController = playerEngine,
                ncmApi = ncmApi,
                onBackClick = {},
                onAddToPlaylistClick = {},
                onToggleQueueClick = {}
            )
        }
        composeTestRule.waitForIdle()

        // Open playlist queue sheet
        composeTestRule.onNodeWithTag("btn_open_queue").performClick()
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("play_queue_sheet", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("play_queue_pull_handle").assertIsDisplayed()

        // Short slow pull down does not dismiss; queue springs back
        composeTestRule.onNodeWithTag("play_queue_pull_handle").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 35f, durationMillis = 300)
        }
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("play_queue_sheet", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("play_queue_pull_handle").assertIsDisplayed()

        // Full pull down from top handle dismisses the play queue
        composeTestRule.onNodeWithTag("play_queue_pull_handle").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 420f, durationMillis = 180)
        }
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.waitForIdle()

        assertEquals(
            "Play queue should be dismissed after pulling down from the top handle",
            0,
            composeTestRule.onAllNodesWithTag("play_queue_sheet", useUnmergedTree = true).fetchSemanticsNodes().size
        )
        assertEquals(
            "Play queue pull handle should be removed after dismissal",
            0,
            composeTestRule.onAllNodesWithTag("play_queue_pull_handle").fetchSemanticsNodes().size
        )
    }
}
