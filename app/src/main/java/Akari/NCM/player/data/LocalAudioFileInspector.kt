package Akari.NCM.player.data

import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.AudioFormatInfo
import Akari.NCM.player.core.PlayableMedia
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream

/**
 * 本地音频文件头解析器：
 * 直接从保存的音频文件（含 FLAC / WAV / MP3 / M4A / Dolby / 解密后的 NCM）读取真实的编码、
 * 采样率、位深、声道数与比特率，避免依赖播放器近似值造成 16-bit FLAC 被误判为 24-bit。
 */
object LocalAudioFileInspector {
    private const val TAG = "LocalAudioInspector"
    private const val MAX_HEADER_PROBE_BYTES = 256 * 1024

    fun inspect(context: Context, media: PlayableMedia.LocalNcm): AudioFormatInfo? {
        val uri = runCatching { Uri.parse(media.uri) }.getOrNull() ?: return null
        return inspectUri(
            context = context,
            uri = uri,
            isNcm = media.isNcm,
            fallbackFormat = media.format,
            knownDurationMs = media.durationMs
        )
    }

    fun inspectFile(
        file: File,
        isNcm: Boolean = file.extension.equals("ncm", ignoreCase = true),
        fallbackFormat: AudioFormat? = null,
        knownDurationMs: Long = 0L
    ): AudioFormatInfo? {
        if (!file.exists() || !file.isFile) return null
        val fileSize = file.length()
        if (fileSize <= 0L) return null
        return runCatching {
            file.inputStream().use { raw ->
                parseAudioStreamInfo(
                    stream = raw,
                    fileSizeBytes = fileSize,
                    durationHintMs = knownDurationMs,
                    fallbackFormat = fallbackFormat
                )
            }
        }.getOrNull()
    }

    fun inspectUri(
        context: Context,
        uri: Uri,
        isNcm: Boolean,
        fallbackFormat: AudioFormat? = null,
        knownDurationMs: Long = 0L
    ): AudioFormatInfo? {
        val fileSize = resolveFileSize(context, uri)

        // 1. 直接二进制容器头解析（支持 FLAC / WAV / MP3 及 NCM 流式解密）
        val parsed = runCatching {
            val stream = if (isNcm) {
                NcmDataSource.openDecryptedStream(context.contentResolver, uri)
            } else {
                openInputStream(context, uri)
            }
            stream?.use {
                parseAudioStreamInfo(
                    stream = it,
                    fileSizeBytes = fileSize,
                    durationHintMs = knownDurationMs,
                    fallbackFormat = fallbackFormat
                )
            }
        }.getOrNull()

        if (parsed != null && isCompleteInfo(parsed)) {
            return parsed
        }

        // 2. 非 NCM 回退 MediaExtractor + MediaMetadataRetriever（覆盖 M4A / ALAC / Dolby E-AC-3 / OGG / Opus）
        if (!isNcm) {
            val extracted = extractWithAndroidMediaApis(context, uri, fileSize, knownDurationMs)
            if (extracted != null) {
                return mergeInfos(primary = parsed, fallback = extracted, fileSizeBytes = fileSize)
            }
        }

        return parsed
    }

