package Akari.NCM.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YrcLyricParserTest {

    @Test
    fun `解析标准单行YRC逐字歌词与相对偏移`() {
        val yrc = "[0,3000](0,500,0)Hello(500,500,0)World"
        val lines = YrcLyricParser.parse(yrc)
        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals(0L, line.timeMs)
        assertEquals(3000L, line.durationMs)
        assertEquals("HelloWorld", line.text)
        assertEquals(2, line.words.size)
        assertEquals("Hello", line.words[0].text)
        assertEquals(0L, line.words[0].startOffsetMs)
        assertEquals(500L, line.words[0].durationMs)
        assertEquals("World", line.words[1].text)
        assertEquals(500L, line.words[1].startOffsetMs)
        assertEquals(500L, line.words[1].durationMs)
    }

    @Test
    fun `多行YRC按行时间戳排序并自动计算行内词偏移`() {
        val yrc = """
            [2000,1000](2000,500,0)Second
            [1000,2000](1000,300,0)Word1(1300,300,0)Word2
        """.trimIndent()
        val lines = YrcLyricParser.parse(yrc)
        assertEquals(2, lines.size)
        assertEquals(1000L, lines[0].timeMs)
        assertEquals(0L, lines[0].words[0].startOffsetMs)
        assertEquals(300L, lines[0].words[1].startOffsetMs)
        assertEquals(2000L, lines[1].timeMs)
    }

    @Test
    fun `过滤JSON元数据行与空行`() {
        val yrc = """
            [0,0]{"t":0,"c":[{"tx":"作词 : Akari"}]}
            
            not a yrc line
            [3000,1500](3000,1000,0)有效歌词
        """.trimIndent()
        val lines = YrcLyricParser.parse(yrc)
        assertEquals(1, lines.size)
        assertEquals(3000L, lines[0].timeMs)
        assertEquals("有效歌词", lines[0].text)
        assertEquals(1, lines[0].words.size)
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(YrcLyricParser.parse("").isEmpty())
        assertTrue(YrcLyricParser.parse(null).isEmpty())
    }
}