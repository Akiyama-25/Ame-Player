package Akari.NCM.player.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class LogLevel(val priority: Int, val label: String) {
    VERBOSE(2, "VERBOSE"),
    DEBUG(3, "DEBUG"),
    INFO(4, "INFO"),
    WARN(5, "WARN"),
    ERROR(6, "ERROR");

    companion object {
        fun fromString(value: String): LogLevel {
            return entries.find { it.name.equals(value, ignoreCase = true) || it.label.equals(value, ignoreCase = true) } ?: ERROR
        }
    }
}

object AppLogger {
    private const val PREFS_NAME = "app_logger_prefs"
    private const val KEY_LOG_LEVEL = "log_level"
    private const val KEY_MAX_FILE_SIZE_KB = "max_file_size_kb"
    private const val DEFAULT_MAX_SIZE_KB = 1024L
    private const val LOG_FILE_NAME = "app_log.txt"

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())
    private var logDir: File? = null
    private var logFile: File? = null
    private var crashHandlerInstalled = false

    private val _currentLevel = MutableStateFlow(LogLevel.DEBUG)
    val currentLevel: StateFlow<LogLevel> = _currentLevel.asStateFlow()

    private val _maxFileSizeKb = MutableStateFlow(DEFAULT_MAX_SIZE_KB)
    val maxFileSizeKb: StateFlow<Long> = _maxFileSizeKb.asStateFlow()

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val levelStr = prefs.getString(KEY_LOG_LEVEL, LogLevel.DEBUG.name) ?: LogLevel.DEBUG.name
        _currentLevel.value = LogLevel.fromString(levelStr)
        _maxFileSizeKb.value = prefs.getLong(KEY_MAX_FILE_SIZE_KB, DEFAULT_MAX_SIZE_KB)

        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        logDir = dir
        logFile = File(dir, LOG_FILE_NAME)

        if (!crashHandlerInstalled) {
            crashHandlerInstalled = true
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                e("CrashHandler", "Uncaught exception in thread '${thread.name}': ${throwable.message}", throwable)
                flush()
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun flush() {
        runBlocking {
            val children = scope.coroutineContext[Job]?.children?.toList().orEmpty()
            children.forEach { it.join() }
        }
    }

    fun setLogLevel(context: Context, level: LogLevel) {
        _currentLevel.value = level
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOG_LEVEL, level.name)
            .apply()
    }

    fun setMaxFileSizeKb(context: Context, sizeKb: Long) {
        val validSize = if (sizeKb < 64L) 64L else sizeKb
        _maxFileSizeKb.value = validSize
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_MAX_FILE_SIZE_KB, validSize)
            .apply()
        flush()
        checkAndRotateLogFile()
    }

    fun v(tag: String, message: String, throwable: Throwable? = null): Int {
        log(LogLevel.VERBOSE, tag, message, throwable)
        return 0
    }

    fun d(tag: String, message: String, throwable: Throwable? = null): Int {
        log(LogLevel.DEBUG, tag, message, throwable)
        return 0
    }

    fun i(tag: String, message: String, throwable: Throwable? = null): Int {
        log(LogLevel.INFO, tag, message, throwable)
        return 0
    }

    fun w(tag: String, message: String, throwable: Throwable? = null): Int {
        log(LogLevel.WARN, tag, message, throwable)
        return 0
    }

    fun w(tag: String, throwable: Throwable): Int {
        return w(tag, throwable.message ?: "", throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null): Int {
        log(LogLevel.ERROR, tag, message, throwable)
        return 0
    }

    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, message, throwable)
            LogLevel.DEBUG -> Log.d(tag, message, throwable)
            LogLevel.INFO -> Log.i(tag, message, throwable)
            LogLevel.WARN -> Log.w(tag, message, throwable)
            LogLevel.ERROR -> Log.e(tag, message, throwable)
        }

        if (level.priority < _currentLevel.value.priority) {
            return
        }

        val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val stackTrace = throwable?.let { "\n" + Log.getStackTraceString(it) } ?: ""
        val line = "[$timestamp] [${level.label}] [$tag]: $message$stackTrace\n"

        scope.launch {
            writeLogLine(line)
        }
    }

    @Synchronized
    private fun writeLogLine(line: String) {
        val file = logFile ?: return
        try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            }
            val maxBytes = _maxFileSizeKb.value * 1024L
            if (file.length() + line.toByteArray().size > maxBytes) {
                rotateFile(file)
            }
            FileWriter(file, true).use { writer ->
                writer.write(line)
            }
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to write log line: ${e.message}")
        }
    }

    @Synchronized
    private fun checkAndRotateLogFile() {
        val file = logFile ?: return
        if (file.exists() && file.length() > _maxFileSizeKb.value * 1024L) {
            rotateFile(file)
        }
    }

    private fun rotateFile(file: File) {
        try {
            val oldBackup = File(file.parentFile, "app_log.1.txt")
            if (oldBackup.exists()) {
                oldBackup.delete()
            }
            file.renameTo(oldBackup)
            file.createNewFile()
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to rotate log file: ${e.message}")
        }
    }

    fun getLogContent(): String {
        flush()
        return readLogContentInternal()
    }

    @Synchronized
    private fun readLogContentInternal(): String {
        val file = logFile
        if (file == null || !file.exists()) return "暂无日志记录"
        val oldBackup = File(file.parentFile, "app_log.1.txt")
        val sb = StringBuilder()
        if (oldBackup.exists()) {
            try {
                sb.append(oldBackup.readText())
            } catch (e: Exception) {
                Log.e("AppLogger", "Failed to read backup log: ${e.message}")
            }
        }
        try {
            sb.append(file.readText())
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to read current log: ${e.message}")
        }
        return if (sb.isEmpty()) "暂无日志记录" else sb.toString()
    }

    fun clearLogs() {
        flush()
        synchronized(this) {
            val dir = logDir ?: return
            dir.listFiles()?.forEach { it.delete() }
            logFile = File(dir, LOG_FILE_NAME)
        }
    }

    fun exportToFormat(context: Context, format: ExportFormat): File? {
        val dir = File(context.cacheDir, "logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val content = getLogContent()

        return when (format) {
            ExportFormat.LOG -> {
                val outFile = File(dir, "ame_player_log_$timestamp.log")
                outFile.writeText(content)
                outFile
            }
            ExportFormat.MD -> {
                val outFile = File(dir, "ame_player_log_$timestamp.md")
                val mdContent = buildString {
                    appendLine("# Ame Player 运行日志")
                    appendLine()
                    appendLine("- 导出时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                    appendLine("- 当前设置等级: ${_currentLevel.value.label}")
                    appendLine("- 大小限制: ${_maxFileSizeKb.value} KB")
                    appendLine()
                    appendLine("## 日志内容")
                    appendLine()
                    appendLine("```log")
                    appendLine(content)
                    appendLine("```")
                }
                outFile.writeText(mdContent)
                outFile
            }
            ExportFormat.ZIP -> {
                val outFile = File(dir, "ame_player_log_$timestamp.zip")
                val logText = content
                try {
                    ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                        val entry = ZipEntry("ame_player_log_$timestamp.log")
                        zos.putNextEntry(entry)
                        zos.write(logText.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                    outFile
                } catch (e: Exception) {
                    Log.e("AppLogger", "Failed to export zip log: ${e.message}")
                    null
                }
            }
        }
    }

    enum class ExportFormat(val extension: String, val mimeType: String) {
        LOG("log", "text/plain"),
        MD("md", "text/markdown"),
        ZIP("zip", "application/zip")
    }
}