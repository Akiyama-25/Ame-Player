package Akari.NCM.player.data

import Akari.NCM.player.core.font.FontAxis
import Akari.NCM.player.core.font.OpenTypeFontInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class OpenTypeFontInspectorTest {

    @Test
    fun `inspect static TTF returns valid non-variable result`() {
        val bytes = buildSfnt(
            sfntVersion = 0x00010000,
            tables = listOf(
                "OS/2" to ByteArray(8),
                "glyf" to ByteArray(16),
                "name" to ByteArray(8)
            )
        )

        val result = OpenTypeFontInspector.inspect(bytes)
        assertTrue(result.isValidFont)
        assertFalse(result.isVariableFont)
        assertFalse(result.isCff2)
        assertFalse(result.isCollection)
        assertTrue(result.axes.isEmpty())
        assertNull(result.weightAxis)
        assertTrue("glyf" in result.tables)
        assertTrue("OS/2" in result.tables)
        assertTrue("name" in result.tables)
    }

    @Test
    fun `inspect true sfnt magic is accepted`() {
        val bytes = buildSfnt(
            sfntVersion = 0x74727565, // 'true'
            tables = listOf("glyf" to ByteArray(8))
        )

        val result = OpenTypeFontInspector.inspect(bytes)
        assertTrue(result.isValidFont)
        assertFalse(result.isVariableFont)
        assertFalse(result.isCff2)
    }

    @Test
    fun `inspect OTTO CFF2 variable font parses fvar wght and opsz axes`() {
        val fvar = buildFvarTable(
            listOf(
                FontAxis("wght", 100f, 400f, 900f),
                FontAxis("opsz", 8f, 14.5f, 72f)
            )
        )
        val bytes = buildSfnt(
            sfntVersion = 0x4F54544F, // 'OTTO'
            tables = listOf(
                "CFF2" to ByteArray(12),
                "STAT" to ByteArray(8),
                "fvar" to fvar
            )
        )

        val result = OpenTypeFontInspector.inspect(bytes)
        assertTrue(result.isValidFont)
        assertTrue(result.isVariableFont)
        assertTrue(result.isCff2)
        assertFalse(result.isCollection)
        assertEquals(2, result.axes.size)
        assertEquals(FontAxis("wght", 100f, 400f, 900f), result.weightAxis)
        val opsz = result.axes[1]
        assertEquals("opsz", opsz.tag)
        assertEquals(8f, opsz.minValue, 0.001f)
        assertEquals(14.5f, opsz.defaultValue, 0.001f)
        assertEquals(72f, opsz.maxValue, 0.001f)
    }

    @Test
    fun `inspect ttcf collection reads first font offset and fvar`() {
        val fvar = buildFvarTable(listOf(FontAxis("wght", 200f, 400f, 700f)))
        // TTC header: 12 bytes + 1 offset (4 bytes) = 16 bytes
        val firstFontOffset = 16
        val singleFont = buildSfnt(
            sfntVersion = 0x00010000,
            tables = listOf(
                "fvar" to fvar,
                "glyf" to ByteArray(8)
            ),
            baseOffset = firstFontOffset
        )

        val ttc = ByteBuffer.allocate(firstFontOffset + singleFont.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putInt(0x74746366) // 'ttcf'
                putShort(1) // majorVersion
                putShort(0) // minorVersion
                putInt(1) // numFonts
                putInt(firstFontOffset)
                put(singleFont)
            }
            .array()

        val result = OpenTypeFontInspector.inspect(ttc)
        assertTrue(result.isValidFont)
        assertTrue(result.isCollection)
        assertTrue(result.isVariableFont)
        assertFalse(result.isCff2)
        assertNotNull(result.weightAxis)
        assertEquals(FontAxis("wght", 200f, 400f, 700f), result.weightAxis)
    }

    @Test
    fun `adaptWeightToAxis performs piecewise linear mapping and clamping`() {
        // 100..400 -> 200..400, 400..900 -> 400..800
        assertEquals(200f, OpenTypeFontInspector.adaptWeightToAxis(100, 200f, 400f, 800f), 0.01f)
        assertEquals(300f, OpenTypeFontInspector.adaptWeightToAxis(250, 200f, 400f, 800f), 0.01f)
        assertEquals(400f, OpenTypeFontInspector.adaptWeightToAxis(400, 200f, 400f, 800f), 0.01f)
        assertEquals(600f, OpenTypeFontInspector.adaptWeightToAxis(650, 200f, 400f, 800f), 0.01f)
        assertEquals(800f, OpenTypeFontInspector.adaptWeightToAxis(900, 200f, 400f, 800f), 0.01f)

        // Clamping beyond 100..900
        assertEquals(200f, OpenTypeFontInspector.adaptWeightToAxis(10, 200f, 400f, 800f), 0.01f)
        assertEquals(800f, OpenTypeFontInspector.adaptWeightToAxis(1200, 200f, 400f, 800f), 0.01f)

        // Degenerate range
        assertEquals(400f, OpenTypeFontInspector.adaptWeightToAxis(700, 400f, 400f, 400f), 0.01f)
    }

    @Test
    fun `adaptWeightToAxis applies nonlinear lowWeightBoost while anchoring bold weights`() {
        // Resource-Han-Rounded profile: wght 200..900, default 400, boost +50
        val min = 200f
        val def = 400f
        val max = 900f
        val boost = 50f

        assertEquals(200f, OpenTypeFontInspector.adaptWeightToAxis(100, min, def, max, boost), 0.01f)
        assertEquals(450f, OpenTypeFontInspector.adaptWeightToAxis(400, min, def, max, boost), 0.01f)
        assertEquals(533.33f, OpenTypeFontInspector.adaptWeightToAxis(500, min, def, max, boost), 0.1f)
        assertEquals(616.67f, OpenTypeFontInspector.adaptWeightToAxis(600, min, def, max, boost), 0.1f)

        // Bold/ExtraBold/Heavy weights remain strictly anchored
        assertEquals(700f, OpenTypeFontInspector.adaptWeightToAxis(700, min, def, max, boost), 0.01f)
        assertEquals(800f, OpenTypeFontInspector.adaptWeightToAxis(800, min, def, max, boost), 0.01f)
        assertEquals(900f, OpenTypeFontInspector.adaptWeightToAxis(900, min, def, max, boost), 0.01f)

        // Monotonically increasing across W100..W900 even at max boost
        val weights = (100..900 step 100).map { w ->
            OpenTypeFontInspector.adaptWeightToAxis(w, min, def, max, lowWeightBoost = 150f)
        }
        for (i in 0 until weights.size - 1) {
            assertTrue("Expected ${weights[i]} <= ${weights[i + 1]}", weights[i] <= weights[i + 1])
        }
    }

    @Test
    fun `inspect invalid or truncated bytes returns invalid result`() {
        assertFalse(OpenTypeFontInspector.inspect(ByteArray(0)).isValidFont)
        assertFalse(OpenTypeFontInspector.inspect(ByteArray(11)).isValidFont)
        assertFalse(OpenTypeFontInspector.inspect("NOT_A_FONT_FILE".toByteArray()).isValidFont)

        // Valid magic but truncated table directory
        val truncated = ByteBuffer.allocate(16)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putInt(0x00010000)
                putShort(5) // claims 5 tables -> needs 12 + 80 bytes
            }
            .array()
        assertFalse(OpenTypeFontInspector.inspect(truncated).isValidFont)

        // Invalid ttcf offset
        val badTtc = ByteBuffer.allocate(20)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                putInt(0x74746366)
                putShort(1)
                putShort(0)
                putInt(1)
                putInt(9999)
            }
            .array()
        assertFalse(OpenTypeFontInspector.inspect(badTtc).isValidFont)
    }

    private fun buildSfnt(
        sfntVersion: Int,
        tables: List<Pair<String, ByteArray>>,
        baseOffset: Int = 0
    ): ByteArray {
        val headerSize = 12 + tables.size * 16
        var currentOffset = baseOffset + headerSize
        val tableOffsets = IntArray(tables.size)
        for (i in tables.indices) {
            tableOffsets[i] = currentOffset
            currentOffset += tables[i].second.size
        }

        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(headerSize).order(ByteOrder.BIG_ENDIAN)
        header.putInt(sfntVersion)
        header.putShort(tables.size.toShort())
        header.putShort(16) // searchRange
        header.putShort(0)  // entrySelector
        header.putShort(0)  // rangeShift

        for (i in tables.indices) {
            val (tag, data) = tables[i]
            val tagBytes = tag.padEnd(4, ' ').take(4).toByteArray(StandardCharsets.US_ASCII)
            header.put(tagBytes)
            header.putInt(0) // checkSum
            header.putInt(tableOffsets[i])
            header.putInt(data.size)
        }
        out.write(header.array())
        for ((_, data) in tables) {
            out.write(data)
        }
        return out.toByteArray()
    }

    private fun buildFvarTable(axes: List<FontAxis>): ByteArray {
        val axesArrayOffset = 16
        val axisSize = 20
        val buf = ByteBuffer.allocate(axesArrayOffset + axes.size * axisSize)
            .order(ByteOrder.BIG_ENDIAN)
        buf.putShort(1) // majorVersion
        buf.putShort(0) // minorVersion
        buf.putShort(axesArrayOffset.toShort())
        buf.putShort(2) // reserved
        buf.putShort(axes.size.toShort())
        buf.putShort(axisSize.toShort())
        buf.putShort(0) // instanceCount
        buf.putShort(0) // instanceSize

        for (axis in axes) {
            buf.put(axis.tag.padEnd(4, ' ').take(4).toByteArray(StandardCharsets.US_ASCII))
            buf.putInt((axis.minValue * 65536f).toInt())
            buf.putInt((axis.defaultValue * 65536f).toInt())
            buf.putInt((axis.maxValue * 65536f).toInt())
            buf.putShort(0) // flags
            buf.putShort(256) // axisNameID
        }
        return buf.array()
    }
}
