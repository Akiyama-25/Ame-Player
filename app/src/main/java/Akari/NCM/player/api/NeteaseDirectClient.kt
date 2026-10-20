package Akari.NCM.player.api

import Akari.NCM.player.data.UserSessionManager
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direct HTTP transport client for NetEase Cloud Music official endpoints.
 * Handles WeAPI and EAPI signature encryption, cookie injection, and response decryption.
 */
@Singleton
class NeteaseDirectClient @Inject constructor(
    private val sessionManager: UserSessionManager? = null
) {
    companion object {
        private const val TAG = "NeteaseDirectClient"
        private const val UA_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val UA_EAPI =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.164 Safari/537.36"
        private const val BASE_COOKIE =
            "os=pc; osver=Microsoft-Windows-10-Professional-build-10512-64bit; appver=3.0.1.201552"

        private val CHINA_IPS = listOf(
            "116.25.146.177",
            "183.14.132.221",
            "220.181.38.148",
            "120.232.145.185",
            "117.136.8.12"
        )
    }

    private fun getFakeIp(): String = CHINA_IPS.random()

    private fun getCookieHeader(isolateSession: Boolean = false): String {
        val stored = if (isolateSession) "" else (sessionManager?.getCookie() ?: "")
        val cleaned = UserSessionManager.cleanCookieString(stored)
        return if (cleaned.isNotBlank()) "$cleaned; $BASE_COOKIE" else BASE_COOKIE
    }

    /**
     * Post an EAPI encrypted request.
     * [apiPath] is the plaintext API path used for signature calculation (e.g. /api/v3/song/detail).
     * [url] is the target endpoint URL (e.g. https://interface.music.163.com/eapi/v3/song/detail).
     */
    suspend fun postEapi(
        client: HttpClient,
        url: String,
        apiPath: String,
        jsonPayload: String
    ): String {
        val encrypted = NeteaseCrypto.eapi(apiPath, jsonPayload)
        val bodyContent = "params=" + URLEncoder.encode(encrypted, "UTF-8")
        val ip = getFakeIp()

        val response: HttpResponse = client.post(url) {
            header("User-Agent", UA_EAPI)
            header("Cookie", getCookieHeader(isolateSession = false))
            header("X-Real-IP", ip)
            header("X-Forwarded-For", ip)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(bodyContent)
        }

        val rawBody = response.bodyAsText().trim()
        return if (rawBody.length >= 32 && rawBody.matches(Regex("^[0-9a-fA-F]+$"))) {
            NeteaseCrypto.decryptEapiResponse(rawBody) ?: rawBody
        } else {
            rawBody
        }
    }

    /**
     * Post a WeAPI encrypted request.
     * Returns Pair of (response body text, list of Set-Cookie header values).
     */
    suspend fun postWeapi(
        client: HttpClient,
        url: String,
        jsonPayload: String,
        isQrLogin: Boolean = false
    ): Pair<String, List<String>> {
        val form = NeteaseCrypto.weapi(jsonPayload)
        val bodyContent = "params=" + URLEncoder.encode(form.params, "UTF-8") +
                "&encSecKey=" + URLEncoder.encode(form.encSecKey, "UTF-8")
        val ip = getFakeIp()

        val response: HttpResponse = client.post(url) {
            header("User-Agent", UA_PC)
            header("Referer", NeteaseEndpoints.HOST_DEFAULT)
            header("Cookie", getCookieHeader(isolateSession = isQrLogin))
            header("X-Real-IP", ip)
            header("X-Forwarded-For", ip)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(bodyContent)
        }

        val body = response.bodyAsText()
        val setCookies = response.headers.getAll("Set-Cookie") ?: emptyList()
        return Pair(body, setCookies)
    }
}