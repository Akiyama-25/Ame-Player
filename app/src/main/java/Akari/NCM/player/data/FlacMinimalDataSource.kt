package Akari.NCM.player.data

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.ByteArrayOutputStream

/**
 * DataSource that rebuilds a clean FLAC stream for FlacExtractor.
 *
 * Keeps STREAMINFO (type 0) and SEEKTABLE (type 3) — both required by FlacExtractor for
 * proper frame parsing and seeking. Strips PICTURE (type 6), VORBIS_COMMENT (type 4),
 * and other non-essential blocks that cause "First frame does not start with sync code" errors.
 *
 * Header data is cached per-URI for fast seek-triggered reopens.
 */
@UnstableApi
class FlacMinimalDataSource(private val delegate: DataSource) : DataSource {

    companion object {
        private val cache = mutableMapOf<String, StreamInfo>()

        data class StreamInfo(
            val headerData: ByteArray,  // fLaC + STREAMINFO + SEEKTABLE (isLast=true)
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

        // First open: parse and rebuild FLAC structure
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

        // Read "fLaC" magic
        val magic = ByteArray(4)
        readFromDelegate(magic, 0, 4)
        if (magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
            magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()
        ) {
            delegate.close()
            val len = delegate.open(
                DataSpec.Builder().setUri(dataSpec.uri).setPosition(0)
                    .setLength(dataSpec.length).build()
            )
            headerPhase = false
            return len
        }

        // Parse all metadata blocks, keeping STREAMINFO (0) and SEEKTABLE (3)
        val keptBlocks = ByteArrayOutputStream()
        keptBlocks.write(magic)
        var pos = 4L
        var lastKeptType = -1

        while (pos < fileLength) {
            val blockHdr = ByteArray(4)
            val n = readFromDelegate(blockHdr, 0, 4)
            if (n < 4) break
            pos += n

            val isLast = (blockHdr[0].toInt() and 0x80) != 0
            val blockType = blockHdr[0].toInt() and 0x3F
            val blockSize = ((blockHdr[1].toInt() and 0xFF) shl 16) or
                    ((blockHdr[2].toInt() and 0xFF) shl 8) or
                    (blockHdr[3].toInt() and 0xFF)

            // Read block data
            val blockData: ByteArray? = if (blockSize > 0) {
                val data = ByteArray(blockSize)
                if (!readFullyFromDelegate(data, 0, blockSize)) null
                else data.also { pos += blockSize }
            } else ByteArray(0)

            if (blockData == null) break

            // Keep STREAMINFO (0) and SEEKTABLE (3)
            if (blockType == 0 || blockType == 3) {
                // Write block header (without isLast, we'll fix it later)
                keptBlocks.write(blockHdr[0].toInt() and 0x7F) // clear isLast
                keptBlocks.write(blockHdr, 1, 3)
                keptBlocks.write(blockData)
                lastKeptType = blockType
            }

            if (isLast) break
        }

        // Build final header: mark last kept block as isLast
        val rawHeader = keptBlocks.toByteArray()
        // Find the last block's header byte and set isLast flag
        // We need to walk through the kept blocks to find the last one's position
        var scanPos = 4 // skip "fLaC"
        var lastBlockHeaderPos = -1
        while (scanPos < rawHeader.size) {
            lastBlockHeaderPos = scanPos
            val bType = rawHeader[scanPos].toInt() and 0x3F
            val bSize = if (scanPos + 3 < rawHeader.size) {
                ((rawHeader[scanPos + 1].toInt() and 0xFF) shl 16) or
                ((rawHeader[scanPos + 2].toInt() and 0xFF) shl 8) or
                (rawHeader[scanPos + 3].toInt() and 0xFF)
            } else 0
            scanPos += 4 + bSize
        }

        // Set isLast on the last kept block
        if (lastBlockHeaderPos >= 0 && lastBlockHeaderPos < rawHeader.size) {
            rawHeader[lastBlockHeaderPos] = (rawHeader[lastBlockHeaderPos].toInt() or 0x80).toByte()
        }

        headerData = rawHeader
        headerSize = headerData.size.toLong()
        audioStart = pos
        headerPos = 0
        headerPhase = true

        cache[uriKey] = StreamInfo(headerData, audioStart, fileLength)

        val apparent = if (fileLength > 0) headerSize + (fileLength - audioStart) else -1L

        Log.d("AmeFlacDS", "open(parsed): header=${headerSize}B, audioStart=$audioStart, apparent=$apparent")
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

    private fun readFullyFromDelegate(buffer: ByteArray, offset: Int, length: Int): Boolean {
        var total = 0
        while (total < length) {
            val n = delegate.read(buffer, offset + total, length - total)
            if (n == -1) return false
            total += n
        }
        return true
    }
}
