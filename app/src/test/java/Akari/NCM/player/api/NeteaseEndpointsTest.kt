package Akari.NCM.player.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseEndpointsTest {
    @Test
    fun endpointsHaveCorrectHosts() {
        assertTrue(NeteaseEndpoints.URL_EAPI_CLOUDSEARCH.startsWith(NeteaseEndpoints.HOST_SERVICE))
        assertTrue(NeteaseEndpoints.URL_EAPI_SONG_DETAIL.startsWith(NeteaseEndpoints.HOST_SERVICE))
        assertTrue(NeteaseEndpoints.URL_EAPI_SONG_URL.startsWith(NeteaseEndpoints.HOST_SERVICE))
        assertTrue(NeteaseEndpoints.URL_EAPI_SONG_LYRIC.startsWith(NeteaseEndpoints.HOST_SERVICE))
        assertTrue(NeteaseEndpoints.URL_WEAPI_LOGIN_QR_KEY.startsWith(NeteaseEndpoints.HOST_DEFAULT))
        assertTrue(NeteaseEndpoints.URL_WEAPI_LOGIN_QR_CHECK.startsWith(NeteaseEndpoints.HOST_DEFAULT))
    }

    @Test
    fun qrLoginUrlPrefixIsValid() {
        assertEquals("https://music.163.com/login?codekey=", NeteaseEndpoints.QR_LOGIN_URL_PREFIX)
    }
}