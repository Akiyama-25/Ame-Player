package Akari.NCM.player.data

import Akari.NCM.player.core.LyricLine
import Akari.NCM.player.core.LyricSource
import Akari.NCM.player.core.WordInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricTimelineModeTest {

    @Test
    fun `纯单句时间轴歌词判定为无逐字播放时间轴`() {
        val lrc = """
            [00:01.00]第一句歌词
            [00:05.50]第二句歌词
        """.trimIndent()
        val parsedLines = LrcParser.parse(lrc)
        val result = LyricResult(lines = parsedLines, source = LyricSource.ONLINE)

        assertFalse(result.hasWordTimestamps)
        assertTrue(result.lines.all { !it.hasWordTimestamps })
        assertTrue(result.lines.all { it.words.isEmpty() })
    }

    @Test
    fun `包含YRC逐字时间轴判定为具备逐字播放时间轴`() {
        val yrc = "[0,3000](0,500,0)Hello(500,500,0)World"
        val parsedLines = YrcLyricParser.parse(yrc)
        val result = LyricResult(lines = parsedLines, source = LyricSource.YRC)

        assertTrue(result.hasWordTimestamps)
        assertTrue(result.lines.first().hasWordTimestamps)
        assertEquals(2, result.lines.first().words.size)
    }

    @Test
    fun `单行歌词对象正确反映逐字时间轴状态`() {
        val lineWithoutWords = LyricLine(timeMs = 1000L, text = "普通单句歌词")
        assertFalse(lineWithoutWords.hasWordTimestamps)

        val lineWithWords = LyricLine(
            timeMs = 1000L,
            text = "逐字歌词",
            words = listOf(WordInfo("逐", 0L, 200L), WordInfo("字", 200L, 200L))
        )
        assertTrue(lineWithWords.hasWordTimestamps)
    }

    @Test
    fun `部分行缺失逐字信息的逐词歌词整体仍具备逐字能力但单行独立区分`() {
        val lines = listOf(
            LyricLine(timeMs = 1000L, text = "伴奏/间奏", words = emptyList()),
            LyricLine(
                timeMs = 5000L,
                text = "唱词",
                words = listOf(WordInfo("唱", 0L, 300L), WordInfo("词", 300L, 300L))
            )
        )
        val result = LyricResult(lines = lines, source = LyricSource.TTML)

        assertTrue(result.hasWordTimestamps)
        assertFalse(lines[0].hasWordTimestamps)
        assertTrue(lines[1].hasWordTimestamps)
    }
}