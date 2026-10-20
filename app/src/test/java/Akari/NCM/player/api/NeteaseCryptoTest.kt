package Akari.NCM.player.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseCryptoTest {
    @Test
    fun weapiProducesBase64ParamsAndFixedWidthRsaField() {
        val form = NeteaseCrypto.weapi("""{"s":"test"}""")
        assertTrue(form.params.isNotBlank())
        assertTrue(form.params.matches(Regex("[A-Za-z0-9+/=]+")))
        assertEquals(256, form.encSecKey.length)
        assertTrue(form.encSecKey.matches(Regex("[0-9a-f]{256}")))
    }

    @Test
    fun weapiDeterministicWithInjectedSecretMatchesVector() {
        val secret = "1234567890abcdef"
        val form = NeteaseCrypto.weapi("""{"id":123}""", customSecret = secret)
        assertEquals(
            "b630e6203cf65d95e2fa460d3d5f3083658ebc7619eb13038622c4d627c2f68903c73ae9d6d53ea7fbf29a6e60b2eb74f3ff5ab8d9b2361b2e2d83769c2794eb84e5658e4585c5ab1e48f8f941198f1f516dbba322bb1e89b2db9bb48c66e927c3ff5a6ea1f34177ef8b8ea5735aa4d59f77e6822c60c87890066d93c1ff9348",
            form.encSecKey
        )
        assertEquals("kR2bK928WbXb0O5e4hJg2A==", form.params)
    }

    @Test
    fun eapiOutputIsUppercaseBlockAlignedHexAndDecryptsBack() {
        val path = "/api/song/lyric/v1"
        val json = """{"id":42,"cp":false}"""
        val encrypted = NeteaseCrypto.eapi(path, json)
        assertTrue(encrypted.isNotBlank())
        assertTrue(encrypted.matches(Regex("[0-9A-F]+")))
        assertEquals(0, encrypted.length % 32)
        assertEquals(encrypted, NeteaseCrypto.eapi(path, json))

        val decrypted = NeteaseCrypto.decryptEapiResponse(encrypted)
        assertTrue(decrypted != null)
        assertTrue(decrypted!!.contains(path))
        assertTrue(decrypted.contains(json))
    }
}