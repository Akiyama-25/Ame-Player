package Akari.NCM.player.data

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayOutputStream

/**
 * DataSource that reads the complete FLAC header once, caches it, then on reopen
 * replays the cached header followed by audio data. This preserves the original
 * file structure (STREAMINFO, SEEKTABLE, PICTURE, VORBIS_COMMENT, etc.) so that
 * FlacExtractor processes it identically to reading the original file.
 *
 * The companion Extractor wrapper (used in AmePlayerEngine) then skips the
 * remaining metadata blocks after STREAMINFO so getFrameStartMarker() can
 * locate the first audio frame sync code.
 */
@UnstableApi
class FlacHeaderCacheDataSource(private val delegate: DataSource) : DataSource {

    companion object {
        private val cache = mutableMapOf<String, StreamInfo>()

        data class StreamInfo(
            val headerData: ByteArray,  // complete header: fLaC + all metadata blocks
            val audioStart: Long,
            val fileLength: Long
        )

        fun clearCache() = cache.clear()
    }

    private var headerData: ByteArray = ByteArray(0)
    private var headerPos: Int = 0
    private var audioStart: Long = 0L
    private var headerPhase: Boolean = true

    override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {
        delegate.addTransferListener(listener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uriKey = dataSpec.uri.toString()
        val info = cache[uriKey]

        if (info != null) {
            headerData = info.headerData
            audioStart = info.audioStart
            headerPos = 0
            headerPhase = true
            delegate.open(
                DataSpec.Builder()
                    .setUri(dataSpec.uri)
                    .setPosition(audioStart)
                    .build()
            )
            val apparent = headerData.size.toLong() + (info.fileLength - audioStart)
            Log.d("AmeFlacDS", "open(cached): header=${headerData.size}B, audio=$audioStart, apparent=$apparent")
            return apparent
        }

        val fileLength = delegate.open(
            DataSpec.Builder()
                .setUri(dataSpec.uri)
                .setPosition(0)
                .setLength(dataSpec.length)
                .build()
        )

        if (fileLength < 4) {
            headerPhase = false
            return fileLength
        }

        // Read and verify "fLaC" magic
        val magic = ByteArray(4)
        readFromDelegate(magic, 0, 4)
        if (magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
            magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()
        ) {
            // Not FLAC — pass through
            delegate.close()
            val len = delegate.open(
                DataSpec.Builder().setUri(dataSpec.uri).setPosition(0)
                    .setLength(dataSpec.length).build()
            )
            headerPhase = false
            return len
        }

        // Read ALL metadata blocks to get the complete header
        val headerBuf = ByteArrayOutputStream()
        headerBuf.write(magic)
        var pos = 4L

        while (pos < fileLength) {
            val blockHdr = ByteArray(4)
            val n = readFromDelegate(blockHdr, 0, 4)
            if (n < 4) break
            headerBuf.write(blockHdr, 0, n)
            pos += n

            val isLast = (blockHdr[0].toInt() and 0x80) != 0
            val blockSize = ((blockHdr[1].toInt() and 0xFF) shl 16) or
                    ((blockHdr[2].toInt() and 0xFF) shl 8) or
                    (blockHdr[3].toInt() and 0xFF)

            if (blockSize > 0) {
                val blockData = ByteArray(blockSize)
                var off = 0
                while (off < blockSize) {
                    val r = readFromDelegate(blockData, off, blockSize - off)
                    if (r <= 0) break
                    off += r
                }
                headerBuf.write(blockData, 0, off)
                pos += off
            }

            if (isLast) break
        }

        headerData = headerBuf.toByteArray()
        audioStart = pos
        headerPos = 0
        headerPhase = true

        cache[uriKey] = StreamInfo(headerData, audioStart, fileLength)

        val apparent = if (fileLength > 0) headerData.size.toLong() + (fileLength - audioStart) else -1L
        Log.d("AmeFlacDS", "open(parsed): header=${headerData.size}B, audioStart=$audioStart, apparent=$apparent")
        return apparent
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        if (headerPhase) {
            val remaining = headerData.size - headerPos
            if (remaining > 0) {
                val toRead = minOf(length, remaining)
                System.arraycopy(headerData, headerPos, buffer, offset, toRead)
                headerPos += toRead
                if (headerPos >= headerData.size) headerPhase = false
                return toRead
            }
            headerPhase = false
        }

        return delegate.read(buffer, offset, length)
    }

    override fun getUri(): Uri = delegate.uri ?: Uri.EMPTY

    override fun close() {
        headerPos = 0
        headerPhase = true
        delegate.close()
    }

    private fun readFromDelegate(buffer: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val n = delegate.read(buffer, offset + total, length - total)
            if (n == -1) break
            total += n
        }
        return total
    }
}
