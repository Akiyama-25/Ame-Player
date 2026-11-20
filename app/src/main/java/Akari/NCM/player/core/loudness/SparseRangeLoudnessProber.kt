package Akari.NCM.player.core.loudness

import Akari.NCM.player.core.AppLogger
import android.media.MediaDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Tier 2 容器字节偏移定位与 HTTP Range 并发稀疏探测引擎
 *
 * 覆盖三种核心音频容器：
 * 1. FLAC：解析 STREAMINFO 与 SEEKTABLE 元数据块（含跳过大尺寸 PICTURE 块），无 SEEKTABLE 时按文件大小线性估算并前后加帧同步余量；
 * 2. MP4 / M4A（AAC / ALAC）：解析 moov 原子内 stts、stsc、stco/co64、stsz 索引表，将时间窗映射为精确字节区间；
 * 3. MP3（CBR / VBR）：跳过 ID3v2，解析 Xing / Info / VBRI TOC 索引表或平均比特率偏移，并提供有效帧头同步查找工具。
 */
object SparseRangeLoudnessProber {
    private const val TAG = "SparseRangeProber"
    private const val INITIAL_PROBE_BYTES = 65_536L
    private const val DEFAULT_CONCURRENCY = 4
    private const val ON_DEMAND_CHUNK_BYTES = 65_536L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    data class ByteRange(
        val start: Long,
        val endInclusive: Long
    ) {
        val length: Long get() = (endInclusive - start + 1L).coerceAtLeast(0L)
    }

    data class HttpRangeProbeInfo(
        val totalSize: Long,
        val initialBytes: ByteArray,
        val contentType: String?
    )

    // =========================================================================
    // 1. FLAC STREAMINFO + SEEKTABLE 字节映射器
    // =========================================================================

    data class FlacSeekPoint(
        val sampleNumber: Long,
        val streamOffset: Long,
        val numSamples: Int
    )

