package Akari.NCM.player.data

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.loudness.AudioTagLoudnessParser
import Akari.NCM.player.core.loudness.LoudnessData
import Akari.NCM.player.core.loudness.LoudnessSource
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 管理与缓存所有在线与本地曲目的 EBU R128 / ITU-R BS.1770 综合响度特征
 *
 * P0 持久化：基于本地 SQLite 缓存表（主键 (sourcePlatform, songId, qualityLevel, fileSignature)，LRU 上限 10,000 条）
 * Tier 1：优先采纳有效非零服务端元数据（/song/url/v1 gain/peak 及 /song/detail、/song/music/detail 分音质 vd），
 *        严格过滤试听截断片段（freeTrialInfo != null）以及绝对值 <= 0.001 的无效占位值。
 */
@Singleton
class LoudnessManager @Inject constructor(
    private val context: Context,
    private val ncmApi: NcmApi
) {
    companion object {
        private const val TAG = "AME_LOUDNESS"
        private const val LEGACY_PREF_NAME = "ame_loudness_cache"
        private const val MIN_VALID_ONLINE_GAIN_DB = 0.001f
        const val PLATFORM_NETEASE = "netease"
        const val PLATFORM_LOCAL = "local"
    }

    private val legacyPrefs by lazy {
        context.getSharedPreferences(LEGACY_PREF_NAME, Context.MODE_PRIVATE)
    }

    internal val cacheDb by lazy {
        LoudnessCacheDatabase(context)
    }

    // 内存高速缓存 (canonical composite key 及 songId -> LoudnessData)
    private val memoryCache = ConcurrentHashMap<String, LoudnessData>()

    // NCM 本地文件 id -> 网易云 musicId 映射缓存
    private val ncmMusicIdCache = ConcurrentHashMap<String, String>()

    init {
        loadPersistedCache()
    }

    private fun loadPersistedCache() {
        try {
            // 1. 兼容迁移旧版 SharedPreferences 有效缓存至 SQLite
            val legacyAll = legacyPrefs.all
            if (legacyAll.isNotEmpty()) {
                for ((key, value) in legacyAll) {
                    if (value is String) {
                        val parts = value.split(",")
                        if (parts.size >= 3) {
                            val lufs = parts[0].toFloatOrNull()
                            val peak = parts[1].toFloatOrNull()
                            val src = runCatching { LoudnessSource.valueOf(parts[2]) }.getOrDefault(LoudnessSource.BS1770_SCAN)
                            if (lufs != null && peak != null && src.isKnownValid) {
                                val isLegacyZeroPlaceholder = src == LoudnessSource.NETEASE_ONLINE &&
                                    abs(lufs + 14.0f) <= 0.0015f &&
                                    abs(peak - 1.0f) <= 0.001f
                                if (!isLegacyZeroPlaceholder) {
                                    val data = LoudnessData(lufs, peak, src)
                                    val platform = if (src == LoudnessSource.REPLAYGAIN_TAG) PLATFORM_LOCAL else PLATFORM_NETEASE
                                    cacheDb.put(platform, key, "", "", data)
                                }
                            }
                        }
                    }
                }
                legacyPrefs.edit().clear().apply()
            }

            // 2. 预热最近访问记录到内存表实现 P0 零毫秒同步命中
            val records = cacheDb.getAllRecent(limit = 3000)
            for (rec in records) {
                val compositeKey = LoudnessCacheDatabase.buildCacheKey(
                    rec.sourcePlatform,
                    rec.songId,
                    rec.qualityLevel,
                    rec.fileSignature
                )
                putMemoryIfHigherOrEqual(compositeKey, rec.loudnessData)
                putMemoryIfHigherOrEqual(rec.songId, rec.loudnessData)
            }
            AppLogger.d(TAG, "Loaded ${records.size} cached loudness profiles from SQLite (P0)")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to load persisted loudness cache: ${e.message}")
        }
    }

    private fun putMemoryIfHigherOrEqual(key: String, data: LoudnessData) {
        val existing = memoryCache[key]
        if (existing == null || existing.source.priority <= data.source.priority) {
            memoryCache[key] = data
        }
    }

    /**
     * 同步高速读取内存中已有的曲目响度缓存（P0 零毫秒命中，用于切歌瞬间无延迟增益预置）
     */
    fun getCachedLoudnessSync(
        media: PlayableMedia,
        qualityLevel: String = "",
        fileSignature: String = ""
    ): LoudnessData? {
        val platform = if (media is PlayableMedia.Online) PLATFORM_NETEASE else PLATFORM_LOCAL
        if (qualityLevel.isNotBlank() || fileSignature.isNotBlank()) {
            val exactKey = LoudnessCacheDatabase.buildCacheKey(platform, media.id, qualityLevel, fileSignature)
            memoryCache[exactKey]?.takeIf { it.source.isKnownValid }?.let { return it }
            // 同步查一次 SQLite 以保证冷启动精确命中
            cacheDb.get(platform, media.id, qualityLevel, fileSignature)?.loudnessData?.let { rec ->
                putMemoryIfHigherOrEqual(exactKey, rec)
                putMemoryIfHigherOrEqual(media.id, rec)
                return rec
            }
        }

        memoryCache[media.id]?.takeIf { it.source.isKnownValid }?.let { return it }
        if (media is PlayableMedia.LocalNcm && media.isNcm) {
            val mappedMusicId = ncmMusicIdCache[media.id]
            if (mappedMusicId != null) {
                memoryCache[mappedMusicId]?.takeIf { it.source.isKnownValid }?.let { return it }
            }
        }
        return null
    }

    /**
     * 保存并持久化曲目综合响度数据至 SQLite LRU 缓存表
     * 按置信度分级保护：禁止低优先级初判覆盖高优先级稀疏抽样 / 全曲锁定数据。
     */
    fun saveLoudness(
        mediaId: String,
        data: LoudnessData,
        sourcePlatform: String = PLATFORM_NETEASE,
        qualityLevel: String = "",
        fileSignature: String = ""
    ) {
        if (!data.source.isKnownValid || data.source == LoudnessSource.QUEUE_AVERAGE) return

        val compositeKey = LoudnessCacheDatabase.buildCacheKey(sourcePlatform, mediaId, qualityLevel, fileSignature)
        val existingComposite = memoryCache[compositeKey]
        if (existingComposite != null && existingComposite.source.priority > data.source.priority) {
            return
        }
        val existingBase = memoryCache[mediaId]
        if (qualityLevel.isBlank() && fileSignature.isBlank() &&
            existingBase != null && existingBase.source.priority > data.source.priority
        ) {
            return
        }

        memoryCache[compositeKey] = data
        putMemoryIfHigherOrEqual(mediaId, data)
        // 仅将已验证的 Tier 1 元数据、Tier 2 稀疏/全曲扫描与中段多点初判持久化至 SQLite；
        // 避免将 0.8s 瞬时前奏初判 / 实时 AGC 中间态固化进 P0 数据库污染后续播放。
        val shouldPersistToDb = data.source.isFinalLocked || data.source == LoudnessSource.FAST_PREVIEW_SCAN
        if (!shouldPersistToDb) return
        try {
            cacheDb.put(
                sourcePlatform = sourcePlatform,
                songId = mediaId,
                qualityLevel = qualityLevel,
                fileSignature = fileSignature,
                data = data
            )
            AppLogger.d(
                TAG,
                "Saved loudness (P0 SQLite) songId=$mediaId [$sourcePlatform/$qualityLevel/$fileSignature]: " +
                    "${data.integratedLufs} LUFS, peak=${data.peakDb} dBTP (${data.source.label})"
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to persist loudness for mediaId=$mediaId: ${e.message}")
        }
    }

    /**
     * Tier 1：注册随在线音频链接与歌曲/音质详情返回的云端响度与峰值特征。
     * - 试听截断片段（isFreeTrial = true）强制拒绝，立即降级至 Tier 2/3；
     * - 过滤缺失、null 以及 |value| <= 0.001（含 0 / -0.0 / -2.0E-4）占位值；
     * - 优先采纳 /song/url/v1 的有效 gain；若 gain 无效则采用 /song/detail 或 /song/music/detail 对应档位 vd。
     */
    fun registerOnlineLoudness(
        songId: String,
        gainDb: Float?,
        peak: Float?,
        vdDb: Float? = null,
        isFreeTrial: Boolean = false,
        qualityLevel: String = "",
        fileSignature: String = ""
    ): LoudnessData? {
        if (isFreeTrial) {
            AppLogger.d(TAG, "Rejecting Tier 1 online loudness for free-trial snippet songId=$songId")
            return null
        }

        val validGain = gainDb?.takeIf { !it.isNaN() && !it.isInfinite() && abs(it) > MIN_VALID_ONLINE_GAIN_DB }
        val validVd = vdDb?.takeIf { !it.isNaN() && !it.isInfinite() && abs(it) > MIN_VALID_ONLINE_GAIN_DB }

        val chosenReferenceLufs: Float
        val chosenGainDb: Float
        val chosenSource: LoudnessSource
        when {
            validGain != null -> {
                // 实测验证（ハテ -8.7 LUFS gain=-9.2841、Clair De Lune -22.6 LUFS gain=+4.68）：
                // /song/url/v1 返回的 gain 参考基准为 -18.0 LUFS (ReplayGain 2.0 / BS.1770)
                chosenReferenceLufs = NcmApi.NETEASE_GAIN_REFERENCE_LUFS
                chosenGainDb = validGain
                chosenSource = LoudnessSource.NETEASE_ONLINE
            }
            validVd != null -> {
                // NcmApi.normalizeNeteaseVdToDb 已将 l/m/h/sq/hr/sk 及 je/jm/db 统一校准至 -14.0 LUFS 基准
                chosenReferenceLufs = NcmApi.NETEASE_VD_REFERENCE_LUFS
                chosenGainDb = validVd
                chosenSource = LoudnessSource.NETEASE_VD
            }
            else -> {
                AppLogger.d(TAG, "Ignoring missing or placeholder online gain/vd (gain=$gainDb, vd=$vdDb) for songId=$songId -> downgrade to Tier 2")
                return null
            }
        }

        val compositeKey = LoudnessCacheDatabase.buildCacheKey(PLATFORM_NETEASE, songId, qualityLevel, fileSignature)
        val existing = memoryCache[compositeKey] ?: memoryCache[songId]
        if (existing != null &&
            (existing.source == LoudnessSource.BS1770_SCAN || existing.source == LoudnessSource.SPARSE_RANGE_SCAN)
        ) {
            return existing
        }

        val integrated = (chosenReferenceLufs - chosenGainDb).coerceIn(-70.0f, 0.0f)
        val safePeak = peak?.takeIf { !it.isNaN() && !it.isInfinite() && it > 0.0001f } ?: 1.0f
        val data = LoudnessData(
            integratedLufs = integrated,
            truePeak = safePeak,
            source = chosenSource
        )
        saveLoudness(
            mediaId = songId,
            data = data,
            sourcePlatform = PLATFORM_NETEASE,
            qualityLevel = qualityLevel,
            fileSignature = fileSignature
        )
        return data
    }

    /**
     * 计算未来队列曲目的平均响度（LUFS）与已就绪数量
     */
    fun computeUpcomingAverageLoudness(upcomingTracks: List<PlayableMedia>): Pair<Float?, Int> {
        if (upcomingTracks.isEmpty()) return null to 0
        val readyLufs = upcomingTracks.mapNotNull { track ->
            getCachedLoudnessSync(track)?.integratedLufs
        }
        val avg = if (readyLufs.isNotEmpty()) readyLufs.average().toFloat() else null
        return avg to readyLufs.size
    }

    /**
     * 异步获取曲目的已知响度数据（P0 SQLite 缓存、云端特征或本地文件内嵌标签）；
     * 若未命中则返回 null，由 Tier 2（11×3s 稀疏抽样）与 Tier 3（实时流式兜底）处理。
     */
    suspend fun getLoudness(
        media: PlayableMedia,
        qualityLevel: String = "",
        fileSignature: String = ""
    ): LoudnessData? = withContext(Dispatchers.IO) {
        getCachedLoudnessSync(media, qualityLevel, fileSignature)?.let { return@withContext it }

        val platform = if (media is PlayableMedia.Online) PLATFORM_NETEASE else PLATFORM_LOCAL
        cacheDb.get(platform, media.id, qualityLevel, fileSignature)?.loudnessData?.let { persisted ->
            val key = LoudnessCacheDatabase.buildCacheKey(platform, media.id, qualityLevel, fileSignature)
            putMemoryIfHigherOrEqual(key, persisted)
            putMemoryIfHigherOrEqual(media.id, persisted)
            return@withContext persisted
        }

        when (media) {
            is PlayableMedia.Online -> null
            is PlayableMedia.LocalNcm -> {
                val uri = Uri.parse(media.uri)

                // 1. 若为 NCM 且包含有效网易云 musicId，优先查找是否已有云端或扫描特征缓存
                if (media.isNcm) {
                    val ncmMeta = NcmDataSource.scanMetadata(context.contentResolver, uri)
                    val musicId = ncmMeta?.first?.musicId?.toString()?.takeIf { it != "0" && it.isNotBlank() }
                    if (musicId != null) {
                        ncmMusicIdCache[media.id] = musicId
                        val matched = memoryCache[musicId]?.takeIf { it.source.isKnownValid }
                            ?: cacheDb.get(PLATFORM_NETEASE, musicId)?.loudnessData
                        if (matched != null) {
                            saveLoudness(media.id, matched, sourcePlatform = PLATFORM_LOCAL, qualityLevel = "local", fileSignature = fileSignature)
                            return@withContext matched
                        }
                    }
                }

                // 2. 尝试从本地音频文件元数据标签提取 (FLAC Vorbis Comment / MP3 ID3v2 ReplayGain / Apple SoundCheck)
                val tagData = if (media.isNcm) {
                    AudioTagLoudnessParser.extractNcmLoudness(context, uri)
                } else {
                    AudioTagLoudnessParser.extractLoudness(context, uri)
                }
                if (tagData != null) {
                    AppLogger.i(TAG, "Extracted tag loudness for '${media.title}': ${tagData.integratedLufs} LUFS, peak=${tagData.peakDb} dBTP")
                    saveLoudness(media.id, tagData, sourcePlatform = PLATFORM_LOCAL, qualityLevel = "local", fileSignature = fileSignature)
                    return@withContext tagData
                }

                null
            }
        }
    }
}