package Akari.NCM.player.ui

import Akari.NCM.player.core.HomepageBlock
import Akari.NCM.player.core.HomepageDragonBall
import Akari.NCM.player.ui.screen.HomepageCache
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HomepageCacheTest {

    @Before
    fun setUp() {
        HomepageCache.clear()
    }

    @Test
    fun testInitialCacheStateIsEmpty() {
        assertNull(HomepageCache.dragonBalls)
        assertNull(HomepageCache.blocks)
        assertNull(HomepageCache.firstSongCover)
        assertNull(HomepageCache.recommendBgUrls)
        assertNull(HomepageCache.recommendPlaylistIds)
        assertFalse(HomepageCache.lastLoginState)
    }

    @Test
    fun testCacheSetAndRetention() {
        val testBalls = listOf(
            HomepageDragonBall(id = 1L, name = "每日推荐"),
            HomepageDragonBall(id = 2L, name = "私人FM")
        )
        val testBlocks = listOf(
            HomepageBlock(blockCode = "HOMEPAGE_BLOCK_PLAYLIST_RCMD", title = "推荐歌单")
        )
        val testBgUrls = listOf("https://example.com/cover1.jpg", "https://example.com/cover2.jpg")
        val testPlaylistIds = listOf(101L, 102L)

        HomepageCache.dragonBalls = testBalls
        HomepageCache.blocks = testBlocks
        HomepageCache.firstSongCover = "https://example.com/song1.jpg"
        HomepageCache.recommendBgUrls = testBgUrls
        HomepageCache.recommendPlaylistIds = testPlaylistIds
        HomepageCache.lastLoginState = true

        assertEquals(testBalls, HomepageCache.dragonBalls)
        assertEquals(testBlocks, HomepageCache.blocks)
        assertEquals("https://example.com/song1.jpg", HomepageCache.firstSongCover)
        assertEquals(testBgUrls, HomepageCache.recommendBgUrls)
        assertEquals(testPlaylistIds, HomepageCache.recommendPlaylistIds)
        assertTrue(HomepageCache.lastLoginState)
    }

    @Test
    fun testClearWipesAllData() {
        HomepageCache.dragonBalls = listOf(HomepageDragonBall(id = 1L, name = "Test"))
        HomepageCache.blocks = listOf(HomepageBlock(title = "Test Block"))
        HomepageCache.firstSongCover = "https://example.com/img.jpg"
        HomepageCache.recommendBgUrls = listOf("https://example.com/bg.jpg")
        HomepageCache.recommendPlaylistIds = listOf(999L)
        HomepageCache.lastLoginState = true

        HomepageCache.clear()

        assertNull(HomepageCache.dragonBalls)
        assertNull(HomepageCache.blocks)
        assertNull(HomepageCache.firstSongCover)
        assertNull(HomepageCache.recommendBgUrls)
        assertNull(HomepageCache.recommendPlaylistIds)
        assertFalse(HomepageCache.lastLoginState)
    }

    @Test
    fun testLoginStateChangeClearsCache() {
        HomepageCache.dragonBalls = listOf(HomepageDragonBall(id = 1L, name = "Anon Data"))
        HomepageCache.lastLoginState = false

        // Simulate login event in UserHomepageView
        val currentLoginState = true
        if (HomepageCache.lastLoginState != currentLoginState) {
            HomepageCache.clear()
        }
        HomepageCache.lastLoginState = currentLoginState

        assertNull(HomepageCache.dragonBalls)
        assertTrue(HomepageCache.lastLoginState)
    }

    @Test
    fun testConcurrentAccess() {
        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(20)

        for (i in 0 until 20) {
            executor.submit {
                try {
                    HomepageCache.dragonBalls = listOf(HomepageDragonBall(id = i.toLong(), name = "Ball $i"))
                    HomepageCache.blocks = listOf(HomepageBlock(title = "Block $i"))
                    val balls = HomepageCache.dragonBalls
                    assertNotNull(balls)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
    }

    @Test
    fun testDragonBallUrlParsingRobustness() {
        // Test parsing url id parameter for FM/DragonBall clicks
        val urlWithId = "orpheus://playlist?id=12345678"
        val parsedId = urlWithId.substringAfterLast("id=").toLongOrNull()
        assertEquals(12345678L, parsedId)

        val invalidUrl = "orpheus://radio/personal_fm"
        val fallbackId = invalidUrl.substringAfterLast("id=").toLongOrNull()
        assertNull(fallbackId)

        val emptyUrl = ""
        val emptyParsed = emptyUrl.substringAfterLast("id=").toLongOrNull()
        assertNull(emptyParsed)
    }

    @Test
    fun testCreativeResourceIdParsing() {
        val numericCreative = HomepageBlock.Creative(resourceId = "987654321")
        assertEquals(987654321L, numericCreative.resourceId.toLongOrNull())

        val nonNumericCreative = HomepageBlock.Creative(resourceId = "SPECIAL_PAGE_ALPHA")
        assertNull(nonNumericCreative.resourceId.toLongOrNull())
    }
}
