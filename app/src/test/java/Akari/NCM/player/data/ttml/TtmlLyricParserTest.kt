package Akari.NCM.player.data.ttml

import Akari.NCM.player.core.LyricAlignment
import Akari.NCM.player.core.WordInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlLyricParserTest {

    @Test
    fun inlineTranslationsPreferSimplifiedChineseOverFirstGermanEntry() {
        val line = parse("""<t:p begin="1s" end="2s"><t:span begin="1s" end="2s">Flare</t:span><t:span m:role="x-translation" xml:lang="de-DE">Deutsche Übersetzung</t:span><t:span m:role="x-translation" xml:lang="zh-TW">繁體翻譯</t:span><t:span m:role="x-translation" xml:lang="zh-CN">简体翻译</t:span></t:p>""").single()
        assertEquals("简体翻译", line.translation)
    }

    @Test
    fun externalSimplifiedTranslationWinsOverInlineGerman() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:m="http://www.w3.org/ns/ttml#metadata" xmlns:i="http://music.apple.com/lyric-ttml-internal"><head><metadata><i:translations><i:translation xml:lang="de-DE"><i:text for="L1">Deutsch</i:text></i:translation><i:translation xml:lang="zh-Hans"><i:text for="L1">简体中文</i:text></i:translation></i:translations></metadata></head><body><div><p begin="1s" end="2s" i:key="L1"><span begin="1s" end="2s">Flare</span><span m:role="x-translation" xml:lang="de-DE">Deutsch inline</span></p></div></body></tt>"""
        assertEquals("简体中文", TtmlLyricParser.parse(xml).single().translation)
    }

    @Test
    fun duetRomanizationBackgroundAndExternalAnnotations() {
        val xml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:m="http://www.w3.org/ns/ttml#metadata" xmlns:i="http://music.apple.com/lyric-ttml-internal">
              <head><metadata><m:agent xml:id="v1"/><m:agent xml:id="v2"/>
                <i:translations><i:translation><i:text for="L1">外部翻译</i:text></i:translation></i:translations>
                <i:transliterations><i:transliteration><i:text for="L1"><span>romaji </span><span>line</span></i:text></i:transliteration></i:transliterations>
              </metadata></head>
              <body><div>
                <p begin="1s" end="3s" m:agent="v2" i:key="L1"><span begin="1s" end="3s">main</span>
                  <span m:role="x-bg" begin="2500ms" end="4s"><span begin="2500ms" end="4s">(back)</span><span m:role="x-translation">背景翻译</span><span m:role="x-roman">background roman</span></span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()
        val line = TtmlLyricParser.parse(xml).single()
        assertEquals(LyricAlignment.END, line.alignment)
        assertEquals("外部翻译", line.translation)
        assertEquals("romaji line", line.roma)
        val background = requireNotNull(line.backgroundLine)
        assertEquals(2500L, background.timeMs)
        assertEquals(1500L, background.durationMs)
        assertEquals("back", background.text)
        assertEquals("背景翻译", background.translation)
        assertEquals("background roman", background.roma)
        assertEquals(LyricAlignment.END, background.alignment)
        assertEquals(listOf(WordInfo("back", 0, 1500)), background.words)
    }

    @Test
    fun backgroundBracketsAreStrippedKeepingWordTiming() {
        val outer = parse("""<t:p begin="1s" end="4s"><t:span begin="1s" end="2s">main</t:span><t:span m:role="x-bg" begin="2500ms" end="4s">(<t:span begin="2500ms" end="4s">back</t:span>)</t:span></t:p>""").single()
        assertEquals("back", requireNotNull(outer.backgroundLine).text)
        assertTrue(requireNotNull(outer.backgroundLine).words.isEmpty())

        val fullWidth = parse("""<t:p begin="1s" end="4s"><t:span begin="1s" end="2s">main</t:span><t:span m:role="x-bg" begin="2500ms" end="4s"><t:span begin="2500ms" end="4s">（合唱）</t:span></t:span></t:p>""").single()
        assertEquals("合唱", requireNotNull(fullWidth.backgroundLine).text)

        val empty = parse("""<t:p begin="1s" end="4s"><t:span begin="1s" end="2s">main</t:span><t:span m:role="x-bg" begin="2500ms" end="4s">()</t:span></t:p>""").single()
        assertNull(empty.backgroundLine)
    }

    @Test
    fun inlineRomanizationOverridesExternalRomanization() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:m="http://www.w3.org/ns/ttml#metadata" xmlns:i="http://music.apple.com/lyric-ttml-internal"><head><metadata><i:transliterations><i:transliteration><i:text for="L1">external</i:text></i:transliteration></i:transliterations></metadata></head><body><div><p begin="1s" end="2s" i:key="L1"><span begin="1s" end="2s">歌</span><span m:role="x-roman">uta</span></p></div></body></tt>"""
        assertEquals("uta", TtmlLyricParser.parse(xml).single().roma)
    }

    @Test
    fun amllExportsCanResetDefaultNamespaceInBody() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:m="http://www.w3.org/ns/ttml#metadata"><head><metadata xmlns=""><p begin="1s" end="2s">not lyrics</p></metadata></head><body><div xmlns=""><p begin="1s" end="2s"><span begin="1s" end="2s">hello</span><span m:role="x-translation">你好</span></p></div></body></tt>"""
        val line = TtmlLyricParser.parse(xml).single()
        assertEquals("hello", line.text)
        assertEquals("你好", line.translation)
        assertEquals(1, line.words.size)
    }

    @Test
    fun wordsTranslationNamespacesAndEntities() {
        val line = parse("""<t:p begin="00:01.000" end="00:04.000"><t:span begin="1s" end="2s">You</t:span> <t:span begin="2500ms" end="4s">&amp; I</t:span><t:span m:role="x-translation">你&#x548c;我</t:span><t:span m:role="x-roman">ignored</t:span><t:span m:role="x-bg">background</t:span></t:p>""").single()
        assertEquals("You & I", line.text)
        assertEquals("你和我", line.translation)
        assertEquals(3000L, line.durationMs)
        assertEquals(listOf(WordInfo("You ", 0, 1000), WordInfo("& I", 1500, 1500)), line.words)
    }

    @Test
    fun plainLinesAndStableDuplicateTimes() {
        val lines = parse("""<t:p begin="2s" end="3s">third</t:p><t:p begin="1s" end="2s">first</t:p><t:p begin="1s" end="2s">second</t:p><t:p begin="1s" end="2s"> </t:p>""")
        assertEquals(listOf("first", "second", "third"), lines.map { it.text })
        assertTrue(lines.all { it.words.isEmpty() })
    }

    @Test
    fun invalidLinesAreSkipped() {
        assertTrue(parse("""<t:p end="2s">missing</t:p><t:p begin="3s" end="2s">reversed</t:p><t:p begin="1s" end="1s">zero</t:p>""").isEmpty())
    }

    @Test
    fun invalidWordTimingFallsBackWithoutLosingText() {
        for (attributes in listOf("begin=\"2s\" end=\"1s\"", "begin=\"1s\"", "begin=\"NaNs\" end=\"2s\"", "begin=\"-1s\" end=\"2s\"")) {
            val line = parse("""<t:p begin="1s" end="3s"><t:span $attributes>hello</t:span></t:p>""").single()
            assertEquals("hello", line.text)
            assertTrue(line.words.isEmpty())
        }
    }

    private fun parse(body: String) = TtmlLyricParser.parse("""
        <t:tt xmlns:t="http://www.w3.org/ns/ttml" xmlns:m="http://www.w3.org/ns/ttml#metadata">
        <t:body><t:div>$body</t:div></t:body></t:tt>
    """.trimIndent())
}