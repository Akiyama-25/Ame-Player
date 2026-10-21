package Akari.NCM.player.data

import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.PlayableMedia
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class PlayableMediaDTO(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
    val uri: String? = null,
    val format: String = "MP3",
    val isNcm: Boolean = false,
    val isLocal: Boolean = true
) {
    fun toPlayableMedia(): PlayableMedia {
        val isRealLocalUri = uri != null && (uri.startsWith("content://") || uri.startsWith("file://"))
        return if (isLocal && isRealLocalUri) {
            PlayableMedia.LocalNcm(
                id = id,
                title = title,
                artist = artist,
                album = album,
                coverUrl = coverUrl,
                durationMs = durationMs,
                uri = uri!!,
                format = if (format.equals("FLAC", ignoreCase = true)) AudioFormat.FLAC else AudioFormat.MP3,
                isNcm = isNcm
            )
        } else {
            PlayableMedia.Online(
                id = id,
                title = title,
                artist = artist,
                album = album,
                coverUrl = coverUrl,
                durationMs = durationMs
            )
        }
    }

    companion object {
        fun fromPlayableMedia(media: PlayableMedia): PlayableMediaDTO {
            return when (media) {
                is PlayableMedia.LocalNcm -> PlayableMediaDTO(
                    id = media.id,
                    title = media.title,
                    artist = media.artist,
                    album = media.album,
                    coverUrl = media.coverUrl,
                    durationMs = media.durationMs,
                    uri = media.uri,
                    format = media.format.name,
                    isNcm = media.isNcm,
                    isLocal = true
                )
                is PlayableMedia.Online -> PlayableMediaDTO(
                    id = media.id,
                    title = media.title,
                    artist = media.artist,
                    album = media.album,
                    coverUrl = media.coverUrl,
                    durationMs = media.durationMs,
                    isLocal = false
                )
            }
        }
    }
}

@Serializable
data class CustomPlaylist(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val songs: List<PlayableMediaDTO> = emptyList(),
    val ncmPlaylistId: Long? = null,        // 若非空，表示该歌单源自网易云同步
    val coverImgUrl: String? = null,         // 云端歌单封面图
    val lastSyncedAt: Long = 0L             // 上次同步时间戳
)

