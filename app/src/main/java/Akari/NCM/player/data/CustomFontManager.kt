package Akari.NCM.player.data

import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.font.FontInspectionResult
import Akari.NCM.player.core.font.OpenTypeFontInspector
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.annotation.FontRes
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

enum class FontSource {
    SYSTEM_DEFAULT,
    PRESET,
    IMPORTED,
    /** 仅用于兼容旧版本持久化的 system:... 选中态，不再参与系统目录扫描 */
    SYSTEM_FILE
}

data class PresetFontConfig(
    val id: String,
    val displayName: String,
    val subtitle: String = "预设字体",
    val candidatePaths: List<String> = emptyList(),
    @FontRes val resId: Int? = null
)

data class FontItemInfo(
    val id: String,
    val displayName: String,
    val filePath: String?,
    val source: FontSource,
    val inspection: FontInspectionResult?,
    val subtitle: String? = null,
    @FontRes val resId: Int? = null
)

/**
 * 全局自定义字体管理器。
 *
 * 支持：
 * - 展示「系统默认 + 显式配置的预设字体 + 用户手动导入字体 (`filesDir/custom_fonts`)」
 * - 通过 SAF `Uri` 导入并校验 `.ttf/.otf/.ttc/.otc` 字体
 * - 可变字体 `wght` 轴 9 档字重映射 (`W100..W900`) 与安全回退
 * - 持久化已选字体 ID 并通过 `StateFlow` 驱动 `AmePlayerTheme`
 */
object CustomFontManager {
    private const val TAG = "CustomFontManager"
    private const val PREFS_NAME = "ame_font_prefs"
    private const val KEY_SELECTED_FONT_ID = "selected_font_id"
    private const val CUSTOM_FONTS_DIR = "custom_fonts"
    const val SYSTEM_DEFAULT_ID = "system_default"

    private val SUPPORTED_EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")
    private val DEFAULT_PRESET_FONTS: List<PresetFontConfig> = emptyList()

    @VisibleForTesting
    internal var presetFontsProvider: () -> List<PresetFontConfig> = { DEFAULT_PRESET_FONTS }

    private val COMPOSE_WEIGHTS = listOf(
        FontWeight.W100,
        FontWeight.W200,
        FontWeight.W300,
        FontWeight.W400,
        FontWeight.W500,
        FontWeight.W600,
        FontWeight.W700,
        FontWeight.W800,
        FontWeight.W900
    )

    @Volatile
    private var initialized = false
    private var prefs: SharedPreferences? = null

    private val _currentFontFamilyFlow = MutableStateFlow<FontFamily?>(null)
    val currentFontFamilyFlow: StateFlow<FontFamily?> = _currentFontFamilyFlow.asStateFlow()

    private val _selectedFontIdFlow = MutableStateFlow<String?>(null)
    val selectedFontIdFlow: StateFlow<String?> = _selectedFontIdFlow.asStateFlow()

    private val _selectedFontInfoFlow = MutableStateFlow<FontItemInfo?>(null)
    val selectedFontInfoFlow: StateFlow<FontItemInfo?> = _selectedFontInfoFlow.asStateFlow()

