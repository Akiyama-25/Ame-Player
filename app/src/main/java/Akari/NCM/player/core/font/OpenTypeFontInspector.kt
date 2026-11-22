package Akari.NCM.player.core.font

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * OpenType `fvar` 轴信息。
 */
data class FontAxis(
    val tag: String,
    val minValue: Float,
    val defaultValue: Float,
    val maxValue: Float
)

/**
 * OpenType 字体头与表目录检查结果。
 */
data class FontInspectionResult(
    val isValidFont: Boolean,
    val isVariableFont: Boolean,
    val isCff2: Boolean,
    val isCollection: Boolean,
    val axes: List<FontAxis>,
    val weightAxis: FontAxis?,
    val tables: Set<String> = emptySet()
)

/**
 * OpenType / TrueType 字体二进制解析器。
 *
 * 支持单字体 (`.ttf`, `.otf`) 与字体集合 (`.ttc`, `.otc`)：
 * - sfnt 魔数：`0x00010000`、`0x74727565` (`true`)、`0x4F54544F` (`OTTO`)
 * - 集合魔数：`0x74746366` (`ttcf`)，自动读取首个子字体偏移
 * - 表目录识别：`fvar`、`CFF2`、`CFF `、`glyf`、`STAT`、`name`、`OS/2`
 * - `fvar` 变体轴解析（Fixed 16.16 转换）
 */
object OpenTypeFontInspector {

    private const val SFNT_TRUETYPE = 0x00010000L
    private const val SFNT_TRUE = 0x74727565L // 'true'
    private const val SFNT_OTTO = 0x4F54544FL // 'OTTO'
    private const val TTC_TAG = 0x74746366L   // 'ttcf'

    private val INTERESTING_TABLES = setOf(
        "fvar", "CFF2", "CFF ", "glyf", "STAT", "name", "OS/2"
    )

    private val INVALID_RESULT = FontInspectionResult(
        isValidFont = false,
        isVariableFont = false,
        isCff2 = false,
        isCollection = false,
        axes = emptyList(),
        weightAxis = null,
        tables = emptySet()
    )

