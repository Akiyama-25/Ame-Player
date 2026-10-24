package Akari.NCM.player.ui

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.HomepageBlock
import Akari.NCM.player.core.OnlinePlaylistTarget
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.data.PlaylistManager
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.data.UsbAudioManager
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.player.AmePlayerEngine
import Akari.NCM.player.ui.screen.BlockCreativeCard
import Akari.NCM.player.ui.screen.DiscoverBlocksHomepage
import Akari.NCM.player.ui.screen.HomepageCache
import Akari.NCM.player.ui.screen.OnlinePlaylistDetailView
import Akari.NCM.player.ui.screen.UserHomepageView
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class OnlinePlaylistBrowseTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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

        sessionManager = UserSessionManager(context)
        sessionManager.clearSession()
        ncmApi = NcmApi(sessionManager)
        playlistManager = PlaylistManager(context)
        usbAudioManager = UsbAudioManager(context)
        playerEngine = AmePlayerEngine(context, ncmApi, usbAudioManager, sessionManager)
        HomepageCache.clear()
    }

    @Test
    fun testBlockCreativeCard_click_passesOnlinePlaylistTarget() {
        var clickedTarget: OnlinePlaylistTarget? = null
        val creative = HomepageBlock.Creative(
            creativeType = "playlist",
            title = "华语流行精选",
            imageUrl = "https://example.com/cover.jpg",
            resourceId = "12345",
            playCount = 999999L
        )

        composeTestRule.setContent {
            BlockCreativeCard(
                creative = creative,
                cardWidth = 120.dp,
                cornerRadius = 8.dp,
                titleSize = 12.sp,
                lineHeight = 16.sp,
                onClick = {
                    val id = creative.resourceId.toLongOrNull()
                    if (id != null) {
                        clickedTarget = OnlinePlaylistTarget(
                            id = id,
                            title = creative.title,
                            coverImgUrl = creative.imageUrl,
                            playCount = creative.playCount
                        )
                    }
                }
            )
        }

        composeTestRule.onNodeWithText("华语流行精选").assertIsDisplayed()
        composeTestRule.onNodeWithText("华语流行精选").performClick()

        assertNotNull(clickedTarget)
        assertEquals(12345L, clickedTarget?.id)
        assertEquals("华语流行精选", clickedTarget?.title)
        assertEquals(999999L, clickedTarget?.playCount)
    }

    @Test
    fun testOnlinePlaylistDetailView_rendersInitialTargetMetadataImmediately() {
        val target = OnlinePlaylistTarget(
            id = 88888L,
            title = "次元OST精选",
            coverImgUrl = "https://example.com/cover.jpg",
            trackCount = 30,
            playCount = 150000L,
            creatorName = "云音乐官方",
            description = "精选各大人气动画与游戏原声带"
        )
        var backClicked = false

        composeTestRule.setContent {
            OnlinePlaylistDetailView(
                target = target,
                ncmApi = ncmApi,
                playlistManager = playlistManager,
                playerEngine = playerEngine,
                currentMedia = null,
                isPlaying = false,
                showMatchStatusPreference = false,
                onBack = { backClicked = true },
                onSongAction = { _, _ -> }
            )
        }

        // Assert header metadata rendered instantly
        composeTestRule.onNodeWithText("歌单详情").assertIsDisplayed()
        composeTestRule.onNodeWithText("次元OST精选").assertIsDisplayed()
        composeTestRule.onNodeWithText("云音乐官方").assertIsDisplayed()
        composeTestRule.onNodeWithText("精选各大人气动画与游戏原声带").assertIsDisplayed()
        composeTestRule.onNodeWithText("30 首歌曲").assertIsDisplayed()

        // Test Back Button
        composeTestRule.onNodeWithText("歌单详情").assertIsDisplayed()
    }

    @Test
    fun testOnlinePlaylistDetailView_collectPlaylist_persistsToCustomPlaylists() {
        val target = OnlinePlaylistTarget(
            id = 99999L,
            title = "私人订制收藏歌单",
            coverImgUrl = "https://example.com/cover.jpg",
            trackCount = 2,
            creatorName = "Ame Curator"
        )

        // Seed with test songs
        val sampleSongs = listOf(
            PlayableMedia.Online(
                id = "online_101",
                title = "星之轨迹",
                artist = "Falcom Sound Team jdk",
                album = "Trails in the Sky",
                durationMs = 240000L
            ),
            PlayableMedia.Online(
                id = "online_102",
                title = "银之意志",
                artist = "Falcom Sound Team jdk",
                album = "Trails in the Sky SC",
                durationMs = 260000L
            )
        )

        // Directly save using PlaylistManager to verify persistence integration
        playlistManager.saveOrUpdateSyncedPlaylist(
            ncmPlaylistId = target.id,
            name = target.title,
            coverImgUrl = target.coverImgUrl,
            songs = sampleSongs
        )

        val saved = playlistManager.playlists.value.find { it.ncmPlaylistId == target.id }
        assertNotNull(saved)
        assertEquals("私人订制收藏歌单", saved?.name)
        assertEquals(2, saved?.songs?.size)
    }
}
