package Akari.NCM.player.data

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayOutputStream

/**
 * DataSource wrapper for local FLAC files with large metadata blocks (lyrics, cover art).
 *
 * Media3 1.5.1's FlacExtractor fails with "sync code" errors when the first audio frame
 * is far from the stream start due to large metadata blocks. This wrapper reads through
 * the metadata blocks once, then replays them followed by audio data, presenting a
 * contiguous stream that FlacExtractor can parse correctly.
 *
 * Header data and stream info are cached per-URI so seek-triggered reopens are fast.
 */
@UnstableApi
class FlacStreamDataSource(private val delegate: DataSource) : DataSource {

    companion object {
        private val cache = mutableMapOf<String, StreamInfo>()

        data class StreamInfo(
            val headerData: ByteArray,
            val audioStart: Long,
            val fileLength: Long
        )

        fun clearCache() = cache.clear()
    }

    private var headerData: ByteArray = ByteArray(0)
    private var headerPos: Int = 0
    private var headerSize: Long = 0L
    private var audioStart: Long = 0L
    private var headerPhase: Boolean = true

    override fun addTransferListener(listener: androidx.media3.datasource.TransferListener) {
        delegate.addTransferListener(listener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uriKey = dataSpec.uri.toString()
        val info = cache[uriKey]

        if (info != null) {
            // Cached: restore state and open delegate at audio position
            headerData = info.headerData
            headerSize = headerData.size.toLong()
            audioStart = info.audioStart
            headerPos = 0
            headerPhase = true
            delegate.open(
                DataSpec.Builder()
                    .setUri(dataSpec.uri)
                    .setPosition(audioStart)
                    .build()
            )
            val apparent = headerSize + (info.fileLength - audioStart)
            Log.d("AmeFlacDS", "open(cached): header=${headerSize}B, audio=$audioStart, apparent=$apparent")
            return apparent
        }

        // First open: parse header from file
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

        // Check "fLaC" magic
        val magic = ByteArray(4)
        readFromDelegate(magic, 0, 4)
        if (magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
            magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()
        ) {
            delegate.close()
            val len = delegate.open(
                DataSpec.Builder()
                    .setUri(dataSpec.uri)
                    .setPosition(0)
                    .setLength(dataSpec.length)
                    .build()
            )
            headerPhase = false
            return len
        }

        // Read all metadata blocks
        val buf = ByteArrayOutputStream()
        buf.write(magic)
        var pos = 4L

        while (pos < fileLength) {
            val hdr = ByteArray(4)
            val n = readFromDelegate(hdr, 0, 4)
            if (n < 4) break
            buf.write(hdr, 0, n)
            pos += n

            val isLast = (hdr[0].toInt() and 0x80) != 0
            val blockSize = ((hdr[1].toInt() and 0xFF) shl 16) or
                    ((hdr[2].toInt() and 0xFF) shl 8) or
                    (hdr[3].toInt() and 0xFF)

            if (blockSize > 0) {
                val block = ByteArray(blockSize)
                var off = 0
                while (off < blockSize) {
                    val r = readFromDelegate(block, off, blockSize - off)
                    if (r <= 0) break
                    off += r
                }
                buf.write(block, 0, off)
                pos += off
            }

            if (isLast) break
        }

        headerData = buf.toByteArray()
        headerSize = headerData.size.toLong()
        audioStart = pos
        headerPos = 0
        headerPhase = true

        cache[uriKey] = StreamInfo(headerData, audioStart, fileLength)

        val apparent = if (fileLength > 0) headerSize + (fileLength - audioStart)
        else C.LENGTH_UNSET.toLong()

        Log.d("AmeFlacDS", "open(parsed): header=${headerSize}B, audio=$audioStart, apparent=$apparent")
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

    private object C {
        const val LENGTH_UNSET = -1
    }
}
