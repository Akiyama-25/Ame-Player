package Akari.NCM.player.player

import Akari.NCM.player.MainActivity
import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.data.LocalFolderManager
import Akari.NCM.player.data.PlaylistManager
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.player.island.FlymeTickerLyricHelper
import Akari.NCM.player.player.island.IslandLiveNotificationManager
import Akari.NCM.player.ui.screen.SettingsScreen
import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.MediaMetadata
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IslandAndMetadataTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ame_theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        AppLogger.init(context)
        ThemePrefs.init(context)
    }

    @Test
    fun testMediaMetadataHelper_optimizesNeteaseUrlAndCompressesLocalArtwork() {
        // Generate a 1024x1024 bitmap and encode to PNG bytes
        val largeBitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        val rawBytes = ByteArrayOutputStream().use { out ->
            largeBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

        val metadata = MediaMetadataHelper.buildTrackMediaMetadata(
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            durationMs = 269000L,
            remoteArtworkUrl = "https://p1.music.126.net/abc/cover.jpg",
            localArtworkBytes = rawBytes
        )

        assertEquals("晴天", metadata.title.toString())
        assertEquals("周杰伦", metadata.artist.toString())
        assertEquals("叶惠美", metadata.albumTitle.toString())
        assertEquals(269000L, metadata.durationMs)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, metadata.mediaType)
        assertEquals(
            "https://p1.music.126.net/abc/cover.jpg?param=512y512",
            metadata.artworkUri.toString()
        )

        val compressedData = metadata.artworkData
        assertNotNull(compressedData)
        val decoded = BitmapFactory.decodeByteArray(compressedData, 0, compressedData!!.size)
        assertNotNull(decoded)
        assertTrue(decoded.width <= 512)
        assertTrue(decoded.height <= 512)
    }

    @Test
    fun testFlymeTickerLyricHelper_appliesAndClearsFlags() {
        val notification = Notification()

        FlymeTickerLyricHelper.applyTickerLyrics(
            notification = notification,
            currentLyric = "故事的小黄花 从出生那年就飘着",
            isLyricOnlyUpdate = false
        )
        assertEquals("故事的小黄花 从出生那年就飘着", notification.tickerText.toString())
        assertTrue((notification.flags and FlymeTickerLyricHelper.FLAG_ALWAYS_SHOW_TICKER) != 0)
        assertFalse((notification.flags and FlymeTickerLyricHelper.FLAG_ONLY_UPDATE_TICKER) != 0)

        FlymeTickerLyricHelper.applyTickerLyrics(
            notification = notification,
            currentLyric = "童年的荡秋千 随记忆一直晃到现在",
            isLyricOnlyUpdate = true
        )
        assertEquals("童年的荡秋千 随记忆一直晃到现在", notification.tickerText.toString())
        assertTrue((notification.flags and FlymeTickerLyricHelper.FLAG_ALWAYS_SHOW_TICKER) != 0)
        assertTrue((notification.flags and FlymeTickerLyricHelper.FLAG_ONLY_UPDATE_TICKER) != 0)

        FlymeTickerLyricHelper.applyTickerLyrics(
            notification = notification,
            currentLyric = "",
            isLyricOnlyUpdate = true
        )
        assertNull(notification.tickerText)
        assertFalse((notification.flags and FlymeTickerLyricHelper.FLAG_ALWAYS_SHOW_TICKER) != 0)
        assertFalse((notification.flags and FlymeTickerLyricHelper.FLAG_ONLY_UPDATE_TICKER) != 0)
    }

    @Test
    fun testIslandLiveNotificationManager_channelAndLifecycle() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val manager = IslandLiveNotificationManager(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = nm.getNotificationChannel(IslandLiveNotificationManager.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        manager.postOrUpdateIsland(
            songTitle = "晴天",
            artist = "周杰伦",
            currentLyric = "刮风这天 我试过握着你手",
            currentTranslation = "On this windy day",
            currentPositionMs = 60_000L,
            durationMs = 269_000L,
            coverBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888),
            contentIntent = pendingIntent
        )

        assertTrue(manager.isIslandActive)
        val activeNotifications = shadowOf(nm).allNotifications
        assertTrue(activeNotifications.isNotEmpty())

        val superxBundle = manager.buildVivoSuperxExtras(
            operation = 1,
            title = "刮风这天 我试过握着你手",
            content = "晴天 - 周杰伦",
            coverBitmap = null,
            clickIntent = pendingIntent
        )
        assertEquals(1, superxBundle.getInt("notification.superx.operation"))
        assertTrue(superxBundle.getBoolean("notification.superx.showNotify"))
        assertNotNull(superxBundle.getBundle("notification.superx.baseInfos"))

        manager.cancelIsland()
        assertFalse(manager.isIslandActive)
    }

    @Test
    @Config(qualifiers = "w400dp-h1600dp")
    fun testSettingsScreen_liveIslandSwitch_togglesAndPersists() {
        val sessionManager = UserSessionManager(context)
        val playlistManager = PlaylistManager(context)
        val ncmApi = NcmApi(sessionManager)
        val localFolderManager = LocalFolderManager(context, sessionManager, playlistManager, ncmApi)

        composeTestRule.setContent {
            SettingsScreen(
                sessionManager = sessionManager,
                ncmApi = ncmApi,
                localFolderManager = localFolderManager,
                onBack = {}
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("灵动岛与实时播放控制").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setting_live_island").assertIsDisplayed()
        assertTrue(ThemePrefs.liveIslandEnabled)

        composeTestRule.onNodeWithTag("switch_live_island").performClick()
        composeTestRule.waitForIdle()
        assertFalse(ThemePrefs.liveIslandEnabled)

        ThemePrefs.init(context)
        assertFalse(ThemePrefs.liveIslandEnabled)
        assertFalse(ThemePrefs.liveIslandEnabledFlow.value)
    }

    @Test
    fun testPlaybackModePersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ThemePrefs.init(context)

        // Default should be OFF and false
        assertEquals("OFF", ThemePrefs.playbackRepeatMode)
        assertFalse(ThemePrefs.playbackShuffleEnabled)

        // Update to single repeat
        ThemePrefs.playbackRepeatMode = "ONE"
        ThemePrefs.playbackShuffleEnabled = false
        assertEquals("ONE", ThemePrefs.playbackRepeatMode)
        assertFalse(ThemePrefs.playbackShuffleEnabled)

        // Re-init from context prefs
        ThemePrefs.init(context)
        assertEquals("ONE", ThemePrefs.playbackRepeatMode)
        assertFalse(ThemePrefs.playbackShuffleEnabled)

        // Update to shuffle
        ThemePrefs.playbackRepeatMode = "OFF"
        ThemePrefs.playbackShuffleEnabled = true
        ThemePrefs.init(context)
        assertEquals("OFF", ThemePrefs.playbackRepeatMode)
        assertTrue(ThemePrefs.playbackShuffleEnabled)
    }
}
