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

/**
 * 管理与缓存所有在线与本地曲目的 EBU R128 / ITU-R BS.1770 响度特征
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
                            memoryCache[key] = LoudnessData(lufs, peak, src)
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
     * 保存并持久化曲目综合响度数据
     */
    fun saveLoudness(mediaId: String, data: LoudnessData) {
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
     * 注册随在线音频链接解析一并返回的云端综合响度与峰值特征
     */
    fun registerOnlineLoudness(songId: String, gainDb: Float?, peak: Float?) {
        if (gainDb != null && gainDb != 0.0f) {
            // 网易云音乐云端 gain 设定基准为 -14.0 LUFS
            val integrated = -14.0f - gainDb
            val data = LoudnessData(
                integratedLufs = integrated,
                truePeak = peak ?: 1.0f,
                source = LoudnessSource.NETEASE_ONLINE
            )
            saveLoudness(songId, data)
        }
    }

    /**
     * 异步获取曲目的综合响度数据；若尚未测算或无内嵌标签则返回 null，由音频处理器进行实时分析
     */
    suspend fun getLoudness(media: PlayableMedia): LoudnessData? = withContext(Dispatchers.IO) {
        val cached = memoryCache[media.id]
        if (cached != null) return@withContext cached

        when (media) {
            is PlayableMedia.Online -> {
                null
            }
            is PlayableMedia.LocalNcm -> {
                val uri = Uri.parse(media.uri)

                // 1. 若为 NCM 且包含有效网易云 musicId，优先查找是否已有云端特征缓存
                if (media.isNcm) {
                    val ncmMeta = NcmDataSource.scanMetadata(context.contentResolver, uri)
                    val musicId = ncmMeta?.first?.musicId?.toString()
                    if (musicId != null && memoryCache.containsKey(musicId)) {
                        val data = memoryCache[musicId]!!
                        saveLoudness(media.id, data)
                        return@withContext data
                    }
                }

                // 2. 尝试从本地音频文件元数据标签提取 (FLAC Vorbis Comment / MP3 ID3v2 ReplayGain / Apple SoundCheck)
                val tagData = AudioTagLoudnessParser.extractLoudness(context, uri)
                if (tagData != null) {
                    AppLogger.i(TAG, "Extracted tag loudness for '${media.title}': ${tagData.integratedLufs} LUFS, peak=${tagData.peakDb} dBFS")
                    saveLoudness(media.id, tagData)
                    return@withContext tagData
                }

                // 3. 本地音频无标签，返回 null 以触发播放时实时 BS.1770 算法分析与动态平衡
                null
            }
        }
    }
}