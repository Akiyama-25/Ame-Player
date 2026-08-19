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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

private val JsonElement?.asJsonObjectOrNull: JsonObject?
    get() = this as? JsonObject

@Singleton
class NcmApi @Inject constructor(
    private val sessionManager: UserSessionManager? = null
) {
    private val baseUrl: String
        get() = sessionManager?.getApiBaseUrl() ?: UserSessionManager.DEFAULT_API_BASE_URL

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
        // 动态标头拦截器：抓取洗净后的合法 Cookie Header
        plugin(HttpSend).intercept { request ->
            val rawCookie = sessionManager?.getCookie()
            val cleanCookie = UserSessionManager.cleanCookieString(rawCookie ?: "")
            if (cleanCookie.isNotBlank()) {
                request.headers.remove("Cookie")
                request.headers.append("Cookie", cleanCookie)
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

    suspend fun searchSongs(keyword: String, limit: Int = 30): List<PlayableMedia.Online> {
        return try {
            val response = client.get("$baseUrl/search") {
                parameter("keywords", keyword)
                parameter("limit", limit)
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull ?: return emptyList()
            val songs = json["result"].asJsonObjectOrNull?.get("songs")?.jsonArray ?: return emptyList()
            songs.mapNotNull { parseSong(it.asJsonObjectOrNull) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getSongDetails(songIds: List<String>): List<PlayableMedia.Online> {
        if (songIds.isEmpty()) return emptyList()
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
     * 获取歌曲播放链接 (自动匹配账号最高可用音�?
     */
    suspend fun getSongUrl(song: PlayableMedia.Online, quality: QualityLevel? = null): String? {
        Log.i("[AME_ONLINE_PLAY]", ">>> Requesting song URL for songId='${song.id}', title='${song.title}' (Follow Account Highest Quality)")

        // 默认按账号可获取的最高级别链依次尝试: HIRES -> LOSSLESS -> EXHIGH -> STANDARD
        val qualitiesToTry = listOf(QualityLevel.HIRES, QualityLevel.LOSSLESS, QualityLevel.EXHIGH, QualityLevel.STANDARD)

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
                val data = json?.get("data")?.jsonArray?.firstOrNull().asJsonObjectOrNull
                val url = data?.get("url")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

                if (url != null) {
                    Log.i("[AME_ONLINE_PLAY]", ">>> [SUCCESS] Successfully resolved URL for '${song.title}' with level=${q.value} (cost=${cost}ms): $url")
                    return url
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
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
            val data = json?.get("data")?.jsonArray?.firstOrNull().asJsonObjectOrNull
            data?.get("url")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getLyric(songId: String): Pair<String?, String?>? {
        return try {
            val response = client.get("$baseUrl/lyric") {
                parameter("id", songId)
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull ?: return null
            val lrc = json["lrc"].asJsonObjectOrNull?.get("lyric")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val tlyric = json["tlyric"].asJsonObjectOrNull?.get("lyric")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (lrc != null || tlyric != null) Pair(lrc, tlyric) else null
        } catch (_: Exception) {
            null
        }
    }

    // ==========================================
    // 扫码登录 API 支持
    // ==========================================

    suspend fun getQrKey(): String? {
        return try {
            val response = client.get("$baseUrl/login/qr/key") {
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val key = json?.get("data").asJsonObjectOrNull?.get("unikey")?.jsonPrimitive?.contentOrNull
                ?: json?.get("unikey")?.jsonPrimitive?.contentOrNull
            Log.i("AME_QR_DEBUG", "getQrKey result: $key")
            key?.takeIf { it.isNotBlank() }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e("AME_QR_DEBUG", "getQrKey failed", e)
            null
        }
    }

    suspend fun createQrUrl(key: String): Pair<String?, String?>? {
        return try {
            val response = client.get("$baseUrl/login/qr/create") {
                parameter("key", key)
                parameter("qrimg", true)
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val dataObj = json?.get("data").asJsonObjectOrNull
            val qrimg = dataObj?.get("qrimg")?.jsonPrimitive?.contentOrNull
            val qrurl = dataObj?.get("qrurl")?.jsonPrimitive?.contentOrNull
            Log.i("AME_QR_DEBUG", "createQrUrl result length: ${qrimg?.length ?: 0}, hasUrl: ${qrurl != null}")
            Pair(qrimg, qrurl)
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e("AME_QR_DEBUG", "createQrUrl failed", e)
            null
        }
    }

    suspend fun checkQrStatus(key: String): QrCheckResult {
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
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e("AME_LOGIN_DEBUG", "checkQrStatus exception", e)
            QrCheckResult(status = QrStatus.UNKNOWN, message = e.message ?: "Network error")
        }
    }

    // ==========================================
    // 用户个人中心与歌�?API 支持
    // ==========================================

    suspend fun getUserProfile(): UserProfile? {
        return try {
            val response = client.get("$baseUrl/user/account") {
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            Log.i("AME_LOGIN_DEBUG", "/user/account body: $body")
            val rootObj = try { Json.parseToJsonElement(body).asJsonObjectOrNull } catch (_: Exception) { null }

            val profileObj = rootObj?.get("profile").asJsonObjectOrNull
                ?: rootObj?.get("account").asJsonObjectOrNull
                ?: rootObj?.get("data").asJsonObjectOrNull?.get("profile").asJsonObjectOrNull
                ?: rootObj?.get("data").asJsonObjectOrNull?.get("account").asJsonObjectOrNull

            var userId = profileObj?.get("userId")?.jsonPrimitive?.longOrNull
                ?: profileObj?.get("id")?.jsonPrimitive?.longOrNull
                ?: rootObj?.get("account").asJsonObjectOrNull?.get("id")?.jsonPrimitive?.longOrNull ?: 0L

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
                    val detailProfile = detailJson?.get("profile").asJsonObjectOrNull
                    if (detailProfile != null) {
                        nickname = detailProfile["nickname"]?.jsonPrimitive?.contentOrNull ?: nickname
                        avatarUrl = detailProfile["avatarUrl"]?.jsonPrimitive?.contentOrNull ?: avatarUrl
                        vipType = detailProfile["vipType"]?.jsonPrimitive?.intOrNull ?: vipType
                        signature = detailProfile["signature"]?.jsonPrimitive?.contentOrNull ?: signature
                    }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
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
                    val statusData = statusRoot?.get("data").asJsonObjectOrNull
                    val statusProfile = statusData?.get("profile").asJsonObjectOrNull
                        ?: statusRoot?.get("profile").asJsonObjectOrNull
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
                    nickname = nickname.ifBlank { "网易云用�?$userId" },
                    avatarUrl = avatarUrl,
                    vipType = vipType,
                    signature = signature
                )
                Log.i("AME_LOGIN_DEBUG", "getUserProfile success: userId=$userId, nickname=${profile.nickname}, avatar=$avatarUrl")
                sessionManager?.saveUserProfile(profile)
                return profile
            }
            null
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e("AME_LOGIN_DEBUG", "getUserProfile Exception", e)
            null
        }
    }

    suspend fun getHomepageDragonBalls(): List<HomepageDragonBall> {
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
        return try {
            val response = client.get("$baseUrl/homepage/block/page") {
                parameter("refresh", true)
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val data = json?.get("data").asJsonObjectOrNull
            val blocks = data?.get("blocks")?.jsonArray ?: return emptyList()
            blocks.mapNotNull { blockElement ->
                val block = blockElement.asJsonObjectOrNull ?: return@mapNotNull null
                val blockCode = block["blockCode"]?.jsonPrimitive?.contentOrNull ?: ""
                val uiElement = block["uiElement"].asJsonObjectOrNull
                val title = uiElement?.get("subTitle").asJsonObjectOrNull?.get("title")?.jsonPrimitive?.contentOrNull ?: ""
                
                val creativesElement = block["creatives"]?.jsonArray ?: return@mapNotNull null
                val creativesList = creativesElement.mapNotNull { creativeElement ->
                    val creative = creativeElement.asJsonObjectOrNull ?: return@mapNotNull null
                    val creativeType = creative["creativeType"]?.jsonPrimitive?.contentOrNull ?: ""
                    val resources = creative["resources"]?.jsonArray ?: return@mapNotNull null
                    if (resources.isEmpty()) return@mapNotNull null
                    
                    val resource = resources[0].asJsonObjectOrNull ?: return@mapNotNull null
                    val resourceId = resource["resourceId"]?.jsonPrimitive?.contentOrNull ?: ""
                    val resourceUiElement = resource["uiElement"].asJsonObjectOrNull
                    val creativeTitle = resourceUiElement?.get("mainTitle").asJsonObjectOrNull?.get("title")?.jsonPrimitive?.contentOrNull ?: ""
                    val imageUrl = resourceUiElement?.get("image").asJsonObjectOrNull?.get("imageUrl")?.jsonPrimitive?.contentOrNull ?: ""
                    val playCount = resource["resourceExtInfo"].asJsonObjectOrNull?.get("playCount")?.jsonPrimitive?.longOrNull ?: 0L
                    
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
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e("AME_HOMEPAGE", "getHomepageBlocks Error", e)
            emptyList()
        }
    }

    suspend fun getDailyRecommendPlaylists(): List<NcmUserPlaylist> {
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
                    creatorName = obj["creator"].asJsonObjectOrNull?.get("nickname")?.jsonPrimitive?.contentOrNull ?: "",
                    description = obj["copywriter"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            emptyList()
        }
    }

    suspend fun getHighQualityPlaylists(): List<NcmUserPlaylist> {
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
                    creatorName = obj["creator"].asJsonObjectOrNull?.get("nickname")?.jsonPrimitive?.contentOrNull ?: "",
                    description = obj["copywriter"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            emptyList()
        }
    }

    suspend fun getDailyRecommendSongs(): List<PlayableMedia.Online> {
        return try {
            val response = client.get("$baseUrl/recommend/songs") {
                parameter("timestamp", System.currentTimeMillis())
            }
            val body = response.bodyAsText()
            val json = Json.parseToJsonElement(body).asJsonObjectOrNull
            val data = json?.get("data").asJsonObjectOrNull
            val dailySongs = data?.get("dailySongs")?.jsonArray ?: return emptyList()
            dailySongs.mapNotNull { parseSong(it.asJsonObjectOrNull) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getUserPlaylists(uid: Long): List<NcmUserPlaylist> {
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
                    creatorName = obj["creator"].asJsonObjectOrNull?.get("nickname")?.jsonPrimitive?.contentOrNull ?: "",
                    description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e("AME_PLAYLIST_DEBUG", "getUserPlaylists Exception", e)
            emptyList()
        }
    }

    /**
     * 全量循环分页拉取歌单曲目，带 10s 超时防护与实�?(fetched/total) 进度回调
     */
    suspend fun getPlaylistTracks(
        playlistId: Long,
        totalCount: Int = 0,
        onProgress: ((fetched: Int, total: Int) -> Unit)? = null
    ): List<PlayableMedia.Online> {
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
                        val tracks = detailJson?.get("playlist").asJsonObjectOrNull?.get("tracks")?.jsonArray
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
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                Log.e("AME_PLAYLIST_DEBUG", "getPlaylistTracks offset=$offset Exception", e)
                hasMore = false
            }
        }
        val expectedTotal = if (totalCount > 0) totalCount else allSongs.size
        onProgress?.invoke(allSongs.size, expectedTotal)
        Log.i("AME_PLAYLIST_DEBUG", "getPlaylistTracks completed for playlistId=$playlistId with total ${allSongs.size} tracks")
        return allSongs
    }

    /**
     * 云端喜欢 / 取消喜欢歌曲 (Netease API: GET /like?id=xxx&like=true/false)
     */
    suspend fun likeSong(songId: String, like: Boolean): Boolean {
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
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e("AME_CLOUD_SYNC", "likeSong failed for id=$songId", e)
            false
        }
    }

    /**
     * 云端歌单添加 / 移除歌曲 (Netease API: GET /playlist/tracks?op=add/del&pid=xxx&tracks=xxx)
     */
    suspend fun updatePlaylistTracks(op: String, playlistId: Long, songId: String): Boolean {
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
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e("AME_CLOUD_SYNC", "updatePlaylistTracks failed op=$op, pid=$playlistId, songId=$songId", e)
            false
        }
    }

    private fun parseSong(json: JsonObject?): PlayableMedia.Online? {
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

            val albumObj = json["al"].asJsonObjectOrNull ?: json["album"].asJsonObjectOrNull
            val albumName = albumObj?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
            val picUrl = albumObj?.get("picUrl")?.jsonPrimitive?.contentOrNull

            PlayableMedia.Online(
                id = id.toString(),
                title = name,
                artist = artists,
                album = albumName,
                coverUrl = picUrl,
                durationMs = json["dt"]?.jsonPrimitive?.longOrNull
                    ?: json["duration"]?.jsonPrimitive?.longOrNull ?: 0L,
                fee = json["fee"]?.jsonPrimitive?.intOrNull ?: 0
            )
        } catch (_: Exception) {
            null
        }
    }
}
