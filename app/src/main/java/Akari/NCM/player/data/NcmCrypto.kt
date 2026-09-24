package Akari.NCM.player.data

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal object AesEcbDecryptor {
    private const val ALGORITHM = "AES/ECB/PKCS7Padding"

    fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }
}

internal object Rc4Engine {
    fun buildKeyBox(key: ByteArray): IntArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xff)) and 0xff
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
        }
        return IntArray(256) { t ->
            val a = (t + 1) and 0xff
            val sa = s[a]
            s[(sa + s[(a + sa) and 0xff]) and 0xff]
        }
    }
}
