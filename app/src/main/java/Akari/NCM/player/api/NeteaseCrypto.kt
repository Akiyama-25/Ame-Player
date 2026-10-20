package Akari.NCM.player.api

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Minimal Android/JVM implementation of the WeAPI and EAPI request signatures. */
internal object NeteaseCrypto {
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val RSA_PUBLIC_EXPONENT = "010001"
    private const val RSA_MODULUS =
        "e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val EAPI_KEY = "e82ckenh8dichen8"
    private const val SECRET_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val secureRandom = SecureRandom()

    data class WeapiForm(val params: String, val encSecKey: String)

    fun weapi(json: String, customSecret: String? = null): WeapiForm {
        val secret = customSecret ?: buildString(16) {
            repeat(16) { append(SECRET_CHARS[secureRandom.nextInt(SECRET_CHARS.length)]) }
        }
        val first = aesCbcBase64(json, PRESET_KEY)
        val params = aesCbcBase64(first, secret)
        val reversedSecret = BigInteger(1, secret.reversed().toByteArray(Charsets.UTF_8))
        val encryptedSecret = reversedSecret.modPow(
            BigInteger(RSA_PUBLIC_EXPONENT, 16),
            BigInteger(RSA_MODULUS, 16)
        )
        return WeapiForm(params, encryptedSecret.toString(16).padStart(256, '0'))
    }

    /** [apiPath] is the plaintext /api/... path, not the /eapi/... URL path. */
    fun eapi(apiPath: String, json: String): String {
        val digest = md5("nobody${apiPath}use${json}md5forencrypt")
        val plaintext = "$apiPath-36cd479b6b5-${json}-36cd479b6b5-${digest}"
        return aesEcbHex(plaintext, EAPI_KEY).uppercase()
    }

    fun decryptEapiResponse(ciphertext: String): String? = try {
        val bytes = ByteArray(ciphertext.length / 2) { index ->
            ciphertext.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(Charsets.UTF_8), "AES"))
        String(cipher.doFinal(bytes), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private fun aesCbcBase64(value: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8))
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    private fun aesEcbHex(value: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        return cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
