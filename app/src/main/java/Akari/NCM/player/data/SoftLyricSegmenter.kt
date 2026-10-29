package Akari.NCM.player.data

import Akari.NCM.player.core.LyricLine
import Akari.NCM.player.core.WordInfo
import kotlin.math.max

object SoftLyricSegmenter {

    data class Token(val text: String, val weight: Float)

    /**
     * 判断字符是否属于 CJK（中文、日文汉字/平假名/片假名、韩文谚文）
     */
    fun isCjk(c: Char): Boolean {
        val ub = Character.UnicodeBlock.of(c)
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.HIRAGANA ||
                ub == Character.UnicodeBlock.KATAKANA ||
                ub == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                ub == Character.UnicodeBlock.HANGUL_JAMO
    }

    private fun isPunctuationOrSpace(c: Char): Boolean {
        return c.isWhitespace() || c in ",.!?:;~()[]{}'\"“”‘’，。！？：；～（）【】、《》、—-"
    }

    /**
     * 将单行文本分词/分字，并计算每个单元的发音权重
     */
    fun tokenize(text: String): List<Token> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val tokens = mutableListOf<Token>()
        var i = 0
        val n = trimmed.length

        while (i < n) {
            val c = trimmed[i]
            when {
                isCjk(c) -> {
                    var tokenStr = c.toString()
                    i++
                    // 将紧随其后的空白或标点附着在当前汉字上
                    while (i < n && isPunctuationOrSpace(trimmed[i]) && !isCjk(trimmed[i]) && !trimmed[i].isLetterOrDigit()) {
                        tokenStr += trimmed[i]
                        i++
                    }
                    tokens.add(Token(tokenStr, 1.0f))
                }
                c.isLetterOrDigit() -> {
                    val sb = StringBuilder()
                    while (i < n && (trimmed[i].isLetterOrDigit() || trimmed[i] == '\'')) {
                        sb.append(trimmed[i])
                        i++
                    }
                    while (i < n && isPunctuationOrSpace(trimmed[i]) && !isCjk(trimmed[i]) && !trimmed[i].isLetterOrDigit()) {
                        sb.append(trimmed[i])
                        i++
                    }
                    val wordText = sb.toString()
                    val rawLength = wordText.trim().length
                    val weight = (rawLength * 0.35f).coerceAtLeast(1.0f)
                    tokens.add(Token(wordText, weight))
                }
                else -> {
                    val sb = StringBuilder()
                    while (i < n && !isCjk(trimmed[i]) && !trimmed[i].isLetterOrDigit()) {
                        sb.append(trimmed[i])
                        i++
                    }
                    val punctStr = sb.toString()
                    if (tokens.isNotEmpty()) {
                        val last = tokens.removeAt(tokens.lastIndex)
                        tokens.add(Token(last.text + punctStr, last.weight))
                    } else {
                        tokens.add(Token(punctStr, 0.5f))
                    }
                }
            }
        }
        return tokens
    }

    /**
     * 对缺失逐字信息的歌词行列表，批量计算并补全软分词时序
     */
    fun fillMissingWords(lines: List<LyricLine>): List<LyricLine> {
        if (lines.isEmpty()) return emptyList()

        val result = ArrayList<LyricLine>(lines.size)
        for (i in lines.indices) {
            val line = lines[i]
            if (line.words.isNotEmpty()) {
                result.add(line)
                continue
            }

            // 纯音乐或空文本不分词
            val trimmedText = line.text.trim()
            if (trimmedText.isEmpty() || trimmedText.contains("纯音乐") || trimmedText == "...") {
                result.add(line)
                continue
            }

            // 预估单行持续时间
            val nextTime = lines.getOrNull(i + 1)?.timeMs
            val rawDuration = if (line.durationMs > 0) {
                line.durationMs
            } else if (nextTime != null && nextTime > line.timeMs) {
                nextTime - line.timeMs
            } else {
                3500L
            }

            val tokens = tokenize(trimmedText)
            if (tokens.isEmpty()) {
                result.add(line)
                continue
            }

            // 限制单句合理唱歌时长范围（根据词数动态调整）
            val minDuration = (tokens.size * 180L).coerceAtLeast(1000L)
            val maxDuration = (tokens.size * 650L).coerceAtLeast(3000L)
            val lineDuration = rawDuration.coerceIn(minDuration, maxDuration)

            // 留出 12% 尾部换气/停顿空白（最多 600ms）
            val pauseMs = (lineDuration * 0.12f).toLong().coerceIn(100L, 600L)
            val singingMs = max(lineDuration - pauseMs, minDuration)

            val totalWeight = tokens.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(0.1f)
            val wordsList = mutableListOf<WordInfo>()
            var currentOffset = 0L

            for (token in tokens) {
                val tokenDuration = ((token.weight / totalWeight) * singingMs).toLong().coerceAtLeast(50L)
                wordsList.add(
                    WordInfo(
                        text = token.text,
                        startOffsetMs = currentOffset,
                        durationMs = tokenDuration
                    )
                )
                currentOffset += tokenDuration
            }

            result.add(
                line.copy(
                    durationMs = lineDuration,
                    words = wordsList
                )
            )
        }
        return result
    }
}