    internal fun parseAudioStreamInfo(
        stream: InputStream,
        fileSizeBytes: Long = 0L,
        durationHintMs: Long = 0L,
        fallbackFormat: AudioFormat? = null
    ): AudioFormatInfo? {
        val probe = ByteArray(16)
        val read = readFullyUpTo(stream, probe, probe.size)
        if (read < 4) return null

        val replayStream = SequenceInputStream(
            ByteArrayInputStream(probe, 0, read),
            stream
        )

        // FLAC: "fLaC"
        if (probe[0] == 'f'.code.toByte() &&
            probe[1] == 'L'.code.toByte() &&
            probe[2] == 'a'.code.toByte() &&
            probe[3] == 'C'.code.toByte()
        ) {
            return parseFlacHeader(replayStream, fileSizeBytes)
        }

        // WAV: "RIFF"...."WAVE"
        if (read >= 12 &&
            probe[0] == 'R'.code.toByte() &&
            probe[1] == 'I'.code.toByte() &&
            probe[2] == 'F'.code.toByte() &&
            probe[3] == 'F'.code.toByte() &&
            probe[8] == 'W'.code.toByte() &&
            probe[9] == 'A'.code.toByte() &&
            probe[10] == 'V'.code.toByte() &&
            probe[11] == 'E'.code.toByte()
        ) {
            return parseWavHeader(replayStream, fileSizeBytes)
        }

        // MP4 / M4A: ...."ftyp"
        if (read >= 8 &&
            probe[4] == 'f'.code.toByte() &&
            probe[5] == 't'.code.toByte() &&
            probe[6] == 'y'.code.toByte() &&
            probe[7] == 'p'.code.toByte()
        ) {
            return parseMp4BasicHeader(replayStream, fileSizeBytes, durationHintMs)
        }

        // ID3v2-tagged MP3/FLAC or raw MPEG frame
        if ((probe[0] == 'I'.code.toByte() && probe[1] == 'D'.code.toByte() && probe[2] == '3'.code.toByte()) ||
            ((probe[0].toInt() and 0xFF) == 0xFF && (probe[1].toInt() and 0xE0) == 0xE0)
        ) {
            return parseMp3OrId3Stream(replayStream, fileSizeBytes, durationHintMs)
        }

        // Ogg container ("OggS")
        if (probe[0] == 'O'.code.toByte() &&
            probe[1] == 'g'.code.toByte() &&
            probe[2] == 'g'.code.toByte() &&
            probe[3] == 'S'.code.toByte()
        ) {
            val bitrate = computeBitrateKbps(fileSizeBytes, durationHintMs)
            return AudioFormatInfo(
                bitrateKbps = bitrate,
                sampleRateHz = 48000,
                bitDepthBits = 16,
                mimeType = "audio/ogg",
                channels = 2,
                fileSizeBytes = fileSizeBytes,
                qualityLevel = null
            )
        }

        return fallbackFormat?.let {
            AudioFormatInfo(
                bitrateKbps = computeBitrateKbps(fileSizeBytes, durationHintMs),
                sampleRateHz = 0,
                bitDepthBits = 16,
                mimeType = it.mimeType,
                channels = 2,
                fileSizeBytes = fileSizeBytes,
                qualityLevel = null
            )
        }
    }