class PlaylistManager(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val playlistFile = File(context.filesDir, "custom_playlists.json")

    private val _playlists = MutableStateFlow<List<CustomPlaylist>>(emptyList())
    val playlists: StateFlow<List<CustomPlaylist>> = _playlists.asStateFlow()

    
    private var ncmIdMatchCache: Map<String, PlayableMedia.LocalNcm> = emptyMap()
    private var nonNcmLayer1Cache: Map<String, PlayableMedia.LocalNcm> = emptyMap()
    private var nonNcmLayer2Cache: Map<String, PlayableMedia.LocalNcm> = emptyMap()
    private var nonNcmLayer3Cache: Map<String, PlayableMedia.LocalNcm> = emptyMap()
    private var nonNcmLayer4Cache: Map<String, PlayableMedia.LocalNcm> = emptyMap()
    private var allLayer1Cache: Map<String, PlayableMedia.LocalNcm> = emptyMap()
    private var allLayer2Cache: Map<String, PlayableMedia.LocalNcm> = emptyMap()
    private var allLayer3Cache: Map<String, PlayableMedia.LocalNcm> = emptyMap()
    private var allLayer4Cache: Map<String, PlayableMedia.LocalNcm> = emptyMap()

    private fun updateLocalMatchCache(list: List<CustomPlaylist>) {
        val localPlaylist = list.find { it.ncmPlaylistId == -9999L }
        if (localPlaylist == null) {
            ncmIdMatchCache = emptyMap()
            nonNcmLayer1Cache = emptyMap()
            nonNcmLayer2Cache = emptyMap()
            nonNcmLayer3Cache = emptyMap()
            nonNcmLayer4Cache = emptyMap()
            allLayer1Cache = emptyMap()
            allLayer2Cache = emptyMap()
            allLayer3Cache = emptyMap()
            allLayer4Cache = emptyMap()
            return
        }

        val idMap = mutableMapOf<String, PlayableMedia.LocalNcm>()
        val nonNcmL1 = mutableMapOf<String, PlayableMedia.LocalNcm>()
        val nonNcmL2 = mutableMapOf<String, PlayableMedia.LocalNcm>()
        val nonNcmL3 = mutableMapOf<String, PlayableMedia.LocalNcm>()
        val nonNcmL4 = mutableMapOf<String, PlayableMedia.LocalNcm>()
        val allL1 = mutableMapOf<String, PlayableMedia.LocalNcm>()
        val allL2 = mutableMapOf<String, PlayableMedia.LocalNcm>()
        val allL3 = mutableMapOf<String, PlayableMedia.LocalNcm>()
        val allL4 = mutableMapOf<String, PlayableMedia.LocalNcm>()

        for (dto in localPlaylist.songs) {
            val media = dto.toPlayableMedia() as? PlayableMedia.LocalNcm ?: continue

            val k1 = layer1Key(media.title, media.artist)
            val k2 = layer2Key(media.title, media.artist)
            val k3 = layer3Key(media.title, media.artist)
            val k4 = layer4Key(media.title, media.artist)

            // 优先记录具有 NCM ID 的项
            if (media.isNcm) {
                idMap[media.id] = media
            } else {
                nonNcmL1.putIfAbsent(k1, media)
                nonNcmL2.putIfAbsent(k2, media)
                nonNcmL3.putIfAbsent(k3, media)
                nonNcmL4.putIfAbsent(k4, media)
            }

            allL1.putIfAbsent(k1, media)
            allL2.putIfAbsent(k2, media)
            allL3.putIfAbsent(k3, media)
            allL4.putIfAbsent(k4, media)
        }

        ncmIdMatchCache = idMap
        nonNcmLayer1Cache = nonNcmL1
        nonNcmLayer2Cache = nonNcmL2
        nonNcmLayer3Cache = nonNcmL3
        nonNcmLayer4Cache = nonNcmL4
        allLayer1Cache = allL1
        allLayer2Cache = allL2
        allLayer3Cache = allL3
        allLayer4Cache = allL4
    }

    fun findLocalMatch(onlineMedia: PlayableMedia.Online): PlayableMedia.LocalNcm? {
        // 1. 优先使用 NCM 文件内的 ID 进行匹配
        ncmIdMatchCache[onlineMedia.id]?.let { return it }

        val k1 = layer1Key(onlineMedia.title, onlineMedia.artist)
        val k2 = layer2Key(onlineMedia.title, onlineMedia.artist)
        val k3 = layer3Key(onlineMedia.title, onlineMedia.artist)
        val k4 = layer4Key(onlineMedia.title, onlineMedia.artist)

        // 2. 对非 NCM 文件使用歌曲名多重匹配
        // 第一层：按照原始格式匹配，不忽略任何特殊字符与空格
        nonNcmLayer1Cache[k1]?.let { return it }

        // 第二层：将制作人部分的分割符号“/”替换为空格后匹配
        nonNcmLayer2Cache[k2]?.let { return it }

        // 第三层：在第二层的基础上，额外将制作人部分的特殊符号（如“*”）替换为空格后匹配
        nonNcmLayer3Cache[k3]?.let { return it }

        // 第四层：兼容首部数字/序号被截断或带曲目编号的情况（如 "7_7" -> "_7"、"164 GUMI" -> "GUMI"）
        nonNcmLayer4Cache[k4]?.let { return it }

        // 3. 兜底：若非 NCM 文件未命中，允许对其他本地项同样按多重层级顺序匹配
        allLayer1Cache[k1]?.let { return it }
        allLayer2Cache[k2]?.let { return it }
        allLayer3Cache[k3]?.let { return it }
        allLayer4Cache[k4]?.let { return it }

        return null
    }

    companion object {
        private fun unifyFullWidth(input: String): String {
            return java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFKC)
                .replace('\u301C', '~') // 兼容日文波浪号 〜 与全角/半角 ~
                .replace(Regex("[\u201C\u201D\u201E\u201F\u2033\uFF02]"), "\"") // 兼容中文/弯双引号 “ ” 等转标准双引号
                .replace(Regex("[\u2018\u2019\u201A\u201B\u2032\uFF07\u0060\u00B4]"), "'") // 兼容弯单引号 ‘ ’ 等转标准单引号
                .replace(Regex("[\uFF0F\u2215\u2044]"), "/") // 兼容全角/特殊斜杠
                .replace(Regex("[\uFF3C\u2216]"), "\\") // 兼容全角/特殊反斜杠
                .replace(Regex("[\uFF1A\u2236\uA789]"), ":") // 兼容特殊冒号
                .replace(Regex("[\uFF0A\u2217\u204E]"), "*") // 兼容特殊星号
                .replace(Regex("[\uFF5C\u2223\u00A6]"), "|") // 兼容特殊竖线
        }

        fun normalizeArtistLayer2(artist: String): String {
            return artist.replace('/', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun normalizeTitleLayer2(title: String): String {
            return title.replace('/', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun normalizeArtistLayer3(artist: String): String {
            return unifyFullWidth(artist)
                .replace('/', ' ')
                .replace(Regex("[*\"<>?\\\\|:]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun normalizeArtistLayer4(artist: String): String {
            val base = normalizeArtistLayer3(artist)
            val stripped = base.replace(Regex("^\\d+\\.?\\s*"), "").trim()
            return stripped.ifEmpty { base }
        }

        fun normalizeTitleLayer3(title: String): String {
            return unifyFullWidth(title)
                .replace('/', ' ')
                .replace(Regex("[*\"<>?\\\\|:]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun layer1Key(title: String, artist: String): String {
            return "${title.trim().lowercase()} - ${artist.trim().lowercase()}"
        }

        fun layer2Key(title: String, artist: String): String {
            return "${normalizeTitleLayer2(title).lowercase()} - ${normalizeArtistLayer2(artist).lowercase()}"
        }

        fun layer3Key(title: String, artist: String): String {
            return "${normalizeTitleLayer3(title).lowercase()} - ${normalizeArtistLayer3(artist).lowercase()}"
        }

        fun layer4Key(title: String, artist: String): String {
            return "${normalizeTitleLayer3(title).lowercase()} - ${normalizeArtistLayer4(artist).lowercase()}"
        }
    }

init {
        loadPlaylists()
    }

    private fun loadPlaylists() {
        if (!playlistFile.exists()) {
            _playlists.value = emptyList()
            updateLocalMatchCache(emptyList())
            return
        }
        try {
            val content = playlistFile.readText()
            val list = json.decodeFromString<List<CustomPlaylist>>(content)
            _playlists.value = list
            updateLocalMatchCache(list)
        } catch (_: Exception) {
            _playlists.value = emptyList()
            updateLocalMatchCache(emptyList())
        }
    }

    private fun savePlaylists() {
        updateLocalMatchCache(_playlists.value)
        try {
            val content = json.encodeToString(_playlists.value)
            playlistFile.writeText(content)
        } catch (_: Exception) {}
    }

    fun createPlaylist(name: String): CustomPlaylist {
        val newPlaylist = CustomPlaylist(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Untitled Playlist" }
        )
        _playlists.value = _playlists.value + newPlaylist
        savePlaylists()
        return newPlaylist
    }

    fun saveOrUpdateSyncedPlaylist(
        ncmPlaylistId: Long,
        name: String,
        coverImgUrl: String?,
        songs: List<PlayableMedia>
    ): CustomPlaylist {
        val dtos = songs.map { PlayableMediaDTO.fromPlayableMedia(it) }
        val existing = _playlists.value.find { it.ncmPlaylistId == ncmPlaylistId }

        val updatedPlaylist = if (existing != null) {
            existing.copy(
                name = name,
                coverImgUrl = coverImgUrl ?: existing.coverImgUrl,
                songs = dtos,
                lastSyncedAt = System.currentTimeMillis()
            )
        } else {
            CustomPlaylist(
                id = UUID.randomUUID().toString(),
                name = name,
                ncmPlaylistId = ncmPlaylistId,
                coverImgUrl = coverImgUrl,
                songs = dtos,
                lastSyncedAt = System.currentTimeMillis()
            )
        }

        _playlists.value = _playlists.value.filterNot { it.id == updatedPlaylist.id } + updatedPlaylist
        savePlaylists()
        return updatedPlaylist
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(name = newName.ifBlank { playlist.name })
            } else playlist
        }
        savePlaylists()
    }

    fun deletePlaylist(playlistId: String) {
        _playlists.value = _playlists.value.filterNot { it.id == playlistId }
        savePlaylists()
    }

    fun addSongToPlaylist(playlistId: String, song: PlayableMedia) {
        val songDto = PlayableMediaDTO.fromPlayableMedia(song)
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                if (playlist.songs.any { it.id == songDto.id }) {
                    playlist
                } else {
                    playlist.copy(songs = playlist.songs + songDto)
                }
            } else playlist
        }
        savePlaylists()
    }

    fun removeSongFromPlaylist(playlistId: String, songId: String) {
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id == playlistId) {
                playlist.copy(songs = playlist.songs.filterNot { it.id == songId })
            } else playlist
        }
        savePlaylists()
    }

    fun getOrCreateFavoritePlaylist(): CustomPlaylist {
        val favorite = _playlists.value.find {
            it.name.contains("喜欢的音乐") || it.name.contains("My Favorite") || it.name.contains("红心歌单")
        }
        if (favorite != null) return favorite
        return createPlaylist("我喜欢的音乐")
    }

    fun isFavorite(songId: String): Boolean {
        val favorite = _playlists.value.find {
            it.name.contains("喜欢的音乐") || it.name.contains("My Favorite") || it.name.contains("红心歌单")
        } ?: return false
        return favorite.songs.any { it.id == songId }
    }

    fun toggleFavorite(song: PlayableMedia): Boolean {
        val favorite = getOrCreateFavoritePlaylist()
        val isFav = favorite.songs.any { it.id == song.id }
        if (isFav) {
            removeSongFromPlaylist(favorite.id, song.id)
            return false
        } else {
            addSongToPlaylist(favorite.id, song)
            return true
        }
    }
}
