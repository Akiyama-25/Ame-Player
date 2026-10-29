package Akari.NCM.player.data.ttml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LyricsCacheTest {

    @Test
    fun rawHitSkipsNetworkAndExpirationWorks() {
        val root = Files.createTempDirectory("amll-cache-test").toFile()
        try {
            var virtualTime = 100000L
            val cache = LyricsCache(
                cacheRoot = root,
                now = { virtualTime },
                rawTtlMs = 5000L,
                negativeTtlMs = 2000L
            )

            val xml = "<tt>test lyrics</tt>"
            cache.putRaw(12345L, xml)

            // Cache hit
            assertEquals(xml, cache.getRaw(12345L))

            // After TTL expired
            virtualTime += 6000L
            assertNull(cache.getRaw(12345L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun negativeCacheRecords404AndExpires() {
        val root = Files.createTempDirectory("amll-neg-cache-test").toFile()
        try {
            var virtualTime = 200000L
            val cache = LyricsCache(
                cacheRoot = root,
                now = { virtualTime },
                rawTtlMs = 10000L,
                negativeTtlMs = 3000L
            )

            assertFalse(cache.isNegative(999L))
            cache.putNegative(999L)
            assertTrue(cache.isNegative(999L))

            // Advance time past negative TTL
            virtualTime += 4000L
            assertFalse(cache.isNegative(999L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removeRawAndClearDeleteFiles() {
        val root = Files.createTempDirectory("amll-clean-test").toFile()
        try {
            val cache = LyricsCache(cacheRoot = root)
            cache.putRaw(1L, "lyrics 1")
            cache.putRaw(2L, "lyrics 2")
            assertEquals("lyrics 1", cache.getRaw(1L))
            assertEquals("lyrics 2", cache.getRaw(2L))

            cache.removeRaw(1L)
            assertNull(cache.getRaw(1L))
            assertEquals("lyrics 2", cache.getRaw(2L))

            cache.clear()
            assertNull(cache.getRaw(2L))
        } finally {
            root.deleteRecursively()
        }
    }
}