    fun inspect(file: File): FontInspectionResult {
        if (!file.exists() || !file.isFile || file.length() < 12L) {
            return INVALID_RESULT
        }
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                inspect(raf)
            }
        }.getOrElse { INVALID_RESULT }
    }

    fun inspect(bytes: ByteArray): FontInspectionResult {
        if (bytes.size < 12) return INVALID_RESULT
        return runCatching {
            inspectReader(ByteArrayFontReader(bytes))
        }.getOrElse { INVALID_RESULT }
    }

    fun inspect(raf: RandomAccessFile): FontInspectionResult {
        return runCatching {
            inspectReader(RandomAccessFileFontReader(raf))
        }.getOrElse { INVALID_RESULT }
    }

    /**
     * 将 Compose 字重 (100..900) 分段线性映射到字体 `wght` 轴范围：
     * - `100..400` 线性映射到 `minValue..defaultValue`
     * - `400..900` 线性映射到 `defaultValue..maxValue`
     * 最终结果钳制在 `[minValue, maxValue]` 范围内。
     */
    fun adaptWeightToAxis(
        composeWeight: Int,
        minValue: Float,
        defaultValue: Float,
        maxValue: Float
    ): Float {
        val low = minOf(minValue, maxValue)
        val high = maxOf(minValue, maxValue)
        if (low == high) return low

        val clampedDefault = defaultValue.coerceIn(low, high)
        val w = composeWeight.coerceIn(100, 900)
        val mapped = if (w <= 400) {
            val t = (w - 100) / 300f
            low + t * (clampedDefault - low)
        } else {
            val t = (w - 400) / 500f
            clampedDefault + t * (high - clampedDefault)
        }
        return mapped.coerceIn(low, high)
    }

    private fun inspectReader(reader: FontReader): FontInspectionResult {
        val totalLength = reader.length()
        if (totalLength < 12L) return INVALID_RESULT

        val magic = reader.readUInt32(0L)
        val isCollection: Boolean
        val sfntOffset: Long

        if (magic == TTC_TAG) {
            isCollection = true
            // TTC Header: tag(4) + majorVersion(2) + minorVersion(2) + numFonts(4) + offset[0](4)
            if (totalLength < 16L) return INVALID_RESULT
            val numFonts = reader.readUInt32(8L)
            if (numFonts <= 0L) return INVALID_RESULT
            sfntOffset = reader.readUInt32(12L)
            if (sfntOffset < 12L || sfntOffset + 12L > totalLength) return INVALID_RESULT
        } else {
            isCollection = false
            sfntOffset = 0L
        }

        val sfntVersion = reader.readUInt32(sfntOffset)
        if (sfntVersion != SFNT_TRUETYPE && sfntVersion != SFNT_TRUE && sfntVersion != SFNT_OTTO) {
            return INVALID_RESULT
        }

        val numTables = reader.readUInt16(sfntOffset + 4L)
        if (numTables <= 0 || numTables > 4096) return INVALID_RESULT

        val tableDirStart = sfntOffset + 12L
        val tableDirEnd = tableDirStart + numTables.toLong() * 16L
        if (tableDirEnd > totalLength) return INVALID_RESULT

        val foundTables = LinkedHashSet<String>()
        var fvarOffset = -1L
        var fvarLength = 0L

        for (i in 0 until numTables) {
            val recordOffset = tableDirStart + i.toLong() * 16L
            val tag = reader.readTag(recordOffset)
            if (tag in INTERESTING_TABLES) {
                foundTables.add(tag)
            }
            if (tag == "fvar") {
                fvarOffset = reader.readUInt32(recordOffset + 8L)
                fvarLength = reader.readUInt32(recordOffset + 12L)
            }
        }

        val axes = if (fvarOffset >= 0L && fvarLength >= 16L && fvarOffset + fvarLength <= totalLength) {
            parseFvarAxes(reader, fvarOffset, fvarLength)
        } else {
            emptyList()
        }

        val isCff2 = "CFF2" in foundTables
        val isVariableFont = "fvar" in foundTables && axes.isNotEmpty()
        val weightAxis = axes.firstOrNull { it.tag == "wght" }

        return FontInspectionResult(
            isValidFont = true,
            isVariableFont = isVariableFont,
            isCff2 = isCff2,
            isCollection = isCollection,
            axes = axes,
            weightAxis = weightAxis,
            tables = foundTables
        )
    }

    private fun parseFvarAxes(
        reader: FontReader,
        fvarOffset: Long,
        fvarLength: Long
    ): List<FontAxis> {
        val majorVersion = reader.readUInt16(fvarOffset)
        if (majorVersion != 1) return emptyList()

        val axesArrayOffset = reader.readUInt16(fvarOffset + 4L)
        val axisCount = reader.readUInt16(fvarOffset + 8L)
        val axisSize = reader.readUInt16(fvarOffset + 10L)

        if (axisCount <= 0 || axisSize < 20) return emptyList()

        val axesStart = fvarOffset + axesArrayOffset.toLong()
        val axesEnd = axesStart + axisCount.toLong() * axisSize.toLong()
        if (axesEnd > fvarOffset + fvarLength) return emptyList()

        val result = ArrayList<FontAxis>(axisCount)
        for (i in 0 until axisCount) {
            val entryOffset = axesStart + i.toLong() * axisSize.toLong()
            val tag = reader.readTag(entryOffset)
            val minValue = reader.readFixed1616(entryOffset + 4L)
            val defaultValue = reader.readFixed1616(entryOffset + 8L)
            val maxValue = reader.readFixed1616(entryOffset + 12L)
            result.add(
                FontAxis(
                    tag = tag,
                    minValue = minValue,
                    defaultValue = defaultValue,
                    maxValue = maxValue
                )
            )
        }
        return result
    }

    private interface FontReader {
        fun length(): Long
        fun readBytes(offset: Long, count: Int): ByteArray

        fun readUInt16(offset: Long): Int {
            val b = readBytes(offset, 2)
            return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        }

        fun readInt32(offset: Long): Int {
            val b = readBytes(offset, 4)
            return ((b[0].toInt() and 0xFF) shl 24) or
                ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or
                (b[3].toInt() and 0xFF)
        }

        fun readUInt32(offset: Long): Long {
            return readInt32(offset).toLong() and 0xFFFFFFFFL
        }

        fun readFixed1616(offset: Long): Float {
            return readInt32(offset) / 65536f
        }

        fun readTag(offset: Long): String {
            return String(readBytes(offset, 4), StandardCharsets.US_ASCII)
        }
    }

    private class ByteArrayFontReader(private val data: ByteArray) : FontReader {
        override fun length(): Long = data.size.toLong()

        override fun readBytes(offset: Long, count: Int): ByteArray {
            val start = offset.toInt()
            require(offset >= 0L && start + count <= data.size) {
                "Out of bounds read at $offset ($count bytes)"
            }
            return data.copyOfRange(start, start + count)
        }
    }

    private class RandomAccessFileFontReader(private val raf: RandomAccessFile) : FontReader {
        private val fileLength = raf.length()

        override fun length(): Long = fileLength

        override fun readBytes(offset: Long, count: Int): ByteArray {
            require(offset >= 0L && offset + count <= fileLength) {
                "Out of bounds read at $offset ($count bytes)"
            }
            val buf = ByteArray(count)
            raf.seek(offset)
            raf.readFully(buf)
            return buf
        }
    }
}
