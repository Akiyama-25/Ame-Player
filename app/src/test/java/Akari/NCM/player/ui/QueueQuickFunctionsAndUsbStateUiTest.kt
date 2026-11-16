package Akari.NCM.player.ui

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.AudioFormatInfo
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.PlaybackState
import Akari.NCM.player.core.PlayerController
import Akari.NCM.player.core.PlayerState
import Akari.NCM.player.core.PlaylistSourceType
import Akari.NCM.player.core.RepeatMode
import Akari.NCM.player.core.UsbDeviceDetails
import Akari.NCM.player.core.loudness.LoudnessNormalizationState
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.data.UsbAudioManager
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.player.AmePlayerEngine
import Akari.NCM.player.ui.screen.MainScreen
import Akari.NCM.player.ui.screen.PlayerScreen
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private class FakePlayerStateController(
    track: PlayableMedia,
    usbExclusiveMode: Boolean = false,
    usbDeviceName: String? = null,
    uacVersion: String? = "未连接",
    usbDetails: UsbDeviceDetails? = null
) : PlayerState, PlayerController {
    override val currentMedia = MutableStateFlow<PlayableMedia?>(track)
    override val isPlaying = MutableStateFlow(false)
    override val playbackState = MutableStateFlow(PlaybackState.READY)
    override val currentPositionMs = MutableStateFlow(12_000L)
    override val durationMs = MutableStateFlow(180_000L)
    override val volume = MutableStateFlow(1f)
    override val repeatMode = MutableStateFlow(RepeatMode.OFF)
    override val shuffleEnabled = MutableStateFlow(false)
    override val playlist = MutableStateFlow(listOf(track))
    override val currentIndex = MutableStateFlow(0)
    override val error = MutableStateFlow<String?>(null)
    override val audioFormatInfo = MutableStateFlow(AudioFormatInfo())
    override val isUsbExclusiveMode = MutableStateFlow(usbExclusiveMode)
    override val connectedUsbDeviceName = MutableStateFlow(usbDeviceName)
    override val usbUacVersion = MutableStateFlow(uacVersion)
    override val usbDeviceDetails = MutableStateFlow(usbDetails)
    override val loudnessState = MutableStateFlow(LoudnessNormalizationState())

    var requestUsbPermissionCalled = false
    var lastExclusiveToggle: Boolean? = null

    override fun load(media: PlayableMedia) {}
    override fun loadPlaylist(items: List<PlayableMedia>, startIndex: Int, sourceType: PlaylistSourceType) {}
    override fun play() {}
    override fun pause() {}
    override fun togglePlayPause() {}
    override fun seekTo(positionMs: Long) {}
    override fun skipToNext() {}
    override fun skipToPrevious() {}
    override fun skipToIndex(index: Int) {}
    override fun setRepeatMode(mode: RepeatMode) {}
    override fun toggleShuffle() {}
    override fun setVolume(volume: Float) {}
    override fun setUsbExclusiveMode(enabled: Boolean) {
        lastExclusiveToggle = enabled
        isUsbExclusiveMode.value = enabled
    }

    override fun requestUsbPermission() {
        requestUsbPermissionCalled = true
    }

    override fun insertAsNext(media: PlayableMedia) {}
    override fun clearUrlCache() {}
    override fun release() {}
}

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueQuickFunctionsAndUsbStateUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var sessionManager: UserSessionManager
    private lateinit var ncmApi: NcmApi
    private var realEngine: AmePlayerEngine? = null

    private val sampleTrack = PlayableMedia.LocalNcm(
        id = "101",
        title = "Sample Song",
        artist = "Sample Artist",
        album = "Sample Album",
        uri = "file:///storage/emulated/0/Music/sample.flac",
        format = AudioFormat.FLAC,
        isNcm = false,
        durationMs = 180_000L
    )

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
        ncmApi = NcmApi(sessionManager)
    }

    @After
    fun tearDown() {
        runCatching { realEngine?.release() }
        realEngine = null
    }

    @Test
    @Config(qualifiers = "w400dp-h1200dp")
    fun testMainScreen_portraitPlayQueue_usesUnifiedSaltDrawerAndPullDownDismisses() {
        val usbAudioManager = UsbAudioManager(context)
        val engine = AmePlayerEngine(context, ncmApi, usbAudioManager, sessionManager)
        realEngine = engine

        composeTestRule.runOnUiThread {
            engine.loadPlaylist(listOf(sampleTrack), startIndex = 0)
        }

        composeTestRule.setContent {
            MainScreen(playerEngine = engine)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("mini_player_queue").performClick()
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("play_queue_sheet", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("play_queue_pull_handle").assertIsDisplayed()
        composeTestRule.onNodeWithText("下滑此处返回").assertIsDisplayed()
        composeTestRule.onNodeWithText("播放队列").assertIsDisplayed()

        // Pull down handle dismisses the unified play queue drawer
        composeTestRule.onNodeWithTag("play_queue_pull_handle").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 420f, durationMillis = 180)
        }
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.waitForIdle()

        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("play_queue_sheet", useUnmergedTree = true).fetchSemanticsNodes().size
        )
    }

    @Test
    @Config(qualifiers = "w400dp-h1200dp")
    fun testPlayerScreen_portraitQuickFunctionsDrawer_opensTogglesAndDismissesOnPullDown() {
        val fakePlayer = FakePlayerStateController(sampleTrack)

        composeTestRule.setContent {
            PlayerScreen(
                playerState = fakePlayer,
                playerController = fakePlayer,
                ncmApi = ncmApi,
                onBackClick = {},
                onAddToPlaylistClick = {},
                onToggleQueueClick = {}
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("btn_quick_functions").performClick()
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("quick_functions_sheet", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("quick_functions_pull_handle").assertIsDisplayed()
        composeTestRule.onNodeWithText("下滑此处返回播放界面").assertIsDisplayed()
        composeTestRule.onNodeWithText("快捷功能与外观").assertIsDisplayed()

        val initialBlurBg = ThemePrefs.playerBlurCoverBackground
        composeTestRule.onNodeWithTag("quick_toggle_blur_cover_bg").performClick()
        composeTestRule.waitForIdle()
        assertNotEquals(initialBlurBg, ThemePrefs.playerBlurCoverBackground)

        // Pull down from top handle dismisses quick functions drawer
        composeTestRule.onNodeWithTag("quick_functions_pull_handle").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 420f, durationMillis = 180)
        }
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.waitForIdle()

        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("quick_functions_sheet", useUnmergedTree = true).fetchSemanticsNodes().size
        )
    }

    @Test
    @Config(qualifiers = "w400dp-h1200dp")
    fun testPlayerScreen_usbExclusivePendingPermission_displaysIntermediateNoticeAndRequestsPermission() {
        val fakePlayer = FakePlayerStateController(
            track = sampleTrack,
            usbExclusiveMode = true,
            usbDeviceName = "MOONDROP Dawn Pro",
            uacVersion = "UAC 2.0",
            usbDetails = UsbDeviceDetails(
                deviceName = "/dev/bus/usb/001/002",
                productName = "MOONDROP Dawn Pro",
                vendorId = 0x1234,
                productId = 0x5678,
                uacVersion = "UAC 2.0",
                isExclusiveClaimed = false,
                hasPermission = false
            )
        )

        composeTestRule.setContent {
            PlayerScreen(
                playerState = fakePlayer,
                playerController = fakePlayer,
                ncmApi = ncmApi,
                onBackClick = {},
                onAddToPlaylistClick = {},
                onToggleQueueClick = {}
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("btn_quick_functions").performClick()
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.waitForIdle()

        // Must show intermediate "待授权" badge and subtitle, NOT "已独占绑定"
        composeTestRule.onNodeWithTag("badge_usb_pending_permission", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("待授权: 请确认 USB 权限以启用独占 (MOONDROP Dawn Pro (UAC 2.0))").assertIsDisplayed()
        assertEquals(
            0,
            composeTestRule.onAllNodesWithText("已独占绑定: MOONDROP Dawn Pro (UAC 2.0)").fetchSemanticsNodes().size
        )

        // Open USB Exclusive configuration dialog and verify pending permission banner & action button
        composeTestRule.onNodeWithTag("quick_card_usb_exclusive").performClick()
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("usb_pending_permission_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("待授权 · 尚未获得 USB 访问权限，独占尚未生效").assertIsDisplayed()
        composeTestRule.onNodeWithText("独占状态: 🟠 待授权 · 等待授予 USB 硬件访问权限（尚未启用独占）").assertIsDisplayed()
        assertEquals(
            0,
            composeTestRule.onAllNodesWithText("独占状态: 🟢 硬件独占直通生效中 (Bit-Perfect Direct)").fetchSemanticsNodes().size
        )

        composeTestRule.onNodeWithTag("btn_request_usb_permission").performClick()
        composeTestRule.waitForIdle()
        assertTrue(fakePlayer.requestUsbPermissionCalled)
    }

    @Test
    @Config(qualifiers = "w400dp-h1200dp")
    fun testPlayerScreen_usbExclusiveAuthorized_showsExclusiveActiveStatus() {
        val fakePlayer = FakePlayerStateController(
            track = sampleTrack,
            usbExclusiveMode = true,
            usbDeviceName = "MOONDROP Dawn Pro",
            uacVersion = "UAC 2.0",
            usbDetails = UsbDeviceDetails(
                deviceName = "/dev/bus/usb/001/002",
                productName = "MOONDROP Dawn Pro",
                vendorId = 0x1234,
                productId = 0x5678,
                uacVersion = "UAC 2.0",
                isExclusiveClaimed = true,
                hasPermission = true
            )
        )

        composeTestRule.setContent {
            PlayerScreen(
                playerState = fakePlayer,
                playerController = fakePlayer,
                ncmApi = ncmApi,
                onBackClick = {},
                onAddToPlaylistClick = {},
                onToggleQueueClick = {}
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("btn_quick_functions").performClick()
        composeTestRule.mainClock.advanceTimeBy(600)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("已独占绑定: MOONDROP Dawn Pro (UAC 2.0)").assertIsDisplayed()
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("badge_usb_pending_permission", useUnmergedTree = true).fetchSemanticsNodes().size
        )
    }
}
