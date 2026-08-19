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

    
    private var localMatchCache: Map<String, PlayableMedia.LocalNcm> = emptyMap()

    private fun updateLocalMatchCache(list: List<CustomPlaylist>) {
        val localPlaylist = list.find { it.ncmPlaylistId == -9999L }
        if (localPlaylist == null) {
            localMatchCache = emptyMap()
            return
        }
        val map = mutableMapOf<String, PlayableMedia.LocalNcm>()
        for (dto in localPlaylist.songs) {
            val media = dto.toPlayableMedia() as? PlayableMedia.LocalNcm ?: continue
            map[media.id] = media
            val titleArtistKey = "${media.title.trim().lowercase()} - ${media.artist.trim().lowercase()}"
            map[titleArtistKey] = media
        }
        localMatchCache = map
    }

    fun findLocalMatch(onlineMedia: PlayableMedia.Online): PlayableMedia.LocalNcm? {
        val exactMatch = localMatchCache[onlineMedia.id]
        if (exactMatch != null) return exactMatch
        val titleArtistKey = "${onlineMedia.title.trim().lowercase()} - ${onlineMedia.artist.trim().lowercase()}"
        return localMatchCache[titleArtistKey]
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
