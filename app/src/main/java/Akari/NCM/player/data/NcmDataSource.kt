package Akari.NCM.player.data

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.NcmMetadata

@UnstableApi
class NcmDataSource : DataSource {

    private var pfd: ParcelFileDescriptor? = null
    private var fileChannel: FileChannel? = null
    private var keyBox: IntArray? = null
    private var audioStartOffset: Long = 0
    private var audioDataSize: Long = 0
    private var currentPosition: Long = 0
    private var fileUri: Uri? = null
    private var metadata: NcmMetadata? = null
    private var detectedFormat: AudioFormat = AudioFormat.MP3

    fun getMetadata(): NcmMetadata? = metadata
    fun getDetectedFormat(): AudioFormat = detectedFormat

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val cr = contentResolver
            ?: throw IllegalStateException("NcmDataSource: contentResolver not set")

        fileUri = uri
        val pfdObj = cr.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Failed to open FileDescriptor for URI: $uri")
        pfd = pfdObj

        val fis = FileInputStream(pfdObj.fileDescriptor)
        val channel = fis.channel
        fileChannel = channel

        val countingStream = CountingInputStream(fis)
        val headerResult = parseHeader(countingStream)

        keyBox = headerResult.keyBox
        metadata = headerResult.metadata
        detectedFormat = headerResult.format
        audioStartOffset = countingStream.count

        val totalFileSize = channel.size()
        audioDataSize = maxOf(0L, totalFileSize - audioStartOffset)