    data class FlacHeaderIndex(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val totalSamples: Long,
        val minFrameSize: Int,
        val maxFrameSize: Int,
        val audioDataStartOffset: Long,
        val totalFileSize: Long,
        val seekPoints: List<FlacSeekPoint>
    ) {
        val durationMs: Long
            get() = if (sampleRate > 0 && totalSamples > 0L) (totalSamples * 1000L) / sampleRate else 0L

        /**
         * 将时间窗 [startMs, endMs] 映射为精确 FLAC 字节区间（保留 1–2 个帧头同步余量）
         */
        fun mapWindowToByteRange(startMs: Long, endMs: Long): ByteRange {
            val maxByte = (totalFileSize - 1L).coerceAtLeast(audioDataStartOffset)
            val frameMarginBytes = (maxFrameSize.takeIf { it > 0 } ?: 16_384).toLong() * 2L
            val startSample = ((startMs.coerceAtLeast(0L) * sampleRate) / 1000L).coerceIn(0L, totalSamples)
            val endSample = ((endMs.coerceAtLeast(startMs) * sampleRate) / 1000L).coerceIn(startSample, totalSamples)

            if (seekPoints.isNotEmpty()) {
                val startPoint = seekPoints.lastOrNull { it.sampleNumber <= startSample }
                val nextAfterEnd = seekPoints.firstOrNull { it.sampleNumber >= endSample }
                val rawStart = if (startPoint != null) {
                    audioDataStartOffset + startPoint.streamOffset
                } else {
                    audioDataStartOffset
                }
                val rawEnd = if (nextAfterEnd != null) {
                    audioDataStartOffset + nextAfterEnd.streamOffset + frameMarginBytes
                } else if (durationMs > 0L && totalFileSize > audioDataStartOffset) {
                    val audioBytes = totalFileSize - audioDataStartOffset
                    audioDataStartOffset + ((audioBytes.toDouble() * endMs / durationMs).toLong()) + frameMarginBytes
                } else {
                    maxByte
                }
                val safeStart = (rawStart - frameMarginBytes / 2).coerceIn(audioDataStartOffset, maxByte)
                val safeEnd = rawEnd.coerceIn(safeStart, maxByte)
                return ByteRange(safeStart, safeEnd)
            }

            // 无 SEEKTABLE 时按文件总字节数与总时长线性估算，前后多取 1–2 帧做帧头同步
            val audioBytes = (totalFileSize - audioDataStartOffset).coerceAtLeast(0L)
            if (durationMs <= 0L || audioBytes <= 0L) {
                return ByteRange(audioDataStartOffset, maxByte)
            }
            val estStart = audioDataStartOffset + (audioBytes.toDouble() * startMs / durationMs).toLong() - frameMarginBytes
            val estEnd = audioDataStartOffset + (audioBytes.toDouble() * endMs / durationMs).toLong() + frameMarginBytes
            val safeStart = estStart.coerceIn(audioDataStartOffset, maxByte)
            val safeEnd = estEnd.coerceIn(safeStart, maxByte)
            return ByteRange(safeStart, safeEnd)
        }

        companion object {
            fun parse(headerBytes: ByteArray, totalFileSize: Long): FlacHeaderIndex? {
                if (headerBytes.size < 42) return null
                if (headerBytes[0] != 'f'.code.toByte() ||
                    headerBytes[1] != 'L'.code.toByte() ||
                    headerBytes[2] != 'a'.code.toByte() ||
                    headerBytes[3] != 'C'.code.toByte()
                ) {
                    return null
                }

                var pos = 4L
                var sampleRate = 0
                var channels = 0
                var bitsPerSample = 0
                var totalSamples = 0L
                var minFrameSize = 0
                var maxFrameSize = 0
                val seekPoints = mutableListOf<FlacSeekPoint>()
                var audioDataStart = 4L

                while (pos + 4L <= headerBytes.size) {
                    val p = pos.toInt()
                    val headerByte = headerBytes[p].toInt() and 0xFF
                    val isLast = (headerByte and 0x80) != 0
                    val blockType = headerByte and 0x7F
                    val blockLen = ((headerBytes[p + 1].toInt() and 0xFF) shl 16) or
                        ((headerBytes[p + 2].toInt() and 0xFF) shl 8) or
                        (headerBytes[p + 3].toInt() and 0xFF)
                    val bodyStart = pos + 4L
                    val bodyEnd = bodyStart + blockLen

                    if (blockType == 0 && blockLen >= 34 && bodyEnd <= headerBytes.size) {
                        val b = bodyStart.toInt()
                        minFrameSize = ((headerBytes[b + 4].toInt() and 0xFF) shl 16) or
                            ((headerBytes[b + 5].toInt() and 0xFF) shl 8) or
                            (headerBytes[b + 6].toInt() and 0xFF)
                        maxFrameSize = ((headerBytes[b + 7].toInt() and 0xFF) shl 16) or
                            ((headerBytes[b + 8].toInt() and 0xFF) shl 8) or
                            (headerBytes[b + 9].toInt() and 0xFF)
                        sampleRate = ((headerBytes[b + 10].toInt() and 0xFF) shl 12) or
                            ((headerBytes[b + 11].toInt() and 0xFF) shl 4) or
                            ((headerBytes[b + 12].toInt() and 0xFF) ushr 4)
                        channels = (((headerBytes[b + 12].toInt() and 0xFF) ushr 1) and 0x07) + 1
                        bitsPerSample = ((((headerBytes[b + 12].toInt() and 0x01) shl 4) or
                            ((headerBytes[b + 13].toInt() and 0xFF) ushr 4))) + 1
                        val high4 = (headerBytes[b + 13].toInt() and 0x0F).toLong() shl 32
                        val low32 = (((headerBytes[b + 14].toInt() and 0xFF).toLong() shl 24) or
                            ((headerBytes[b + 15].toInt() and 0xFF).toLong() shl 16) or
                            ((headerBytes[b + 16].toInt() and 0xFF).toLong() shl 8) or
                            (headerBytes[b + 17].toInt() and 0xFF).toLong())
                        totalSamples = high4 or low32
                    } else if (blockType == 3 && blockLen >= 18 && bodyEnd <= headerBytes.size) {
                        val count = blockLen / 18
                        var sPos = bodyStart.toInt()
                        for (i in 0 until count) {
                            val sampleNum = readLongBE(headerBytes, sPos)
                            val streamOff = readLongBE(headerBytes, sPos + 8)
                            val numSamples = ((headerBytes[sPos + 16].toInt() and 0xFF) shl 8) or
                                (headerBytes[sPos + 17].toInt() and 0xFF)
                            // 跳过 placeholder seekpoint (0xFFFFFFFFFFFFFFFF)
                            if (sampleNum != -1L && numSamples > 0) {
                                seekPoints.add(FlacSeekPoint(sampleNum, streamOff, numSamples))
                            }
                            sPos += 18
                        }
                    }

                    pos = bodyEnd
                    audioDataStart = pos
                    if (isLast) break
                    // 若超大 PICTURE 块跨越了初始缓冲，记录已推导的精确偏移并跳出循环
                    if (pos + 4L > headerBytes.size) {
                        break
                    }
                }

                if (sampleRate <= 0 || totalSamples <= 0L) return null
                return FlacHeaderIndex(
                    sampleRate = sampleRate,
                    channels = channels,
                    bitsPerSample = bitsPerSample,
                    totalSamples = totalSamples,
                    minFrameSize = minFrameSize,
                    maxFrameSize = maxFrameSize,
                    audioDataStartOffset = audioDataStart.coerceAtMost(totalFileSize),
                    totalFileSize = totalFileSize,
                    seekPoints = seekPoints.sortedBy { it.sampleNumber }
                )
            }
        }
    }

