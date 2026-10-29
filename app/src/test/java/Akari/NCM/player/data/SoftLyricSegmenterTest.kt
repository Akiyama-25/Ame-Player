package Akari.NCM.player.data

import Akari.NCM.player.core.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftLyricSegmenterTest {

    @Test
    fun `CJK字符识别`() {
        assertTrue(SoftLyricSegmenter.isCjk('我'))
        assertTrue(SoftLyricSegmenter.isCjk('あ'))
        assertTrue(SoftLyricSegmenter.isCjk('ア'))
        assertTrue(SoftLyricSegmenter.isCjk('한'))
        assertFalse(SoftLyricSegmenter.isCjk('A'))
        assertFalse(SoftLyricSegmenter.isCjk('1'))
        assertFalse(SoftLyricSegmenter.isCjk(' '))
        assertFalse(SoftLyricSegmenter.isCjk('!'))
    }

    @Test
    fun `中英文混合文本分词`() {
        val tokens = SoftLyricSegmenter.tokenize("Hello 世界!")
        assertEquals(3, tokens.size)
        assertEquals("Hello ", tokens[0].text)
        assertEquals("世", tokens[1].text)
        assertEquals("界!", tokens[2].text)
    }

    @Test
    fun `为缺失逐字信息的LRC自动补全软分词与合理时序`() {
        val lines = listOf(
            LyricLine(timeMs = 1000L, text = "Hello 世界"),
            LyricLine(timeMs = 5000L, text = "第二行歌词")
        )
        val filled = SoftLyricSegmenter.fillMissingWords(lines)
        assertEquals(2, filled.size)

        val firstLine = filled[0]
        assertTrue(firstLine.words.isNotEmpty())
        assertEquals("Hello ", firstLine.words[0].text)
        assertEquals(0L, firstLine.words[0].startOffsetMs)
        assertTrue(firstLine.words[0].durationMs > 0L)

        // 验证最后一个词结束时在整行时长范围内并保留尾部停顿
        val lastWord = firstLine.words.last()
        val totalSinging = lastWord.startOffsetMs + lastWord.durationMs
        assertTrue(totalSinging <= firstLine.durationMs)
    }

    @Test
    fun `已具备逐字信息的歌词行不被篡改`() {
        val originalWords = listOf(
            Akari.NCM.player.core.WordInfo("已", 0L, 500L),
            Akari.NCM.player.core.WordInfo("有", 500L, 500L)
        )
        val lines = listOf(
            LyricLine(timeMs = 1000L, durationMs = 1000L, text = "已有", words = originalWords)
        )
        val filled = SoftLyricSegmenter.fillMissingWords(lines)
        assertEquals(originalWords, filled[0].words)
    }
}