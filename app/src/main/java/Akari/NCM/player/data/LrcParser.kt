package Akari.NCM.player.data

import Akari.NCM.player.core.LyricLine
import java.util.regex.Pattern
import kotlin.math.abs

object LrcParser {

    private val TIME_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})(?:[\\.\\:](\\d{2,3}))?\\]")

    fun parse(lrcContent: String?): List<LyricLine> {
        if (lrcContent.isNullOrBlank()) return emptyList()

        val lines = lrcContent.lines()
        val result = mutableListOf<LyricLine>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val matcher = TIME_PATTERN.matcher(trimmed)
            val timestamps = mutableListOf<Long>()

            var lastMatchEnd = 0
            while (matcher.find()) {
                val minutes = matcher.group(1)?.toLongOrNull() ?: 0L
                val seconds = matcher.group(2)?.toLongOrNull() ?: 0L
                val msGroup = matcher.group(3)
                val millis = when {
                    msGroup == null -> 0L
                    msGroup.length == 2 -> msGroup.toLong() * 10
                    msGroup.length == 3 -> msGroup.toLong()
                    else -> 0L
                }

                val totalMs = (minutes * 60 + seconds) * 1000 + millis
                timestamps.add(totalMs)
                lastMatchEnd = matcher.end()
            }

            if (timestamps.isNotEmpty()) {
                val lyricText = trimmed.substring(lastMatchEnd).trim()
                if (lyricText.isNotBlank()) {
                    for (timeMs in timestamps) {
                        result.add(LyricLine(timeMs = timeMs, text = lyricText))
                    }
                }
            }
        }

        return result.sortedBy { it.timeMs }
    }

    /**
     * 合并原文歌词与翻译歌词，按时间戳精准对齐
     */
    fun mergeWithTranslation(originalList: List<LyricLine>, transList: List<LyricLine>): List<LyricLine> {
        if (transList.isEmpty()) return originalList

        val transMap = transList.associateBy { it.timeMs }
        return originalList.map { mainLine ->
            // 精确时间匹配，或者 ±500ms 容错匹配
            val matchedTrans = transMap[mainLine.timeMs]
                ?: transList.firstOrNull { abs(it.timeMs - mainLine.timeMs) <= 500 }
            if (matchedTrans != null && matchedTrans.text.isNotBlank()) {
                mainLine.copy(translation = matchedTrans.text)
            } else {
                mainLine
            }
        }
    }
}
