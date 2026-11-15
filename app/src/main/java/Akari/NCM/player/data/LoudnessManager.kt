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
 * 管理与缓存所有在线与本地曲目的 EBU R128 / ITU-R BS.1770 全曲综合响度特征
 */
@Singleton
class LoudnessManager @Inject constructor(
    private val context: Context,
    private val ncmApi: NcmApi
) {
    companion object {
        private const val TAG = "AME_LOUDNESS"
        private const val PREF_NAME = "ame_loudness_cache"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // 内存高速缓存 (songId / mediaUri -> LoudnessData)
    private val memoryCache = ConcurrentHashMap<String, LoudnessData>()

    // NCM 本地文件 id -> 网易云 musicId 映射缓存
    private val ncmMusicIdCache = ConcurrentHashMap<String, String>()

    init {
        loadPersistedCache()
    }

    private fun loadPersistedCache() {
        try {
            val all = prefs.all
            for ((key, value) in all) {
                if (value is String) {
                    val parts = value.split(",")
                    if (parts.size >= 3) {
                        val lufs = parts[0].toFloatOrNull()
                        val peak = parts[1].toFloatOrNull()
                        val srcName = parts[2]
                        val src = runCatching { LoudnessSource.valueOf(srcName) }.getOrDefault(LoudnessSource.BS1770_SCAN)
                        if (lufs != null && peak != null) {
                            // 过滤早期版本由网易云占位零值 gain=0.0 写入的无效缓存项，允许重新精准测算
                            val isLegacyZeroPlaceholder = src == LoudnessSource.NETEASE_ONLINE &&
                                abs(lufs + 14.0f) < 0.01f &&
                                abs(peak - 1.0f) < 0.001f
                            if (!isLegacyZeroPlaceholder && src.isKnownValid) {
                                memoryCache[key] = LoudnessData(lufs, peak, src)
                            }
                        }
                    }
                }
            }
            AppLogger.d(TAG, "Loaded ${memoryCache.size} cached loudness profiles from disk")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to load persisted loudness cache: ${e.message}")
        }
    }

    /**
     * 同步高速读取内存中已有的曲目响度缓存（用于切歌瞬间的零延迟增益预置）
     */
    fun getCachedLoudnessSync(media: PlayableMedia): LoudnessData? {
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
     * 保存并持久化曲目综合响度数据（避免用临时初判覆盖已完成的全曲精确锁定数据）
     */
    fun saveLoudness(mediaId: String, data: LoudnessData) {
        if (!data.source.isKnownValid || data.source == LoudnessSource.QUEUE_AVERAGE) return

        val existing = memoryCache[mediaId]
        if (existing != null && existing.source.isFinalLocked && !data.source.isFinalLocked) {
            return
        }

        memoryCache[mediaId] = data
        try {
            val encoded = "${data.integratedLufs},${data.truePeak},${data.source.name}"
            prefs.edit().putString(mediaId, encoded).apply()
            AppLogger.d(TAG, "Saved loudness for mediaId=$mediaId: ${data.integratedLufs} LUFS, peak=${data.peakDb} dBFS (${data.source.label})")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to persist loudness for mediaId=$mediaId: ${e.message}")
        }
    }

    /**
     * 注册随在线音频链接解析一并返回的云端全曲综合响度与峰值特征。
     * 过滤缺失与 0.0 无效占位默认值，仅当为有效非零增益时才采纳。
     */
    fun registerOnlineLoudness(songId: String, gainDb: Float?, peak: Float?): LoudnessData? {
        if (gainDb == null || abs(gainDb) < 0.01f) {
            AppLogger.d(TAG, "Ignoring missing or zero placeholder online gain ($gainDb) for songId=$songId")
            return null
        }

        val existing = memoryCache[songId]
        if (existing != null && existing.source == LoudnessSource.BS1770_SCAN) {
            return existing
        }

        // 网易云音乐云端 gain 设定基准为 -14.0 LUFS
        val integrated = (-14.0f - gainDb).coerceIn(-70.0f, 0.0f)
        val safePeak = peak?.takeIf { it > 0.0001f } ?: 1.0f
        val data = LoudnessData(
            integratedLufs = integrated,
            truePeak = safePeak,
            source = LoudnessSource.NETEASE_ONLINE
        )
        saveLoudness(songId, data)
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
     * 异步获取曲目的已知响度数据（缓存、云端特征或本地文件内嵌标签）；
     * 若尚未测算或无内嵌标签则返回 null，由快速抽样与全曲扫描器分析。
     */
    suspend fun getLoudness(media: PlayableMedia): LoudnessData? = withContext(Dispatchers.IO) {
        getCachedLoudnessSync(media)?.let { return@withContext it }

        when (media) {
            is PlayableMedia.Online -> {
                val persisted = prefs.getString(media.id, null)
                if (persisted != null) {
                    val parts = persisted.split(",")
                    if (parts.size >= 3) {
                        val lufs = parts[0].toFloatOrNull()
                        val peak = parts[1].toFloatOrNull()
                        val src = runCatching { LoudnessSource.valueOf(parts[2]) }.getOrDefault(LoudnessSource.BS1770_SCAN)
                        if (lufs != null && peak != null && src.isKnownValid) {
                            val isLegacyZero = src == LoudnessSource.NETEASE_ONLINE &&
                                abs(lufs + 14.0f) < 0.01f &&
                                abs(peak - 1.0f) < 0.001f
                            if (!isLegacyZero) {
                                val data = LoudnessData(lufs, peak, src)
                                memoryCache[media.id] = data
                                return@withContext data
                            }
                        }
                    }
                }
                null
            }
            is PlayableMedia.LocalNcm -> {
                val uri = Uri.parse(media.uri)

                // 1. 若为 NCM 且包含有效网易云 musicId，优先查找是否已有云端或扫描特征缓存
                if (media.isNcm) {
                    val ncmMeta = NcmDataSource.scanMetadata(context.contentResolver, uri)
                    val musicId = ncmMeta?.first?.musicId?.toString()?.takeIf { it != "0" && it.isNotBlank() }
                    if (musicId != null) {
                        ncmMusicIdCache[media.id] = musicId
                        val matched = memoryCache[musicId]?.takeIf { it.source.isKnownValid }
                        if (matched != null) {
                            saveLoudness(media.id, matched)
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
                    AppLogger.i(TAG, "Extracted tag loudness for '${media.title}': ${tagData.integratedLufs} LUFS, peak=${tagData.peakDb} dBFS")
                    saveLoudness(media.id, tagData)
                    return@withContext tagData
                }

                null
            }
        }
    }
}