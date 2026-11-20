package Akari.NCM.player.data

import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.loudness.EbuR128Calculator
import Akari.NCM.player.core.loudness.LoudnessData
import Akari.NCM.player.core.loudness.LoudnessSource
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlin.math.abs

/**
 * P0 本地 SQLite 响度缓存数据库
 *
 * 主键结构：(sourcePlatform, songId, qualityLevel, fileSignature[md5/size])
 * 存储字段：estimatedLufs、sampledTruePeak、targetGainDb（基准 -14 LUFS）、source、updatedAt
 * 容量策略：LRU 上限 10,000 条记录，自动淘汰最久未访问条目。
 */
class LoudnessCacheDatabase(
    context: Context,
    dbName: String = DB_NAME,
    private val maxEntries: Int = MAX_CACHE_ENTRIES
) : SQLiteOpenHelper(context, dbName, null, DB_VERSION) {

    companion object {
        private const val TAG = "AME_LOUDNESS_DB"
        const val DB_NAME = "ame_loudness_cache.db"
        private const val DB_VERSION = 2
        const val MAX_CACHE_ENTRIES = 10_000

        private const val TABLE_NAME = "loudness_cache"
        private const val COL_CACHE_KEY = "cache_key"
        private const val COL_SOURCE_PLATFORM = "source_platform"
        private const val COL_SONG_ID = "song_id"
        private const val COL_QUALITY_LEVEL = "quality_level"
        private const val COL_FILE_SIGNATURE = "file_signature"
        private const val COL_ESTIMATED_LUFS = "estimated_lufs"
        private const val COL_SAMPLED_TRUE_PEAK = "sampled_true_peak"
        private const val COL_TARGET_GAIN_DB = "target_gain_db"
        private const val COL_SOURCE = "source"
        private const val COL_UPDATED_AT = "updated_at"

        fun buildCacheKey(
            sourcePlatform: String,
            songId: String,
            qualityLevel: String = "",
            fileSignature: String = ""
        ): String {
            return "${sourcePlatform.lowercase().trim()}|${songId.trim()}|${qualityLevel.lowercase().trim()}|${fileSignature.trim()}"
        }
    }

    data class CacheRecord(
        val sourcePlatform: String,
        val songId: String,
        val qualityLevel: String,
        val fileSignature: String,
        val loudnessData: LoudnessData,
        val targetGainDb: Float,
        val updatedAt: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_CACHE_KEY TEXT PRIMARY KEY,
                $COL_SOURCE_PLATFORM TEXT NOT NULL,
                $COL_SONG_ID TEXT NOT NULL,
                $COL_QUALITY_LEVEL TEXT NOT NULL,
                $COL_FILE_SIGNATURE TEXT NOT NULL,
                $COL_ESTIMATED_LUFS REAL NOT NULL,
                $COL_SAMPLED_TRUE_PEAK REAL NOT NULL,
                $COL_TARGET_GAIN_DB REAL NOT NULL,
                $COL_SOURCE TEXT NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_loudness_song_id ON $TABLE_NAME ($COL_SONG_ID, $COL_UPDATED_AT DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_loudness_updated_at ON $TABLE_NAME ($COL_UPDATED_AT ASC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2 完成了网易云 gain (-18.0 LUFS 基准) 与分音质 vd 标度校准，清理旧版未校准云端条目以便重新获取
            runCatching {
                db.delete(
                    TABLE_NAME,
                    "$COL_SOURCE IN (?, ?)",
                    arrayOf(LoudnessSource.NETEASE_ONLINE.name, LoudnessSource.NETEASE_VD.name)
                )
            }
        }
    }

    @Synchronized
    fun put(
        sourcePlatform: String,
        songId: String,
        qualityLevel: String = "",
        fileSignature: String = "",
        data: LoudnessData,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        if (!data.source.isKnownValid || data.source == LoudnessSource.QUEUE_AVERAGE) return
        val key = buildCacheKey(sourcePlatform, songId, qualityLevel, fileSignature)
        try {
            val db = writableDatabase
            val existing = queryByKeyInternal(db, key)
            if (existing != null && existing.loudnessData.source.priority > data.source.priority) {
                return
            }

            val refGain = EbuR128Calculator.computeGain(
                loudness = data,
                targetLufs = EbuR128Calculator.TARGET_STREAMING_LUFS,
                peakLimitEnabled = true
            ).effectiveGainDb

            val values = ContentValues().apply {
                put(COL_CACHE_KEY, key)
                put(COL_SOURCE_PLATFORM, sourcePlatform.lowercase().trim())
                put(COL_SONG_ID, songId.trim())
                put(COL_QUALITY_LEVEL, qualityLevel.lowercase().trim())
                put(COL_FILE_SIGNATURE, fileSignature.trim())
                put(COL_ESTIMATED_LUFS, data.integratedLufs)
                put(COL_SAMPLED_TRUE_PEAK, data.truePeak)
                put(COL_TARGET_GAIN_DB, refGain)
                put(COL_SOURCE, data.source.name)
                put(COL_UPDATED_AT, timestampMs)
            }
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            pruneLruIfNeeded(db)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to write loudness record ($key): ${e.message}")
        }
    }

    @Synchronized
    fun get(
        sourcePlatform: String,
        songId: String,
        qualityLevel: String = "",
        fileSignature: String = ""
    ): CacheRecord? {
        return try {
            val db = writableDatabase
            val exactKey = buildCacheKey(sourcePlatform, songId, qualityLevel, fileSignature)
            var record = queryByKeyInternal(db, exactKey)

            // 若未指定 fileSignature / qualityLevel，或精确键未命中时，按 songId 降级查找最高优先级有效记录
            if (record == null && (qualityLevel.isBlank() || fileSignature.isBlank())) {
                record = queryBestBySongIdInternal(db, songId.trim(), sourcePlatform.lowercase().trim(), qualityLevel.lowercase().trim())
            }

            if (record != null) {
                val now = System.currentTimeMillis()
                val matchedKey = buildCacheKey(record.sourcePlatform, record.songId, record.qualityLevel, record.fileSignature)
                val touch = ContentValues().apply { put(COL_UPDATED_AT, now) }
                db.update(TABLE_NAME, touch, "$COL_CACHE_KEY = ?", arrayOf(matchedKey))
            }
            record
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to read loudness record (songId=$songId): ${e.message}")
            null
        }
    }

    @Synchronized
    fun getAllRecent(limit: Int = 2000): List<CacheRecord> {
        val result = mutableListOf<CacheRecord>()
        try {
            val db = readableDatabase
            db.query(
                TABLE_NAME,
                null,
                null,
                null,
                null,
                null,
                "$COL_UPDATED_AT DESC",
                limit.toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    parseRow(cursor)?.let { result.add(it) }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to load recent loudness records: ${e.message}")
        }
        return result
    }

    @Synchronized
    fun count(): Int {
        return try {
            val db = readableDatabase
            db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        } catch (_: Exception) {
            0
        }
    }

    @Synchronized
    fun clearAll() {
        try {
            writableDatabase.delete(TABLE_NAME, null, null)
        } catch (_: Exception) {}
    }

    private fun pruneLruIfNeeded(db: SQLiteDatabase) {
        val total = db.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        val excess = total - maxEntries
        if (excess > 0) {
            db.execSQL(
                """
                DELETE FROM $TABLE_NAME WHERE $COL_CACHE_KEY IN (
                    SELECT $COL_CACHE_KEY FROM $TABLE_NAME ORDER BY $COL_UPDATED_AT ASC LIMIT $excess
                )
                """.trimIndent()
            )
            AppLogger.d(TAG, "Pruned $excess least-recently-used loudness records (limit=$maxEntries)")
        }
    }

    private fun queryByKeyInternal(db: SQLiteDatabase, key: String): CacheRecord? {
        db.query(
            TABLE_NAME,
            null,
            "$COL_CACHE_KEY = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return parseRow(cursor)
            }
        }
        return null
    }

    private fun queryBestBySongIdInternal(
        db: SQLiteDatabase,
        songId: String,
        sourcePlatform: String,
        qualityLevel: String
    ): CacheRecord? {
        val candidates = mutableListOf<CacheRecord>()
        db.query(
            TABLE_NAME,
            null,
            "$COL_SONG_ID = ?",
            arrayOf(songId),
            null,
            null,
            "$COL_UPDATED_AT DESC",
            "12"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                parseRow(cursor)?.let { candidates.add(it) }
            }
        }
        if (candidates.isEmpty()) return null
        return candidates
            .filter { sourcePlatform.isBlank() || it.sourcePlatform == sourcePlatform || it.sourcePlatform == "netease" }
            .ifEmpty { candidates }
            .sortedWith(
                compareByDescending<CacheRecord> {
                    if (qualityLevel.isNotBlank() && it.qualityLevel == qualityLevel) 1 else 0
                }.thenByDescending { it.loudnessData.source.priority }
                    .thenByDescending { it.updatedAt }
            )
            .firstOrNull()
    }

    private fun parseRow(cursor: android.database.Cursor): CacheRecord? {
        val platform = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE_PLATFORM))
        val songId = cursor.getString(cursor.getColumnIndexOrThrow(COL_SONG_ID))
        val quality = cursor.getString(cursor.getColumnIndexOrThrow(COL_QUALITY_LEVEL))
        val signature = cursor.getString(cursor.getColumnIndexOrThrow(COL_FILE_SIGNATURE))
        val lufs = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_ESTIMATED_LUFS))
        val peak = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_SAMPLED_TRUE_PEAK))
        val targetGain = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_TARGET_GAIN_DB))
        val srcName = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE))
        val updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED_AT))
        val src = runCatching { LoudnessSource.valueOf(srcName) }.getOrDefault(LoudnessSource.BS1770_SCAN)

        val isLegacyZeroPlaceholder = src == LoudnessSource.NETEASE_ONLINE &&
            (abs(lufs + 14.0f) <= 0.0015f || abs(lufs + 18.0f) <= 0.0015f) &&
            abs(peak - 1.0f) <= 0.001f
        if (isLegacyZeroPlaceholder || !src.isKnownValid) return null

        return CacheRecord(
            sourcePlatform = platform,
            songId = songId,
            qualityLevel = quality,
            fileSignature = signature,
            loudnessData = LoudnessData(lufs, peak, src),
            targetGainDb = targetGain,
            updatedAt = updatedAt
        )
    }
}
