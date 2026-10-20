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
            "bc05a756cb87b1a2674efda9e90a27d641dcbbe2e6b7be3b61193e47315c298183d409578ac80502bd441d82bf1611d6d9238bcaf64794d096417b726373fdd8cf4a9e9d31d198d6d785ffa3d0a2e8c621b4d6c019fabe0ca6f3a815d9f2a4875a0bcab968f331f394566e1162603ed1dd002aa89b47fd8dcbc77d2c6976efed",
            form.encSecKey
        )
        assertEquals("MvSwiohLqrSWzsk69j/ejdehgnAZzf6BZhrEjdrv/EI=", form.params)
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