package Akari.NCM.player.ui

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.data.UsbAudioManager
import Akari.NCM.player.player.AmePlayerEngine
import Akari.NCM.player.ui.screen.MainScreen
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
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
class AdaptiveNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var sessionManager: UserSessionManager
    private lateinit var ncmApi: NcmApi
    private lateinit var usbAudioManager: UsbAudioManager
    private lateinit var playerEngine: AmePlayerEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sessionManager = UserSessionManager(context)
        ncmApi = NcmApi(sessionManager)
        usbAudioManager = UsbAudioManager(context)
        playerEngine = AmePlayerEngine(context, ncmApi, usbAudioManager, sessionManager)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun testCompactScreen_rendersBottomNavigationBar() {
        composeTestRule.setContent {
            MainScreen(playerEngine = playerEngine)
        }

        // Check that navigation items exist and are displayed
        composeTestRule.onNodeWithText("首页").assertIsDisplayed()
        composeTestRule.onNodeWithText("歌单").assertIsDisplayed()

        // In Compact mode (NavigationBar at bottom), navigation items should be positioned near bottom of screen
        val homeBounds = composeTestRule.onNodeWithText("首页").getUnclippedBoundsInRoot()
        val playlistBounds = composeTestRule.onNodeWithText("歌单").getUnclippedBoundsInRoot()

        // Y position of items should be near the bottom (screen height = 800dp)
        assertTrue("In compact mode, Home tab top (${homeBounds.top}) should be near bottom (>650dp)", homeBounds.top > 650.dp)
        assertTrue("In compact mode, Playlist tab top (${playlistBounds.top}) should be near bottom (>650dp)", playlistBounds.top > 650.dp)

        // In bottom bar, items are laid out horizontally: Home is on the left of Playlist
        assertTrue("In compact bottom bar, Home (${homeBounds.left}) should be left of Playlist (${playlistBounds.left})", homeBounds.left < playlistBounds.left)
    }

    @Test
    @Config(qualifiers = "w700dp-h900dp")
    fun testMediumScreen_rendersNavigationRailOnLeft() {
        composeTestRule.setContent {
            MainScreen(playerEngine = playerEngine)
        }

        composeTestRule.onNodeWithText("首页").assertIsDisplayed()
        composeTestRule.onNodeWithText("歌单").assertIsDisplayed()

        val homeBounds = composeTestRule.onNodeWithText("首页").getUnclippedBoundsInRoot()
        val playlistBounds = composeTestRule.onNodeWithText("歌单").getUnclippedBoundsInRoot()

        // In NavigationRail (left side), items X position is at the start (<100dp)
        assertTrue("In NavigationRail, Home X (${homeBounds.left}) should be on the left (<100dp)", homeBounds.left < 100.dp)
        assertTrue("In NavigationRail, Playlist X (${playlistBounds.left}) should be on the left (<100dp)", playlistBounds.left < 100.dp)

        // In NavigationRail, items are laid out vertically: Home is above Playlist
        assertTrue("In NavigationRail, Home (${homeBounds.top}) should be above Playlist (${playlistBounds.top})", homeBounds.top < playlistBounds.top)
    }

    @Test
    @Config(qualifiers = "w1024dp-h768dp")
    fun testExpandedScreen_rendersNavigationRailOnLeft() {
        composeTestRule.setContent {
            MainScreen(playerEngine = playerEngine)
        }

        composeTestRule.onNodeWithText("首页").assertIsDisplayed()
        composeTestRule.onNodeWithText("歌单").assertIsDisplayed()

        val homeBounds = composeTestRule.onNodeWithText("首页").getUnclippedBoundsInRoot()
        val playlistBounds = composeTestRule.onNodeWithText("歌单").getUnclippedBoundsInRoot()

        // In Expanded NavigationRail, items X position is on the left
        assertTrue("In Expanded NavigationRail, Home X (${homeBounds.left}) should be on the left (<100dp)", homeBounds.left < 100.dp)
        assertTrue("In Expanded NavigationRail, Playlist X (${playlistBounds.left}) should be on the left (<100dp)", playlistBounds.left < 100.dp)

        // In NavigationRail, items are vertically stacked
        assertTrue("In Expanded NavigationRail, Home (${homeBounds.top}) should be above Playlist (${playlistBounds.top})", homeBounds.top < playlistBounds.top)
    }

    @Test
    @Config(qualifiers = "w700dp-h900dp")
    fun testNavigationTabSwitching() {
        composeTestRule.setContent {
            MainScreen(playerEngine = playerEngine)
        }

        // Initially Home tab is selected, clicking Playlist tab switches to Playlist view
        composeTestRule.onNodeWithText("歌单").performClick()
        composeTestRule.waitForIdle()

        // After clicking Playlist, the view should show Playlist-specific UI (e.g., "尚未创建任何歌单" or "新建本地")
        composeTestRule.onNodeWithContentDescription("新建本地").assertIsDisplayed()

        // Click Home tab to switch back
        composeTestRule.onNodeWithText("首页").performClick()
        composeTestRule.waitForIdle()

        // Should return to homepage
        composeTestRule.onNodeWithText("首页").assertIsDisplayed()
    }
}
