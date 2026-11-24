package Akari.NCM.player.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomFontManagerTest {

    private lateinit var context: Context
    private lateinit var customFontsDir: File
    private lateinit var tempPresetDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ame_font_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        customFontsDir = File(context.filesDir, "custom_fonts")
        customFontsDir.deleteRecursively()
        tempPresetDir = File(context.cacheDir, "test_preset_fonts")
        tempPresetDir.deleteRecursively()
        tempPresetDir.mkdirs()
        CustomFontManager.resetForTest()
    }

    @After
    fun tearDown() {
        CustomFontManager.resetForTest()
        customFontsDir.deleteRecursively()
        tempPresetDir.deleteRecursively()
    }

    @Test
    fun `getAvailableFonts does not scan system font directories`() {
        val fonts = CustomFontManager.getAvailableFonts(context)

        assertEquals(1, fonts.size)
        assertEquals(CustomFontManager.SYSTEM_DEFAULT_ID, fonts.first().id)
        assertEquals(FontSource.SYSTEM_DEFAULT, fonts.first().source)
        assertTrue(fonts.none { it.source == FontSource.SYSTEM_FILE })
    }

    @Test
    fun `getAvailableFonts without presets returns system default and imported fonts only`() {
        customFontsDir.mkdirs()
        val validFont = File(customFontsDir, "HarmonyOS_Sans.ttf")
        validFont.writeBytes(buildMinimalValidTtf())
        val invalidFont = File(customFontsDir, "corrupted.ttf")
        invalidFont.writeText("not a valid font")

        val fonts = CustomFontManager.getAvailableFonts(context)

        assertEquals(2, fonts.size)
        assertEquals(FontSource.SYSTEM_DEFAULT, fonts[0].source)
        assertEquals("imported:HarmonyOS_Sans.ttf", fonts[1].id)
        assertEquals("HarmonyOS_Sans", fonts[1].displayName)
        assertEquals(FontSource.IMPORTED, fonts[1].source)
        assertTrue(fonts.none { it.source == FontSource.SYSTEM_FILE })
    }

    @Test
    fun `getAvailableFonts includes valid preset and ignores missing preset files`() {
        val existingPresetFile = File(tempPresetDir, "MiSans.ttf")
        existingPresetFile.writeBytes(buildMinimalValidTtf())
        val missingPresetPath = File(tempPresetDir, "DoesNotExist.ttf").absolutePath

        CustomFontManager.presetFontsProvider = {
            listOf(
                PresetFontConfig(
                    id = "preset:misans",
                    displayName = "MiSans",
                    subtitle = "系统预设字体",
                    candidatePaths = listOf(missingPresetPath, existingPresetFile.absolutePath)
                ),
                PresetFontConfig(
                    id = "preset:missing",
                    displayName = "MissingFont",
                    subtitle = "不存在的预设",
                    candidatePaths = listOf(missingPresetPath)
                )
            )
        }

        val fonts = CustomFontManager.getAvailableFonts(context)

        assertEquals(2, fonts.size)
        assertEquals(FontSource.SYSTEM_DEFAULT, fonts[0].source)
        assertEquals("preset:misans", fonts[1].id)
        assertEquals("MiSans", fonts[1].displayName)
        assertEquals("系统预设字体", fonts[1].subtitle)
        assertEquals(FontSource.PRESET, fonts[1].source)
        assertEquals(existingPresetFile.absolutePath, fonts[1].filePath)
    }

    @Test
    fun `legacy system font selection falls back safely to system default when file missing`() {
        context.getSharedPreferences("ame_font_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("selected_font_id", "system:/system/fonts/NonExistentLegacyFont.ttf")
            .commit()

        CustomFontManager.init(context)

        assertNull(CustomFontManager.selectedFontIdFlow.value)
        assertEquals(FontSource.SYSTEM_DEFAULT, CustomFontManager.selectedFontInfoFlow.value?.source)
        assertNull(CustomFontManager.currentFontFamilyFlow.value)
    }

    private fun buildMinimalValidTtf(): ByteArray {
        val tables = listOf(
            "OS/2" to ByteArray(8),
            "glyf" to ByteArray(16),
            "name" to ByteArray(8)
        )
        val headerSize = 12 + tables.size * 16
        val body = ByteArrayOutputStream()
        val directory = ByteBuffer.allocate(headerSize).order(ByteOrder.BIG_ENDIAN)
        directory.putInt(0x00010000)
        directory.putShort(tables.size.toShort())
        directory.putShort(0)
        directory.putShort(0)
        directory.putShort(0)

        var currentOffset = headerSize
        for ((tag, data) in tables) {
            directory.put(tag.toByteArray(StandardCharsets.US_ASCII), 0, 4)
            directory.putInt(0)
            directory.putInt(currentOffset)
            directory.putInt(data.size)
            body.write(data)
            val pad = (4 - (data.size % 4)) % 4
            repeat(pad) { body.write(0) }
            currentOffset += data.size + pad
        }
        return directory.array() + body.toByteArray()
    }
}
