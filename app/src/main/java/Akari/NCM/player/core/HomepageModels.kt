package Akari.NCM.player.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * 网易云首页卡片与入口的加载方式分类
 */
@Serializable
enum class PlaylistLoadType {
    /** 传统固定歌单（或专辑），点击后进入歌单详情页加载固定曲目列表 */
    FIXED_PLAYLIST,
    /** 单曲类推荐卡片：从特定单曲开始加入新播放队列，并通过相似歌曲接口无限推荐下一首 */
    SONG_RADIO,
    /** 带种子歌曲的雷达或漫游歌单：从特定种子歌曲开始播放，并通过相似歌曲接口无限推荐 */
    SEED_RADAR_RADIO,
    /** 私人 FM：通过私人 FM 接口无限加载个性化推荐曲目 */
    PERSONAL_FM,
    /** 心动模式：基于歌单与种子歌曲通过智能推荐列表无限续播 */
    HEARTBEAT_INTELLIGENCE
}

@Serializable
data class HomepageDragonBall(
    val id: Long = 0L,
    val name: String = "",
    val iconUrl: String = "",
    val url: String = ""
) {
    fun resolveLoadType(): PlaylistLoadType {
        return when {
            id == -6L || id == -2L || name.contains("FM", ignoreCase = true) || url.contains("privatefm", ignoreCase = true) ->
                PlaylistLoadType.PERSONAL_FM
            name.contains("心动") || url.contains("intelligence", ignoreCase = true) ->
                PlaylistLoadType.HEARTBEAT_INTELLIGENCE
            else -> PlaylistLoadType.FIXED_PLAYLIST
        }
    }
}

@Serializable
data class HomepageBlock(
    val blockCode: String = "",
    val title: String = "",
    val creatives: List<Creative> = emptyList()
) {
    @Serializable
    data class Creative(
        val creativeType: String = "",
        val title: String = "",
        val imageUrl: String = "",
        val resourceId: String = "",
        val playCount: Long = 0L,
        val resourceType: String = "list",
        val action: String = "",
        val actionType: String = "",
        val seedSongId: String? = null,
        @Transient
        val seedSong: PlayableMedia.Online? = null,
        val specialType: Int = 0
    ) {
        /**
         * 判断当前卡片应当按固定歌单打开详情页，还是按特定种子歌曲启动无限推荐播放队列
         */
        fun resolveLoadType(): PlaylistLoadType {
            return when {
                resourceType.equals("song", ignoreCase = true) ||
                    creativeType.contains("SONG", ignoreCase = true) -> {
                    PlaylistLoadType.SONG_RADIO
                }
                !seedSongId.isNullOrBlank() -> {
                    PlaylistLoadType.SEED_RADAR_RADIO
                }
                else -> PlaylistLoadType.FIXED_PLAYLIST
            }
        }
    }
}
