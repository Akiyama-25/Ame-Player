package Akari.NCM.player.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.ParcelFileDescriptor
import Akari.NCM.player.core.AppLogger
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.IOException
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

    internal data class HeaderResult(
        val keyBox: IntArray,
        val metadata: NcmMetadata,
        val format: AudioFormat
    )

    private fun parseHeader(input: InputStream): HeaderResult = parseHeaderStatic(input)

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

    internal class CountingInputStream(private val delegate: InputStream) : InputStream() {
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
                    // AppLogger.d("AmeScanner", "scanMetadata: first8bytes=[$magicHex] ascii=[$magicStr]")
                    val expected = byteArrayOf(67, 84, 69, 78, 70, 68, 65, 77)
                    if (!magic.contentEquals(expected)) {
                        // AppLogger.d("AmeScanner", "scanMetadata: NOT NCM (magic mismatch)")
                        return null
                    }

                    readBytesStatic(stream, 2) // skip

                    // Skip key block
                    val keyLen = readUint32LEStatic(stream)
                    // AppLogger.d("AmeScanner", "scanMetadata: keyBlockLen=$keyLen")
                    skipBytesStatic(stream, keyLen.toLong())

                    // Read meta block
                    val metaLen = readUint32LEStatic(stream)
                    // AppLogger.d("AmeScanner", "scanMetadata: metaBlockLen=$metaLen")
                    val metaEnc = readBytesStatic(stream, metaLen)
                    for (i in metaEnc.indices) {
                        metaEnc[i] = (metaEnc[i].toInt() xor 0x63).toByte()
                    }

                    val metadata = parseMetaBlockStatic(metaEnc)
                    // AppLogger.d("AmeScanner", "scanMetadata: parsed id=${metadata.musicId}, name=${metadata.musicName}, format=${metadata.format}, artists=${metadata.artists}")
                    // Invalid NCM if metadata is empty (magic matched but content is not NCM)
                    if (metadata.musicId == 0L && metadata.musicName == "Unknown") {
                        // AppLogger.d("AmeScanner", "scanMetadata: empty metadata, rejecting as NCM")
                        return null
                    }

                    val format = if (metadata.format.equals("flac", ignoreCase = true)) {
                        AudioFormat.FLAC
                    } else AudioFormat.MP3

                    // Extract embedded cover image
                    skipBytesStatic(stream, 5) // Skip 5 bytes CRC/gap
                    val imageLen = readUint32LEStatic(stream)
                    // AppLogger.i("[AME_COVER_DEBUG]", "Embedded imageLen=$imageLen")
                    val coverBytes = if (imageLen in 1..(20 * 1024 * 1024)) {
                        try {
                            readBytesStatic(stream, imageLen)
                        } catch (e: Exception) {
                            AppLogger.e("[AME_COVER_DEBUG]", "Failed to read image bytes: ${e.message}", e)
                            null
                        }
                    } else null

                    // AppLogger.i("[AME_COVER_DEBUG]", "Scan complete. format=$format, coverBytesSize=${coverBytes?.size ?: 0}")
                    Triple(metadata, format, coverBytes)
                }
            } catch (e: Exception) {
                AppLogger.e("[AME_COVER_DEBUG]", "scanMetadata exception=${e.javaClass.simpleName}: ${e.message}", e)
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
                // AppLogger.i("[AME_COVER_DEBUG]", "Raw metadata JSON: $jsonStr")
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

                // AppLogger.i("[AME_COVER_DEBUG]", "Parsed metadata: musicId=${metaJson.optLong("musicId")}, rawAlbumPic='$rawAlbumPic', finalAlbumPic='$albumPic'")

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
                AppLogger.e("[AME_COVER_DEBUG]", "parseMetaBlockStatic exception: ${e.message}", e)
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

        internal fun parseHeaderStatic(input: InputStream): HeaderResult {
            // Verify magic: "CTENFDAM"
            val magic = readBytesStatic(input, 8)
            val expected = byteArrayOf(67, 84, 69, 78, 70, 68, 65, 77)
            if (!magic.contentEquals(expected)) {
                throw IllegalArgumentException("Not a valid NCM file")
            }

            // Skip 2 bytes
            readBytesStatic(input, 2)

            // Key block: length (uint32 LE) + encrypted data
            val keyLength = readUint32LEStatic(input)
            val keyEncrypted = readBytesStatic(input, keyLength)
            for (i in keyEncrypted.indices) {
                keyEncrypted[i] = (keyEncrypted[i].toInt() xor 0x64).toByte()
            }
            val decryptedKey = AesEcbDecryptor.decrypt(keyEncrypted, CORE_KEY)
            val rc4Key = decryptedKey.copyOfRange(17, decryptedKey.size)
            val keyBox = Rc4Engine.buildKeyBox(rc4Key)

            // Meta block: length (uint32 LE) + encrypted data
            val metaLength = readUint32LEStatic(input)
            val metaEncrypted = readBytesStatic(input, metaLength)
            for (i in metaEncrypted.indices) {
                metaEncrypted[i] = (metaEncrypted[i].toInt() xor 0x63).toByte()
            }
            val metadata = parseMetaBlockStatic(metaEncrypted)

            // Gap block: skip
            skipBytesStatic(input, 5)
            val gapSize = readUint32LEStatic(input)
            skipBytesStatic(input, gapSize.toLong() + 4)

            val format = when (metadata.format.lowercase()) {
                "flac" -> AudioFormat.FLAC
                else -> AudioFormat.MP3
            }

            return HeaderResult(keyBox, metadata, format)
        }

        fun openDecryptedStream(cr: ContentResolver, uri: Uri): InputStream? {
            return try {
                val pfd = cr.openFileDescriptor(uri, "r") ?: return null
                val fis = FileInputStream(pfd.fileDescriptor)
                val channel = fis.channel
                val countingStream = CountingInputStream(fis)
                val headerResult = parseHeaderStatic(countingStream)
                val audioStartOffset = countingStream.count
                val totalFileSize = channel.size()
                val audioDataSize = maxOf(0L, totalFileSize - audioStartOffset)
                NcmDecryptedInputStream(pfd, fis, channel, headerResult.keyBox, audioStartOffset, audioDataSize)
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * 专为 Android Framework [android.media.MediaExtractor] 打造的原生流式 NCM 解密数据源。
 * 允许底层解码器直接读取解密后的标准音频流（FLAC/MP3），实现离线全曲 EBU R128 综合响度扫描。
 */
class NcmMediaDataSource(
    context: Context,
    uri: Uri
) : MediaDataSource() {

    private var pfd: ParcelFileDescriptor? = null
    private var fis: FileInputStream? = null
    private var fileChannel: FileChannel? = null
    private var keyBox: IntArray? = null
    private var audioStartOffset: Long = 0L
    private var audioDataSize: Long = 0L

    init {
        val cr = context.contentResolver
        val p = cr.openFileDescriptor(uri, "r")
            ?: throw IOException("Failed to open FileDescriptor for URI: $uri")
        pfd = p
        val fStream = FileInputStream(p.fileDescriptor)
        fis = fStream
        val channel = fStream.channel
        fileChannel = channel

        val countingStream = NcmDataSource.CountingInputStream(fStream)
        val headerResult = NcmDataSource.parseHeaderStatic(countingStream)
        keyBox = headerResult.keyBox
        audioStartOffset = countingStream.count

        val totalFileSize = channel.size()
        audioDataSize = maxOf(0L, totalFileSize - audioStartOffset)
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        val channel = fileChannel ?: return -1
        val box = keyBox ?: return -1

        if (position >= audioDataSize) return -1
        val bytesToRead = minOf(size.toLong(), audioDataSize - position).toInt()
        if (bytesToRead <= 0) return -1

        val byteBuffer = ByteBuffer.wrap(buffer, offset, bytesToRead)
        var totalRead = 0
        while (byteBuffer.hasRemaining()) {
            val read = channel.read(byteBuffer, audioStartOffset + position + totalRead)
            if (read <= 0) break
            totalRead += read
        }
        if (totalRead <= 0) return -1

        for (i in 0 until totalRead) {
            val filePos = position + i
            val keyIndex = (filePos and 0xff).toInt()
            val encryptedByte = buffer[offset + i].toInt() and 0xff
            buffer[offset + i] = (encryptedByte xor box[keyIndex]).toByte()
        }
        return totalRead
    }

    override fun getSize(): Long = audioDataSize

    override fun close() {
        runCatching { fileChannel?.close() }
        runCatching { fis?.close() }
        runCatching { pfd?.close() }
        fileChannel = null
        fis = null
        pfd = null
        keyBox = null
    }
}

/**
 * NCM 本地音频流式解密输入流，供元数据标签解析器读取内嵌 ReplayGain 等标签
 */
class NcmDecryptedInputStream(
    private val pfd: ParcelFileDescriptor,
    private val fis: FileInputStream,
    private val channel: FileChannel,
    private val keyBox: IntArray,
    private val audioStartOffset: Long,
    private val audioDataSize: Long
) : InputStream() {
    private var pos: Long = 0L

    override fun read(): Int {
        if (pos >= audioDataSize) return -1
        val buf = ByteBuffer.allocate(1)
        val r = channel.read(buf, audioStartOffset + pos)
        if (r <= 0) return -1
        val enc = buf.get(0).toInt() and 0xff
        val dec = enc xor keyBox[(pos and 0xff).toInt()]
        pos++
        return dec
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= audioDataSize) return -1
        val toRead = minOf(len.toLong(), audioDataSize - pos).toInt()
        if (toRead <= 0) return -1
        val buf = ByteBuffer.wrap(b, off, toRead)
        val r = channel.read(buf, audioStartOffset + pos)
        if (r <= 0) return -1
        for (i in 0 until r) {
            val keyIdx = ((pos + i) and 0xff).toInt()
            b[off + i] = ((b[off + i].toInt() and 0xff) xor keyBox[keyIdx]).toByte()
        }
        pos += r
        return r
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { fis.close() }
        runCatching { pfd.close() }
    }
}
