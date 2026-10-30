package Akari.NCM.player.core.loudness

import Akari.NCM.player.core.AppLogger
import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10

/**
 * 解析本地音频文件中的 ReplayGain / EBU R128 / Apple Sound Check 响度元数据标签
 */
object AudioTagLoudnessParser {
    private const val TAG = "AudioTagLoudnessParser"

    fun extractLoudness(context: Context, uri: Uri): LoudnessData? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                parseStream(stream)
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Failed to parse audio tags: ${e.message}")
            null
        }
    }

    fun parseStream(stream: InputStream): LoudnessData? {
        val header = ByteArray(12)
        val read = stream.read(header)
        if (read < 4) return null

        return when {
            // FLAC: "fLaC"
            header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                    header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte() -> {
                parseFlac(stream)
            }
            // MP3 ID3v2: "ID3"
            header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte() -> {
                parseId3(header, stream)
            }
            else -> null
        }
    }

    private fun parseFlac(stream: InputStream): LoudnessData? {
        var isLast = false
        var replayGain: Float? = null
        var r128Gain: Float? = null
        var trackPeak: Float? = null

        while (!isLast) {
            val blockHeader = ByteArray(4)
            if (stream.read(blockHeader) < 4) break
            val firstByte = blockHeader[0].toInt() and 0xFF
            isLast = (firstByte and 0x80) != 0
            val blockType = firstByte and 0x7F
            val length = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                    ((blockHeader[2].toInt() and 0xFF) shl 8) or
                    (blockHeader[3].toInt() and 0xFF)

            if (blockType == 4) { // VORBIS_COMMENT
                val commentBytes = ByteArray(length)
                if (stream.read(commentBytes) < length) break
                val comments = parseVorbisComments(commentBytes)
                for ((key, value) in comments) {
                    when (key.uppercase()) {
                        "REPLAYGAIN_TRACK_GAIN" -> {
                            replayGain = parseGainDb(value)
                        }
                        "R128_TRACK_GAIN" -> {
                            r128Gain = parseGainDb(value)
                        }
                        "REPLAYGAIN_TRACK_PEAK" -> {
                            trackPeak = value.toFloatOrNull()
                        }
                    }
                }
                break
            } else {
                stream.skip(length.toLong())
            }
        }

        if (r128Gain != null) {
            // R128_TRACK_GAIN 标准基准为 -23.0 LUFS
            val integrated = -23.0f - r128Gain
            return LoudnessData(integrated, trackPeak ?: 1.0f, LoudnessSource.REPLAYGAIN_TAG)
        }
        if (replayGain != null) {
            // ReplayGain 2.0 (BS.1770) 标准参考基准为 -18.0 LUFS
            val integrated = -18.0f - replayGain
            return LoudnessData(integrated, trackPeak ?: 1.0f, LoudnessSource.REPLAYGAIN_TAG)
        }
        return null
    }

    private fun parseVorbisComments(bytes: ByteArray): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 4) return list
        val vendorLen = buf.int
        if (vendorLen < 0 || vendorLen > buf.remaining()) return list
        buf.position(buf.position() + vendorLen)
        if (buf.remaining() < 4) return list
        val userCommentCount = buf.int
        var count = 0
        while (count < userCommentCount && buf.remaining() >= 4) {
            val len = buf.int
            if (len <= 0 || len > buf.remaining()) break
            val strBytes = ByteArray(len)
            buf.get(strBytes)
            val str = String(strBytes, Charsets.UTF_8)
            val parts = str.split("=", limit = 2)
            if (parts.size == 2) {
                list.add(parts[0].trim() to parts[1].trim())
            }
            count++
        }
        return list
    }

    private fun parseId3(header10: ByteArray, stream: InputStream): LoudnessData? {
        val versionMajor = header10[3].toInt() and 0xFF
        val tagSize = ((header10[6].toInt() and 0x7F) shl 21) or
                ((header10[7].toInt() and 0x7F) shl 14) or
                ((header10[8].toInt() and 0x7F) shl 7) or
                (header10[9].toInt() and 0x7F)

        if (tagSize <= 0 || tagSize > 10 * 1024 * 1024) return null
        val tagBytes = ByteArray(tagSize)
        var totalRead = 0
        while (totalRead < tagSize) {
            val r = stream.read(tagBytes, totalRead, tagSize - totalRead)
            if (r <= 0) break
            totalRead += r
        }

        val buf = ByteBuffer.wrap(tagBytes)
        var replayGain: Float? = null
        var trackPeak: Float? = null

        while (buf.remaining() >= 10) {
            val frameId = ByteArray(4)
            buf.get(frameId)
            if (frameId[0].toInt() == 0) break // ID3 padding
            val frameIdStr = String(frameId, Charsets.US_ASCII)

            val frameSize = if (versionMajor >= 4) {
                ((buf.get().toInt() and 0x7F) shl 21) or
                        ((buf.get().toInt() and 0x7F) shl 14) or
                        ((buf.get().toInt() and 0x7F) shl 7) or
                        (buf.get().toInt() and 0x7F)
            } else {
                buf.int
            }
            buf.short // flags

            if (frameSize <= 0 || frameSize > buf.remaining()) break

            val frameData = ByteArray(frameSize)
            buf.get(frameData)

            if (frameIdStr == "TXXX") {
                val str = parseTxxxFrame(frameData)
                if (str != null) {
                    val (desc, value) = str
                    if (desc.equals("REPLAYGAIN_TRACK_GAIN", ignoreCase = true)) {
                        replayGain = parseGainDb(value)
                    } else if (desc.equals("REPLAYGAIN_TRACK_PEAK", ignoreCase = true)) {
                        trackPeak = value.toFloatOrNull()
                    }
                }
            } else if (frameIdStr == "COMM" || frameIdStr == "COM ") {
                val soundCheck = parseSoundCheckComm(frameData)
                if (soundCheck != null && replayGain == null) {
                    replayGain = soundCheck
                }
            }
        }

        if (replayGain != null) {
            val integrated = -18.0f - replayGain
            return LoudnessData(integrated, trackPeak ?: 1.0f, LoudnessSource.REPLAYGAIN_TAG)
        }
        return null
    }

    private fun parseTxxxFrame(data: ByteArray): Pair<String, String>? {
        if (data.size < 2) return null
        val encoding = data[0].toInt()
        val textBytes = data.copyOfRange(1, data.size)
        val text = when (encoding) {
            1 -> String(textBytes, Charsets.UTF_16)
            2 -> String(textBytes, Charsets.UTF_16BE)
            3 -> String(textBytes, Charsets.UTF_8)
            else -> String(textBytes, Charsets.ISO_8859_1)
        }
        val parts = text.split("\u0000", limit = 2)
        return if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
    }

    private fun parseSoundCheckComm(data: ByteArray): Float? {
        if (data.size < 5) return null
        val text = String(data, Charsets.ISO_8859_1)
        if (!text.contains("iTunNORM")) return null
        val match = Regex("""([0-9A-Fa-f]{8}\s+){9}[0-9A-Fa-f]{8}""").find(text) ?: return null
        val tokens = match.value.trim().split(Regex("""\s+"""))
        if (tokens.size >= 2) {
            val val1 = tokens[0].toLongOrNull(16) ?: return null
            if (val1 > 0) {
                return (-10.0 * log10(val1.toDouble() / 1000.0)).toFloat()
            }
        }
        return null
    }

    private fun parseGainDb(value: String): Float? {
        val clean = value.replace("dB", "", ignoreCase = true).trim()
        return clean.toFloatOrNull()
    }
}