        currentPosition = dataSpec.position
        channel.position(audioStartOffset + currentPosition)

        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, audioDataSize - currentPosition)
        } else {
            audioDataSize - currentPosition
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val channel = fileChannel ?: return -1
        val box = keyBox ?: return -1

        if (currentPosition >= audioDataSize) return -1

        val bytesToRead = minOf(length.toLong(), audioDataSize - currentPosition).toInt()
        if (bytesToRead <= 0) return -1

        val byteBuffer = ByteBuffer.wrap(buffer, offset, bytesToRead)
        val bytesRead = channel.read(byteBuffer)
        if (bytesRead <= 0) return -1

        // In-place XOR decryption
        for (i in 0 until bytesRead) {
            val filePos = currentPosition + i
            val keyIndex = (filePos and 0xff).toInt()
            val encryptedByte = buffer[offset + i].toInt() and 0xff
            buffer[offset + i] = (encryptedByte xor box[keyIndex]).toByte()
        }

        currentPosition += bytesRead
        return bytesRead
    }

    override fun getUri(): Uri = fileUri ?: Uri.EMPTY

    override fun close() {
        try {
            fileChannel?.close()
        } catch (_: Exception) {}
        try {
            pfd?.close()
        } catch (_: Exception) {}

        fileChannel = null
        pfd = null
        keyBox = null
        metadata = null
        currentPosition = 0
        audioStartOffset = 0
        audioDataSize = 0
    }

    private data class HeaderResult(
        val keyBox: IntArray,
        val metadata: NcmMetadata,
        val format: AudioFormat
    )

    private fun parseHeader(input: InputStream): HeaderResult {
        // Verify magic: "CTENFDAM"
        val magic = readBytes(input, 8)
        val expected = byteArrayOf(67, 84, 69, 78, 70, 68, 65, 77)
        if (!magic.contentEquals(expected)) {
            throw IllegalArgumentException("Not a valid NCM file")
        }

        // Skip 2 bytes
        readBytes(input, 2)

        // Key block: length (uint32 LE) + encrypted data
        val keyLength = readUint32LE(input)
        val keyEncrypted = readBytes(input, keyLength)
        for (i in keyEncrypted.indices) {
            keyEncrypted[i] = (keyEncrypted[i].toInt() xor 0x64).toByte()
        }
        val decryptedKey = AesEcbDecryptor.decrypt(keyEncrypted, CORE_KEY)
        val rc4Key = decryptedKey.copyOfRange(17, decryptedKey.size)
        val keyBox = Rc4Engine.buildKeyBox(rc4Key)

        // Meta block: length (uint32 LE) + encrypted data
        val metaLength = readUint32LE(input)
        val metaEncrypted = readBytes(input, metaLength)
        for (i in metaEncrypted.indices) {
            metaEncrypted[i] = (metaEncrypted[i].toInt() xor 0x63).toByte()
        }
        val metadata = parseMetaBlock(metaEncrypted)

        // Gap block: skip
        skipBytes(input, 5)
        val gapSize = readUint32LE(input)
        skipBytes(input, gapSize.toLong() + 4)

        val format = when (metadata.format.lowercase()) {
            "flac" -> AudioFormat.FLAC
            else -> AudioFormat.MP3
        }

        return HeaderResult(keyBox, metadata, format)
    }

    private fun parseMetaBlock(data: ByteArray): NcmMetadata {
        if (data.size <= 22) return NcmMetadata()
        return try {
            val payload = data.copyOfRange(22, data.size)
            val decoded = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            val decrypted = AesEcbDecryptor.decrypt(decoded, META_KEY)
            val jsonStr = String(decrypted)
            parseMetadataJson(jsonStr)
        } catch (_: Exception) {
            NcmMetadata()
        }
    }

    private fun parseMetadataJson(jsonStr: String): NcmMetadata {
        return try {
            val clean = jsonStr.substringAfter(":").trim()
            val json = JSONObject(clean)
            val metaJson = if (jsonStr.startsWith("dj:")) {
                json.optJSONObject("mainMusic") ?: json
            } else json

            val artists = mutableListOf<String>()
            metaJson.optJSONArray("artist")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val name = when (val item = arr.get(i)) {
                        is JSONArray -> item.optString(0) ?: ""
                        is String -> item
                        else -> item.toString()
                    }
                    if (name.isNotBlank()) artists.add(name)
                }
            }

            var albumPic = metaJson.optString("albumPic", "")
            if (albumPic.isNotBlank()) {
                albumPic = albumPic.replace("http://", "https://").trim()
                val separator = if ("?" in albumPic) "&" else "?"
                albumPic += "${separator}param=500y500"
            }

            NcmMetadata(
                musicId = metaJson.optLong("musicId", 0L),
                musicName = metaJson.optString("musicName", "Unknown"),
                artists = artists,
                album = metaJson.optString("album", ""),
                albumPic = albumPic,
                format = metaJson.optString("format", ""),
                duration = metaJson.optLong("duration", 0L)
            )
        } catch (_: Exception) {
            NcmMetadata()
        }
    }

    private fun readBytes(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buf, offset, count - offset)
            if (read == -1) throw IllegalStateException("Unexpected end of stream")
            offset += read
        }
        return buf
    }

    private fun readUint32LE(input: InputStream): Int {
        val b = readBytes(input, 4)
        return (b[0].toInt() and 0xff) or
                ((b[1].toInt() and 0xff) shl 8) or
                ((b[2].toInt() and 0xff) shl 16) or
                ((b[3].toInt() and 0xff) shl 24)
    }

    private fun skipBytes(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val read = input.read(buf, 0, toRead)
            if (read == -1) throw IllegalStateException("Unexpected end of stream")
            remaining -= read.toLong()
        }
    }

    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var count: Long = 0
            private set

        override fun read(): Int {
            val result = delegate.read()
            if (result != -1) count++
            return result
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val result = delegate.read(b, off, len)
            if (result > 0) count += result
            return result
        }

        override fun skip(n: Long): Long {
            val skipped = delegate.skip(n)
            if (skipped > 0) count += skipped
            return skipped
        }
    }

    companion object {
        private val CORE_KEY = hexToBytes("687a4852416d736f356b496e62617857")
        private val META_KEY = hexToBytes("2331346C6A6B5F215C5D2630553C2728")

        var contentResolver: ContentResolver? = null

        private fun hexToBytes(hex: String): ByteArray {
            val result = ByteArray(hex.length / 2)
            for (i in hex.indices step 2) {
                result[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
            }
            return result
        }

        // Utility: scan NCM metadata from URI (without decrypting audio)
        fun scanMetadata(cr: ContentResolver, uri: Uri): Triple<NcmMetadata, AudioFormat, ByteArray?>? {
            return try {
                cr.openInputStream(uri)?.use { stream ->
                    // Verify magic
                    val magic = readBytesStatic(stream, 8)
                    val magicHex = magic.joinToString("") { "%02x".format(it) }
                    val magicStr = String(magic, Charsets.US_ASCII)
                    // Log.d("AmeScanner", "scanMetadata: first8bytes=[$magicHex] ascii=[$magicStr]")
                    val expected = byteArrayOf(67, 84, 69, 78, 70, 68, 65, 77)
                    if (!magic.contentEquals(expected)) {
                        // Log.d("AmeScanner", "scanMetadata: NOT NCM (magic mismatch)")
                        return null
                    }

                    readBytesStatic(stream, 2) // skip

                    // Skip key block
                    val keyLen = readUint32LEStatic(stream)
                    // Log.d("AmeScanner", "scanMetadata: keyBlockLen=$keyLen")
                    skipBytesStatic(stream, keyLen.toLong())

                    // Read meta block
                    val metaLen = readUint32LEStatic(stream)
                    // Log.d("AmeScanner", "scanMetadata: metaBlockLen=$metaLen")
                    val metaEnc = readBytesStatic(stream, metaLen)
                    for (i in metaEnc.indices) {
                        metaEnc[i] = (metaEnc[i].toInt() xor 0x63).toByte()
                    }

                    val metadata = parseMetaBlockStatic(metaEnc)
                    // Log.d("AmeScanner", "scanMetadata: parsed id=${metadata.musicId}, name=${metadata.musicName}, format=${metadata.format}, artists=${metadata.artists}")
                    // Invalid NCM if metadata is empty (magic matched but content is not NCM)
                    if (metadata.musicId == 0L && metadata.musicName == "Unknown") {
                        // Log.d("AmeScanner", "scanMetadata: empty metadata, rejecting as NCM")
                        return null
                    }

                    val format = if (metadata.format.equals("flac", ignoreCase = true)) {
                        AudioFormat.FLAC
                    } else AudioFormat.MP3

                    // Extract embedded cover image
                    skipBytesStatic(stream, 5) // Skip 5 bytes CRC/gap
                    val imageLen = readUint32LEStatic(stream)
                    // Log.i("[AME_COVER_DEBUG]", "Embedded imageLen=$imageLen")
                    val coverBytes = if (imageLen in 1..(20 * 1024 * 1024)) {
                        try {
                            readBytesStatic(stream, imageLen)
                        } catch (e: Exception) {
                            Log.e("[AME_COVER_DEBUG]", "Failed to read image bytes: ${e.message}", e)
                            null
                        }
                    } else null

                    // Log.i("[AME_COVER_DEBUG]", "Scan complete. format=$format, coverBytesSize=${coverBytes?.size ?: 0}")
                    Triple(metadata, format, coverBytes)
                }
            } catch (e: Exception) {
                Log.e("[AME_COVER_DEBUG]", "scanMetadata exception=${e.javaClass.simpleName}: ${e.message}", e)
                null
            }
        }

        private fun parseMetaBlockStatic(data: ByteArray): NcmMetadata {
            if (data.size <= 22) return NcmMetadata()
            return try {
                val payload = data.copyOfRange(22, data.size)
                val decoded = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                val decrypted = AesEcbDecryptor.decrypt(decoded, META_KEY)
                val jsonStr = String(decrypted)
                // Log.i("[AME_COVER_DEBUG]", "Raw metadata JSON: $jsonStr")
                val cleanJson = jsonStr.substringAfter(":").trim()
                val json = JSONObject(cleanJson)
                val metaJson = if (jsonStr.startsWith("dj:")) {
                    json.optJSONObject("mainMusic") ?: json
                } else {
                    json
                }

                val artists = mutableListOf<String>()
                metaJson.optJSONArray("artist")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val name = when (val item = arr.get(i)) {
                            is JSONArray -> item.optString(0) ?: ""
                            is String -> item
                            else -> item.toString()
                        }
                        if (name.isNotBlank()) artists.add(name)
                    }
                }

                val rawAlbumPic = metaJson.optString("albumPic", "")
                var albumPic = rawAlbumPic
                if (albumPic.isNotBlank()) {
                    albumPic = albumPic.replace("http://", "https://").trim()
                    val separator = if ("?" in albumPic) "&" else "?"
                    albumPic += "${separator}param=500y500"
                }

                // Log.i("[AME_COVER_DEBUG]", "Parsed metadata: musicId=${metaJson.optLong("musicId")}, rawAlbumPic='$rawAlbumPic', finalAlbumPic='$albumPic'")

                NcmMetadata(
                    musicId = metaJson.optLong("musicId", 0L),
                    musicName = metaJson.optString("musicName", "Unknown"),
                    artists = artists,
                    album = metaJson.optString("album", ""),
                    albumPic = albumPic,
                    format = metaJson.optString("format", ""),
                    duration = metaJson.optLong("duration", 0L)
                )
            } catch (e: Exception) {
                Log.e("[AME_COVER_DEBUG]", "parseMetaBlockStatic exception: ${e.message}", e)
                NcmMetadata()
            }
        }

        private fun readBytesStatic(input: InputStream, count: Int): ByteArray {
            val buf = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(buf, offset, count - offset)
                if (read == -1) throw IllegalStateException("Unexpected end of stream")
                offset += read
            }
            return buf
        }

        private fun readUint32LEStatic(input: InputStream): Int {
            val b = readBytesStatic(input, 4)
            return (b[0].toInt() and 0xff) or
                    ((b[1].toInt() and 0xff) shl 8) or
                    ((b[2].toInt() and 0xff) shl 16) or
                    ((b[3].toInt() and 0xff) shl 24)
        }

        private fun skipBytesStatic(input: InputStream, count: Long) {
            var remaining = count
            val buf = ByteArray(8192)
            while (remaining > 0) {
                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                val read = input.read(buf, 0, toRead)
                if (read == -1) throw IllegalStateException("Unexpected end of stream")
                remaining -= read.toLong()
            }
        }
    }
}
