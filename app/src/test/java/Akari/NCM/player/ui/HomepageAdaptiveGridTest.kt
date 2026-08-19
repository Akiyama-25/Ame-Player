package Akari.NCM.player.ui

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.HomepageBlock
import Akari.NCM.player.core.HomepageDragonBall
import Akari.NCM.player.core.HomepageMode
import Akari.NCM.player.core.NcmUserPlaylist
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.ui.screen.BlockCreativeCard
import Akari.NCM.player.ui.screen.DiscoverBlocksHomepage
import Akari.NCM.player.ui.screen.DragonBallCard
import Akari.NCM.player.ui.screen.HomepageCache
import Akari.NCM.player.ui.screen.PlaylistGrid
import Akari.NCM.player.ui.screen.UserHomepageView
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomepageAdaptiveGridTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var sessionManager: UserSessionManager
    private lateinit var ncmApi: NcmApi

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sessionManager = UserSessionManager(context)
        sessionManager.clearSession()
        ncmApi = NcmApi(sessionManager)
        HomepageCache.clear()
    }

    private fun createTestPlaylists(count: Int): List<NcmUserPlaylist> {
        return (1..count).map { i ->
            NcmUserPlaylist(
                id = i.toLong(),
                name = "Test Playlist $i",
                coverImgUrl = "https://example.com/cover$i.jpg",
                trackCount = 20 + i,
                playCount = 1000L * i,
                creatorName = "Creator $i",
                description = "Description $i"
            )
        }
    }

    // =========================================================================
    // 1. PlaylistGrid 响应式多列排版与断点测试
    // =========================================================================

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun testPlaylistGrid_compact_rendersThreeColumns() {
        val playlists = createTestPlaylists(8)
        var clickedId = 0L

        composeTestRule.setContent {
            PlaylistGrid(
                playlists = playlists,
                onPlaylistClick = { clickedId = it }
            )
        }

        // 验证各卡片展示
        composeTestRule.onNodeWithText("Test Playlist 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Playlist 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Playlist 3").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Playlist 4").assertIsDisplayed()

        // 检查第一行 items 的 top 坐标一致性 (Row 0 应该有 3 列)
        val item1Bounds = composeTestRule.onNodeWithText("Test Playlist 1").getUnclippedBoundsInRoot()
        val item2Bounds = composeTestRule.onNodeWithText("Test Playlist 2").getUnclippedBoundsInRoot()
        val item3Bounds = composeTestRule.onNodeWithText("Test Playlist 3").getUnclippedBoundsInRoot()
        val item4Bounds = composeTestRule.onNodeWithText("Test Playlist 4").getUnclippedBoundsInRoot()

        // Item 1, 2, 3 都在第一行
        assertEquals(item1Bounds.top.value, item2Bounds.top.value, 1.5f)
        assertEquals(item1Bounds.top.value, item3Bounds.top.value, 1.5f)

        // Item 4 换行到第二行
        assertTrue("Item 4 should wrap to next row", item4Bounds.top.value > item1Bounds.top.value)
        assertEquals("Item 4 should align with Item 1 left", item1Bounds.left.value, item4Bounds.left.value, 1.5f)

        // 水平内边距验证: Compact 为 16dp
        assertEquals(16f, item1Bounds.left.value, 1.5f)

        // 点击交互验证
        composeTestRule.onNodeWithText("Test Playlist 1").performClick()
        assertEquals(1L, clickedId)
    }

    @Test
    @Config(qualifiers = "w700dp-h900dp")
    fun testPlaylistGrid_medium_rendersFourColumns() {
        val playlists = createTestPlaylists(8)

        composeTestRule.setContent {
            PlaylistGrid(
                playlists = playlists,
                onPlaylistClick = {}
            )
        }

        val item1Bounds = composeTestRule.onNodeWithText("Test Playlist 1").getUnclippedBoundsInRoot()
        val item2Bounds = composeTestRule.onNodeWithText("Test Playlist 2").getUnclippedBoundsInRoot()
        val item3Bounds = composeTestRule.onNodeWithText("Test Playlist 3").getUnclippedBoundsInRoot()
        val item4Bounds = composeTestRule.onNodeWithText("Test Playlist 4").getUnclippedBoundsInRoot()
        val item5Bounds = composeTestRule.onNodeWithText("Test Playlist 5").getUnclippedBoundsInRoot()

        // Item 1, 2, 3, 4 都在第一行 (4 列)
        assertEquals(item1Bounds.top.value, item2Bounds.top.value, 1.5f)
        assertEquals(item1Bounds.top.value, item3Bounds.top.value, 1.5f)
        assertEquals(item1Bounds.top.value, item4Bounds.top.value, 1.5f)

        // Item 5 换行到第二行
        assertTrue("Item 5 should wrap to next row", item5Bounds.top.value > item1Bounds.top.value)
        assertEquals("Item 5 should align with Item 1 left", item1Bounds.left.value, item5Bounds.left.value, 1.5f)

        // 水平内边距验证: Medium 为 20dp
        assertEquals(20f, item1Bounds.left.value, 1.5f)
    }

    @Test
    @Config(qualifiers = "w1024dp-h768dp")
    fun testPlaylistGrid_expanded_rendersSixColumns() {
        val playlists = createTestPlaylists(8)

        composeTestRule.setContent {
            PlaylistGrid(
                playlists = playlists,
                onPlaylistClick = {}
            )
        }

        val item1Bounds = composeTestRule.onNodeWithText("Test Playlist 1").getUnclippedBoundsInRoot()
        val item2Bounds = composeTestRule.onNodeWithText("Test Playlist 2").getUnclippedBoundsInRoot()
        val item3Bounds = composeTestRule.onNodeWithText("Test Playlist 3").getUnclippedBoundsInRoot()
        val item4Bounds = composeTestRule.onNodeWithText("Test Playlist 4").getUnclippedBoundsInRoot()
        val item5Bounds = composeTestRule.onNodeWithText("Test Playlist 5").getUnclippedBoundsInRoot()
        val item6Bounds = composeTestRule.onNodeWithText("Test Playlist 6").getUnclippedBoundsInRoot()
        val item7Bounds = composeTestRule.onNodeWithText("Test Playlist 7").getUnclippedBoundsInRoot()

        // Item 1 至 6 都在第一行 (6 列)
        assertEquals(item1Bounds.top.value, item2Bounds.top.value, 1.5f)
        assertEquals(item1Bounds.top.value, item3Bounds.top.value, 1.5f)
        assertEquals(item1Bounds.top.value, item4Bounds.top.value, 1.5f)
        assertEquals(item1Bounds.top.value, item5Bounds.top.value, 1.5f)
        assertEquals(item1Bounds.top.value, item6Bounds.top.value, 1.5f)

        // Item 7 换行到第二行
        assertTrue("Item 7 should wrap to next row", item7Bounds.top.value > item1Bounds.top.value)
        assertEquals("Item 7 should align with Item 1 left", item1Bounds.left.value, item7Bounds.left.value, 1.5f)

        // 水平内边距验证: Expanded 为 28dp
        assertEquals(28f, item1Bounds.left.value, 1.5f)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun testPlaylistGrid_emptyList_rendersEmptyState() {
        composeTestRule.setContent {
            PlaylistGrid(
                playlists = emptyList(),
                onPlaylistClick = {}
            )
        }

        composeTestRule.onNodeWithText("暂无歌单内容").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Test Playlist", substring = true).assertCountEquals(0)
    }

    // =========================================================================
    // 2. DragonBallCard 与 BlockCreativeCard 尺寸缩放测试
    // =========================================================================

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun testDragonBallCard_compactDimensions() {
        val ball = HomepageDragonBall(id = 1L, name = "每日推荐")

        composeTestRule.setContent {
            DragonBallCard(
                ball = ball,
                bgUrl = null,
                cardWidth = 120.dp,
                cardHeight = 160.dp,
                onClick = {}
            )
        }

        val cardBounds = composeTestRule.onNodeWithText("每日推荐").getUnclippedBoundsInRoot()
        assertTrue("Card should contain title", (cardBounds.right - cardBounds.left).value > 0f)
    }

    @Test
    @Config(qualifiers = "w700dp-h900dp")
    fun testDiscoverBlocksHomepage_medium_scalesCards() {
        val testBalls = listOf(
            HomepageDragonBall(id = 1L, name = "每日推荐"),
            HomepageDragonBall(id = 2L, name = "心动模式")
        )
        val testBlocks = listOf(
            HomepageBlock(
                blockCode = "BLOCK_1",
                title = "编辑精选歌单",
                creatives = listOf(
                    HomepageBlock.Creative(creativeType = "playlist", title = "精选 1", resourceId = "101"),
                    HomepageBlock.Creative(creativeType = "playlist", title = "精选 2", resourceId = "102")
                )
            )
        )
        HomepageCache.dragonBalls = testBalls
        HomepageCache.blocks = testBlocks

        composeTestRule.setContent {
            DiscoverBlocksHomepage(
                ncmApi = ncmApi,
                sessionManager = sessionManager,
                onPlaylistClick = {},
                onDailyRecommendClick = {}
            )
        }

        composeTestRule.onNodeWithText("每日推荐").assertIsDisplayed()
        composeTestRule.onNodeWithText("心动模式").assertIsDisplayed()
        composeTestRule.onNodeWithText("编辑精选歌单").assertIsDisplayed()
        composeTestRule.onNodeWithText("精选 1").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1024dp-h768dp")
    fun testDiscoverBlocksHomepage_expanded_scalesCards() {
        val testBalls = listOf(
            HomepageDragonBall(id = 1L, name = "每日推荐"),
            HomepageDragonBall(id = 2L, name = "心动模式")
        )
        val testBlocks = listOf(
            HomepageBlock(
                blockCode = "BLOCK_1",
                title = "推荐歌单",
                creatives = listOf(
                    HomepageBlock.Creative(creativeType = "playlist", title = "大屏推荐歌单", resourceId = "201")
                )
            )
        )
        HomepageCache.dragonBalls = testBalls
        HomepageCache.blocks = testBlocks

        composeTestRule.setContent {
            DiscoverBlocksHomepage(
                ncmApi = ncmApi,
                sessionManager = sessionManager,
                onPlaylistClick = {},
                onDailyRecommendClick = {}
            )
        }

        composeTestRule.onNodeWithText("每日推荐").assertIsDisplayed()
        composeTestRule.onNodeWithText("大屏推荐歌单").assertIsDisplayed()
    }

    // =========================================================================
    // 3. UserHomepageView 完整页面路由与未登录提示测试
    // =========================================================================

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun testUserHomepageView_dailyOnly_notLoggedIn_showsLoginPrompt() {
        sessionManager.saveHomepageMode(HomepageMode.DAILY_ONLY)

        composeTestRule.setContent {
            UserHomepageView(
                ncmApi = ncmApi,
                sessionManager = sessionManager,
                onPlaylistClick = {},
                onDailyRecommendClick = {}
            )
        }

        composeTestRule.onNodeWithText("每日推荐需要登录网易云音乐账号才能查看").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun testUserHomepageView_appLike_withCache_rendersImmediately() {
        sessionManager.saveHomepageMode(HomepageMode.APP_LIKE)
        HomepageCache.dragonBalls = listOf(
            HomepageDragonBall(id = 1L, name = "每日推荐"),
            HomepageDragonBall(id = 2L, name = "心动模式")
        )
        HomepageCache.blocks = listOf(
            HomepageBlock(
                blockCode = "BLOCK_RCMD",
                title = "推荐歌单",
                creatives = listOf(
                    HomepageBlock.Creative(creativeType = "playlist", title = "即时渲染歌单", resourceId = "301")
                )
            )
        )

        composeTestRule.setContent {
            UserHomepageView(
                ncmApi = ncmApi,
                sessionManager = sessionManager,
                onPlaylistClick = {},
                onDailyRecommendClick = {}
            )
        }

        composeTestRule.onNodeWithText("每日推荐").assertIsDisplayed()
        composeTestRule.onNodeWithText("心动模式").assertIsDisplayed()
        composeTestRule.onNodeWithText("推荐歌单").assertIsDisplayed()
        composeTestRule.onNodeWithText("即时渲染歌单").assertIsDisplayed()
    }
}
