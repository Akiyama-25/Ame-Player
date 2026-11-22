package Akari.NCM.player.data

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.NcmArtistBrief
import Akari.NCM.player.core.NcmUserPlaylist
import Akari.NCM.player.core.PlayableMedia
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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
    val isLocal: Boolean = true,
    val alias: String? = null,
    val transName: String? = null,
    val albumId: Long = 0L,
    val artistsList: List<NcmArtistBrief> = emptyList()
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
                durationMs = durationMs,
                alias = alias,
                transName = transName,
                albumId = albumId,
                artistsList = artistsList
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
                    isLocal = false,
                    alias = media.alias,
                    transName = media.transName,
                    albumId = media.albumId,
                    artistsList = media.artistsList
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

@Serializable
data class CloudFavoriteCacheData(
    val uid: Long = 0L,
    val likedSongIds: Set<String> = emptySet(),
    val subscribedPlaylistIds: Set<Long> = emptySet(),
    val favoriteCloudPlaylistId: Long? = null,
    val updatedAt: Long = 0L
)

class PlaylistManager(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val playlistFile = File(context.filesDir, "custom_playlists.json")
    private val favoriteCacheFile = File(context.filesDir, "cloud_favorites_aes256.enc")
    private val favoriteKeyFile = File(context.filesDir, ".cloud_favorites_key256.bin")
    private val favoriteCacheLock = Any()

    private val _playlists = MutableStateFlow<List<CustomPlaylist>>(emptyList())
    val playlists: StateFlow<List<CustomPlaylist>> = _playlists.asStateFlow()

    private val _favoriteSongIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteSongIds: StateFlow<Set<String>> = _favoriteSongIds.asStateFlow()

    private val _subscribedPlaylistIds = MutableStateFlow<Set<Long>>(emptySet())
    val subscribedPlaylistIds: StateFlow<Set<Long>> = _subscribedPlaylistIds.asStateFlow()

    @Volatile
    private var favoriteCloudPlaylistId: Long? = null

    @Volatile
    private var favoriteCacheUid: Long = 0L

    
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

            // 优先记录具有 NCM ID 的项
            if (media.isNcm) {
                idMap[media.id] = media
                val k1 = layer1Key(media.title, media.artist)
                val k2 = layer2Key(media.title, media.artist)
                val k3 = layer3Key(media.title, media.artist)
                val k4 = layer4Key(media.title, media.artist)
                allL1.putIfAbsent(k1, media)
                allL2.putIfAbsent(k2, media)
                allL3.putIfAbsent(k3, media)
                allL4.putIfAbsent(k4, media)
            } else {
                // 非 NCM 文件：title 为原始文件名（未区分艺术家与歌名）
                val rawName = media.title
                val candidatePairs = mutableListOf<Pair<String, String>>()

                if (media.artist.isNotBlank()) {
                    candidatePairs.add(rawName to media.artist)
                }

                val hyphenParts = rawName.split(" - ", limit = 2)
                if (hyphenParts.size == 2) {
                    val p0 = hyphenParts[0].trim()
                    val p1 = hyphenParts[1].trim()
                    candidatePairs.add(p1 to p0) // (title, artist)
                    candidatePairs.add(p0 to p1) // (artist, title)
                }
                candidatePairs.add(rawName to "")

                for ((t, a) in candidatePairs) {
                    val k1 = layer1Key(t, a)
                    val k2 = layer2Key(t, a)
                    val k3 = layer3Key(t, a)
                    val k4 = layer4Key(t, a)
                    nonNcmL1.putIfAbsent(k1, media)
                    nonNcmL2.putIfAbsent(k2, media)
                    nonNcmL3.putIfAbsent(k3, media)
                    nonNcmL4.putIfAbsent(k4, media)

                    allL1.putIfAbsent(k1, media)
                    allL2.putIfAbsent(k2, media)
                    allL3.putIfAbsent(k3, media)
                    allL4.putIfAbsent(k4, media)
                }
            }
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

    /**
     * 查找与云端歌曲匹配的本地音频条目原始扫描对象
     */
    fun findLocalMatchRaw(onlineMedia: PlayableMedia.Online): PlayableMedia.LocalNcm? {
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

        // 4. 纯曲名匹配兜底（当本地文件为纯曲名命名时）
        val kTitle1 = layer1Key(onlineMedia.title, "")
        val kTitle2 = layer2Key(onlineMedia.title, "")
        nonNcmLayer1Cache[kTitle1]?.let { return it }
        nonNcmLayer2Cache[kTitle2]?.let { return it }
        allLayer1Cache[kTitle1]?.let { return it }
        allLayer2Cache[kTitle2]?.let { return it }

        return null
    }

    /**
     * 查找与云端歌曲匹配的本地音频条目。
     *
     * 依据规则：非NCM文件直接显示原始文件名的设定仅应用于本地扫描歌单；
     * 在云端歌单或使用云端歌单的播放列表中匹配本地非NCM文件时，必须保留云端原本的歌曲ID、标题、艺术家、专辑与封面，
     * 仅替换音频源 URI 和解码格式以使用本地高质量文件播放。
     */
    fun findLocalMatch(onlineMedia: PlayableMedia.Online): PlayableMedia.LocalNcm? {
        val matched = findLocalMatchRaw(onlineMedia) ?: return null
        return matched.copy(
            id = onlineMedia.id,
            title = onlineMedia.title,
            artist = onlineMedia.artist,
            album = onlineMedia.album,
            coverUrl = onlineMedia.coverUrl ?: matched.coverUrl,
            durationMs = if (onlineMedia.durationMs > 0L) onlineMedia.durationMs else matched.durationMs
        )
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
        loadEncryptedFavoritesCache()
    }

    private fun getOrCreateAes256Key(): SecretKeySpec {
        synchronized(favoriteCacheLock) {
            if (favoriteKeyFile.exists()) {
                val existing = runCatching { favoriteKeyFile.readBytes() }.getOrNull()
                if (existing != null && existing.size == 32) {
                    return SecretKeySpec(existing, "AES")
                }
            }
            val keyBytes = ByteArray(32)
            SecureRandom().nextBytes(keyBytes)
            runCatching { favoriteKeyFile.writeBytes(keyBytes) }
            return SecretKeySpec(keyBytes, "AES")
        }
    }

    private fun loadEncryptedFavoritesCache() {
        synchronized(favoriteCacheLock) {
            if (!favoriteCacheFile.exists()) {
                _favoriteSongIds.value = emptySet()
                _subscribedPlaylistIds.value = emptySet()
                favoriteCloudPlaylistId = null
                favoriteCacheUid = 0L
                return
            }
            try {
                val bytes = favoriteCacheFile.readBytes()
                val headerSize = 4 + 12 // "ACF2" + 12-byte GCM IV
                if (bytes.size <= headerSize ||
                    bytes[0] != 'A'.code.toByte() ||
                    bytes[1] != 'C'.code.toByte() ||
                    bytes[2] != 'F'.code.toByte() ||
                    bytes[3] != '2'.code.toByte()
                ) {
                    return
                }
                val iv = bytes.copyOfRange(4, 16)
                val cipherText = bytes.copyOfRange(16, bytes.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateAes256Key(), GCMParameterSpec(128, iv))
                val plainBytes = cipher.doFinal(cipherText)
                val cached = json.decodeFromString<CloudFavoriteCacheData>(plainBytes.decodeToString())
                _favoriteSongIds.value = cached.likedSongIds
                _subscribedPlaylistIds.value = cached.subscribedPlaylistIds
                favoriteCloudPlaylistId = cached.favoriteCloudPlaylistId
                favoriteCacheUid = cached.uid
            } catch (e: Exception) {
                AppLogger.w("AME_CLOUD_SYNC", "Failed to load AES-256 favorite cache: ${e.message}")
            }
        }
    }

    private fun saveEncryptedFavoritesCache() {
        synchronized(favoriteCacheLock) {
            try {
                val payload = CloudFavoriteCacheData(
                    uid = favoriteCacheUid,
                    likedSongIds = _favoriteSongIds.value,
                    subscribedPlaylistIds = _subscribedPlaylistIds.value,
                    favoriteCloudPlaylistId = favoriteCloudPlaylistId,
                    updatedAt = System.currentTimeMillis()
                )
                val plainBytes = json.encodeToString(payload).encodeToByteArray()
                val iv = ByteArray(12)
                SecureRandom().nextBytes(iv)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAes256Key(), GCMParameterSpec(128, iv))
                val cipherText = cipher.doFinal(plainBytes)

                val out = ByteArray(4 + iv.size + cipherText.size)
                out[0] = 'A'.code.toByte()
                out[1] = 'C'.code.toByte()
                out[2] = 'F'.code.toByte()
                out[3] = '2'.code.toByte()
                System.arraycopy(iv, 0, out, 4, iv.size)
                System.arraycopy(cipherText, 0, out, 16, cipherText.size)
                favoriteCacheFile.writeBytes(out)
            } catch (e: Exception) {
                AppLogger.w("AME_CLOUD_SYNC", "Failed to save AES-256 favorite cache: ${e.message}")
            }
        }
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
            name = name.ifBlank { "未命名歌单" }
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

        _playlists.value = if (existing != null) {
            _playlists.value.map { if (it.id == updatedPlaylist.id) updatedPlaylist else it }
        } else {
            _playlists.value + updatedPlaylist
        }
        savePlaylists()

        if (ncmPlaylistId > 0L) {
            val isFavoriteCloudPlaylist = (favoriteCloudPlaylistId != null && ncmPlaylistId == favoriteCloudPlaylistId) ||
                name.contains("喜欢的音乐") || name.contains("我喜欢的音乐")
            if (isFavoriteCloudPlaylist) {
                if (favoriteCloudPlaylistId == null) {
                    favoriteCloudPlaylistId = ncmPlaylistId
                }
                val cloudSongIds = songs.map { it.id }.toSet()
                if (cloudSongIds.isNotEmpty() || _favoriteSongIds.value.isNotEmpty()) {
                    _favoriteSongIds.value = cloudSongIds
                    saveEncryptedFavoritesCache()
                }
            }
        }

        return updatedPlaylist
    }

    suspend fun syncCloudFavorites(
        ncmApi: NcmApi,
        sessionManager: UserSessionManager,
        cloudPlaylists: List<NcmUserPlaylist>? = null
    ) {
        if (!sessionManager.isLoggedIn()) return
        try {
            val uid = sessionManager.getUserProfile()?.userId
                ?: ncmApi.getUserProfile()?.userId
                ?: 0L
            if (uid <= 0L) return

            val playlists = cloudPlaylists ?: runCatching { ncmApi.getUserPlaylists(uid) }.getOrNull().orEmpty()
            var cacheChanged = false

            if (favoriteCacheUid != uid) {
                favoriteCacheUid = uid
                cacheChanged = true
            }

            if (playlists.isNotEmpty()) {
                val cloudIds = playlists.map { it.id }.toSet()
                if (_subscribedPlaylistIds.value != cloudIds) {
                    _subscribedPlaylistIds.value = cloudIds
                    cacheChanged = true
                }
                val favCloudId = playlists.firstOrNull {
                    it.name.contains("喜欢的音乐") || it.name.contains("我喜欢的音乐")
                }?.id
                if (favCloudId != null && favoriteCloudPlaylistId != favCloudId) {
                    favoriteCloudPlaylistId = favCloudId
                    cacheChanged = true
                }
            }

            val likedIds = ncmApi.getLikedSongIds(uid)
            if (likedIds != null) {
                if (_favoriteSongIds.value != likedIds) {
                    _favoriteSongIds.value = likedIds
                    cacheChanged = true
                }
            } else {
                val favCloudId = favoriteCloudPlaylistId
                if (favCloudId != null) {
                    val syncedFav = _playlists.value.firstOrNull { it.ncmPlaylistId == favCloudId }
                    if (syncedFav != null && syncedFav.songs.isNotEmpty()) {
                        val syncedIds = syncedFav.songs.map { it.id }.toSet()
                        if (_favoriteSongIds.value != syncedIds) {
                            _favoriteSongIds.value = syncedIds
                            cacheChanged = true
                        }
                    }
                }
            }

            if (cacheChanged) {
                saveEncryptedFavoritesCache()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("AME_CLOUD_SYNC", "syncCloudFavorites failed: ${e.message}")
        }
    }

    suspend fun syncAllCloudPlaylistsSilently(
        ncmApi: NcmApi,
        sessionManager: UserSessionManager
    ) {
        if (!sessionManager.isLoggedIn()) return
        try {
            val uid = sessionManager.getUserProfile()?.userId
                ?: ncmApi.getUserProfile()?.userId
                ?: 0L
            if (uid <= 0L) return

            val cloudPlaylists = ncmApi.getUserPlaylists(uid)
            syncCloudFavorites(ncmApi, sessionManager, cloudPlaylists)

            if (cloudPlaylists.isEmpty()) return

            val linkedNcmIds = _playlists.value.mapNotNull { it.ncmPlaylistId }.toMutableSet()
            var boundAny = false
            val updatedBindings = _playlists.value.map { local ->
                if (local.ncmPlaylistId == null) {
                    val matchedCloud = cloudPlaylists.firstOrNull { it.name == local.name }
                    if (matchedCloud != null && linkedNcmIds.add(matchedCloud.id)) {
                        boundAny = true
                        local.copy(
                            ncmPlaylistId = matchedCloud.id,
                            coverImgUrl = matchedCloud.coverImgUrl.ifBlank { local.coverImgUrl }
                        )
                    } else {
                        local
                    }
                } else {
                    local
                }
            }
            if (boundAny) {
                _playlists.value = updatedBindings
                savePlaylists()
            }

            val toSync = _playlists.value.filter { it.ncmPlaylistId != null && it.ncmPlaylistId > 0L }
            for (local in toSync) {
                val ncmId = local.ncmPlaylistId ?: continue
                try {
                    val detail = ncmApi.getPlaylistDetailAndTracks(ncmId)
                    if (detail.playlist != null || detail.tracks.isNotEmpty()) {
                        saveOrUpdateSyncedPlaylist(
                            ncmPlaylistId = ncmId,
                            name = detail.playlist?.name?.ifBlank { local.name } ?: local.name,
                            coverImgUrl = detail.playlist?.coverImgUrl?.ifBlank { local.coverImgUrl } ?: local.coverImgUrl,
                            songs = detail.tracks
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("AME_CLOUD_SYNC", "Silent sync failed for playlist $ncmId: ${e.message}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w("AME_CLOUD_SYNC", "syncAllCloudPlaylistsSilently failed: ${e.message}")
        }
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

    fun isFavorite(songId: String): Boolean {
        return _favoriteSongIds.value.contains(songId)
    }

    fun setFavorite(songId: String, isFav: Boolean) {
        val current = _favoriteSongIds.value
        val updated = if (isFav) current + songId else current - songId
        if (updated != current) {
            _favoriteSongIds.value = updated
            saveEncryptedFavoritesCache()
        }
    }

    fun toggleFavorite(song: PlayableMedia): Boolean {
        val nextFav = !_favoriteSongIds.value.contains(song.id)
        setFavorite(song.id, nextFav)
        val favCloudId = favoriteCloudPlaylistId
        if (favCloudId != null && favCloudId > 0L) {
            val cloudFavPlaylist = _playlists.value.find { it.ncmPlaylistId == favCloudId }
            if (cloudFavPlaylist != null) {
                if (nextFav) {
                    addSongToPlaylist(cloudFavPlaylist.id, song)
                } else {
                    removeSongFromPlaylist(cloudFavPlaylist.id, song.id)
                }
            }
        }
        return nextFav
    }

    /**
     * 在所有本地与已同步歌单中检索歌曲（匹配歌名、歌手、专辑）。
     */
    fun searchSongsInPlaylists(query: String): List<PlaylistSongMatch> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()

        val results = mutableListOf<PlaylistSongMatch>()
        val seenUniqueKeys = mutableSetOf<String>()

        for (playlist in _playlists.value) {
            for (song in playlist.songs) {
                val matches = song.title.lowercase().contains(q) ||
                        song.artist.lowercase().contains(q) ||
                        song.album.lowercase().contains(q) ||
                        (song.transName?.lowercase()?.contains(q) == true) ||
                        (song.alias?.lowercase()?.contains(q) == true)
                if (matches) {
                    val uniqueKey = "${playlist.id}:${song.id}"
                    if (seenUniqueKeys.add(uniqueKey)) {
                        results.add(PlaylistSongMatch(song = song, playlistId = playlist.id, playlistName = playlist.name))
                    }
                }
            }
        }
        return results
    }
}

/**
 * 跨歌单搜索结果匹配模型。
 */
data class PlaylistSongMatch(
    val song: PlayableMediaDTO,
    val playlistId: String,
    val playlistName: String
)
