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
    }

    // 内存高速缓存 (songId / mediaUri -> LoudnessData)
    private val memoryCache = ConcurrentHashMap<String, LoudnessData>()

    /**
     * 注册随在线音频链接解析一并返回的云端综合响度与峰值特征
     *
     * @param songId 网易云单曲 ID
     * @param gainDb 网易云音频增益（基准为 -14.0 LUFS）
     * @param peak 线性真峰值电平（0.0 ~ 1.0）
     */
    fun registerOnlineLoudness(songId: String, gainDb: Float?, peak: Float?) {
        if (gainDb != null) {
            // 网易云音乐云端 gain 设定基准为 -14.0 LUFS
            val integrated = -14.0f - gainDb
            val data = LoudnessData(
                integratedLufs = integrated,
                truePeak = peak ?: 1.0f,
                source = LoudnessSource.NETEASE_ONLINE
            )
            memoryCache[songId] = data
            AppLogger.d(TAG, "Registered online loudness for songId=$songId: ${data.integratedLufs} LUFS, peak=${data.peakDb} dBFS")
        }
    }

    /**
     * 异步获取曲目的综合响度数据
     */
    suspend fun getLoudness(media: PlayableMedia): LoudnessData = withContext(Dispatchers.IO) {
        val cached = memoryCache[media.id]
        if (cached != null) return@withContext cached

        when (media) {
            is PlayableMedia.Online -> {
                // 在线歌曲若尚未注册，使用现代流媒体基准平准兜底
                val fallback = LoudnessData(-14.0f, 1.0f, LoudnessSource.DEFAULT_FALLBACK)
                memoryCache[media.id] = fallback
                fallback
            }
            is PlayableMedia.LocalNcm -> {
                val uri = Uri.parse(media.uri)

                // 1. 若为 NCM 且包含有效网易云 musicId，优先查找是否已有云端特征缓存
                if (media.isNcm) {
                    val ncmMeta = NcmDataSource.scanMetadata(context.contentResolver, uri)
                    val musicId = ncmMeta?.first?.musicId?.toString()
                    if (musicId != null && memoryCache.containsKey(musicId)) {
                        val data = memoryCache[musicId]!!
                        memoryCache[media.id] = data
                        return@withContext data
                    }
                }

                // 2. 尝试从本地音频文件元数据标签提取 (FLAC Vorbis Comment / MP3 ID3v2 ReplayGain / Apple SoundCheck)
                val tagData = AudioTagLoudnessParser.extractLoudness(context, uri)
                if (tagData != null) {
                    AppLogger.i(TAG, "Extracted tag loudness for '${media.title}': ${tagData.integratedLufs} LUFS, peak=${tagData.peakDb} dBFS")
                    memoryCache[media.id] = tagData
                    return@withContext tagData
                }

                // 3. 兜底平准电平
                val fallback = LoudnessData(-14.0f, 1.0f, LoudnessSource.DEFAULT_FALLBACK)
                memoryCache[media.id] = fallback
                fallback
            }
        }
    }
}