    // =========================================================================
    // 2. MP4 / M4A moov (stts / stsc / stco / co64 / stsz) 字节映射器
    // =========================================================================

    data class Mp4BoxIndex(
        val timescale: Int,
        val durationUnits: Long,
        val sampleOffsets: LongArray,
        val sampleSizes: IntArray,
        val sampleTimes: LongArray,
        val totalFileSize: Long
    ) {
        val durationMs: Long
            get() = if (timescale > 0) (durationUnits * 1000L) / timescale else 0L

        fun mapWindowToByteRange(startMs: Long, endMs: Long): ByteRange {
            val maxByte = (totalFileSize - 1L).coerceAtLeast(0L)
            if (sampleOffsets.isEmpty() || timescale <= 0) {
                return ByteRange(0L, maxByte)
            }
            val startTick = (startMs.coerceAtLeast(0L) * timescale) / 1000L
            val endTick = (endMs.coerceAtLeast(startMs) * timescale) / 1000L

            var startIdx = sampleTimes.binarySearch(startTick)
            if (startIdx < 0) startIdx = (-startIdx - 2).coerceIn(0, sampleOffsets.lastIndex)
            var endIdx = sampleTimes.binarySearch(endTick)
            if (endIdx < 0) endIdx = (-endIdx - 1).coerceIn(startIdx, sampleOffsets.lastIndex)

            val startByte = sampleOffsets[startIdx].coerceIn(0L, maxByte)
            val endByte = (sampleOffsets[endIdx] + sampleSizes[endIdx].toLong()).coerceIn(startByte, maxByte)
            return ByteRange(startByte, endByte)
        }

        companion object {
            fun parse(data: ByteArray, totalFileSize: Long): Mp4BoxIndex? {
                if (data.size < 16) return null
                // 检测 ftyp 或直接 moov 盒
                val moovBounds = findBox(data, 0, data.size, "moov") ?: return null
                var cursor = moovBounds.first
                val moovEnd = moovBounds.second

                while (cursor + 8 <= moovEnd) {
                    val box = readBoxHeader(data, cursor, moovEnd) ?: break
                    if (box.type == "trak") {
                        val parsed = parseAudioTrack(data, box.payloadStart, box.boxEnd, totalFileSize)
                        if (parsed != null) return parsed
                    }
                    cursor = box.boxEnd
                }
                return null
            }

            private fun parseAudioTrack(data: ByteArray, start: Int, end: Int, totalFileSize: Long): Mp4BoxIndex? {
                val mdia = findBox(data, start, end, "mdia") ?: return null
                val hdlr = findBox(data, mdia.first, mdia.second, "hdlr")
                if (hdlr != null && hdlr.first + 12 <= hdlr.second) {
                    val handlerType = String(data, hdlr.first + 8, 4, Charsets.US_ASCII)
                    if (handlerType != "soun") return null
                }

                val mdhd = findBox(data, mdia.first, mdia.second, "mdhd") ?: return null
                val (timescale, duration) = parseMdhd(data, mdhd.first, mdhd.second) ?: return null

                val minf = findBox(data, mdia.first, mdia.second, "minf") ?: return null
                val stbl = findBox(data, minf.first, minf.second, "stbl") ?: return null

                val stts = findBox(data, stbl.first, stbl.second, "stts") ?: return null
                val stsc = findBox(data, stbl.first, stbl.second, "stsc") ?: return null
                val stsz = findBox(data, stbl.first, stbl.second, "stsz") ?: return null
                val stco = findBox(data, stbl.first, stbl.second, "stco")
                val co64 = findBox(data, stbl.first, stbl.second, "co64")

                val sampleSizes = parseStsz(data, stsz.first, stsz.second) ?: return null
                if (sampleSizes.isEmpty()) return null
                val chunkOffsets = when {
                    stco != null -> parseStco(data, stco.first, stco.second)
                    co64 != null -> parseCo64(data, co64.first, co64.second)
                    else -> null
                } ?: return null
                val stscEntries = parseStsc(data, stsc.first, stsc.second) ?: return null
                val sampleTimes = parseSttsToTimes(data, stts.first, stts.second, sampleSizes.size) ?: return null

                val sampleOffsets = LongArray(sampleSizes.size)
                var sampleIdx = 0
                for (chunkIdx in chunkOffsets.indices) {
                    val chunkNum1Based = chunkIdx + 1
                    val samplesPerChunk = stscEntries.lastOrNull { it.first <= chunkNum1Based }?.second ?: 1
                    var offsetInChunk = chunkOffsets[chunkIdx]
                    for (s in 0 until samplesPerChunk) {
                        if (sampleIdx >= sampleOffsets.size) break
                        sampleOffsets[sampleIdx] = offsetInChunk
                        offsetInChunk += sampleSizes[sampleIdx]
                        sampleIdx++
                    }
                    if (sampleIdx >= sampleOffsets.size) break
                }

                return Mp4BoxIndex(
                    timescale = timescale,
                    durationUnits = duration,
                    sampleOffsets = sampleOffsets,
                    sampleSizes = sampleSizes,
                    sampleTimes = sampleTimes,
                    totalFileSize = totalFileSize
                )
            }

            private data class BoxHeader(val type: String, val payloadStart: Int, val boxEnd: Int)

            private fun readBoxHeader(data: ByteArray, offset: Int, limit: Int): BoxHeader? {
                if (offset + 8 > limit) return null
                val size32 = readIntBE(data, offset).toLong() and 0xFFFFFFFFL
                val type = String(data, offset + 4, 4, Charsets.US_ASCII)
                val (payloadStart, boxSize) = when (size32) {
                    0L -> (offset + 8) to (limit - offset).toLong()
                    1L -> {
                        if (offset + 16 > limit) return null
                        (offset + 16) to readLongBE(data, offset + 8)
                    }
                    else -> (offset + 8) to size32
                }
                if (boxSize < 8L) return null
                val boxEnd = min(limit.toLong(), offset + boxSize).toInt()
                if (payloadStart > boxEnd) return null
                return BoxHeader(type, payloadStart, boxEnd)
            }

            private fun findBox(data: ByteArray, start: Int, end: Int, targetType: String): Pair<Int, Int>? {
                var pos = start
                while (pos + 8 <= end) {
                    val box = readBoxHeader(data, pos, end) ?: break
                    if (box.type == targetType) return box.payloadStart to box.boxEnd
                    if (box.boxEnd <= pos) break
                    pos = box.boxEnd
                }
                return null
            }

            private fun parseMdhd(data: ByteArray, start: Int, end: Int): Pair<Int, Long>? {
                if (start + 4 > end) return null
                val version = data[start].toInt() and 0xFF
                return if (version == 1 && start + 32 <= end) {
                    val timescale = readIntBE(data, start + 20)
                    val duration = readLongBE(data, start + 24)
                    timescale to duration
                } else if (start + 20 <= end) {
                    val timescale = readIntBE(data, start + 12)
                    val duration = readIntBE(data, start + 16).toLong() and 0xFFFFFFFFL
                    timescale to duration
                } else null
            }

            private fun parseStsz(data: ByteArray, start: Int, end: Int): IntArray? {
                if (start + 12 > end) return null
                val uniformSize = readIntBE(data, start + 4)
                val count = readIntBE(data, start + 8)
                if (count <= 0 || count > 500_000) return null
                if (uniformSize > 0) return IntArray(count) { uniformSize }
                if (start + 12 + count * 4 > end) return null
                return IntArray(count) { i -> readIntBE(data, start + 12 + i * 4) }
            }

            private fun parseStco(data: ByteArray, start: Int, end: Int): LongArray? {
                if (start + 8 > end) return null
                val count = readIntBE(data, start + 4)
                if (count <= 0 || start + 8 + count * 4 > end) return null
                return LongArray(count) { i -> readIntBE(data, start + 8 + i * 4).toLong() and 0xFFFFFFFFL }
            }

            private fun parseCo64(data: ByteArray, start: Int, end: Int): LongArray? {
                if (start + 8 > end) return null
                val count = readIntBE(data, start + 4)
                if (count <= 0 || start + 8 + count * 8 > end) return null
                return LongArray(count) { i -> readLongBE(data, start + 8 + i * 8) }
            }

            private fun parseStsc(data: ByteArray, start: Int, end: Int): List<Pair<Int, Int>>? {
                if (start + 8 > end) return null
                val count = readIntBE(data, start + 4)
                if (count <= 0 || start + 8 + count * 12 > end) return null
                return List(count) { i ->
                    val base = start + 8 + i * 12
                    val firstChunk = readIntBE(data, base)
                    val samplesPerChunk = readIntBE(data, base + 4)
                    firstChunk to samplesPerChunk
                }
            }

            private fun parseSttsToTimes(data: ByteArray, start: Int, end: Int, totalSamples: Int): LongArray? {
                if (start + 8 > end) return null
                val entryCount = readIntBE(data, start + 4)
                if (entryCount <= 0 || start + 8 + entryCount * 8 > end) return null
                val times = LongArray(totalSamples)
                var sampleIdx = 0
                var currentTime = 0L
                for (i in 0 until entryCount) {
                    val base = start + 8 + i * 8
                    val count = readIntBE(data, base)
                    val delta = readIntBE(data, base + 4).toLong() and 0xFFFFFFFFL
                    for (c in 0 until count) {
                        if (sampleIdx >= totalSamples) break
                        times[sampleIdx++] = currentTime
                        currentTime += delta
                    }
                }
                return times
            }
        }
    }

