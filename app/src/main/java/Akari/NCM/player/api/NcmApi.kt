package Akari.NCM.player.api

import Akari.NCM.player.core.*
import Akari.NCM.player.data.UserSessionManager
import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.plugin
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

private val JsonElement?.asJsonObjectOrNull: JsonObject?
    get() = this as? JsonObject

@Singleton
class NcmApi @Inject constructor(
    private val sessionManager: UserSessionManager? = null,
    private val directClient: NeteaseDirectClient = NeteaseDirectClient(sessionManager)
) {
    private val baseUrl: String
        get() = sessionManager?.getApiBaseUrl() ?: UserSessionManager.DEFAULT_API_BASE_URL

    val isDirectMode: Boolean
        get() {
            val url = sessionManager?.getApiBaseUrl() ?: UserSessionManager.DEFAULT_API_BASE_URL
            return url.isBlank() || url.contains("music.163.com")
        }

    private val jsonSerializer = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(jsonSerializer)
        }
        defaultRequest {
            header("User-Agent", "AmePlayer/1.0")
            header("Accept", "application/json")
        }
    }.apply {
        // 动态标头拦截器：若请求未显式配置 Cookie，抓取洗净后的合法 Cookie Header
        plugin(HttpSend).intercept { request ->
            if (!request.headers.contains("Cookie")) {
                val rawCookie = sessionManager?.getCookie()
                val cleanCookie = UserSessionManager.cleanCookieString(rawCookie ?: "")
                if (cleanCookie.isNotBlank()) {
                    request.headers.append("Cookie", cleanCookie)
                }
            }
            execute(request)
        }
    }

    init {
        // 启动时自动静默刷新已登录用户 Profile 信息
        if (sessionManager?.isLoggedIn() == true) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    getUserProfile()
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun searchCloudRaw(keyword: String, type: Int, limit: Int, offset: Int): JsonObject? {
        if (keyword.isBlank()) return null
        if (isDirectMode) {
            try {
                val payload = buildJsonObject {
                    put("s", keyword.trim())
                    put("type", type)
                    put("limit", limit)
                    put("offset", offset)
                    put("total", true)
                }.toString()
                val body = directClient.postEapi(
                    client,
                    NeteaseEndpoints.URL_EAPI_CLOUDSEARCH,
                    NeteaseEndpoints.PATH_EAPI_CLOUDSEARCH,
                    payload
                )
                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val res = json?.get("result")?.asJsonObjectOrNull ?: json
                if (res != null) return res
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("AME_NCM_API", "Direct search type=$type failed: ${e.message}")
            }
        }
        return try {
            val url = if (baseUrl.isNotBlank()) "$baseUrl/cloudsearch" else "$baseUrl/search"
            val response = try {
                client.get(url) {
                    parameter("keywords", keyword.trim())
                    parameter("type", type)
                    parameter("limit", limit)
                    parameter("offset", offset)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                client.get("$baseUrl/search") {
                    parameter("keywords", keyword.trim())
                    parameter("type", type)
                    parameter("limit", limit)
                    parameter("offset", offset)
                }
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            json?.get("result")?.asJsonObjectOrNull ?: json
        } catch (_: Exception) {
            null
        }
    }

    suspend fun searchSongs(keyword: String, limit: Int = 30, offset: Int = 0): List<PlayableMedia.Online> {
        val json = searchCloudRaw(keyword, type = 1, limit = limit, offset = offset) ?: return emptyList()
        val songs = json["songs"]?.jsonArray ?: return emptyList()
        return songs.mapNotNull { parseSong(it.asJsonObjectOrNull) }
    }

    suspend fun searchAlbums(keyword: String, limit: Int = 30, offset: Int = 0): List<NcmAlbum> {
        val json = searchCloudRaw(keyword, type = 10, limit = limit, offset = offset) ?: return emptyList()
        val albums = json["albums"]?.jsonArray ?: return emptyList()
        return albums.mapNotNull { parseAlbum(it.asJsonObjectOrNull) }
    }

    suspend fun searchArtists(keyword: String, limit: Int = 30, offset: Int = 0): List<NcmArtist> {
        val json = searchCloudRaw(keyword, type = 100, limit = limit, offset = offset) ?: return emptyList()
        val artists = json["artists"]?.jsonArray ?: return emptyList()
        return artists.mapNotNull { parseArtist(it.asJsonObjectOrNull) }
    }

    suspend fun searchLyrics(keyword: String, limit: Int = 30, offset: Int = 0): List<PlayableMedia.Online> {
        val json = searchCloudRaw(keyword, type = 1006, limit = limit, offset = offset) ?: return emptyList()
        val songs = json["songs"]?.jsonArray ?: return emptyList()
        return songs.mapNotNull { element ->
            val obj = element.asJsonObjectOrNull ?: return@mapNotNull null
            val lyricsArray = obj["lyrics"]?.jsonArray
            val rawLyric = lyricsArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
            val cleanLyric = rawLyric?.replace(Regex("\\[\\d+:\\d+.*?\\]"), "")?.trim()
            parseSong(obj, matchedLyric = cleanLyric)
        }
    }

    /**
     * 混合搜索模式（默认模式）：
     * - 单曲（包含歌词命中，优先歌曲名匹配）限制展示 10 首；
     * - 专辑限制展示 5 个；
     * - 歌手仅在名称匹配时展示。
     */
    suspend fun searchMixed(keyword: String): NcmMixedSearchResult = coroutineScope {
        if (keyword.isBlank()) return@coroutineScope NcmMixedSearchResult()

        val songsDeferred = async { searchSongs(keyword, limit = 10, offset = 0) }
        val lyricsDeferred = async { searchLyrics(keyword, limit = 10, offset = 0) }
        val albumsDeferred = async { searchAlbums(keyword, limit = 5, offset = 0) }
        val artistsDeferred = async { searchArtists(keyword, limit = 5, offset = 0) }

        val directSongs = songsDeferred.await()
        val lyricSongs = lyricsDeferred.await()
        val rawAlbums = albumsDeferred.await()
        val rawArtists = artistsDeferred.await()

        // 单曲组合：优先歌曲名匹配，歌词命中补充，按 id 去重，最多 10 首
        val combinedSongs = mutableListOf<PlayableMedia.Online>()
        val seenIds = mutableSetOf<String>()
        for (s in directSongs) {
            if (seenIds.add(s.id)) {
                combinedSongs.add(s)
            }
        }
        for (s in lyricSongs) {
            if (combinedSongs.size >= 10) break
            if (seenIds.add(s.id)) {
                combinedSongs.add(s)
            }
        }
        val finalSongs = combinedSongs.take(10)

        // 专辑限制 5 个
        val finalAlbums = rawAlbums.take(5)

        // 歌手：仅在名称或别名包含关键词时展示
        val q = keyword.trim().lowercase()
        val matchedArtists = rawArtists.filter { artist ->
            artist.name.lowercase().contains(q) ||
            artist.alias.any { it.lowercase().contains(q) } ||
            (artist.trans?.lowercase()?.contains(q) == true)
        }.take(2)

        NcmMixedSearchResult(
            songs = finalSongs,
            albums = finalAlbums,
            artists = matchedArtists
        )
    }

    suspend fun getAlbumTracks(albumId: Long): List<PlayableMedia.Online> {
        if (isDirectMode) {
            try {
                val body = directClient.postEapi(
                    client,
                    "${NeteaseEndpoints.HOST_SERVICE}/eapi/v1/album/$albumId",
                    "/api/v1/album/$albumId",
                    "{}"
                )
                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val songs = json?.get("songs")?.jsonArray
                if (songs != null) {
                    val list = songs.mapNotNull { parseSong(it.asJsonObjectOrNull) }
                    if (list.isNotEmpty()) return list
                }
            } catch (_: Exception) {}
        }
        return try {
            val response = client.get("$baseUrl/album") {
                parameter("id", albumId)
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val songs = json?.get("songs")?.jsonArray ?: return emptyList()
            songs.mapNotNull { parseSong(it.asJsonObjectOrNull) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getArtistTopSongs(artistId: Long): List<PlayableMedia.Online> {
        if (isDirectMode) {
            try {
                val body = directClient.postEapi(
                    client,
                    "${NeteaseEndpoints.HOST_SERVICE}/eapi/artist/top/song",
                    "/api/artist/top/song",
                    buildJsonObject { put("id", artistId) }.toString()
                )
                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val songs = json?.get("songs")?.jsonArray
                if (songs != null) {
                    val list = songs.mapNotNull { parseSong(it.asJsonObjectOrNull) }
                    if (list.isNotEmpty()) return list
                }
            } catch (_: Exception) {}
        }
        return try {
            val response = client.get("$baseUrl/artist/top/song") {
                parameter("id", artistId)
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val songs = json?.get("songs")?.jsonArray ?: return emptyList()
            songs.mapNotNull { parseSong(it.asJsonObjectOrNull) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getSongDetails(songIds: List<String>): List<PlayableMedia.Online> {
        if (songIds.isEmpty()) return emptyList()
        if (isDirectMode) {
            try {
                val cArray = buildJsonArray {
                    songIds.forEach { idStr ->
                        val idLong = idStr.toLongOrNull()
                        if (idLong != null) {
                            add(buildJsonObject { put("id", idLong) })
                        }
                    }
                }.toString()
                val payload = buildJsonObject {
                    put("c", cArray)
                }.toString()
                val body = directClient.postEapi(
                    client,
                    NeteaseEndpoints.URL_EAPI_SONG_DETAIL,
                    NeteaseEndpoints.PATH_EAPI_SONG_DETAIL,
                    payload
                )
                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val songs = json?.get("songs")?.jsonArray
                if (songs != null) {
                    val list = songs.mapNotNull { parseSong(it.asJsonObjectOrNull) }
                    if (list.isNotEmpty()) return list
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("AME_NCM_API", "Direct getSongDetails failed: ${e.message}")
            }
        }
        return try {
            val response = client.get("$baseUrl/song/detail") {
                parameter("ids", songIds.joinToString(","))
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull ?: return emptyList()
            val songs = json["songs"]?.jsonArray ?: return emptyList()
            songs.mapNotNull { parseSong(it.asJsonObjectOrNull) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 获取歌曲播放链接 (自动匹配账号最高可用音质)
     */
    suspend fun getSongUrl(song: PlayableMedia.Online, quality: QualityLevel? = null): String? {
        Log.i("[AME_ONLINE_PLAY]", ">>> Requesting song URL for songId='${song.id}', title='${song.title}' (isDirectMode=$isDirectMode)")

        // 默认按账号可获取的最高级别链依次尝试: HIRES -> LOSSLESS -> EXHIGH -> STANDARD
        val qualitiesToTry = if (quality != null) {
            listOf(quality)
        } else {
            listOf(QualityLevel.HIRES, QualityLevel.LOSSLESS, QualityLevel.EXHIGH, QualityLevel.STANDARD)
        }

        if (isDirectMode) {
            for (q in qualitiesToTry) {
                try {
                    val startTime = System.currentTimeMillis()
                    val payload = buildJsonObject {
                        put("ids", "[${song.id}]")
                        put("level", q.value)
                        put("encodeType", "flac")
                    }.toString()
                    val body = directClient.postEapi(
                        client,
                        NeteaseEndpoints.URL_EAPI_SONG_URL,
                        NeteaseEndpoints.PATH_EAPI_SONG_URL,
                        payload
                    )
                    val cost = System.currentTimeMillis() - startTime
                    val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                    val data = json?.get("data")?.jsonArray?.firstOrNull()?.asJsonObjectOrNull
                    val url = data?.get("url")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

                    if (url != null) {
                        Log.i("[AME_ONLINE_PLAY]", ">>> [SUCCESS] Direct resolved URL for '${song.title}' with level=${q.value} (cost=${cost}ms): $url")
                        return url
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.d("[AME_ONLINE_PLAY]", "--- Direct quality level ${q.value} try failed: ${e.message}")
                }
            }
        }

        for (q in qualitiesToTry) {
            try {
                val startTime = System.currentTimeMillis()
                val response = client.get("$baseUrl/song/url/v1") {
                    parameter("id", song.id)
                    parameter("level", q.value)
                }
                val cost = System.currentTimeMillis() - startTime
                val body = response.bodyAsText()

                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val data = json?.get("data")?.jsonArray?.firstOrNull()?.asJsonObjectOrNull
                val url = data?.get("url")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

                if (url != null) {
                    Log.i("[AME_ONLINE_PLAY]", ">>> [SUCCESS] Successfully resolved URL for '${song.title}' with level=${q.value} (cost=${cost}ms): $url")
                    return url
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.d("[AME_ONLINE_PLAY]", "--- Quality level ${q.value} try failed: ${e.message}")
            }
        }

        // 兜底退回旧接口 /song/url
        Log.w("[AME_ONLINE_PLAY]", "--- Trying legacy fallback for '${song.title}' (id=${song.id}) ---")
        for (br in listOf(320000, 128000)) {
            val fallbackUrl = getForcedMp3Url(song.id, br)
            if (fallbackUrl != null) return fallbackUrl
        }

        Log.e("[AME_ONLINE_PLAY]", ">>> All song URL resolution attempts failed for '${song.title}' (id=${song.id})")
        return null
    }

    private suspend fun getForcedMp3Url(songId: String, bitrate: Int): String? {
        return try {
            val response = client.get("$baseUrl/song/url") {
                parameter("id", songId)
                parameter("br", bitrate)
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val data = json?.get("data")?.jsonArray?.firstOrNull()?.asJsonObjectOrNull
            data?.get("url")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getLyric(songId: String): Pair<String?, String?>? {
        if (isDirectMode) {
            try {
                val songIdLong = songId.toLongOrNull() ?: 0L
                val payload = buildJsonObject {
                    put("id", songIdLong)
                    put("cp", false)
                    put("tv", 0)
                    put("lv", 0)
                    put("rv", 0)
                    put("kv", 0)
                    put("yv", 0)
                }.toString()
                val body = directClient.postEapi(
                    client,
                    NeteaseEndpoints.URL_EAPI_SONG_LYRIC,
                    NeteaseEndpoints.PATH_EAPI_SONG_LYRIC,
                    payload
                )
                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val lrc = json?.get("lrc")?.asJsonObjectOrNull?.get("lyric")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val tlyric = json?.get("tlyric")?.asJsonObjectOrNull?.get("lyric")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                if (lrc != null || tlyric != null) return Pair(lrc, tlyric)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("AME_NCM_API", "Direct getLyric failed: ${e.message}")
            }
        }
        return try {
            val response = client.get("$baseUrl/lyric") {
                parameter("id", songId)
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull ?: return null
            val lrc = json["lrc"]?.asJsonObjectOrNull?.get("lyric")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val tlyric = json["tlyric"]?.asJsonObjectOrNull?.get("lyric")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (lrc != null || tlyric != null) Pair(lrc, tlyric) else null
        } catch (_: Exception) {
            null
        }
    }

    // ==========================================
    // 扫码登录 API 支持
    // ==========================================

    suspend fun getQrKey(): String? {
        if (isDirectMode) {
            try {
                val payload = buildJsonObject {
                    put("type", 1)
                    put("csrf_token", "")
                }.toString()
                val (body, _) = directClient.postWeapi(
                    client,
                    NeteaseEndpoints.URL_WEAPI_LOGIN_QR_KEY,
                    payload,
                    isQrLogin = true
                )
                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val key = json?.get("data")?.asJsonObjectOrNull?.get("unikey")?.jsonPrimitive?.contentOrNull
                    ?: json?.get("unikey")?.jsonPrimitive?.contentOrNull
                Log.i("AME_QR_DEBUG", "Direct getQrKey result: $key")
                if (!key.isNullOrBlank()) return key
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("AME_QR_DEBUG", "Direct getQrKey failed", e)
            }
        }
        return try {
            val response = client.get("$baseUrl/login/qr/key") {
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val key = json?.get("data")?.asJsonObjectOrNull?.get("unikey")?.jsonPrimitive?.contentOrNull
                ?: json?.get("unikey")?.jsonPrimitive?.contentOrNull
            Log.i("AME_QR_DEBUG", "getQrKey result: $key")
            key?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("AME_QR_DEBUG", "getQrKey failed", e)
            null
        }
    }

    suspend fun createQrUrl(key: String): Pair<String?, String?>? {
        if (isDirectMode) {
            val qrUrl = NeteaseEndpoints.QR_LOGIN_URL_PREFIX + key
            Log.i("AME_QR_DEBUG", "Direct createQrUrl: qrurl=$qrUrl")
            return Pair(null, qrUrl)
        }
        return try {
            val response = client.get("$baseUrl/login/qr/create") {
                parameter("key", key)
                parameter("qrimg", true)
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val dataObj = json?.get("data")?.asJsonObjectOrNull
            val qrimg = dataObj?.get("qrimg")?.jsonPrimitive?.contentOrNull
            val qrurl = dataObj?.get("qrurl")?.jsonPrimitive?.contentOrNull
            Log.i("AME_QR_DEBUG", "createQrUrl result length: ${qrimg?.length ?: 0}, hasUrl: ${qrurl != null}")
            Pair(qrimg, qrurl)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("AME_QR_DEBUG", "createQrUrl failed", e)
            null
        }
    }

    suspend fun checkQrStatus(key: String): QrCheckResult {
        if (isDirectMode) {
            try {
                val payload = buildJsonObject {
                    put("key", key)
                    put("type", 1)
                    put("csrf_token", "")
                }.toString()
                val (body, setCookies) = directClient.postWeapi(
                    client,
                    NeteaseEndpoints.URL_WEAPI_LOGIN_QR_CHECK,
                    payload,
                    isQrLogin = true
                )
                val json = try { Json.parseToJsonElement(body).asJsonObjectOrNull } catch (_: Exception) { null }
                val code = json?.get("code")?.jsonPrimitive?.intOrNull ?: 0
                val message = json?.get("message")?.jsonPrimitive?.contentOrNull ?: ""

                val status = when (code) {
                    800 -> QrStatus.EXPIRED
                    801 -> QrStatus.WAITING
                    802 -> QrStatus.SCANNING
                    803 -> {
                        var cookieStr = json?.get("cookie")?.jsonPrimitive?.contentOrNull ?: ""
                        if (cookieStr.isBlank() && setCookies.isNotEmpty()) {
                            cookieStr = setCookies.joinToString("; ")
                        }
                        val cleanedCookie = UserSessionManager.cleanCookieString(cookieStr)
                        Log.i("AME_LOGIN_DEBUG", "803 Success! Extracted & Cleaned cookie: $cleanedCookie")
                        if (cleanedCookie.isNotBlank()) {
                            sessionManager?.saveCookie(cleanedCookie)
                            getUserProfile()
                        }
                        QrStatus.SUCCESS
                    }
                    else -> QrStatus.UNKNOWN
                }
                return QrCheckResult(status = status, message = message, cookie = sessionManager?.getCookie() ?: "")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("AME_LOGIN_DEBUG", "Direct checkQrStatus exception", e)
                return QrCheckResult(status = QrStatus.UNKNOWN, message = e.message ?: "Network error")
            }
        }
        return try {
            val response = client.get("$baseUrl/login/qr/check") {
                parameter("key", key)
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            val json = try { Json.parseToJsonElement(body).asJsonObjectOrNull } catch (_: Exception) { null }
            val code = json?.get("code")?.jsonPrimitive?.intOrNull ?: 0
            val message = json?.get("message")?.jsonPrimitive?.contentOrNull ?: ""

            val status = when (code) {
                800 -> QrStatus.EXPIRED
                801 -> QrStatus.WAITING
                802 -> QrStatus.SCANNING
                803 -> {
                    var cookieStr = json?.get("cookie")?.jsonPrimitive?.contentOrNull ?: ""
                    if (cookieStr.isBlank()) {
                        val setCookies = response.headers.getAll("Set-Cookie")
                        if (!setCookies.isNullOrEmpty()) {
                            cookieStr = setCookies.joinToString("; ")
                        }
                    }
                    val cleanedCookie = UserSessionManager.cleanCookieString(cookieStr)
                    Log.i("AME_LOGIN_DEBUG", "803 Success! Extracted & Cleaned cookie: $cleanedCookie")
                    if (cleanedCookie.isNotBlank()) {
                        sessionManager?.saveCookie(cleanedCookie)
                        getUserProfile()
                    }
                    QrStatus.SUCCESS
                }
                else -> QrStatus.UNKNOWN
            }
            QrCheckResult(status = status, message = message, cookie = sessionManager?.getCookie() ?: "")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("AME_LOGIN_DEBUG", "checkQrStatus exception", e)
            QrCheckResult(status = QrStatus.UNKNOWN, message = e.message ?: "Network error")
        }
    }

    // ==========================================
    // 用户个人中心与歌单 API 支持
    // ==========================================

    suspend fun getUserProfile(): UserProfile? {
        if (isDirectMode) {
            try {
                val body = directClient.postEapi(
                    client,
                    NeteaseEndpoints.URL_EAPI_USER_ACCOUNT,
                    NeteaseEndpoints.PATH_EAPI_USER_ACCOUNT,
                    "{}"
                )
                val rootObj = try { Json.parseToJsonElement(body).asJsonObjectOrNull } catch (_: Exception) { null }

                val profileObj = rootObj?.get("profile")?.asJsonObjectOrNull
                    ?: rootObj?.get("account")?.asJsonObjectOrNull
                    ?: rootObj?.get("data")?.asJsonObjectOrNull?.get("profile")?.asJsonObjectOrNull
                    ?: rootObj?.get("data")?.asJsonObjectOrNull?.get("account")?.asJsonObjectOrNull

                var userId = profileObj?.get("userId")?.jsonPrimitive?.longOrNull
                    ?: profileObj?.get("id")?.jsonPrimitive?.longOrNull
                    ?: rootObj?.get("account")?.asJsonObjectOrNull?.get("id")?.jsonPrimitive?.longOrNull ?: 0L

                var nickname = profileObj?.get("nickname")?.jsonPrimitive?.contentOrNull
                    ?: profileObj?.get("userName")?.jsonPrimitive?.contentOrNull ?: ""
                var avatarUrl = profileObj?.get("avatarUrl")?.jsonPrimitive?.contentOrNull ?: ""
                var vipType = profileObj?.get("vipType")?.jsonPrimitive?.intOrNull ?: 0
                var signature = profileObj?.get("signature")?.jsonPrimitive?.contentOrNull ?: ""

                if (userId > 0L) {
                    try {
                        val detailBody = directClient.postEapi(
                            client,
                            NeteaseEndpoints.URL_EAPI_USER_DETAIL,
                            NeteaseEndpoints.PATH_EAPI_USER_DETAIL,
                            """{"userId":$userId}"""
                        )
                        val detailJson = Json.parseToJsonElement(detailBody).asJsonObjectOrNull
                        val detailProfile = detailJson?.get("profile")?.asJsonObjectOrNull
                        if (detailProfile != null) {
                            nickname = detailProfile["nickname"]?.jsonPrimitive?.contentOrNull ?: nickname
                            avatarUrl = detailProfile["avatarUrl"]?.jsonPrimitive?.contentOrNull ?: avatarUrl
                            vipType = detailProfile["vipType"]?.jsonPrimitive?.intOrNull ?: vipType
                            signature = detailProfile["signature"]?.jsonPrimitive?.contentOrNull ?: signature
                        }
                    } catch (_: Exception) {}
                }

                if (nickname.isNotBlank() || userId > 0L) {
                    val profile = UserProfile(
                        userId = userId,
                        nickname = nickname.ifBlank { "网易云用户$userId" },
                        avatarUrl = avatarUrl,
                        vipType = vipType,
                        signature = signature
                    )
                    Log.i("AME_LOGIN_DEBUG", "Direct getUserProfile success: userId=$userId, nickname=${profile.nickname}")
                    sessionManager?.saveUserProfile(profile)
                    return profile
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("AME_LOGIN_DEBUG", "Direct getUserProfile failed: ${e.message}")
            }
        }
        return try {
            val response = client.get("$baseUrl/user/account") {
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            Log.i("AME_LOGIN_DEBUG", "/user/account body: $body")
            val rootObj = try { Json.parseToJsonElement(body).asJsonObjectOrNull } catch (_: Exception) { null }

            val profileObj = rootObj?.get("profile")?.asJsonObjectOrNull
                ?: rootObj?.get("account")?.asJsonObjectOrNull
                ?: rootObj?.get("data")?.asJsonObjectOrNull?.get("profile")?.asJsonObjectOrNull
                ?: rootObj?.get("data")?.asJsonObjectOrNull?.get("account")?.asJsonObjectOrNull

            var userId = profileObj?.get("userId")?.jsonPrimitive?.longOrNull
                ?: profileObj?.get("id")?.jsonPrimitive?.longOrNull
                ?: rootObj?.get("account")?.asJsonObjectOrNull?.get("id")?.jsonPrimitive?.longOrNull ?: 0L

            var nickname = profileObj?.get("nickname")?.jsonPrimitive?.contentOrNull
                ?: profileObj?.get("userName")?.jsonPrimitive?.contentOrNull ?: ""
            var avatarUrl = profileObj?.get("avatarUrl")?.jsonPrimitive?.contentOrNull ?: ""
            var vipType = profileObj?.get("vipType")?.jsonPrimitive?.intOrNull ?: 0
            var signature = profileObj?.get("signature")?.jsonPrimitive?.contentOrNull ?: ""

            if (userId > 0L) {
                try {
                    val detailResp = client.get("$baseUrl/user/detail") {
                        parameter("uid", userId)
                        parameter("timestamp", System.currentTimeMillis())
                    }
                    val detailBody = detailResp.bodyAsText()
                    val detailJson = Json.parseToJsonElement(detailBody).asJsonObjectOrNull
                    val detailProfile = detailJson?.get("profile")?.asJsonObjectOrNull
                    if (detailProfile != null) {
                        nickname = detailProfile["nickname"]?.jsonPrimitive?.contentOrNull ?: nickname
                        avatarUrl = detailProfile["avatarUrl"]?.jsonPrimitive?.contentOrNull ?: avatarUrl
                        vipType = detailProfile["vipType"]?.jsonPrimitive?.intOrNull ?: vipType
                        signature = detailProfile["signature"]?.jsonPrimitive?.contentOrNull ?: signature
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e("AME_LOGIN_DEBUG", "fetch /user/detail failed", e)
                }
            }

            if (nickname.isBlank()) {
                try {
                    val statusResp = client.get("$baseUrl/login/status") {
                        parameter("timestamp", System.currentTimeMillis())
                    }
                    val statusBody = statusResp.bodyAsText()
                    val statusRoot = Json.parseToJsonElement(statusBody).asJsonObjectOrNull
                    val statusData = statusRoot?.get("data")?.asJsonObjectOrNull
                    val statusProfile = statusData?.get("profile")?.asJsonObjectOrNull
                        ?: statusRoot?.get("profile")?.asJsonObjectOrNull
                    if (statusProfile != null) {
                        userId = statusProfile["userId"]?.jsonPrimitive?.longOrNull ?: userId
                        nickname = statusProfile["nickname"]?.jsonPrimitive?.contentOrNull ?: nickname
                        avatarUrl = statusProfile["avatarUrl"]?.jsonPrimitive?.contentOrNull ?: avatarUrl
                        vipType = statusProfile["vipType"]?.jsonPrimitive?.intOrNull ?: vipType
                        signature = statusProfile["signature"]?.jsonPrimitive?.contentOrNull ?: signature
                    }
                } catch (_: Exception) {}
            }

            if (nickname.isNotBlank() || userId > 0L) {
                val profile = UserProfile(
                    userId = userId,
                    nickname = nickname.ifBlank { "网易云用户$userId" },
                    avatarUrl = avatarUrl,
                    vipType = vipType,
                    signature = signature
                )
                Log.i("AME_LOGIN_DEBUG", "getUserProfile success: userId=$userId, nickname=${profile.nickname}, avatar=$avatarUrl")
                sessionManager?.saveUserProfile(profile)
                return profile
            }
            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("AME_LOGIN_DEBUG", "getUserProfile Exception", e)
            null
        }
    }

    suspend fun getHomepageDragonBalls(): List<HomepageDragonBall> {
        if (isDirectMode) {
            try {
                val body = directClient.postEapi(
                    client,
                    NeteaseEndpoints.URL_EAPI_HOMEPAGE_DRAGON_BALL,
                    NeteaseEndpoints.PATH_EAPI_HOMEPAGE_DRAGON_BALL,
                    "{}"
                )
                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val data = json?.get("data")?.jsonArray
                if (data != null && data.isNotEmpty()) {
                    return data.mapNotNull {
                        val obj = it.asJsonObjectOrNull ?: return@mapNotNull null
                        HomepageDragonBall(
                            id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            iconUrl = obj["iconUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                            url = obj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("AME_HOMEPAGE", "Direct getHomepageDragonBalls failed: ${e.message}")
            }
        }
        return try {
            val response = client.get("$baseUrl/homepage/dragon/ball") {
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val data = json?.get("data")?.jsonArray ?: return emptyList()
            data.mapNotNull {
                val obj = it.asJsonObjectOrNull ?: return@mapNotNull null
                HomepageDragonBall(
                    id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    iconUrl = obj["iconUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                    url = obj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getHomepageBlocks(): List<HomepageBlock> {
        val bodyText = if (isDirectMode) {
            try {
                directClient.postEapi(
                    client,
                    NeteaseEndpoints.URL_EAPI_HOMEPAGE_BLOCK,
                    NeteaseEndpoints.PATH_EAPI_HOMEPAGE_BLOCK,
                    """{"refresh":true}"""
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("AME_HOMEPAGE", "Direct getHomepageBlocks failed: ${e.message}")
                null
            }
        } else null

        val body = bodyText ?: try {
            val response = client.get("$baseUrl/homepage/block/page") {
                parameter("refresh", true)
                parameter("timestamp", System.currentTimeMillis())
            }
            response.bodyAsText()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return emptyList()
        }

        return try {
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val data = json?.get("data")?.asJsonObjectOrNull
            val blocks = data?.get("blocks")?.jsonArray ?: return emptyList()
            blocks.mapNotNull { blockElement ->
                val block = blockElement.asJsonObjectOrNull ?: return@mapNotNull null
                val blockCode = block["blockCode"]?.jsonPrimitive?.contentOrNull ?: ""
                val uiElement = block["uiElement"]?.asJsonObjectOrNull
                val title = uiElement?.get("subTitle")?.asJsonObjectOrNull?.get("title")?.jsonPrimitive?.contentOrNull ?: ""

                val creativesElement = block["creatives"]?.jsonArray ?: return@mapNotNull null
                val creativesList = creativesElement.mapNotNull { creativeElement ->
                    val creative = creativeElement.asJsonObjectOrNull ?: return@mapNotNull null
                    val creativeType = creative["creativeType"]?.jsonPrimitive?.contentOrNull ?: ""
                    val resources = creative["resources"]?.jsonArray ?: return@mapNotNull null
                    if (resources.isEmpty()) return@mapNotNull null

                    val resource = resources[0].asJsonObjectOrNull ?: return@mapNotNull null
                    val resourceId = resource["resourceId"]?.jsonPrimitive?.contentOrNull ?: ""
                    val resourceUiElement = resource["uiElement"]?.asJsonObjectOrNull
                    val creativeTitle = resourceUiElement?.get("mainTitle")?.asJsonObjectOrNull?.get("title")?.jsonPrimitive?.contentOrNull ?: ""
                    val imageUrl = resourceUiElement?.get("image")?.asJsonObjectOrNull?.get("imageUrl")?.jsonPrimitive?.contentOrNull ?: ""
                    val playCount = resource["resourceExtInfo"]?.asJsonObjectOrNull?.get("playCount")?.jsonPrimitive?.longOrNull ?: 0L

                    HomepageBlock.Creative(
                        creativeType = creativeType,
                        title = creativeTitle,
                        imageUrl = imageUrl,
                        resourceId = resourceId,
                        playCount = playCount
                    )
                }

                HomepageBlock(
                    blockCode = blockCode,
                    title = title,
                    creatives = creativesList
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("AME_HOMEPAGE", "getHomepageBlocks Error", e)
            emptyList()
        }
    }

    suspend fun getDailyRecommendPlaylists(): List<NcmUserPlaylist> {
        if (isDirectMode) {
            try {
                val (body, _) = directClient.postWeapi(
                    client,
                    NeteaseEndpoints.URL_WEAPI_RECOMMEND_RESOURCE,
                    """{"csrf_token":""}"""
                )
                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val recommend = json?.get("recommend")?.jsonArray
                if (recommend != null) {
                    return recommend.mapNotNull {
                        val obj = it.asJsonObjectOrNull ?: return@mapNotNull null
                        NcmUserPlaylist(
                            id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            coverImgUrl = obj["picUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                            trackCount = obj["trackCount"]?.jsonPrimitive?.intOrNull ?: 0,
                            playCount = obj["playcount"]?.jsonPrimitive?.longOrNull ?: 0L,
                            creatorName = obj["creator"]?.asJsonObjectOrNull?.get("nickname")?.jsonPrimitive?.contentOrNull ?: "",
                            description = obj["copywriter"]?.jsonPrimitive?.contentOrNull ?: ""
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("AME_PLAYLIST_DEBUG", "Direct getDailyRecommendPlaylists failed: ${e.message}")
            }
        }
        return try {
            val response = client.get("$baseUrl/recommend/resource") {
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val recommend = json?.get("recommend")?.jsonArray ?: return emptyList()
            recommend.mapNotNull {
                val obj = it.asJsonObjectOrNull ?: return@mapNotNull null
                NcmUserPlaylist(
                    id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    coverImgUrl = obj["picUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                    trackCount = obj["trackCount"]?.jsonPrimitive?.intOrNull ?: 0,
                    playCount = obj["playcount"]?.jsonPrimitive?.longOrNull ?: 0L,
                    creatorName = obj["creator"]?.asJsonObjectOrNull?.get("nickname")?.jsonPrimitive?.contentOrNull ?: "",
                    description = obj["copywriter"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
    }

    suspend fun getHighQualityPlaylists(): List<NcmUserPlaylist> {
        if (isDirectMode) {
            try {
                val payload = buildJsonObject {
                    put("cat", "全部")
                    put("limit", 30)
                    put("lasttime", 0)
                    put("total", true)
                    put("csrf_token", "")
                }.toString()
                val (body, _) = directClient.postWeapi(
                    client,
                    NeteaseEndpoints.URL_WEAPI_TOP_PLAYLIST_HIGHQUALITY,
                    payload
                )
                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val playlists = json?.get("playlists")?.jsonArray
                if (playlists != null) {
                    return playlists.mapNotNull {
                        val obj = it.asJsonObjectOrNull ?: return@mapNotNull null
                        NcmUserPlaylist(
                            id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            coverImgUrl = obj["coverImgUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                            trackCount = obj["trackCount"]?.jsonPrimitive?.intOrNull ?: 0,
                            playCount = obj["playCount"]?.jsonPrimitive?.longOrNull ?: 0L,
                            creatorName = obj["creator"]?.asJsonObjectOrNull?.get("nickname")?.jsonPrimitive?.contentOrNull ?: "",
                            description = obj["copywriter"]?.jsonPrimitive?.contentOrNull ?: ""
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("AME_PLAYLIST_DEBUG", "Direct getHighQualityPlaylists failed: ${e.message}")
            }
        }
        return try {
            val response = client.get("$baseUrl/top/playlist/highquality") {
                parameter("limit", 30)
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val playlists = json?.get("playlists")?.jsonArray ?: return emptyList()
            playlists.mapNotNull {
                val obj = it.asJsonObjectOrNull ?: return@mapNotNull null
                NcmUserPlaylist(
                    id = obj["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    coverImgUrl = obj["coverImgUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                    trackCount = obj["trackCount"]?.jsonPrimitive?.intOrNull ?: 0,
                    playCount = obj["playCount"]?.jsonPrimitive?.longOrNull ?: 0L,
                    creatorName = obj["creator"]?.asJsonObjectOrNull?.get("nickname")?.jsonPrimitive?.contentOrNull ?: "",
                    description = obj["copywriter"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
    }

    suspend fun getDailyRecommendSongs(): List<PlayableMedia.Online> {
        if (isDirectMode) {
            try {
                val body = directClient.postEapi(
                    client,
                    NeteaseEndpoints.URL_EAPI_RECOMMEND_SONGS,
                    NeteaseEndpoints.PATH_EAPI_RECOMMEND_SONGS,
                    "{}"
                )
                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val data = json?.get("data")?.asJsonObjectOrNull
                val dailySongs = data?.get("dailySongs")?.jsonArray
                    ?: json?.get("recommend")?.jsonArray
                if (dailySongs != null) {
                    val list = dailySongs.mapNotNull { parseSong(it.asJsonObjectOrNull) }
                    if (list.isNotEmpty()) return list
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("AME_PLAYLIST_DEBUG", "Direct getDailyRecommendSongs failed: ${e.message}")
            }
        }
        return try {
            val response = client.get("$baseUrl/recommend/songs") {
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val data = json?.get("data")?.asJsonObjectOrNull
            val dailySongs = data?.get("dailySongs")?.jsonArray ?: return emptyList()
            dailySongs.mapNotNull { parseSong(it.asJsonObjectOrNull) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getUserPlaylists(uid: Long): List<NcmUserPlaylist> {
        if (isDirectMode) {
            try {
                val targetUid = if (uid > 0L) uid else (sessionManager?.getUserProfile()?.userId ?: 0L)
                val payload = buildJsonObject {
                    put("uid", targetUid)
                    put("limit", 100)
                    put("offset", 0)
                }.toString()
                val body = directClient.postEapi(
                    client,
                    NeteaseEndpoints.URL_EAPI_USER_PLAYLIST,
                    NeteaseEndpoints.PATH_EAPI_USER_PLAYLIST,
                    payload
                )
                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val playlists = json?.get("playlist")?.jsonArray
                if (playlists != null) {
                    return playlists.mapNotNull {
                        val obj = it.asJsonObjectOrNull ?: return@mapNotNull null
                        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                        NcmUserPlaylist(
                            id = id,
                            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            coverImgUrl = obj["coverImgUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                            trackCount = obj["trackCount"]?.jsonPrimitive?.intOrNull ?: 0,
                            playCount = obj["playCount"]?.jsonPrimitive?.longOrNull ?: 0L,
                            creatorName = obj["creator"]?.asJsonObjectOrNull?.get("nickname")?.jsonPrimitive?.contentOrNull ?: "",
                            description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("AME_PLAYLIST_DEBUG", "Direct getUserPlaylists failed: ${e.message}")
            }
        }
        return try {
            Log.i("AME_PLAYLIST_DEBUG", "getUserPlaylists requesting with uid=$uid")
            val response = client.get("$baseUrl/user/playlist") {
                if (uid > 0L) parameter("uid", uid)
                parameter("limit", 100)
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val playlists = json?.get("playlist")?.jsonArray ?: return emptyList()
            playlists.mapNotNull {
                val obj = it.asJsonObjectOrNull ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                NcmUserPlaylist(
                    id = id,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    coverImgUrl = obj["coverImgUrl"]?.jsonPrimitive?.contentOrNull ?: "",
                    trackCount = obj["trackCount"]?.jsonPrimitive?.intOrNull ?: 0,
                    playCount = obj["playCount"]?.jsonPrimitive?.longOrNull ?: 0L,
                    creatorName = obj["creator"]?.asJsonObjectOrNull?.get("nickname")?.jsonPrimitive?.contentOrNull ?: "",
                    description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("AME_PLAYLIST_DEBUG", "getUserPlaylists Exception", e)
            emptyList()
        }
    }

data class NcmPlaylistDetailResult(
    val playlist: NcmUserPlaylist?,
    val tracks: List<PlayableMedia.Online>
)

    /**
     * 全量循环分页拉取歌单曲目及歌单详情，带 10s 超时防护与实时 (fetched/total) 进度回调
     */
    suspend fun getPlaylistDetailAndTracks(
        playlistId: Long,
        totalCount: Int = 0,
        onProgress: ((fetched: Int, total: Int) -> Unit)? = null
    ): NcmPlaylistDetailResult {
        var parsedPlaylistMeta: NcmUserPlaylist? = null

        if (isDirectMode) {
            try {
                val payload = buildJsonObject {
                    put("id", playlistId)
                    put("n", 100000)
                    put("s", 8)
                }.toString()
                val body = directClient.postEapi(
                    client,
                    NeteaseEndpoints.URL_EAPI_PLAYLIST_DETAIL,
                    NeteaseEndpoints.PATH_EAPI_PLAYLIST_DETAIL,
                    payload
                )
                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val playlist = json?.get("playlist")?.asJsonObjectOrNull
                if (playlist != null) {
                    parsedPlaylistMeta = NcmUserPlaylist(
                        id = playlist.get("id")?.jsonPrimitive?.longOrNull ?: playlistId,
                        name = playlist.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                        coverImgUrl = playlist.get("coverImgUrl")?.jsonPrimitive?.contentOrNull ?: "",
                        trackCount = playlist.get("trackCount")?.jsonPrimitive?.intOrNull ?: 0,
                        playCount = playlist.get("playCount")?.jsonPrimitive?.longOrNull ?: 0L,
                        creatorName = playlist.get("creator")?.asJsonObjectOrNull?.get("nickname")?.jsonPrimitive?.contentOrNull ?: "",
                        description = playlist.get("description")?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
                val trackIdsArray = playlist?.get("trackIds")?.jsonArray
                val tracksArray = playlist?.get("tracks")?.jsonArray

                if (tracksArray != null && (trackIdsArray == null || tracksArray.size >= trackIdsArray.size)) {
                    val songs = tracksArray.mapNotNull { parseSong(it.asJsonObjectOrNull) }
                    val expectedTotal = if (totalCount > 0) totalCount else songs.size
                    onProgress?.invoke(songs.size, expectedTotal)
                    return NcmPlaylistDetailResult(parsedPlaylistMeta, songs)
                }

                if (trackIdsArray != null && trackIdsArray.isNotEmpty()) {
                    val allTrackIds = trackIdsArray.mapNotNull { it.asJsonObjectOrNull?.get("id")?.jsonPrimitive?.longOrNull?.toString() }
                    val allSongs = mutableListOf<PlayableMedia.Online>()
                    val chunkSize = 200
                    for (i in allTrackIds.indices step chunkSize) {
                        val chunk = allTrackIds.subList(i, minOf(i + chunkSize, allTrackIds.size))
                        val fetched = getSongDetails(chunk)
                        allSongs.addAll(fetched)
                        val expectedTotal = if (totalCount > 0) totalCount else allTrackIds.size
                        onProgress?.invoke(allSongs.size, expectedTotal)
                    }
                    if (allSongs.isNotEmpty()) return NcmPlaylistDetailResult(parsedPlaylistMeta, allSongs)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("AME_PLAYLIST_DEBUG", "Direct getPlaylistTracks failed: ${e.message}")
            }
        }

        val allSongs = mutableListOf<PlayableMedia.Online>()
        val limit = 100
        var offset = 0
        var hasMore = true

        while (hasMore && offset < 10000) {
            try {
                Log.d("AME_PLAYLIST_DEBUG", "getPlaylistTracks requesting playlistId=$playlistId, limit=$limit, offset=$offset")
                val response = withTimeoutOrNull(10_000L) {
                    client.get("$baseUrl/playlist/track/all") {
                        parameter("id", playlistId)
                        parameter("limit", limit)
                        parameter("offset", offset)
                        parameter("timestamp", System.currentTimeMillis())
                    }
                }

                if (response == null) {
                    Log.w("AME_PLAYLIST_DEBUG", "getPlaylistTracks request timeout at offset=$offset")
                    hasMore = false
                    break
                }

                val body = response.bodyAsText()
                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val songsArray = json?.get("songs")?.jsonArray

                if (songsArray.isNullOrEmpty()) {
                    if (offset == 0) {
                        val detailResp = withTimeoutOrNull(10_000L) {
                            client.get("$baseUrl/playlist/detail") {
                                parameter("id", playlistId)
                                parameter("timestamp", System.currentTimeMillis())
                            }
                        }
                        val detailBody = detailResp?.bodyAsText() ?: ""
                        val detailJson = Json.parseToJsonElement(detailBody).asJsonObjectOrNull
                        val detailPlaylist = detailJson?.get("playlist")?.asJsonObjectOrNull
                        if (parsedPlaylistMeta == null && detailPlaylist != null) {
                            parsedPlaylistMeta = NcmUserPlaylist(
                                id = detailPlaylist.get("id")?.jsonPrimitive?.longOrNull ?: playlistId,
                                name = detailPlaylist.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                                coverImgUrl = detailPlaylist.get("coverImgUrl")?.jsonPrimitive?.contentOrNull ?: "",
                                trackCount = detailPlaylist.get("trackCount")?.jsonPrimitive?.intOrNull ?: 0,
                                playCount = detailPlaylist.get("playCount")?.jsonPrimitive?.longOrNull ?: 0L,
                                creatorName = detailPlaylist.get("creator")?.asJsonObjectOrNull?.get("nickname")?.jsonPrimitive?.contentOrNull ?: "",
                                description = detailPlaylist.get("description")?.jsonPrimitive?.contentOrNull ?: ""
                            )
                        }
                        val tracks = detailPlaylist?.get("tracks")?.jsonArray
                        tracks?.mapNotNull { parseSong(it.asJsonObjectOrNull) }?.let { allSongs.addAll(it) }
                    }
                    hasMore = false
                } else {
                    val pageSongs = songsArray.mapNotNull { parseSong(it.asJsonObjectOrNull) }
                    allSongs.addAll(pageSongs)
                    val expectedTotal = if (totalCount > 0) totalCount else allSongs.size
                    onProgress?.invoke(allSongs.size, expectedTotal)

                    if (pageSongs.size < limit) {
                        hasMore = false
                    } else {
                        offset += limit
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("AME_PLAYLIST_DEBUG", "getPlaylistTracks offset=$offset Exception", e)
                hasMore = false
            }
        }
        val expectedTotal = if (totalCount > 0) totalCount else allSongs.size
        onProgress?.invoke(allSongs.size, expectedTotal)
        Log.i("AME_PLAYLIST_DEBUG", "getPlaylistTracks completed for playlistId=$playlistId with total ${allSongs.size} tracks")
        return NcmPlaylistDetailResult(parsedPlaylistMeta, allSongs)
    }

    /**
     * 全量循环分页拉取歌单曲目，带 10s 超时防护与实时 (fetched/total) 进度回调
     */
    suspend fun getPlaylistTracks(
        playlistId: Long,
        totalCount: Int = 0,
        onProgress: ((fetched: Int, total: Int) -> Unit)? = null
    ): List<PlayableMedia.Online> {
        return getPlaylistDetailAndTracks(playlistId, totalCount, onProgress).tracks
    }

    /**
     * 云端喜欢 / 取消喜欢歌曲 (Netease API: GET /like?id=xxx&like=true/false)
     */
    suspend fun likeSong(songId: String, like: Boolean): Boolean {
        if (isDirectMode) {
            try {
                val payload = buildJsonObject {
                    put("trackId", songId)
                    put("like", like)
                    put("time", 3)
                    put("csrf_token", "")
                }.toString()
                val (body, _) = directClient.postWeapi(
                    client,
                    NeteaseEndpoints.URL_WEAPI_SONG_LIKE,
                    payload
                )
                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val code = json?.get("code")?.jsonPrimitive?.intOrNull ?: 0
                Log.i("AME_CLOUD_SYNC", "Direct likeSong songId=$songId, like=$like, code=$code")
                if (code == 200) return true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("AME_CLOUD_SYNC", "Direct likeSong failed for id=$songId", e)
            }
        }
        return try {
            val response = client.get("$baseUrl/like") {
                parameter("id", songId)
                parameter("like", like)
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val code = json?.get("code")?.jsonPrimitive?.intOrNull ?: 0
            Log.i("AME_CLOUD_SYNC", "likeSong songId=$songId, like=$like, code=$code")
            code == 200
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("AME_CLOUD_SYNC", "likeSong failed for id=$songId", e)
            false
        }
    }

    /**
     * 云端歌单添加 / 移除歌曲 (Netease API: GET /playlist/tracks?op=add/del&pid=xxx&tracks=xxx)
     */
    suspend fun updatePlaylistTracks(op: String, playlistId: Long, songId: String): Boolean {
        if (isDirectMode) {
            try {
                val payload = buildJsonObject {
                    put("op", op)
                    put("pid", playlistId)
                    put("trackIds", "[$songId]")
                    put("csrf_token", "")
                }.toString()
                val (body, _) = directClient.postWeapi(
                    client,
                    NeteaseEndpoints.URL_WEAPI_PLAYLIST_MANIPULATE,
                    payload
                )
                val json = Json.parseToJsonElement(body).asJsonObjectOrNull
                val code = json?.get("code")?.jsonPrimitive?.intOrNull ?: 0
                Log.i("AME_CLOUD_SYNC", "Direct updatePlaylistTracks op=$op, playlistId=$playlistId, songId=$songId, code=$code")
                if (code == 200) return true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("AME_CLOUD_SYNC", "Direct updatePlaylistTracks failed op=$op, pid=$playlistId, songId=$songId", e)
            }
        }
        return try {
            val response = client.get("$baseUrl/playlist/tracks") {
                parameter("op", op) // "add" or "del"
                parameter("pid", playlistId)
                parameter("tracks", songId)
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val code = json?.get("code")?.jsonPrimitive?.intOrNull ?: 0
            Log.i("AME_CLOUD_SYNC", "updatePlaylistTracks op=$op, playlistId=$playlistId, songId=$songId, code=$code")
            code == 200
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("AME_CLOUD_SYNC", "updatePlaylistTracks failed op=$op, pid=$playlistId, songId=$songId", e)
            false
        }
    }

    private fun parseSong(json: JsonObject?, matchedLyric: String? = null): PlayableMedia.Online? {
        if (json == null) return null
        return try {
            val id = json["id"]?.jsonPrimitive?.longOrNull ?: return null
            val name = json["name"]?.jsonPrimitive?.contentOrNull ?: ""
            val artists = json["ar"]?.jsonArray?.mapNotNull {
                it.asJsonObjectOrNull?.get("name")?.jsonPrimitive?.contentOrNull
            }?.joinToString("/")
                ?: json["artists"]?.jsonArray?.mapNotNull {
                    it.asJsonObjectOrNull?.get("name")?.jsonPrimitive?.contentOrNull
                }?.joinToString("/") ?: ""

            val albumObj = json["al"]?.asJsonObjectOrNull ?: json["album"]?.asJsonObjectOrNull
            val albumName = albumObj?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
            val picUrl = albumObj?.get("picUrl")?.jsonPrimitive?.contentOrNull

            val alias = json["alia"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.firstOrNull { it.isNotBlank() }
                ?: json["alias"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.firstOrNull { it.isNotBlank() }
            val transName = json["tns"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.firstOrNull { it.isNotBlank() }
                ?: json["transNames"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.firstOrNull { it.isNotBlank() }

            PlayableMedia.Online(
                id = id.toString(),
                title = name,
                artist = artists,
                album = albumName,
                coverUrl = picUrl,
                durationMs = json["dt"]?.jsonPrimitive?.longOrNull
                    ?: json["duration"]?.jsonPrimitive?.longOrNull ?: 0L,
                fee = json["fee"]?.jsonPrimitive?.intOrNull ?: 0,
                alias = alias,
                transName = transName,
                matchedLyric = matchedLyric
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAlbum(json: JsonObject?): NcmAlbum? {
        if (json == null) return null
        return try {
            val id = json["id"]?.jsonPrimitive?.longOrNull ?: return null
            val name = json["name"]?.jsonPrimitive?.contentOrNull ?: return null
            val artistName = json["artist"]?.asJsonObjectOrNull?.get("name")?.jsonPrimitive?.contentOrNull
                ?: json["artists"]?.jsonArray?.firstOrNull()?.asJsonObjectOrNull?.get("name")?.jsonPrimitive?.contentOrNull
                ?: ""
            val picUrl = json["picUrl"]?.jsonPrimitive?.contentOrNull
            val size = json["size"]?.jsonPrimitive?.intOrNull ?: 0
            val publishTime = json["publishTime"]?.jsonPrimitive?.longOrNull ?: 0L
            val transNames = json["transNames"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val alias = json["alias"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

            NcmAlbum(
                id = id,
                name = name,
                artistName = artistName,
                picUrl = picUrl,
                songCount = size,
                publishTimeMs = publishTime,
                transNames = transNames,
                alias = alias
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseArtist(json: JsonObject?): NcmArtist? {
        if (json == null) return null
        return try {
            val id = json["id"]?.jsonPrimitive?.longOrNull ?: return null
            val name = json["name"]?.jsonPrimitive?.contentOrNull ?: return null
            val picUrl = json["picUrl"]?.jsonPrimitive?.contentOrNull
                ?: json["img1v1Url"]?.jsonPrimitive?.contentOrNull
            val albumCount = json["albumSize"]?.jsonPrimitive?.intOrNull ?: 0
            val songCount = json["musicSize"]?.jsonPrimitive?.intOrNull ?: 0
            val alias = json["alias"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val trans = json["trans"]?.jsonPrimitive?.contentOrNull
                ?: json["transNames"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull

            NcmArtist(
                id = id,
                name = name,
                picUrl = picUrl,
                albumCount = albumCount,
                songCount = songCount,
                alias = alias,
                trans = trans
            )
        } catch (_: Exception) {
            null
        }
    }
}