    private val _importedFontsFlow = MutableStateFlow<List<FontItemInfo>>(emptyList())
    val importedFontsFlow: StateFlow<List<FontItemInfo>> = _importedFontsFlow.asStateFlow()

    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(this) {
            initialized = false
            prefs = null
            presetFontsProvider = { DEFAULT_PRESET_FONTS }
            _currentFontFamilyFlow.value = null
            _selectedFontIdFlow.value = null
            _selectedFontInfoFlow.value = null
            _importedFontsFlow.value = emptyList()
        }
    }

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            val sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = sp

            val imported = scanImportedFonts(appCtx)
            _importedFontsFlow.value = imported

            val savedId = sp.getString(KEY_SELECTED_FONT_ID, null)
            applySelectionInternal(appCtx, savedId, persist = false)
            initialized = true
        }
    }

    fun getAvailableFonts(context: Context): List<FontItemInfo> {
        init(context)
        val presetFonts = loadPresetFonts()
        val imported = scanImportedFonts(context.applicationContext)
        _importedFontsFlow.value = imported

        val result = ArrayList<FontItemInfo>(1 + presetFonts.size + imported.size)
        result.add(systemDefaultInfo())
        result.addAll(presetFonts)
        result.addAll(imported)
        return result
    }

    fun importFontFromUri(context: Context, uri: Uri): Result<FontItemInfo> {
        init(context)
        val appCtx = context.applicationContext
        return runCatching {
            val rawName = resolveDisplayName(appCtx, uri) ?: "custom_font.ttf"
            val ext = rawName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext.isNotEmpty() && ext !in SUPPORTED_EXTENSIONS) {
                throw IllegalArgumentException("不支持的字体格式: .$ext")
            }

            val dir = customFontsDir(appCtx)
            val tempFile = File.createTempFile("import_", ".tmp", dir)
            try {
                appCtx.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("无法读取所选字体文件")

                val inspection = OpenTypeFontInspector.inspect(tempFile)
                if (!inspection.isValidFont) {
                    throw IllegalArgumentException("无效的 OpenType/TrueType 字体文件")
                }

                val finalExt = if (ext in SUPPORTED_EXTENSIONS) {
                    ext
                } else when {
                    inspection.isCollection -> "ttc"
                    inspection.isCff2 -> "otf"
                    else -> "ttf"
                }

                val baseName = sanitizeFileName(rawName.substringBeforeLast('.', rawName))
                    .ifBlank { "custom_font" }
                val targetFile = uniqueTargetFile(dir, baseName, finalExt)
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }

                val item = buildImportedFontInfo(targetFile, inspection)
                _importedFontsFlow.value = scanImportedFonts(appCtx)
                applySelectionInternal(appCtx, item.id, persist = true)
                AppLogger.i(TAG, "Imported font: ${item.displayName} (${targetFile.name})")
                item
            } catch (t: Throwable) {
                tempFile.delete()
                throw t
            }
        }
    }

    fun deleteImportedFont(context: Context, id: String) {
        init(context)
        val appCtx = context.applicationContext
        val imported = _importedFontsFlow.value
        val target = imported.firstOrNull { it.id == id }
        val file = target?.filePath?.let { File(it) }
            ?: File(customFontsDir(appCtx), id.removePrefix("imported:"))
        if (file.exists()) {
            file.delete()
        }
        _importedFontsFlow.value = scanImportedFonts(appCtx)
        if (_selectedFontIdFlow.value == id) {
            selectFont(appCtx, null)
        }
    }

    fun selectFont(context: Context, id: String?) {
        init(context)
        applySelectionInternal(context.applicationContext, id, persist = true)
    }

    private fun applySelectionInternal(context: Context, rawId: String?, persist: Boolean) {
        val normalizedId = rawId?.takeIf { it.isNotBlank() && it != SYSTEM_DEFAULT_ID }
        if (normalizedId == null) {
            _selectedFontIdFlow.value = null
            _selectedFontInfoFlow.value = systemDefaultInfo()
            _currentFontFamilyFlow.value = null
            if (persist) {
                prefs?.edit()?.remove(KEY_SELECTED_FONT_ID)?.apply()
            }
            return
        }

        val info = findFontById(context, normalizedId)
        val file = info?.filePath?.let { File(it) }?.takeIf { it.exists() && it.isFile }
        val resId = info?.resId
        if (info == null || (file == null && resId == null)) {
            _selectedFontIdFlow.value = null
            _selectedFontInfoFlow.value = systemDefaultInfo()
            _currentFontFamilyFlow.value = null
            if (persist) {
                prefs?.edit()?.remove(KEY_SELECTED_FONT_ID)?.apply()
            }
            return
        }

        val family = buildComposeFontFamily(file = file, resId = resId, inspection = info.inspection)
        if (family == null) {
            _selectedFontIdFlow.value = null
            _selectedFontInfoFlow.value = systemDefaultInfo()
            _currentFontFamilyFlow.value = null
            if (persist) {
                prefs?.edit()?.remove(KEY_SELECTED_FONT_ID)?.apply()
            }
            return
        }

        _selectedFontIdFlow.value = info.id
        _selectedFontInfoFlow.value = info
        _currentFontFamilyFlow.value = family
        if (persist) {
            prefs?.edit()?.putString(KEY_SELECTED_FONT_ID, info.id)?.apply()
        }
    }

    private fun findFontById(context: Context, id: String): FontItemInfo? {
        _importedFontsFlow.value.firstOrNull { it.id == id }?.let { return it }

        if (id.startsWith("preset:")) {
            loadPresetFonts().firstOrNull { it.id == id }?.let { return it }
        }

        if (id.startsWith("imported:")) {
            val file = File(customFontsDir(context), id.removePrefix("imported:"))
            if (file.exists() && file.isFile) {
                val inspection = OpenTypeFontInspector.inspect(file)
                if (inspection.isValidFont) return buildImportedFontInfo(file, inspection)
            }
        }

        // 兼容旧版本持久化的 system:... 选中态（单文件定向查找，不再做全量系统扫描）
        if (id.startsWith("system:")) {
            val file = File(id.removePrefix("system:"))
            if (file.exists() && file.isFile) {
                val inspection = OpenTypeFontInspector.inspect(file)
                if (inspection.isValidFont) return buildLegacySystemFontInfo(file, inspection)
            }
        }

        return null
    }

    private fun buildComposeFontFamily(
        file: File?,
        @FontRes resId: Int?,
        inspection: FontInspectionResult?
    ): FontFamily? {
        if (resId != null) {
            return runCatching {
                FontFamily(Font(resId = resId))
            }.onFailure { err ->
                AppLogger.w(TAG, "Resource FontFamily build failed for resId=$resId: ${err.message}")
            }.getOrNull()
        }

        if (file == null) return null

        val weightAxis = inspection?.weightAxis
        if (weightAxis != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !inspection.isCollection) {
            val variableFamily = runCatching {
                val fonts = COMPOSE_WEIGHTS.map { composeWeight ->
                    val adapted = OpenTypeFontInspector.adaptWeightToAxis(
                        composeWeight = composeWeight.weight,
                        minValue = weightAxis.minValue,
                        defaultValue = weightAxis.defaultValue,
                        maxValue = weightAxis.maxValue
                    ).roundToInt().coerceIn(1, 1000)
                    Font(
                        file = file,
                        weight = composeWeight,
                        variationSettings = FontVariation.Settings(
                            FontVariation.weight(adapted)
                        )
                    )
                }
                FontFamily(fonts)
            }.onFailure { err ->
                AppLogger.w(TAG, "Variable FontFamily build failed for ${file.name}, falling back: ${err.message}")
            }.getOrNull()

            if (variableFamily != null) return variableFamily
        }

        return runCatching {
            FontFamily(Font(file = file))
        }.onFailure { err ->
            AppLogger.w(TAG, "Static FontFamily build failed for ${file.name}: ${err.message}")
        }.getOrNull()
    }

    private fun loadPresetFonts(): List<FontItemInfo> {
        val configs = presetFontsProvider()
        if (configs.isEmpty()) return emptyList()

        val result = ArrayList<FontItemInfo>(configs.size)
        for (config in configs) {
            var matchedFile: File? = null
            var matchedInspection: FontInspectionResult? = null
            for (path in config.candidatePaths) {
                val candidate = File(path)
                if (!candidate.exists() || !candidate.isFile) continue
                val inspection = OpenTypeFontInspector.inspect(candidate)
                if (inspection.isValidFont) {
                    matchedFile = candidate
                    matchedInspection = inspection
                    break
                }
            }

            if (matchedFile != null && matchedInspection != null) {
                result.add(
                    FontItemInfo(
                        id = config.id,
                        displayName = config.displayName,
                        filePath = matchedFile.absolutePath,
                        source = FontSource.PRESET,
                        inspection = matchedInspection,
                        subtitle = config.subtitle
                    )
                )
            } else if (config.resId != null) {
                result.add(
                    FontItemInfo(
                        id = config.id,
                        displayName = config.displayName,
                        filePath = null,
                        source = FontSource.PRESET,
                        inspection = null,
                        subtitle = config.subtitle,
                        resId = config.resId
                    )
                )
            }
        }
        return result
    }

    private fun scanImportedFonts(context: Context): List<FontItemInfo> {
        val dir = customFontsDir(context)
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && hasSupportedExtension(it) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .mapNotNull { file ->
                val inspection = OpenTypeFontInspector.inspect(file)
                if (inspection.isValidFont) buildImportedFontInfo(file, inspection) else null
            }
            .toList()
    }

    private fun buildImportedFontInfo(
        file: File,
        inspection: FontInspectionResult
    ): FontItemInfo {
        return FontItemInfo(
            id = "imported:${file.name}",
            displayName = file.nameWithoutExtension,
            filePath = file.absolutePath,
            source = FontSource.IMPORTED,
            inspection = inspection,
            subtitle = "已导入本地字体"
        )
    }

    private fun buildLegacySystemFontInfo(
        file: File,
        inspection: FontInspectionResult
    ): FontItemInfo {
        return FontItemInfo(
            id = "system:${file.absolutePath}",
            displayName = file.nameWithoutExtension,
            filePath = file.absolutePath,
            source = FontSource.SYSTEM_FILE,
            inspection = inspection,
            subtitle = file.absolutePath
        )
    }

    private fun systemDefaultInfo(): FontItemInfo {
        return FontItemInfo(
            id = SYSTEM_DEFAULT_ID,
            displayName = "系统默认",
            filePath = null,
            source = FontSource.SYSTEM_DEFAULT,
            inspection = null,
            subtitle = "使用系统与 Compose 默认字体排版"
        )
    }

    private fun customFontsDir(context: Context): File {
        val dir = File(context.filesDir, CUSTOM_FONTS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun hasSupportedExtension(file: File): Boolean {
        val ext = file.extension.lowercase(Locale.ROOT)
        return ext in SUPPORTED_EXTENSIONS
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx)
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5 ]"), "_")
            .trim()
            .take(80)
    }

    private fun uniqueTargetFile(dir: File, baseName: String, ext: String): File {
        var candidate = File(dir, "$baseName.$ext")
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "${baseName}_$index.$ext")
            index++
        }
        return candidate
    }
}
