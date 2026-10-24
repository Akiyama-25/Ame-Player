package Akari.NCM.player.ui

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.data.PlaylistManager
import Akari.NCM.player.data.SearchHistoryManager
import Akari.NCM.player.data.UsbAudioManager
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.player.AmePlayerEngine
import Akari.NCM.player.ui.screen.SearchScreen
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
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
class SearchFeatureTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var sessionManager: UserSessionManager
    private lateinit var playlistManager: PlaylistManager
    private lateinit var searchHistoryManager: SearchHistoryManager
    private lateinit var ncmApi: NcmApi
    private lateinit var usbAudioManager: UsbAudioManager
    private lateinit var playerEngine: AmePlayerEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ame_search_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "custom_playlists.json").delete()

        sessionManager = UserSessionManager(context)
        sessionManager.clearSession()
        ncmApi = NcmApi(sessionManager)
        playlistManager = PlaylistManager(context)
        searchHistoryManager = SearchHistoryManager(context)
        usbAudioManager = UsbAudioManager(context)
        playerEngine = AmePlayerEngine(context, ncmApi, usbAudioManager, sessionManager)
    }

    @Test
    fun testSearchHistoryManager_add_remove_clear() {
        searchHistoryManager.addSearchQuery("周杰伦")
        searchHistoryManager.addSearchQuery("Falcom")
        searchHistoryManager.addSearchQuery("周杰伦") // Re-adding moves to top

        val history = searchHistoryManager.history.value
        assertEquals(2, history.size)
        assertEquals("周杰伦", history[0])
        assertEquals("Falcom", history[1])

        searchHistoryManager.removeSearchQuery("Falcom")
        assertEquals(1, searchHistoryManager.history.value.size)
        assertEquals("周杰伦", searchHistoryManager.history.value[0])

        searchHistoryManager.clearHistory()
        assertTrue(searchHistoryManager.history.value.isEmpty())
    }

    @Test
    fun testPlaylistManager_searchSongsInPlaylists() {
        // Create 2 playlists with sample tracks
        val p1 = playlistManager.createPlaylist("J-POP 精选")
        val p2 = playlistManager.createPlaylist("游戏原声带")

        val song1 = PlayableMedia.Online(
            id = "1001",
            title = "Lemon",
            artist = "米津玄师",
            album = "BOOTLEG",
            durationMs = 250000L,
            transName = "柠檬"
        )
        val song2 = PlayableMedia.Online(
            id = "1002",
            title = "银之意志",
            artist = "Falcom Sound Team jdk",
            album = "Trails in the Sky",
            durationMs = 260000L,
            alias = "空之轨迹 SC 主题曲"
        )
        val song3 = PlayableMedia.Online(
            id = "1003",
            title = "打上花火",
            artist = "米津玄师 / DAOKO",
            album = "THANK YOU BLUE",
            durationMs = 290000L
        )

        playlistManager.addSongToPlaylist(p1.id, song1)
        playlistManager.addSongToPlaylist(p1.id, song3)
        playlistManager.addSongToPlaylist(p2.id, song2)

        // Search by artist
        val artistMatches = playlistManager.searchSongsInPlaylists("米津玄师")
        assertEquals(2, artistMatches.size)
        assertTrue(artistMatches.any { it.song.title == "Lemon" && it.playlistName == "J-POP 精选" })
        assertTrue(artistMatches.any { it.song.title == "打上花火" && it.playlistName == "J-POP 精选" })

        // Search by album
        val albumMatches = playlistManager.searchSongsInPlaylists("Trails")
        assertEquals(1, albumMatches.size)
        assertEquals("银之意志", albumMatches[0].song.title)
        assertEquals("游戏原声带", albumMatches[0].playlistName)

        // Search by transName & alias
        val transMatches = playlistManager.searchSongsInPlaylists("柠檬")
        assertEquals(1, transMatches.size)
        assertEquals("Lemon", transMatches[0].song.title)

        val aliasMatches = playlistManager.searchSongsInPlaylists("空之轨迹")
        assertEquals(1, aliasMatches.size)
        assertEquals("银之意志", aliasMatches[0].song.title)

        // Case-insensitive & partial search
        val partialMatches = playlistManager.searchSongsInPlaylists("lemon")
        assertEquals(1, partialMatches.size)
        assertEquals("Lemon", partialMatches[0].song.title)
    }

    @Test
    fun testPlayableMedia_displayTitle() {
        val s1 = PlayableMedia.Online(id = "1", title = "夜に駆ける", artist = "YOASOBI", album = "THE BOOK", durationMs = 1000L)
        assertEquals("夜に駆ける", s1.displayTitle)

        val s2 = PlayableMedia.Online(id = "2", title = "夜に駆ける", artist = "YOASOBI", album = "THE BOOK", durationMs = 1000L, transName = "向夜晚奔去")
        assertEquals("夜に駆ける (向夜晚奔去)", s2.displayTitle)

        val s3 = PlayableMedia.Online(id = "3", title = "夜に駆ける", artist = "YOASOBI", album = "THE BOOK", durationMs = 1000L, alias = "Racing into the Night")
        assertEquals("夜に駆ける (Racing into the Night)", s3.displayTitle)

        val s4 = PlayableMedia.Online(id = "4", title = "夜に駆ける", artist = "YOASOBI", album = "THE BOOK", durationMs = 1000L, transName = "向夜晚奔去", alias = "Racing into the Night")
        assertEquals("夜に駆ける (向夜晚奔去)", s4.displayTitle)
    }

    @Test
    fun testSearchScreen_rendersTabsAndHistory() {
        searchHistoryManager.addSearchQuery("Yoasobi")
        searchHistoryManager.addSearchQuery("Aimer")

        var backClicked = false

        composeTestRule.setContent {
            SearchScreen(
                playerState = playerEngine,
                playerController = playerEngine,
                ncmApi = ncmApi,
                playlistManager = playlistManager,
                searchHistoryManager = searchHistoryManager,
                initialQuery = "",
                onBack = { backClicked = true },
                onSongActionClick = { _, _ -> }
            )
        }

        // Assert top navigation and tabs (综合, 单曲, 专辑, 歌手, 歌词)
        composeTestRule.onNodeWithText("综合").assertIsDisplayed()
        composeTestRule.onNodeWithText("单曲").assertIsDisplayed()
        composeTestRule.onNodeWithText("专辑").assertIsDisplayed()
        composeTestRule.onNodeWithText("歌手").assertIsDisplayed()
        composeTestRule.onNodeWithText("歌词").assertIsDisplayed()

        // Assert search history chips
        composeTestRule.onNodeWithText("搜索历史").assertIsDisplayed()
        composeTestRule.onNodeWithText("Yoasobi").assertIsDisplayed()
        composeTestRule.onNodeWithText("Aimer").assertIsDisplayed()

        // Switch to "单曲" Tab
        composeTestRule.onNodeWithText("单曲").performClick()
        composeTestRule.onNodeWithText("单曲").assertIsSelected()
    }
}
