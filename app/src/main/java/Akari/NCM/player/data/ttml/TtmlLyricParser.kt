package Akari.NCM.player.data.ttml

import Akari.NCM.player.core.LyricAlignment
import Akari.NCM.player.core.LyricLine
import Akari.NCM.player.core.WordInfo

/** Parses AMLL TTML into Ame Player's native lyric model. */
object TtmlLyricParser {
    const val MAX_LENGTH = 2 * 1024 * 1024
    private const val TTML = "http://www.w3.org/ns/ttml"
    private const val MAX_TIME_MS = 604_800_000L

    private data class LocalizedTranslation(val text: String, val priority: Int)
    private data class TimedWord(val text: String, val begin: Long, val end: Long)

    private val doctypeOrEntity = Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)

    fun parse(xml: String): List<LyricLine> {
        if (xml.isBlank() || xml.length > MAX_LENGTH || doctypeOrEntity.containsMatchIn(xml)) return emptyList()
        return try {
            val root = XmlReader.parse(xml) ?: return emptyList()
            if (root.localName != "tt" || root.namespaceUri != TTML) return emptyList()
            val alignments = parseAgentAlignments(root)
            val translations = parseExternalTranslations(root)
            val romanizations = parseExternalAnnotations(root, "transliteration")
            val lines = mutableListOf<LyricLine>()
            fun visit(node: XmlElement, depth: Int) {
                require(depth <= 64) { "TTML nesting is too deep" }
                if (node.localName == "p" && (node.namespaceUri == TTML || node.namespaceUri.isNullOrEmpty())) {
                    parseLine(node, alignments, translations, romanizations)?.let(lines::add)
                } else node.childElements().forEach { visit(it, depth + 1) }
            }
            root.childElements().filter { it.localName == "body" }.forEach { visit(it, 0) }
            lines.sortedBy { it.timeMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseLine(
        p: XmlElement,
        alignments: Map<String, LyricAlignment>,
        translations: Map<String, LocalizedTranslation>,
        romanizations: Map<String, String>
    ): LyricLine? {
        val start = time(p.attribute("begin"))
        val end = time(p.attribute("end"))
        val key = p.attribute("key")
        val alignment = alignments[p.attribute("agent")] ?: LyricAlignment.START
        val background = p.childElements().firstOrNull { it.role() == "x-bg" }
            ?.let { parseBackground(it, alignment) }
        return parseTrack(
            element = p,
            start = start,
            end = end,
            alignment = alignment,
            translation = listOfNotNull(p.directTranslation(), translations[key])
                .minByOrNull { it.priority }?.text,
            romanization = p.directAnnotation("x-roman") ?: romanizations[key],
            backgroundLine = background
        )
    }

    private fun parseBackground(element: XmlElement, alignment: LyricAlignment): LyricLine? {
        return parseTrack(
            element = element,
            start = time(element.attribute("begin")),
            end = time(element.attribute("end")),
            alignment = alignment,
            translation = element.directTranslation()?.text,
            romanization = element.directAnnotation("x-roman")
        )?.let(::stripBackgroundBrackets)
    }

    // AMLL 背景和声习惯用半角/全角圆括号包裹（如 "(Yeah)"），展示时去掉括号；
    // 逐字 timing 保留在去括号后的字词上，并用 words 拼接重建文本，
    // 保证与严格一致校验（words 拼接 == 文本）兼容，不会退化丢词
    private fun stripBackgroundBrackets(line: LyricLine): LyricLine? {
        fun strip(s: String): String = s.filterNot { it == '(' || it == ')' || it == '（' || it == '）' }
        if (line.words.isEmpty()) {
            val text = strip(line.text).trim()
            if (text.isEmpty()) return null
            return line.copy(text = text)
        }
        val kept = line.words.mapNotNull { word ->
            val text = strip(word.text)
            if (text.isBlank()) null else word.copy(text = text)
        }.toMutableList()
        if (kept.isNotEmpty()) {
            kept[0] = kept[0].copy(text = kept[0].text.trimStart())
            if (kept[0].text.isEmpty()) kept.removeAt(0)
        }
        if (kept.isNotEmpty()) {
            kept[kept.lastIndex] = kept[kept.lastIndex].copy(text = kept[kept.lastIndex].text.trimEnd())
            if (kept[kept.lastIndex].text.isEmpty()) kept.removeAt(kept.lastIndex)
        }
        if (kept.isEmpty()) {
            val text = strip(line.text).trim()
            if (text.isEmpty()) return null
            return line.copy(text = text, words = emptyList())
        }
        return line.copy(text = kept.joinToString("") { it.text }, words = kept)
    }

    private fun parseTrack(
        element: XmlElement,
        start: Long?,
        end: Long?,
        alignment: LyricAlignment,
        translation: String?,
        romanization: String?,
        backgroundLine: LyricLine? = null
    ): LyricLine? {
        if (start != null && end != null && end <= start) return null
        val parts = mutableListOf<TimedWord>()
        val text = StringBuilder()
        var validWords = true
        var previousStart = 0L
        fun walk(node: XmlNode, depth: Int) {
            require(depth <= 64) { "TTML nesting is too deep" }
            when (node) {
                is XmlText -> {
                    val value = node.value
                    if (!(value.isBlank() && (value.contains('\n') || value.contains('\r')))) text.append(value)
                }

                is XmlElement -> {
                    if (node.role() in setOf("x-translation", "x-roman", "x-bg")) return
                    if (node.localName == "br") {
                        text.append(' ')
                        validWords = false
                        return
                    }
                    val timed = node.localName == "span" &&
                        (node.hasAttribute("begin") || node.hasAttribute("end"))
                    if (timed) {
                        val before = text.length
                        node.children.forEach { walk(it, depth + 1) }
                        val wordText = text.substring(before)
                        val begin = time(node.attribute("begin"))
                        val finish = time(node.attribute("end"))
                        if (begin == null || finish == null || finish < begin) validWords = false
                        else if (wordText.isNotBlank()) {
                            if (begin < previousStart) validWords = false
                            parts += TimedWord(wordText, begin, finish)
                            previousStart = begin
                        }
                        return
                    }
                    node.children.forEach { walk(it, depth + 1) }
                }
            }
        }
        element.children.forEach { walk(it, 0) }
        val content = text.toString().trim()
        if (content.isEmpty()) return null
        // 仅使用有效词时间补全或扩展父行范围，保持词的绝对时间不变。
        // 翻译/罗马音不参与推导；背景和声使用自身已解析的有效范围。
        // 零时长词可能是编辑器未填写的 0 → 0 占位，不能用于推导父行范围。
        val timedParts = parts.filter { it.end > it.begin }
        val resolvedStart = listOfNotNull(start, timedParts.minOfOrNull { it.begin }, backgroundLine?.timeMs)
            .minOrNull() ?: return null
        val resolvedEnd = listOfNotNull(end, timedParts.maxOfOrNull { it.end },
            backgroundLine?.let { it.timeMs + it.durationMs }).maxOrNull() ?: return null
        if (resolvedEnd <= resolvedStart) return null
        val words = if (validWords && timedParts.isNotEmpty() &&
            parts.all { it.begin >= resolvedStart && it.end <= resolvedEnd } &&
            parts.joinToString("") { it.text }.filterNot(Char::isWhitespace) == content.filterNot(Char::isWhitespace)) {
            attachInterstitialSpaces(content, parts.map {
                WordInfo(it.text, it.begin - resolvedStart, it.end - it.begin)
            })
        } else emptyList()
        return LyricLine(
            timeMs = resolvedStart,
            durationMs = resolvedEnd - resolvedStart,
            text = content,
            translation = translation?.trim()?.takeIf { it.isNotEmpty() },
            roma = romanization?.trim()?.takeIf { it.isNotEmpty() },
            words = words,
            alignment = alignment,
            backgroundLine = backgroundLine
        )
    }

    private fun attachInterstitialSpaces(content: String, parts: List<WordInfo>): List<WordInfo> {
        var cursor = 0
        return parts.mapIndexed { index, word ->
            val token = word.text.trim()
            val at = content.indexOf(token, cursor).coerceAtLeast(cursor)
            val next = if (index == parts.lastIndex) content.length else
                content.indexOf(parts[index + 1].text.trim(), at + token.length)
                    .takeIf { it >= 0 } ?: (at + token.length)
            cursor = next
            word.copy(text = content.substring(at, next))
        }
    }

    private fun parseAgentAlignments(root: XmlElement): Map<String, LyricAlignment> {
        return buildMap {
            for (agent in root.findDescendants("agent")) {
                val id = agent.attribute("id") ?: continue
                put(id, if (isEmpty()) LyricAlignment.START else LyricAlignment.END)
            }
        }
    }

    private fun parseExternalTranslations(root: XmlElement): Map<String, LocalizedTranslation> {
        val result = mutableMapOf<String, LocalizedTranslation>()
        for (container in root.findDescendants("translation")) {
            container.childElements().filter { it.localName == "text" }.forEach { text ->
                val key = text.attribute("for") ?: return@forEach
                val value = text.textContent.trim().takeIf(String::isNotEmpty) ?: return@forEach
                val language = text.attribute("lang") ?: container.attribute("lang")
                val candidate = LocalizedTranslation(value, translationPriority(language))
                if (candidate.priority < (result[key]?.priority ?: Int.MAX_VALUE)) result[key] = candidate
            }
        }
        return result
    }

    private fun parseExternalAnnotations(root: XmlElement, containerName: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (container in root.findDescendants(containerName)) {
            container.childElements().filter { it.localName == "text" }.forEach { text ->
                val key = text.attribute("for")
                val value = text.textContent.trim()
                if (key != null && value.isNotEmpty()) result[key] = value
            }
        }
        return result
    }

    private fun XmlElement.directAnnotation(role: String): String? = childElements()
        .firstOrNull { it.role() == role }?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun XmlElement.directTranslation(): LocalizedTranslation? = childElements()
        .filter { it.role() == "x-translation" }
        .mapNotNull { span ->
            span.textContent.trim().takeIf(String::isNotEmpty)?.let {
                LocalizedTranslation(it, translationPriority(span.attribute("lang")))
            }
        }
        .minByOrNull { it.priority }

    private fun translationPriority(language: String?): Int {
        val tag = language?.lowercase()?.replace('_', '-') ?: return 3
        return when {
            tag.startsWith("zh-hans") || tag == "zh-cn" || tag == "zh-sg" || tag == "zh-my" -> 0
            tag == "zh" -> 1
            tag.startsWith("zh-") -> 2
            else -> 4
        }
    }

    private fun XmlElement.role(): String? = attribute("role")

    internal fun time(value: String?): Long? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        val millis = when {
            v.matches(Regex("\\d+(\\.\\d+)?ms")) -> decimalToMillis(v.dropLast(2), fromSeconds = false)
            v.matches(Regex("\\d+(\\.\\d+)?s")) -> decimalToMillis(v.dropLast(1), fromSeconds = true)
            // AMLL also uses TTML's bare offset time, where the value is seconds.
            v.matches(Regex("\\d+(\\.\\d+)?")) -> decimalToMillis(v, fromSeconds = true)
            v.matches(Regex("(?:\\d+:)?\\d{1,2}:\\d{2}(\\.\\d+)?")) -> clockToMillis(v)
            else -> null
        } ?: return null
        return millis.takeIf { it in 0..MAX_TIME_MS }
    }

    /**
     * 十进制秒/毫秒文本转毫秒整数。截断而非四舍五入。
     * 整数部分超过 12 位直接判非法——任何合法值都远小于 7 天上限。
     */
    private fun decimalToMillis(text: String, fromSeconds: Boolean): Long? {
        val dot = text.indexOf('.')
        val intText = if (dot >= 0) text.substring(0, dot) else text
        val fracText = if (dot >= 0) text.substring(dot + 1) else ""
        if (intText.isEmpty() || intText.length > 12) return null
        if (intText.any { !it.isDigit() } || fracText.any { !it.isDigit() }) return null
        val whole = intText.toLongOrNull() ?: return null
        if (!fromSeconds) return whole
        val fraction = fracText.take(3).padEnd(3, '0').toLongOrNull() ?: 0L
        return whole * 1000 + fraction
    }

    /** `[hh:]mm:ss[.fff]`。末段（秒）≥ 60 判非法；三段式时中间段（分）≥ 60 也判非法。 */
    private fun clockToMillis(value: String): Long? {
        val fields = value.split(':')
        if (fields.size !in 1..3) return null
        if (fields.any { it.isEmpty() || it.length > 12 }) return null
        for (i in 0 until fields.lastIndex) if (fields[i].any { !it.isDigit() }) return null

        val last = fields[fields.lastIndex]
        val dot = last.indexOf('.')
        val lastWholeText = if (dot >= 0) last.substring(0, dot) else last
        val fracText = if (dot >= 0) last.substring(dot + 1) else ""
        if (lastWholeText.isEmpty() || fracText.any { !it.isDigit() }) return null
        val lastWhole = lastWholeText.toLongOrNull() ?: return null
        if (lastWhole >= 60) return null
        if (fields.size == 3 && (fields[1].toLongOrNull() ?: return null) >= 60) return null

        var totalSeconds = lastWhole
        var multiplier = 60L
        for (i in fields.lastIndex - 1 downTo 0) {
            val field = fields[i].toLongOrNull() ?: return null
            totalSeconds += field * multiplier
            multiplier *= 60
        }
        val fraction = fracText.take(3).padEnd(3, '0').toLongOrNull() ?: 0L
        return totalSeconds * 1000 + fraction
    }
}