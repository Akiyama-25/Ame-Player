package Akari.NCM.player.data

import Akari.NCM.player.core.LyricLine
import Akari.NCM.player.core.WordInfo

object YrcLyricParser {

    private val YRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val YRC_WORD_REGEX = Regex("""\((\d+),(\d+),\d+\)([^(\n]+)""")

    /**
     * 解析网易云 YRC 逐字歌词文本为包含精确词级时序的 [LyricLine] 列表
     * 格式形如：[0,3000](0,500,0)Hello(500,500,0)World
     */
    fun parse(yrcContent: String?): List<LyricLine> {
        if (yrcContent.isNullOrBlank()) return emptyList()

        return yrcContent.lines().mapNotNull { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) return@mapNotNull null

            YRC_LINE_REGEX.find(trimmed)?.let { match ->
                val lineStartTime = match.groupValues[1].toLongOrNull() ?: return@let null
                val lineDuration = match.groupValues[2].toLongOrNull() ?: return@let null
                val wordsContent = match.groupValues[3]

                val wordsList = mutableListOf<WordInfo>()
                val fullTextBuilder = StringBuilder()

                YRC_WORD_REGEX.findAll(wordsContent).forEach { wordMatch ->
                    val absoluteTime = wordMatch.groupValues[1].toLongOrNull() ?: lineStartTime
                    val startOffset = (absoluteTime - lineStartTime).coerceAtLeast(0L)
                    val duration = wordMatch.groupValues[2].toLongOrNull() ?: 0L
                    val wordText = wordMatch.groupValues[3]

                    wordsList.add(WordInfo(wordText, startOffset, duration))
                    fullTextBuilder.append(wordText)
                }

                val lineText = if (fullTextBuilder.isNotEmpty()) {
                    fullTextBuilder.toString()
                } else {
                    wordsContent.trim()
                }

                // 过滤 JSON 元数据行，如 [0,0]{"t":0,"c":[{"tx":"作词 : ..."}]}
                if (lineText.isBlank() || (lineText.startsWith("{") && lineText.endsWith("}"))) {
                    return@mapNotNull null
                }

                LyricLine(
                    timeMs = lineStartTime,
                    durationMs = lineDuration,
                    text = lineText,
                    words = wordsList
                )
            }
        }.sortedBy { it.timeMs }
    }
}