    // =========================================================================
    // 3. MP3 (CBR / VBR Xing / VBRI TOC) 字节映射器与帧头同步
    // =========================================================================

    data class Mp3HeaderIndex(
        val audioDataStartOffset: Long,
        val totalFileSize: Long,
        val sampleRate: Int,
        val averageBitrateBps: Int,
        val durationMs: Long,
        val xingToc: ByteArray? = null
    ) {
        fun mapWindowToByteRange(startMs: Long, endMs: Long): ByteRange {
            val maxByte = (totalFileSize - 1L).coerceAtLeast(audioDataStartOffset)
            val audioBytes = (totalFileSize - audioDataStartOffset).coerceAtLeast(0L)
            val frameMargin = 4096L // 额外前移/后移帧同步余量，便于解码前丢弃首个不完整帧

            fun msToByte(ms: Long): Long {
                if (durationMs <= 0L || audioBytes <= 0L) return audioDataStartOffset
                val percent = (ms.toDouble() / durationMs.toDouble() * 100.0).coerceIn(0.0, 100.0)
                val toc = xingToc
                return if (toc != null && toc.size == 100) {
                    val idx = percent.toInt().coerceIn(0, 99)
                    val fa = (toc[idx].toInt() and 0xFF).toDouble()
                    val fb = if (idx < 99) (toc[idx + 1].toInt() and 0xFF).toDouble() else 256.0
                    val fx = fa + (fb - fa) * (percent - idx)
                    audioDataStartOffset + ((fx / 256.0) * audioBytes).toLong()
                } else {
                    audioDataStartOffset + ((averageBitrateBps.toLong() * ms) / 8000L)
                }
            }

            val startByte = (msToByte(startMs) - frameMargin).coerceIn(audioDataStartOffset, maxByte)
            val endByte = (msToByte(endMs) + frameMargin).coerceIn(startByte, maxByte)
            return ByteRange(startByte, endByte)
        }

        companion object {
            private val MPEG1_L3_BITRATES = intArrayOf(
                0, 32000, 40000, 48000, 56000, 64000, 80000, 96000,
                112000, 128000, 160000, 192000, 224000, 256000, 320000, 0
            )
            private val MPEG1_SAMPLE_RATES = intArrayOf(44100, 48000, 32000, 0)
            private val MPEG2_SAMPLE_RATES = intArrayOf(22050, 24000, 16000, 0)

            /**
             * 查找下一个有效 MP3 帧头同步偏移（跳过首个不完整帧）
             */
            fun findNextMp3FrameSync(bytes: ByteArray, startOffset: Int = 0): Int {
                var i = startOffset.coerceAtLeast(0)
                while (i + 4 <= bytes.size) {
                    val b0 = bytes[i].toInt() and 0xFF
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    val b2 = bytes[i + 2].toInt() and 0xFF
                    if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                        val versionBits = (b1 ushr 3) and 0x03
                        val layerBits = (b1 ushr 1) and 0x03
                        val bitrateIdx = (b2 ushr 4) and 0x0F
                        val srIdx = (b2 ushr 2) and 0x03
                        if (versionBits != 1 && layerBits != 0 && bitrateIdx in 1..14 && srIdx != 3) {
                            return i
                        }
                    }
                    i++
                }
                return -1
            }

            fun parse(headerBytes: ByteArray, totalFileSize: Long): Mp3HeaderIndex? {
                if (headerBytes.size < 16) return null
                var offset = 0
                // 跳过 ID3v2 标签
                if (headerBytes[0] == 'I'.code.toByte() &&
                    headerBytes[1] == 'D'.code.toByte() &&
                    headerBytes[2] == '3'.code.toByte()
                ) {
                    val tagSize = ((headerBytes[6].toInt() and 0x7F) shl 21) or
                        ((headerBytes[7].toInt() and 0x7F) shl 14) or
                        ((headerBytes[8].toInt() and 0x7F) shl 7) or
                        (headerBytes[9].toInt() and 0x7F)
                    offset = 10 + tagSize
                }
                if (offset + 4 > headerBytes.size) return null

                val syncPos = findNextMp3FrameSync(headerBytes, offset)
                if (syncPos < 0 || syncPos + 4 > headerBytes.size) return null

                val b1 = headerBytes[syncPos + 1].toInt() and 0xFF
                val b2 = headerBytes[syncPos + 2].toInt() and 0xFF
                val b3 = headerBytes[syncPos + 3].toInt() and 0xFF
                val isMpeg1 = ((b1 ushr 3) and 0x03) == 3
                val bitrateIdx = (b2 ushr 4) and 0x0F
                val srIdx = (b2 ushr 2) and 0x03
                val isMono = ((b3 ushr 6) and 0x03) == 3

                val sampleRate = if (isMpeg1) MPEG1_SAMPLE_RATES[srIdx] else MPEG2_SAMPLE_RATES[srIdx]
                val headerBitrate = MPEG1_L3_BITRATES[bitrateIdx]
                if (sampleRate <= 0 || headerBitrate <= 0) return null

                val sideInfoSize = when {
                    isMpeg1 && isMono -> 17
                    isMpeg1 && !isMono -> 32
                    !isMpeg1 && isMono -> 9
                    else -> 17
                }
                val xingPos = syncPos + 4 + sideInfoSize
                var xingToc: ByteArray? = null
                var durationMs = 0L

                if (xingPos + 12 <= headerBytes.size) {
                    val tag = String(headerBytes, xingPos, 4, Charsets.US_ASCII)
                    if (tag == "Xing" || tag == "Info") {
                        val flags = readIntBE(headerBytes, xingPos + 4)
                        var cursor = xingPos + 8
                        var totalFrames = 0L
                        if ((flags and 0x01) != 0 && cursor + 4 <= headerBytes.size) {
                            totalFrames = readIntBE(headerBytes, cursor).toLong() and 0xFFFFFFFFL
                            cursor += 4
                        }
                        if ((flags and 0x02) != 0 && cursor + 4 <= headerBytes.size) {
                            cursor += 4 // total bytes
                        }
                        if ((flags and 0x04) != 0 && cursor + 100 <= headerBytes.size) {
                            xingToc = headerBytes.copyOfRange(cursor, cursor + 100)
                        }
                        if (totalFrames > 0L) {
                            val samplesPerFrame = if (isMpeg1) 1152L else 576L
                            durationMs = (totalFrames * samplesPerFrame * 1000L) / sampleRate
                        }
                    }
                }

                val audioDataStart = syncPos.toLong()
                val audioBytes = (totalFileSize - audioDataStart).coerceAtLeast(0L)
                if (durationMs <= 0L && audioBytes > 0L) {
                    durationMs = (audioBytes * 8000L) / headerBitrate
                }
                val avgBitrate = if (durationMs > 0L && audioBytes > 0L) {
                    ((audioBytes * 8000L) / durationMs).toInt().coerceAtLeast(32_000)
                } else {
                    headerBitrate
                }

                return Mp3HeaderIndex(
                    audioDataStartOffset = audioDataStart,
                    totalFileSize = totalFileSize,
                    sampleRate = sampleRate,
                    averageBitrateBps = avgBitrate,
                    durationMs = durationMs,
                    xingToc = xingToc
                )
            }
        }
    }

    // =========================================================================
    // 4. HTTP Range 探测与 3–4 并发稀疏缓存 MediaDataSource
    // =========================================================================

    suspend fun probeHttpRangeSupport(
        url: String,
        headers: Map<String, String>? = null
    ): HttpRangeProbeInfo? = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${INITIAL_PROBE_BYTES - 1}")
                .header("User-Agent", headers?.get("User-Agent") ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Referer", headers?.get("Referer") ?: "https://music.163.com/")
            headers?.forEach { (k, v) ->
                if (!k.equals("Range", ignoreCase = true)) {
                    reqBuilder.header(k, v)
                }
            }
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code != 206) {
                    AppLogger.d(TAG, "HTTP Range unsupported (status=${resp.code}) for $url -> Tier 3 fallback")
                    return@withContext null
                }
                val contentRange = resp.header("Content-Range") ?: return@withContext null
                val totalSize = parseTotalSizeFromContentRange(contentRange) ?: return@withContext null
                val bytes = resp.body?.bytes() ?: return@withContext null
                if (bytes.isEmpty() || totalSize <= 0L) return@withContext null
                HttpRangeProbeInfo(
                    totalSize = totalSize,
                    initialBytes = bytes,
                    contentType = resp.header("Content-Type")
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLogger.d(TAG, "HTTP Range probe failed for $url: ${e.message}")
            null
        }
    }

    internal fun parseTotalSizeFromContentRange(contentRange: String): Long? {
        val slash = contentRange.lastIndexOf('/')
        if (slash < 0 || slash + 1 >= contentRange.length) return null
        val totalPart = contentRange.substring(slash + 1).trim()
        if (totalPart == "*") return null
        return totalPart.toLongOrNull()?.takeIf { it > 0L }
    }

    /**
     * 基于 HTTP Range 的稀疏字节缓存 MediaDataSource：
     * - 支持通过 [prefetchRangesConcurrently] 以 3–4 并发提前拉取 11×3s 字节区间；
     * - MediaExtractor 解码与 Seek 优先命中内存稀疏块缓存，避免整曲下载。
     */
    class SparseHttpRangeMediaDataSource(
        private val url: String,
        private val headers: Map<String, String>?,
        private val totalSize: Long,
        initialHeaderBytes: ByteArray
    ) : MediaDataSource() {

        private val cachedChunks = TreeMap<Long, ByteArray>()
        @Volatile
        private var closed = false

        init {
            if (initialHeaderBytes.isNotEmpty()) {
                synchronized(cachedChunks) {
                    cachedChunks[0L] = initialHeaderBytes
                }
            }
        }

        /**
         * 使用受控并发度（默认 4）批量拉取稀疏字节区间到内存缓存
         */
        suspend fun prefetchRangesConcurrently(
            ranges: List<ByteRange>,
            maxConcurrency: Int = DEFAULT_CONCURRENCY
        ) = coroutineScope {
            if (ranges.isEmpty() || closed) return@coroutineScope
            val semaphore = Semaphore(maxConcurrency.coerceIn(1, 4))
            val jobs = ranges.map { range ->
                async(Dispatchers.IO) {
                    if (closed) return@async
                    val safeStart = range.start.coerceIn(0L, totalSize - 1L)
                    val safeEnd = range.endInclusive.coerceIn(safeStart, totalSize - 1L)
                    if (hasFullCoverage(safeStart, safeEnd)) return@async
                    semaphore.withPermit {
                        if (!closed && !hasFullCoverage(safeStart, safeEnd)) {
                            fetchAndStoreRange(safeStart, safeEnd)
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        override fun getSize(): Long = totalSize

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (closed) throw IOException("DataSource closed")
            if (size <= 0) return 0
            if (position >= totalSize) return -1

            val toRead = min(size.toLong(), totalSize - position).toInt()
            var copied = 0
            var curPos = position

            while (copied < toRead && !closed) {
                val chunkHit = readFromCacheLocked(curPos, buffer, offset + copied, toRead - copied)
                if (chunkHit > 0) {
                    copied += chunkHit
                    curPos += chunkHit
                } else {
                    val fetchEnd = min(totalSize - 1L, curPos + max(ON_DEMAND_CHUNK_BYTES, (toRead - copied).toLong()) - 1L)
                    val fetched = fetchAndStoreRange(curPos, fetchEnd)
                    if (!fetched) {
                        return if (copied > 0) copied else -1
                    }
                }
            }
            return if (copied > 0) copied else -1
        }

        override fun close() {
            closed = true
            synchronized(cachedChunks) {
                cachedChunks.clear()
            }
        }

        private fun hasFullCoverage(start: Long, endInclusive: Long): Boolean {
            synchronized(cachedChunks) {
                val floor = cachedChunks.floorEntry(start) ?: return false
                return floor.key + floor.value.size - 1L >= endInclusive
            }
        }

        private fun readFromCacheLocked(pos: Long, dst: ByteArray, dstOffset: Int, maxLen: Int): Int {
            synchronized(cachedChunks) {
                val entry = cachedChunks.floorEntry(pos) ?: return 0
                val chunkStart = entry.key
                val data = entry.value
                val chunkEndExclusive = chunkStart + data.size
                if (pos >= chunkEndExclusive) return 0
                val srcOffset = (pos - chunkStart).toInt()
                val available = (chunkEndExclusive - pos).toInt()
                val n = min(maxLen, available)
                System.arraycopy(data, srcOffset, dst, dstOffset, n)
                return n
            }
        }

        private fun fetchAndStoreRange(start: Long, endInclusive: Long): Boolean {
            if (closed || start > endInclusive) return false
            return try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$start-$endInclusive")
                    .header("User-Agent", headers?.get("User-Agent") ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", headers?.get("Referer") ?: "https://music.163.com/")
                headers?.forEach { (k, v) ->
                    if (!k.equals("Range", ignoreCase = true)) {
                        reqBuilder.header(k, v)
                    }
                }
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code != 206 && resp.code != 200) return false
                    val bodyBytes = resp.body?.bytes() ?: return false
                    if (bodyBytes.isEmpty()) return false
                    synchronized(cachedChunks) {
                        cachedChunks[start] = bodyBytes
                    }
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun readIntBE(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun readLongBE(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (bytes[offset + i].toInt() and 0xFF).toLong()
        }
        return v
    }
}
