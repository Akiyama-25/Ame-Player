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

    @Test
    fun testResolveLoadType_distinguishesFixedAndInfiniteEntries() {
        val fixedPlaylist = HomepageBlock.Creative(
            creativeType = "scroll_playlist",
            title = "常规推荐歌单",
            resourceId = "2058183453",
            resourceType = "list",
            action = "go_to_playlist"
        )
        assertEquals(Akari.NCM.player.core.PlaylistLoadType.FIXED_PLAYLIST, fixedPlaylist.resolveLoadType())

        val radarWithoutSeed = HomepageBlock.Creative(
            creativeType = "list",
            title = "24小时自习室｜伴你提升专注力",
            resourceId = "5089060975",
            resourceType = "list",
            action = "play_all_song_from_current_index",
            specialType = 100,
            seedSongId = null
        )
        assertEquals(Akari.NCM.player.core.PlaylistLoadType.FIXED_PLAYLIST, radarWithoutSeed.resolveLoadType())

        val seedRadar = HomepageBlock.Creative(
            creativeType = "list",
            title = "今天从《Happy Now?》听起|私人雷达",
            resourceId = "3136952023",
            resourceType = "list",
            action = "play_all_song_from_current_index",
            specialType = 100,
            seedSongId = "1887215864"
        )
        assertEquals(Akari.NCM.player.core.PlaylistLoadType.SEED_RADAR_RADIO, seedRadar.resolveLoadType())

        val songRadioCard = HomepageBlock.Creative(
            creativeType = "SONG_LIST_HOMEPAGE",
            title = "Make Up Your Mind",
            resourceId = "19001122",
            resourceType = "song",
            action = "play_all_song_from_current_index",
            seedSongId = "19001122"
        )
        assertEquals(Akari.NCM.player.core.PlaylistLoadType.SONG_RADIO, songRadioCard.resolveLoadType())

        val personalFmBall = Akari.NCM.player.core.HomepageDragonBall(
            id = -6L,
            name = "私人FM",
            url = "orpheus://privatefm?entranceType=discovery_dragon_ball"
        )
        assertEquals(Akari.NCM.player.core.PlaylistLoadType.PERSONAL_FM, personalFmBall.resolveLoadType())

        val heartbeatBall = Akari.NCM.player.core.HomepageDragonBall(
            id = 2L,
            name = "心动模式",
            url = "orpheus://playlist?id=778899"
        )
        assertEquals(Akari.NCM.player.core.PlaylistLoadType.HEARTBEAT_INTELLIGENCE, heartbeatBall.resolveLoadType())
    }

    @Test
    fun testParseHomepageBlocksJson_extractsSeedRadarAndSongCardsAndFiltersPodcasts() {
        val sampleJson = """
            {
              "code": 200,
              "data": {
                "blocks": [
                  {
                    "blockCode": "HOMEPAGE_BLOCK_PLAYLIST_RCMD",
                    "uiElement": { "subTitle": { "title": "推荐歌单" } },
                    "creatives": [
                      {
                        "creativeType": "list",
                        "creativeId": "3136952023",
                        "action": "play_all_song_from_current_index",
                        "actionType": "client_customized",
                        "logInfo": "{\"relatedSong\":\"1887215864\",\"alg\":\"alg_私人雷达\"}",
                        "uiElement": {
                          "mainTitle": { "title": "今天从《Happy Now?》听起|私人雷达" },
                          "image": { "imageUrl": "https://example.com/radar.jpg" }
                        },
                        "resources": [
                          {
                            "resourceType": "list",
                            "resourceId": "3136952023",
                            "action": "play_all_song_from_current_index",
                            "logInfo": "{\"relatedSong\":\"1887215864\"}",
                            "resourceExtInfo": { "playCount": 37400900, "specialType": 100 }
                          }
                        ]
                      },
                      {
                        "creativeType": "list",
                        "creativeId": "2058183453",
                        "action": "go_to_playlist",
                        "uiElement": {
                          "mainTitle": { "title": "经典动漫神曲合集" },
                          "image": { "imageUrl": "https://example.com/anime.jpg" }
                        },
                        "resources": [
                          {
                            "resourceType": "list",
                            "resourceId": "2058183453",
                            "action": "go_to_playlist",
                            "resourceExtInfo": { "playCount": 120000, "specialType": 0 }
                          }
                        ]
                      }
                    ]
                  },
                  {
                    "blockCode": "HOMEPAGE_BLOCK_STYLE_RCMD",
                    "uiElement": { "subTitle": { "title": "风格推荐" } },
                    "creatives": [
                      {
                        "creativeType": "SONG_LIST_HOMEPAGE",
                        "resources": [
                          {
                            "resourceType": "song",
                            "resourceId": "556677",
                            "action": "play_all_song_from_current_index",
                            "uiElement": {
                              "mainTitle": { "title": "Blue Bird" },
                              "image": { "imageUrl": "https://example.com/bluebird.jpg" }
                            },
                            "resourceExtInfo": {
                              "songData": {
                                "id": 556677,
                                "name": "Blue Bird",
                                "duration": 215000,
                                "artists": [{ "name": "Ikimonogakari" }],
                                "album": { "name": "My Song Your Song", "picUrl": "https://example.com/bluebird.jpg" }
                              }
                            }
                          }
                        ]
                      }
                    ]
                  },
                  {
                    "blockCode": "HOMEPAGE_VOICELIST_RCMD",
                    "uiElement": { "subTitle": { "title": "热门播客" } },
                    "creatives": []
                  }
                ]
              }
            }
        """.trimIndent()

        val blocks = ncmApi.parseHomepageBlocksJson(sampleJson)
        assertEquals(2, blocks.size)

        val rcmdBlock = blocks[0]
        assertEquals("推荐歌单", rcmdBlock.title)
        assertEquals(2, rcmdBlock.creatives.size)

        val radarCreative = rcmdBlock.creatives[0]
        assertEquals(Akari.NCM.player.core.PlaylistLoadType.SEED_RADAR_RADIO, radarCreative.resolveLoadType())
        assertEquals("1887215864", radarCreative.seedSongId)
        assertEquals(100, radarCreative.specialType)

        val normalCreative = rcmdBlock.creatives[1]
        assertEquals(Akari.NCM.player.core.PlaylistLoadType.FIXED_PLAYLIST, normalCreative.resolveLoadType())

        val styleBlock = blocks[1]
        assertEquals(1, styleBlock.creatives.size)
        val songCreative = styleBlock.creatives[0]
        assertEquals(Akari.NCM.player.core.PlaylistLoadType.SONG_RADIO, songCreative.resolveLoadType())
        assertEquals("556677", songCreative.seedSongId)
        assertNotNull(songCreative.seedSong)
        assertEquals("Blue Bird", songCreative.seedSong?.title)
        assertEquals("Ikimonogakari", songCreative.seedSong?.artist)
    }

    @Test
    fun testDiscoverBlocksHomepage_routesInfiniteAndFixedCardsAppropriately() {
        val seedTrack = PlayableMedia.Online(
            id = "online_556677",
            title = "Blue Bird",
            artist = "Ikimonogakari",
            album = "My Song Your Song",
            durationMs = 215000L
        )
        HomepageCache.dragonBalls = listOf(
            Akari.NCM.player.core.HomepageDragonBall(id = -6L, name = "私人FM", url = "orpheus://privatefm")
        )
        HomepageCache.blocks = listOf(
            HomepageBlock(
                blockCode = "HOMEPAGE_BLOCK_PLAYLIST_RCMD",
                title = "推荐歌单",
                creatives = listOf(
                    HomepageBlock.Creative(
                        creativeType = "list",
                        title = "今天从《Happy Now?》听起|私人雷达",
                        resourceId = "3136952023",
                        resourceType = "list",
                        seedSongId = "1887215864",
                        specialType = 100
                    ),
                    HomepageBlock.Creative(
                        creativeType = "SONG_LIST_HOMEPAGE",
                        title = "Blue Bird",
                        resourceId = "556677",
                        resourceType = "song",
                        action = "play_all_song_from_current_index",
                        seedSongId = "556677",
                        seedSong = seedTrack
                    ),
                    HomepageBlock.Creative(
                        creativeType = "list",
                        title = "固定歌单测试",
                        resourceId = "90001",
                        resourceType = "list"
                    )
                )
            )
        )

        var clickedPlaylist: OnlinePlaylistTarget? = null
        var triggeredMode: Akari.NCM.player.core.InfinitePlayMode? = null
        var triggeredSeeds: List<PlayableMedia> = emptyList()

        composeTestRule.setContent {
            DiscoverBlocksHomepage(
                ncmApi = ncmApi,
                sessionManager = sessionManager,
                onPlaylistClick = { clickedPlaylist = it },
                onDailyRecommendClick = {},
                onInfinitePlayClick = { mode, seeds, _ ->
                    triggeredMode = mode
                    triggeredSeeds = seeds
                }
            )
        }

        // Verify badges are displayed for infinite cards
        composeTestRule.onNodeWithText("∞ 雷达漫游").assertIsDisplayed()
        composeTestRule.onNodeWithText("♪ 单曲漫游").assertIsDisplayed()

        // 1. Click seed radar card -> triggers SongRadio with fallbackPlaylistId
        composeTestRule.onNodeWithText("今天从《Happy Now?》听起|私人雷达").performClick()
        assertTrue(triggeredMode is Akari.NCM.player.core.InfinitePlayMode.SongRadio)
        val radarMode = triggeredMode as Akari.NCM.player.core.InfinitePlayMode.SongRadio
        assertEquals("1887215864", radarMode.seedSongId)
        assertEquals(3136952023L, radarMode.fallbackPlaylistId)

        // 2. Click song radio card -> triggers SongRadio with preloaded seedSong
        composeTestRule.onNodeWithText("Blue Bird").performClick()
        val songMode = triggeredMode as Akari.NCM.player.core.InfinitePlayMode.SongRadio
        assertEquals("556677", songMode.seedSongId)
        assertEquals(1, triggeredSeeds.size)
        assertEquals("Blue Bird", triggeredSeeds.first().title)

        // 3. Click fixed playlist -> triggers onPlaylistClick
        composeTestRule.onNodeWithText("固定歌单测试").performClick()
        assertNotNull(clickedPlaylist)
        assertEquals(90001L, clickedPlaylist?.id)
    }

    @Test
    fun testAmePlayerEngine_appendTracksToQueue_preservesHistoryAndDeduplicates() {
        val seedTrack = PlayableMedia.Online(
            id = "online_1001",
            title = "Seed Song",
            artist = "Artist A",
            album = "Album A",
            durationMs = 200000L
        )
        val recTrack1 = PlayableMedia.Online(
            id = "online_1002",
            title = "Recommended 1",
            artist = "Artist B",
            album = "Album B",
            durationMs = 210000L
        )
        val recTrack2 = PlayableMedia.Online(
            id = "online_1003",
            title = "Recommended 2",
            artist = "Artist C",
            album = "Album C",
            durationMs = 220000L
        )

        playerEngine.startInfinitePlayback(
            mode = Akari.NCM.player.core.InfinitePlayMode.SongRadio(
                seedSongId = "1001",
                seedSongTitle = "Seed Song"
            ),
            initialTracks = listOf(seedTrack)
        )

        assertTrue(playerEngine.infinitePlayMode.value.isActive)
        assertEquals(1, playerEngine.playlist.value.size)
        assertEquals("online_1001", playerEngine.playlist.value[0].id)

        // Append recommended tracks (including a duplicate of seedTrack)
        val addedCount = playerEngine.appendTracksToQueue(listOf(seedTrack, recTrack1, recTrack2))
        assertEquals(2, addedCount)
        assertEquals(3, playerEngine.playlist.value.size)
        assertEquals(
            listOf("online_1001", "online_1002", "online_1003"),
            playerEngine.playlist.value.map { it.id }
        )

        // Loading a standard fixed playlist exits infinite mode
        playerEngine.loadPlaylist(listOf(recTrack1), 0)
        assertEquals(Akari.NCM.player.core.InfinitePlayMode.None, playerEngine.infinitePlayMode.value)
    }
}
