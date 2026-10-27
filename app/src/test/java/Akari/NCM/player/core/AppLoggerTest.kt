package Akari.NCM.player.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLoggerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_logger_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        AppLogger.init(context)
        AppLogger.clearLogs()
    }

    @After
    fun tearDown() {
        AppLogger.clearLogs()
        File(context.cacheDir, "logs").deleteRecursively()
    }

    @Test
    fun testDefaultLogLevelFiltersLowerPriorityLogs() {
        // Default level is DEBUG
        assertEquals(LogLevel.DEBUG, AppLogger.currentLevel.value)

        AppLogger.v("TestTag", "Verbose message should be ignored")
        AppLogger.d("TestTag", "Debug message should be recorded")
        AppLogger.i("TestTag", "Info message should be recorded")
        AppLogger.w("TestTag", "Warn message should be recorded")
        AppLogger.e("TestTag", "Error message should be recorded")

        val content = AppLogger.getLogContent()
        assertFalse(content.contains("Verbose message should be ignored"))
        assertTrue(content.contains("[DEBUG] [TestTag]: Debug message should be recorded"))
        assertTrue(content.contains("[INFO] [TestTag]: Info message should be recorded"))
        assertTrue(content.contains("[WARN] [TestTag]: Warn message should be recorded"))
        assertTrue(content.contains("[ERROR] [TestTag]: Error message should be recorded"))
    }

    @Test
    fun testSetLogLevelAndPersistenceAcrossRestart() {
        AppLogger.setLogLevel(context, LogLevel.DEBUG)
        assertEquals(LogLevel.DEBUG, AppLogger.currentLevel.value)

        AppLogger.v("TestTag", "Verbose ignored at DEBUG")
        AppLogger.d("TestTag", "Debug recorded at DEBUG")
        AppLogger.i("TestTag", "Info recorded at DEBUG")

        val content = AppLogger.getLogContent()
        assertFalse(content.contains("Verbose ignored at DEBUG"))
        assertTrue(content.contains("[DEBUG] [TestTag]: Debug recorded at DEBUG"))
        assertTrue(content.contains("[INFO] [TestTag]: Info recorded at DEBUG"))

        // Simulate process restart by re-initializing from SharedPreferences
        AppLogger.setMaxFileSizeKb(context, 512L)
        AppLogger.init(context)
        assertEquals(LogLevel.DEBUG, AppLogger.currentLevel.value)
        assertEquals(512L, AppLogger.maxFileSizeKb.value)
    }

    @Test
    fun testLogRotationWhenExceedingMaxFileSize() {
        AppLogger.setLogLevel(context, LogLevel.INFO)
        // Minimum enforced size is 64 KB (65536 bytes)
        AppLogger.setMaxFileSizeKb(context, 64L)
        assertEquals(64L, AppLogger.maxFileSizeKb.value)

        val logDir = File(context.filesDir, "logs")
        val currentFile = File(logDir, "app_log.txt")
        val backupFile = File(logDir, "app_log.1.txt")

        // Write ~70 KB of logs to trigger rotation past 64 KB limit
        val chunk = "A".repeat(1024)
        for (i in 1..68) {
            AppLogger.i("RotateTest", "Line-$i-$chunk")
        }

        val combinedContent = AppLogger.getLogContent()
        assertTrue("Backup log file should exist after rotation", backupFile.exists())
        assertTrue("Backup log file should not be empty", backupFile.length() > 0L)
        assertTrue("Current log file should exist after rotation", currentFile.exists())
        assertTrue("Current log file should be smaller than max limit", currentFile.length() < 64L * 1024L)

        // Both pre-rotation and post-rotation entries should be preserved in getLogContent()
        assertTrue(combinedContent.contains("Line-1-"))
        assertTrue(combinedContent.contains("Line-68-"))
    }

    @Test
    fun testExportToLogMdAndZipFormats() {
        AppLogger.setLogLevel(context, LogLevel.VERBOSE)
        AppLogger.i("ExportTag", "First exportable log line")
        AppLogger.e("ExportTag", "Second exportable error line")

        // 1. Export as .log
        val logExport = AppLogger.exportToFormat(context, AppLogger.ExportFormat.LOG)
        assertNotNull(logExport)
        assertTrue(logExport!!.exists())
        assertEquals("log", logExport.extension)
        val rawLogText = logExport.readText()
        assertTrue(rawLogText.contains("First exportable log line"))
        assertTrue(rawLogText.contains("Second exportable error line"))

        // 2. Export as .md
        val mdExport = AppLogger.exportToFormat(context, AppLogger.ExportFormat.MD)
        assertNotNull(mdExport)
        assertTrue(mdExport!!.exists())
        assertEquals("md", mdExport.extension)
        val mdText = mdExport.readText()
        assertTrue(mdText.contains("# Ame Player 运行日志"))
        assertTrue(mdText.contains("- 当前设置等级: VERBOSE"))
        assertTrue(mdText.contains("```log"))
        assertTrue(mdText.contains("First exportable log line"))

        // 3. Export as .zip
        val zipExport = AppLogger.exportToFormat(context, AppLogger.ExportFormat.ZIP)
        assertNotNull(zipExport)
        assertTrue(zipExport!!.exists())
        assertEquals("zip", zipExport.extension)

        var entryCount = 0
        var unzippedContent = ""
        ZipInputStream(FileInputStream(zipExport)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryCount++
                assertTrue(entry.name.endsWith(".log"))
                unzippedContent = zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        assertEquals(1, entryCount)
        assertTrue(unzippedContent.contains("First exportable log line"))
        assertTrue(unzippedContent.contains("Second exportable error line"))
    }
}