    /**
     * 解析 FLAC STREAMINFO 元数据块，获取精确的采样率、声道数、位深与总采样数。
     */
    internal fun parseFlacHeader(stream: InputStream, fileSizeBytes: Long = 0L): AudioFormatInfo? {
        val magic = ByteArray(4)
        if (readFullyUpTo(stream, magic, 4) < 4) return null
        if (magic[0] != 'f'.code.toByte() ||
            magic[1] != 'L'.code.toByte() ||
            magic[2] != 'a'.code.toByte() ||
            magic[3] != 'C'.code.toByte()
        ) {
            return null
        }

        var isLast = false
        var guard = 0
        while (!isLast && guard++ < 64) {
            val header = ByteArray(4)
            if (readFullyUpTo(stream, header, 4) < 4) break
            val b0 = header[0].toInt() and 0xFF
            isLast = (b0 and 0x80) != 0
            val blockType = b0 and 0x7F
            val blockLength = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)

            if (blockType == 0 && blockLength >= 34) {
                val info = ByteArray(34)
                if (readFullyUpTo(stream, info, 34) < 34) return null

                // Bytes 10..17 in STREAMINFO:
                // 20 bits sampleRate | 3 bits (channels - 1) | 5 bits (bitsPerSample - 1) | 36 bits totalSamples
                val b10 = info[10].toInt() and 0xFF
                val b11 = info[11].toInt() and 0xFF
                val b12 = info[12].toInt() and 0xFF
                val b13 = info[13].toInt() and 0xFF
                val b14 = info[14].toInt() and 0xFF
                val b15 = info[15].toInt() and 0xFF
                val b16 = info[16].toInt() and 0xFF
                val b17 = info[17].toInt() and 0xFF

                val sampleRate = (b10 shl 12) or (b11 shl 4) or ((b12 ushr 4) and 0x0F)
                val channels = ((b12 ushr 1) and 0x07) + 1
                val bitsPerSample = (((b12 and 0x01) shl 4) or ((b13 ushr 4) and 0x0F)) + 1
                val totalSamples = (((b13 and 0x0F).toLong()) shl 32) or
                    (b14.toLong() shl 24) or
                    (b15.toLong() shl 16) or
                    (b16.toLong() shl 8) or
                    b17.toLong()

                val durationMs = if (sampleRate > 0 && totalSamples > 0L) {
                    (totalSamples * 1000L) / sampleRate
                } else 0L
                val bitrateKbps = computeBitrateKbps(fileSizeBytes, durationMs)

                return AudioFormatInfo(
                    bitrateKbps = bitrateKbps,
                    sampleRateHz = sampleRate,
                    bitDepthBits = bitsPerSample,
                    mimeType = "audio/flac",
                    channels = channels,
                    fileSizeBytes = fileSizeBytes,
                    qualityLevel = null
                )
            } else {
                skipFully(stream, blockLength.toLong())
            }
        }
        return null
    }

    /**
     * 解析 WAV RIFF fmt 块，获取精确的采样率、位深、声道数与比特率。
     */
    internal fun parseWavHeader(stream: InputStream, fileSizeBytes: Long = 0L): AudioFormatInfo? {
        val riffHeader = ByteArray(12)
        if (readFullyUpTo(stream, riffHeader, 12) < 12) return null
        if (String(riffHeader, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(riffHeader, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) {
            return null
        }

        var guard = 0
        while (guard++ < 32) {
            val chunkHeader = ByteArray(8)
            if (readFullyUpTo(stream, chunkHeader, 8) < 8) break
            val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val chunkSize = readUint32LE(chunkHeader, 4)
            if (chunkId == "fmt " && chunkSize >= 16L) {
                val fmt = ByteArray(16)
                if (readFullyUpTo(stream, fmt, 16) < 16) return null
                val channels = readUint16LE(fmt, 2)
                val sampleRate = readUint32LE(fmt, 4).toInt()
                val byteRate = readUint32LE(fmt, 8)
                val bitsPerSample = readUint16LE(fmt, 14)
                val bitrateKbps = if (byteRate > 0L) ((byteRate * 8L) / 1000L).toInt() else 0

                return AudioFormatInfo(
                    bitrateKbps = bitrateKbps,
                    sampleRateHz = sampleRate,
                    bitDepthBits = if (bitsPerSample > 0) bitsPerSample else 16,
                    mimeType = "audio/wav",
                    channels = if (channels > 0) channels else 2,
                    fileSizeBytes = fileSizeBytes,
                    qualityLevel = null
                )
            } else {
                val padded = chunkSize + (chunkSize and 1L)
                if (padded <= 0L) break
                skipFully(stream, padded)
            }
        }
        return null
    }

    /**
     * 解析 ID3v2 标签后的 FLAC 或 MPEG (MP3) 帧头，获取采样率、声道数与比特率。
     */
    internal fun parseMp3OrId3Stream(
        stream: InputStream,
        fileSizeBytes: Long = 0L,
        durationHintMs: Long = 0L
    ): AudioFormatInfo? {
        val first10 = ByteArray(10)
        val n = readFullyUpTo(stream, first10, 10)
        if (n < 4) return null

        val audioStream: InputStream = if (n >= 10 &&
            first10[0] == 'I'.code.toByte() &&
            first10[1] == 'D'.code.toByte() &&
            first10[2] == '3'.code.toByte()
        ) {
            val id3Size = syncSafeInt(first10, 6)
            val hasFooter = (first10[5].toInt() and 0x10) != 0
            val toSkip = id3Size.toLong() + if (hasFooter) 10L else 0L
            if (toSkip > 0L) {
                skipFully(stream, toSkip)
            }
            stream
        } else {
            SequenceInputStream(ByteArrayInputStream(first10, 0, n), stream)
        }

        val scanBuf = ByteArray(MAX_HEADER_PROBE_BYTES)
        val scanned = readFullyUpTo(audioStream, scanBuf, scanBuf.size)
        if (scanned < 4) return null

        // 若 ID3v2 后紧跟 FLAC 流，转交 FLAC 解析器
        if (scanBuf[0] == 'f'.code.toByte() &&
            scanBuf[1] == 'L'.code.toByte() &&
            scanBuf[2] == 'a'.code.toByte() &&
            scanBuf[3] == 'C'.code.toByte()
        ) {
            return parseFlacHeader(ByteArrayInputStream(scanBuf, 0, scanned), fileSizeBytes)
        }

        // 查找合法的 MPEG-1/2 Audio 帧头
        for (i in 0 until scanned - 4) {
            val b0 = scanBuf[i].toInt() and 0xFF
            val b1 = scanBuf[i + 1].toInt() and 0xFF
            val b2 = scanBuf[i + 2].toInt() and 0xFF
            val b3 = scanBuf[i + 3].toInt() and 0xFF

            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) continue
            val versionBits = (b1 ushr 3) and 0x03
            val layerBits = (b1 ushr 1) and 0x03
            val bitrateIdx = (b2 ushr 4) and 0x0F
            val sampleRateIdx = (b2 ushr 2) and 0x03
            val channelMode = (b3 ushr 6) and 0x03

            if (versionBits == 1 || layerBits == 0 || bitrateIdx == 0 || bitrateIdx == 15 || sampleRateIdx == 3) {
                continue
            }

            val sampleRate = lookupMpegSampleRate(versionBits, sampleRateIdx)
            val frameBitrateKbps = lookupMpegBitrateKbps(versionBits, layerBits, bitrateIdx)
            if (sampleRate <= 0 || frameBitrateKbps <= 0) continue

            val channels = if (channelMode == 3) 1 else 2
            val isVbrHeader = containsXingOrVbriTag(scanBuf, i, minOf(scanned, i + 180))
            val computedAvg = computeBitrateKbps(fileSizeBytes, durationHintMs)
            val finalBitrate = if (isVbrHeader && computedAvg > 0) {
                computedAvg
            } else {
                frameBitrateKbps
            }

            return AudioFormatInfo(
                bitrateKbps = finalBitrate,
                sampleRateHz = sampleRate,
                bitDepthBits = 16,
                mimeType = "audio/mpeg",
                channels = channels,
                fileSizeBytes = fileSizeBytes,
                qualityLevel = null
            )
        }
        return null
    }

    /**
     * 快速扫描 MP4/M4A 头部前若干 KB 以识别封装编码（Dolby E-AC-3 / AC-3 / AC-4 / ALAC / FLAC / AAC）。
     */
    private fun parseMp4BasicHeader(
        stream: InputStream,
        fileSizeBytes: Long,
        durationHintMs: Long
    ): AudioFormatInfo? {
        val buf = ByteArray(minOf(MAX_HEADER_PROBE_BYTES, 128 * 1024))
        val read = readFullyUpTo(stream, buf, buf.size)
        if (read < 16) return null
        val ascii = String(buf, 0, read, Charsets.ISO_8859_1)
        val mime = when {
            ascii.contains("ec-3") || ascii.contains("dec3") -> "audio/eac3"
            ascii.contains("ac-4") || ascii.contains("dac4") -> "audio/ac4"
            ascii.contains("ac-3") || ascii.contains("dac3") -> "audio/ac3"
            ascii.contains("alac") -> "audio/alac"
            ascii.contains("fLaC") -> "audio/flac"
            ascii.contains("mp4a") -> "audio/mp4a-latm"
            else -> null
        } ?: return null

        return AudioFormatInfo(
            bitrateKbps = computeBitrateKbps(fileSizeBytes, durationHintMs),
            sampleRateHz = 0,
            bitDepthBits = 16,
            mimeType = mime,
            channels = 2,
            fileSizeBytes = fileSizeBytes,
            qualityLevel = null
        )
    }

    private fun extractWithAndroidMediaApis(
        context: Context,
        uri: Uri,
        fileSizeBytes: Long,
        knownDurationMs: Long
    ): AudioFormatInfo? {
        var mimeType: String? = null
        var sampleRate = 0
        var channels = 0
        var bitDepth = 0
        var bitrateKbps = 0
        var durationMs = knownDurationMs

        val extractor = MediaExtractor()
        try {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                extractor.setDataSource(uri.path!!)
            } else {
                extractor.setDataSource(context, uri, null)
            }
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                mimeType = mime
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    bitrateKbps = format.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
                }
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    val us = format.getLong(MediaFormat.KEY_DURATION)
                    if (us > 0L) durationMs = us / 1000L
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    val enc = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    bitDepth = pcmEncodingToBitDepth(enc)
                }
                break
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "MediaExtractor probe skipped for $uri: ${e.message}")
        } finally {
            runCatching { extractor.release() }
        }

        if (bitrateKbps <= 0 || durationMs <= 0L || sampleRate <= 0 || bitDepth <= 0) {
            val retriever = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                    retriever.setDataSource(uri.path!!)
                } else {
                    retriever.setDataSource(context, uri)
                }
                if (mimeType.isNullOrBlank()) {
                    mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                }
                if (bitrateKbps <= 0) {
                    val br = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                    if (br > 0) bitrateKbps = br / 1000
                }
                if (durationMs <= 0L) {
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (sampleRate <= 0) {
                        sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
                    }
                    if (bitDepth <= 0) {
                        bitDepth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { retriever.release() }
            }
        }

        if (bitrateKbps <= 0) {
            bitrateKbps = computeBitrateKbps(fileSizeBytes, durationMs)
        }
        if (mimeType.isNullOrBlank() && sampleRate <= 0 && bitrateKbps <= 0) {
            return null
        }

        return AudioFormatInfo(
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRate,
            bitDepthBits = if (bitDepth > 0) bitDepth else 16,
            mimeType = mimeType,
            channels = if (channels > 0) channels else 2,
            fileSizeBytes = fileSizeBytes,
            qualityLevel = null
        )
    }

    private fun mergeInfos(
        primary: AudioFormatInfo?,
        fallback: AudioFormatInfo,
        fileSizeBytes: Long
    ): AudioFormatInfo {
        if (primary == null) return fallback
        val primaryMime = primary.mimeType
        val fallbackMime = fallback.mimeType
        val mergedMime = when {
            !fallbackMime.isNullOrBlank() && (primaryMime.isNullOrBlank() || primaryMime == "audio/mp4a-latm") -> fallbackMime
            !primaryMime.isNullOrBlank() -> primaryMime
            else -> fallbackMime
        }
        return AudioFormatInfo(
            bitrateKbps = if (primary.bitrateKbps > 0) primary.bitrateKbps else fallback.bitrateKbps,
            sampleRateHz = if (primary.sampleRateHz > 0) primary.sampleRateHz else fallback.sampleRateHz,
            bitDepthBits = if (primary.bitDepthBits > 0 && primary.sampleRateHz > 0) primary.bitDepthBits else fallback.bitDepthBits,
            mimeType = mergedMime,
            channels = if (primary.sampleRateHz > 0 && primary.channels > 0) primary.channels else fallback.channels,
            fileSizeBytes = if (fileSizeBytes > 0L) fileSizeBytes else maxOf(primary.fileSizeBytes, fallback.fileSizeBytes),
            qualityLevel = null
        )
    }

    private fun isCompleteInfo(info: AudioFormatInfo): Boolean {
        return !info.mimeType.isNullOrBlank() &&
            info.sampleRateHz > 0 &&
            (info.mimeType.contains("flac", ignoreCase = true) ||
                info.mimeType.contains("wav", ignoreCase = true) ||
                info.bitrateKbps > 0)
    }

    private fun pcmEncodingToBitDepth(encoding: Int): Int {
        return when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
            android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            android.media.AudioFormat.ENCODING_PCM_32BIT,
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }
    }

    private fun resolveFileSize(context: Context, uri: Uri): Long {
        return runCatching {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                File(uri.path!!).length()
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }
        }.getOrDefault(0L)
    }

    private fun openInputStream(context: Context, uri: Uri): InputStream? {
        return if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
            val f = File(uri.path!!)
            if (f.exists() && f.isFile) f.inputStream() else null
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }

    private fun computeBitrateKbps(fileSizeBytes: Long, durationMs: Long): Int {
        if (fileSizeBytes <= 0L || durationMs <= 0L) return 0
        return ((fileSizeBytes * 8L) / durationMs).toInt()
    }

    private fun containsXingOrVbriTag(buf: ByteArray, start: Int, endExclusive: Int): Boolean {
        for (i in start until (endExclusive - 4)) {
            if ((buf[i] == 'X'.code.toByte() && buf[i + 1] == 'i'.code.toByte() && buf[i + 2] == 'n'.code.toByte() && buf[i + 3] == 'g'.code.toByte()) ||
                (buf[i] == 'I'.code.toByte() && buf[i + 1] == 'n'.code.toByte() && buf[i + 2] == 'f'.code.toByte() && buf[i + 3] == 'o'.code.toByte()) ||
                (buf[i] == 'V'.code.toByte() && buf[i + 1] == 'B'.code.toByte() && buf[i + 2] == 'R'.code.toByte() && buf[i + 3] == 'I'.code.toByte())
            ) {
                return true
            }
        }
        return false
    }

    private fun lookupMpegSampleRate(versionBits: Int, index: Int): Int {
        val mpeg1 = intArrayOf(44100, 48000, 32000)
        val mpeg2 = intArrayOf(22050, 24000, 16000)
        val mpeg25 = intArrayOf(11025, 12000, 8000)
        if (index !in 0..2) return 0
        return when (versionBits) {
            3 -> mpeg1[index]
            2 -> mpeg2[index]
            0 -> mpeg25[index]
            else -> 0
        }
    }

    private fun lookupMpegBitrateKbps(versionBits: Int, layerBits: Int, index: Int): Int {
        if (index !in 1..14) return 0
        val v1l3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        val v1l2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
        val v1l1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
        val v2l23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        val v2l1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
        return if (versionBits == 3) {
            when (layerBits) {
                1 -> v1l3[index]
                2 -> v1l2[index]
                3 -> v1l1[index]
                else -> 0
            }
        } else {
            when (layerBits) {
                1, 2 -> v2l23[index]
                3 -> v2l1[index]
                else -> 0
            }
        }
    }

    private fun syncSafeInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun readUint16LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUint32LE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }

    private fun readFullyUpTo(stream: InputStream, dst: ByteArray, count: Int): Int {
        var total = 0
        while (total < count) {
            val n = stream.read(dst, total, count - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        val discard = ByteArray(4096)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val r = stream.read(discard, 0, minOf(remaining, discard.size.toLong()).toInt())
                if (r <= 0) break
                remaining -= r.toLong()
            }
        }
    }
}
