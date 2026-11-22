package Akari.NCM.player.ui

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.AudioFormatInfo
import Akari.NCM.player.core.NcmArtistBrief
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.PlaybackState
import Akari.NCM.player.core.PlayerController
import Akari.NCM.player.core.PlayerState
import Akari.NCM.player.core.PlaylistSourceType
import Akari.NCM.player.core.QualityLevel
import Akari.NCM.player.core.RepeatMode
import Akari.NCM.player.core.UsbDeviceDetails
import Akari.NCM.player.core.UserProfile
import Akari.NCM.player.core.VipTier
import Akari.NCM.player.core.loudness.LoudnessNormalizationState
import Akari.NCM.player.data.PlaylistManager
import Akari.NCM.player.data.SearchHistoryManager
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.data.UsbAudioManager
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.player.AmePlayerEngine
import Akari.NCM.player.ui.screen.PlayerScreen
import Akari.NCM.player.ui.screen.SearchScreen
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
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

private class TestPlayerStateController(
    track: PlayableMedia,
    formatInfo: AudioFormatInfo = AudioFormatInfo()
) : PlayerState, PlayerController {
    override val currentMedia = MutableStateFlow<PlayableMedia?>(track)
    override val isPlaying = MutableStateFlow(false)
    override val playbackState = MutableStateFlow(PlaybackState.READY)
    override val currentPositionMs = MutableStateFlow(15_000L)
    override val durationMs = MutableStateFlow(210_000L)
    override val volume = MutableStateFlow(1f)
    override val repeatMode = MutableStateFlow(RepeatMode.OFF)
    override val shuffleEnabled = MutableStateFlow(false)
    override val playlist = MutableStateFlow(listOf(track))
    override val currentIndex = MutableStateFlow(0)
    override val error = MutableStateFlow<String?>(null)
    override val audioFormatInfo = MutableStateFlow(formatInfo)
    override val isUsbExclusiveMode = MutableStateFlow(false)
    override val connectedUsbDeviceName = MutableStateFlow<String?>(null)
    override val usbUacVersion = MutableStateFlow<String?>("未连接")
    override val usbDeviceDetails = MutableStateFlow<UsbDeviceDetails?>(null)
    override val loudnessState = MutableStateFlow(LoudnessNormalizationState())
    override val playlistSourceType = MutableStateFlow(PlaylistSourceType.ONLINE)

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
    override fun setUsbExclusiveMode(enabled: Boolean) {}
    override fun requestUsbPermission() {}
    override fun insertAsNext(media: PlayableMedia) {}
    override fun clearUrlCache() {}
    override fun release() {}
}

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QualityAndPlayerInteractionTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var sessionManager: UserSessionManager
    private lateinit var playlistManager: PlaylistManager
    private lateinit var searchHistoryManager: SearchHistoryManager
    private lateinit var ncmApi: NcmApi

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ame_theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ame_ncm_session", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ame_search_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("app_logger_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "custom_playlists.json").delete()

        AppLogger.init(context)
        ThemePrefs.init(context)
        sessionManager = UserSessionManager(context)
        sessionManager.clearSession()
        playlistManager = PlaylistManager(context)
        searchHistoryManager = SearchHistoryManager(context)
        ncmApi = NcmApi(sessionManager)
    }

    @Test
    fun testQualityLevel_displayLabelFor_mapsAllKnownAndFallbackValues() {
        assertEquals(QualityLevel.STANDARD.label, QualityLevel.displayLabelFor("standard"))
        assertEquals("较高音质 (192k)", QualityLevel.displayLabelFor("higher"))
        assertEquals(QualityLevel.EXHIGH.label, QualityLevel.displayLabelFor("exhigh"))
        assertEquals(QualityLevel.LOSSLESS.label, QualityLevel.displayLabelFor("lossless"))
        assertEquals(QualityLevel.HIRES.label, QualityLevel.displayLabelFor("HIRES"))
        assertEquals(QualityLevel.JYEFFECT.label, QualityLevel.displayLabelFor("jyeffect"))
        assertEquals(QualityLevel.JYMASTER.label, QualityLevel.displayLabelFor("jymaster"))
        assertEquals("沉浸环绕声", QualityLevel.displayLabelFor("sky"))
        assertEquals("杜比全景声", QualityLevel.displayLabelFor("dolby"))
        assertEquals("custom_raw", QualityLevel.displayLabelFor("custom_raw"))
        assertEquals(QualityLevel.EXHIGH.label, QualityLevel.displayLabelFor(null))
        assertEquals(QualityLevel.EXHIGH.label, QualityLevel.displayLabelFor("   "))
    }

    @Test
    fun testUserSessionManager_getEffectiveQualityLevel_clampsByVipTier() {
        sessionManager.saveQualityLevel(QualityLevel.JYMASTER)
        assertEquals(QualityLevel.EXHIGH, sessionManager.getEffectiveQualityLevel())

        sessionManager.saveCookie("MUSIC_U=test_token;")
        sessionManager.saveUserProfile(UserProfile(userId = 1L, nickname = "Free", vipType = 0))
        assertEquals(VipTier.NONE, sessionManager.getUserProfile()?.vipTier)
        assertEquals(QualityLevel.EXHIGH, sessionManager.getEffectiveQualityLevel())

        sessionManager.saveUserProfile(UserProfile(userId = 2L, nickname = "VipUser", vipType = 11, redVipLevel = 3))
        assertEquals(VipTier.VIP, sessionManager.getUserProfile()?.vipTier)
        assertEquals(QualityLevel.HIRES, sessionManager.getEffectiveQualityLevel())

        sessionManager.saveQualityLevel(QualityLevel.LOSSLESS)
        assertEquals(QualityLevel.LOSSLESS, sessionManager.getEffectiveQualityLevel())

        sessionManager.saveQualityLevel(QualityLevel.JYMASTER)
        sessionManager.saveUserProfile(UserProfile(userId = 3L, nickname = "SvipUser", vipType = 11, isSvip = true, redVipLevel = 7))
        assertEquals(VipTier.SVIP, sessionManager.getUserProfile()?.vipTier)
        assertEquals(QualityLevel.JYMASTER, sessionManager.getEffectiveQualityLevel())
    }

    @Test
    fun testSearchScreen_livePreviewTypingAndSubmitCoordination() {
        searchHistoryManager.addSearchQuery("历史歌曲")
        val usbAudioManager = UsbAudioManager(context)
        val engine = AmePlayerEngine(context, ncmApi, usbAudioManager, sessionManager)
        val inputStates = mutableListOf<Boolean>()

        try {
            composeTestRule.setContent {
                SearchScreen(
                    playerState = engine,
                    playerController = engine,
                    ncmApi = ncmApi,
                    playlistManager = playlistManager,
                    searchHistoryManager = searchHistoryManager,
                    onBack = {},
                    onSongActionClick = { _, _ -> },
                    onInputProgressChanged = { inputStates.add(it) }
                )
            }

            composeTestRule.onNodeWithText("历史歌曲").assertIsDisplayed()

            // Focus and type into search field -> enters live typing preview state
            val searchField = composeTestRule.onNodeWithText("搜索歌曲、专辑、歌手、歌词...")
            searchField.performClick()
            searchField.performTextInput("Lemon")
            composeTestRule.waitForIdle()

            // Exits search history into live preview state
            composeTestRule.onNodeWithText("历史歌曲").assertDoesNotExist()
            assertTrue(inputStates.lastOrNull() == true)

            // Submitting search clears input-in-progress state
            composeTestRule.onNodeWithText("Lemon").performImeAction()
            composeTestRule.waitForIdle()
            assertTrue(inputStates.lastOrNull() == false)
        } finally {
            engine.release()
        }
    }

    @Test
    fun testPlayerScreen_favoriteButton_togglesPlaylistFavorite() {
        val track = PlayableMedia.Online(
            id = "9001",
            title = "Test Track",
            artist = "Artist One",
            album = "Test Album",
            durationMs = 210_000L
        )
        val controller = TestPlayerStateController(track)
        controller.playlistSourceType.value = PlaylistSourceType.ONLINE

        composeTestRule.setContent {
            PlayerScreen(
                playerState = controller,
                playerController = controller,
                ncmApi = ncmApi,
                playlistManager = playlistManager,
                onBackClick = {},
                onAddToPlaylistClick = {},
                onToggleQueueClick = {}
            )
        }

        assertFalse(playlistManager.isFavorite("9001"))

        composeTestRule.onNodeWithTag("btn_favorite_song").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertTrue(playlistManager.isFavorite("9001"))
        assertTrue(playlistManager.playlists.value.isEmpty())

        val encFile = File(context.filesDir, "cloud_favorites_aes256.enc")
        assertTrue(encFile.exists())
        val rawBytes = encFile.readBytes()
        assertTrue(rawBytes.size > 16)
        assertEquals("ACF2", rawBytes.copyOfRange(0, 4).decodeToString())
        assertFalse(rawBytes.decodeToString().contains("9001"))

        val reloadedManager = PlaylistManager(context)
        assertTrue(reloadedManager.isFavorite("9001"))

        composeTestRule.onNodeWithTag("btn_favorite_song").performClick()
        composeTestRule.waitForIdle()
        assertFalse(playlistManager.isFavorite("9001"))

        controller.playlistSourceType.value = PlaylistSourceType.LOCAL
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("btn_favorite_song").assertDoesNotExist()
    }

    @Test
    fun testPlayerScreen_multiArtistSelectorBox_andSingleArtistDirectNavigation() {
        val multiArtistTrack = PlayableMedia.Online(
            id = "9002",
            title = "Collab Track",
            artist = "米津玄师 / DAOKO",
            album = "THANK YOU BLUE",
            albumId = 5001L,
            artistsList = listOf(
                NcmArtistBrief(id = 101L, name = "米津玄师"),
                NcmArtistBrief(id = 102L, name = "DAOKO")
            ),
            durationMs = 210_000L
        )
        val controller = TestPlayerStateController(multiArtistTrack)
        var navigatedArtist: NcmArtistBrief? = null

        composeTestRule.setContent {
            PlayerScreen(
                playerState = controller,
                playerController = controller,
                ncmApi = ncmApi,
                playlistManager = playlistManager,
                onBackClick = {},
                onAddToPlaylistClick = {},
                onToggleQueueClick = {},
                onNavigateToArtist = { navigatedArtist = it }
            )
        }

        composeTestRule.onNodeWithTag("player_artist_click").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("player_artist_selector_box").assertIsDisplayed()
        composeTestRule.onNodeWithText("选择歌手").assertIsDisplayed()
        composeTestRule.onNodeWithText("DAOKO").performClick()
        composeTestRule.waitForIdle()

        assertEquals(NcmArtistBrief(id = 102L, name = "DAOKO"), navigatedArtist)
        assertTrue(composeTestRule.onAllNodesWithTag("player_artist_selector_box").fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(qualifiers = "w400dp-h1600dp")
    fun testPlayerScreen_playbackStatus_displaysActualQualityLevel() {
        val cloudTrack = PlayableMedia.Online(
            id = "9003",
            title = "Hi-Res Track",
            artist = "Studio Artist",
            album = "Master Album",
            durationMs = 240_000L
        )
        val controller = TestPlayerStateController(
            track = cloudTrack,
            formatInfo = AudioFormatInfo(
                bitrateKbps = 2304,
                sampleRateHz = 96000,
                bitDepthBits = 24,
                mimeType = "audio/flac",
                fileSizeBytes = 42_000_000L,
                qualityLevel = "hires"
            )
        )

        composeTestRule.setContent {
            PlayerScreen(
                playerState = controller,
                playerController = controller,
                ncmApi = ncmApi,
                playlistManager = playlistManager,
                onBackClick = {},
                onAddToPlaylistClick = {},
                onToggleQueueClick = {}
            )
        }

        composeTestRule.onNodeWithTag("btn_quick_functions").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("播放状态").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("quick_submenu_playback_status", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("云端在线 (Cloud) · Hi-Res 高解析", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("实际音质层级", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Hi-Res 高解析 (hires)", useUnmergedTree = true).assertIsDisplayed()
    }
}
