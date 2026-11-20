package Akari.NCM.player.core.loudness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class EbuR128CalculatorTest {

    @Test
    fun `测试ITU-R BS1770滤波器系数计算`() {
        val fs = 48000.0
        val s1 = EbuR128Calculator.calculateStage1Coeffs(fs)
        // 验证预滤波 Stage 1 (High-shelf +4dB)
        assertTrue(s1.b0 > 1.5 && s1.b0 < 1.55)
        assertTrue(s1.b1 < -2.65 && s1.b1 > -2.7)
        assertTrue(s1.b2 > 1.15 && s1.b2 < 1.25)
        assertTrue(s1.a1 < -1.65 && s1.a1 > -1.7)
        assertTrue(s1.a2 > 0.7 && s1.a2 < 0.75)

        val s2 = EbuR128Calculator.calculateStage2Coeffs(fs)
        // 验证 RLB 高通 Stage 2
        assertTrue(s2.b0 > 0.99 && s2.b0 <= 1.0)
        assertTrue(s2.b1 < -1.98 && s2.b1 >= -2.0)
        assertTrue(s2.a1 < -1.98 && s2.a1 > -2.0)
        assertTrue(s2.a2 > 0.98 && s2.a2 < 1.0)
    }

    @Test
    fun `测试标准流媒体目标响度下高电平流行音乐的衰减计算`() {
        // 现代流行音乐经过响度战争母带，综合响度常在 -9.0 LUFS，真峰值 0.988 (-0.1 dBFS)
        val loudPop = LoudnessData(
            integratedLufs = -9.0f,
            truePeak = 0.988f,
            source = LoudnessSource.NETEASE_ONLINE
        )
        val result = EbuR128Calculator.computeGain(
            loudness = loudPop,
            targetLufs = EbuR128Calculator.TARGET_STREAMING_LUFS, // -14.0 LUFS
            peakLimitEnabled = true
        )

        // 目标为 -14.0 LUFS，计算出的增益应为 -14 - (-9) = -5.0 dB
        assertEquals(-5.0f, result.rawGainDb, 0.05f)
        assertEquals(-5.0f, result.effectiveGainDb, 0.05f)
        // 衰减不会触发真峰值超标
        assertFalse(result.isPeakLimited)
        // 线性乘数应在 0.56 左右 (10^(-5/20) ≈ 0.5623)
        assertTrue(result.linearMultiplier > 0.55f && result.linearMultiplier < 0.57f)
    }

    @Test
    fun `测试EBU R128广电标准目标下的大动态衰减`() {
        // EBU R128 基准目标为 -23.0 LUFS
        val track = LoudnessData(
            integratedLufs = -15.0f,
            truePeak = 0.90f,
            source = LoudnessSource.REPLAYGAIN_TAG
        )
        val result = EbuR128Calculator.computeGain(
            loudness = track,
            targetLufs = EbuR128Calculator.TARGET_EBU_R128_LUFS, // -23.0 LUFS
            peakLimitEnabled = true
        )

        assertEquals(-8.0f, result.rawGainDb, 0.05f)
        assertEquals(-8.0f, result.effectiveGainDb, 0.05f)
        assertFalse(result.isPeakLimited)
        assertTrue(result.linearMultiplier > 0.38f && result.linearMultiplier < 0.41f)
    }

    @Test
    fun `测试安静曲目正增益提升触发防破音真峰值压制保护`() {
        // 安静的古典/录音室曲目：综合响度 -20.0 LUFS，但某处瞬态已有极大真峰值 0.95 (-0.45 dBFS)
        val quietTrackWithSpike = LoudnessData(
            integratedLufs = -20.0f,
            truePeak = 0.95f, // peakDb ≈ -0.45 dBFS
            source = LoudnessSource.BS1770_SCAN
        )
        val result = EbuR128Calculator.computeGain(
            loudness = quietTrackWithSpike,
            targetLufs = EbuR128Calculator.TARGET_STREAMING_LUFS, // -14.0 LUFS
            peakLimitEnabled = true
        )

        // 原始增益理论应为 -14 - (-20) = +6.0 dB
        assertEquals(6.0f, result.rawGainDb, 0.05f)
        // 若直接加 +6.0 dB，峰值将达到 -0.45 + 6.0 = +5.55 dBFS，造成剧烈数字削波破音！
        // 启用真峰值限制 (-1.0 dBTP) 后，允许的最大增益为 -1.0 - (-0.45) = -0.55 dB
        assertTrue(result.isPeakLimited)
        assertTrue(result.effectiveGainDb < 0.0f)
        assertTrue(result.effectiveGainDb <= -0.5f)
    }

    @Test
    fun `测试未达到真峰值上限的正增益安全提升`() {
        // 安静且动态很小的纯声乐：综合响度 -18.0 LUFS，真峰值仅 0.30 (-10.45 dBFS)
        val softVocal = LoudnessData(
            integratedLufs = -18.0f,
            truePeak = 0.30f,
            source = LoudnessSource.NETEASE_ONLINE
        )
        val result = EbuR128Calculator.computeGain(
            loudness = softVocal,
            targetLufs = EbuR128Calculator.TARGET_STREAMING_LUFS, // -14.0 LUFS
            peakLimitEnabled = true
        )

        // 原始增益为 +4.0 dB
        assertEquals(4.0f, result.rawGainDb, 0.05f)
        // +4.0 dB 施加后峰值仅 -10.45 + 4.0 = -6.45 dBFS，远低于 -1.0 dBTP，不触发压制
        assertEquals(4.0f, result.effectiveGainDb, 0.05f)
        assertFalse(result.isPeakLimited)
        assertTrue(result.linearMultiplier > 1.5f)
    }

    @Test
    fun `测试双声道PCM正弦波数据的综合响度与峰值计算`() {
        val sampleRate = 48000
        val durationSec = 1.0 // 1 秒
        val numSamples = (sampleRate * durationSec).toInt()

        val left = FloatArray(numSamples)
        val right = FloatArray(numSamples)

        // 生成 1kHz 正弦波，峰值幅值 0.5 (-6.02 dBFS)
        val freq = 1000.0
        for (i in 0 until numSamples) {
            val v = (0.5 * sin(2.0 * PI * freq * i / sampleRate)).toFloat()
            left[i] = v
            right[i] = v
        }

        val loudness = EbuR128Calculator.calculateIntegratedLoudness(left, right, sampleRate)

        // 峰值应精确接近 0.5
        assertEquals(0.5f, loudness.truePeak, 0.01f)
        assertEquals(-6.02f, loudness.peakDb, 0.1f)
        // 1kHz 立体声正弦波（峰值 0.5）经过 K-weighting 与双重门控能量积分，综合响度约为 -6.06 LUFS
        assertEquals(-6.06f, loudness.integratedLufs, 0.2f)
        assertEquals(LoudnessSource.BS1770_SCAN, loudness.source)
    }

    @Test
    fun `测试静音数据的综合响度返回最低门限`() {
        val sampleRate = 48000
        val silence = FloatArray(sampleRate) // 1 秒静音
        val loudness = EbuR128Calculator.calculateIntegratedLoudness(silence, silence, sampleRate)

        assertEquals(-70.0f, loudness.integratedLufs, 0.1f)
        assertEquals(0.0f, loudness.truePeak, 0.001f)
    }

    @Test
    fun `测试音量均衡实时状态文本格式化`() {
        val disabledState = LoudnessNormalizationState(isEnabled = false)
        assertEquals("未启用", disabledState.statusSummary)

        val activeState = LoudnessNormalizationState(
            isEnabled = true,
            sourceLoudnessLufs = -11.2f,
            appliedGainDb = -2.8f,
            isPeakLimited = false
        )
        assertTrue(activeState.statusSummary.contains("-11.2 LUFS"))
        assertTrue(activeState.statusSummary.contains("-2.8 dB"))

        val peakLimitedState = LoudnessNormalizationState(
            isEnabled = true,
            sourceLoudnessLufs = -19.0f,
            appliedGainDb = -0.5f,
            isPeakLimited = true
        )
        assertTrue(peakLimitedState.statusSummary.contains("防过载压制"))
    }

    @Test
    fun `测试双阶段响度来源标识与曲间等候状态文本`() {
        assertFalse(LoudnessSource.DEFAULT_FALLBACK.isKnownValid)
        assertTrue(LoudnessSource.FAST_PREVIEW_SCAN.isKnownValid)
        assertFalse(LoudnessSource.FAST_PREVIEW_SCAN.isFinalLocked)
        assertFalse(LoudnessSource.QUEUE_AVERAGE.isFinalLocked)
        assertTrue(LoudnessSource.NETEASE_ONLINE.isFinalLocked)
        assertTrue(LoudnessSource.BS1770_SCAN.isFinalLocked)

        val waitingState = LoudnessNormalizationState(
            isEnabled = true,
            sourceLoudnessLufs = -10.5f,
            isInterTrackWaiting = true
        )
        assertTrue(waitingState.statusSummary.contains("曲间等候 (1s)"))
        assertTrue(waitingState.statusSummary.contains("-10.5 LUFS"))

        val previewState = LoudnessNormalizationState(
            isEnabled = true,
            sourceLoudnessLufs = -11.0f,
            appliedGainDb = -3.0f,
            source = LoudnessSource.FAST_PREVIEW_SCAN
        )
        assertTrue(previewState.statusSummary.contains("初判"))
    }

    @Test
    fun `未锁定初判源的正向增益限制在2dB以内而衰减与锁定全曲不受此限`() {
        // 极低响度前奏 -21.3 LUFS -> 理论正增益 +7.3 dB
        for (source in listOf(
            LoudnessSource.INTRO_PREVIEW_SCAN,
            LoudnessSource.FAST_PREVIEW_SCAN,
            LoudnessSource.QUEUE_AVERAGE
        )) {
            val preview = LoudnessData(
                integratedLufs = -21.3f,
                truePeak = 0.25f,
                source = source
            )
            val result = EbuR128Calculator.computeGain(
                loudness = preview,
                targetLufs = EbuR128Calculator.TARGET_STREAMING_LUFS,
                peakLimitEnabled = true
            )
            assertEquals(7.3f, result.rawGainDb, 0.05f)
            assertEquals(EbuR128Calculator.MAX_UNVERIFIED_POSITIVE_GAIN_DB, result.effectiveGainDb, 0.01f)
        }

        // 未锁定初判若需负向衰减（如 -7.0 LUFS -> -7.0 dB），应全额执行不被 +2dB 截断
        val loudPreview = LoudnessData(
            integratedLufs = -7.0f,
            truePeak = 0.95f,
            source = LoudnessSource.FAST_PREVIEW_SCAN
        )
        val attenResult = EbuR128Calculator.computeGain(
            loudness = loudPreview,
            targetLufs = EbuR128Calculator.TARGET_STREAMING_LUFS,
            peakLimitEnabled = true
        )
        assertEquals(-7.0f, attenResult.effectiveGainDb, 0.05f)

        // 已锁定的全曲测量可使用完整正增益范围
        val lockedQuiet = LoudnessData(
            integratedLufs = -21.3f,
            truePeak = 0.25f,
            source = LoudnessSource.BS1770_SCAN
        )
        val lockedResult = EbuR128Calculator.computeGain(
            loudness = lockedQuiet,
            targetLufs = EbuR128Calculator.TARGET_STREAMING_LUFS,
            peakLimitEnabled = true
        )
        assertEquals(7.3f, lockedResult.effectiveGainDb, 0.05f)
    }

    @Test
    fun `测试仅降不升模式与响亮档位预设`() {
        val loudTrack = LoudnessData(-8.3f, 1.07f, LoudnessSource.SPARSE_RANGE_SCAN)
        val quietTrack = LoudnessData(-20.0f, 0.30f, LoudnessSource.BS1770_SCAN)

        // 响亮档位 -11.0 LUFS
        val loudPresetResult = EbuR128Calculator.computeGain(
            loudness = loudTrack,
            targetLufs = EbuR128Calculator.TARGET_LOUD_LUFS,
            peakLimitEnabled = true,
            negativeGainOnly = false
        )
        assertEquals(-2.7f, loudPresetResult.effectiveGainDb, 0.05f)

        // 仅降不升模式下：过响曲目正常衰减，低响度曲目强制钳位至 0.0 dB
        val negOnlyLoud = EbuR128Calculator.computeGain(
            loudness = loudTrack,
            targetLufs = EbuR128Calculator.TARGET_STREAMING_LUFS,
            peakLimitEnabled = true,
            negativeGainOnly = true
        )
        assertEquals(-5.7f, negOnlyLoud.effectiveGainDb, 0.05f)

        val negOnlyQuiet = EbuR128Calculator.computeGain(
            loudness = quietTrack,
            targetLufs = EbuR128Calculator.TARGET_STREAMING_LUFS,
            peakLimitEnabled = true,
            negativeGainOnly = true
        )
        assertEquals(0.0f, negOnlyQuiet.effectiveGainDb, 0.001f)
        assertEquals(1.0f, negOnlyQuiet.linearMultiplier, 0.001f)
    }

    @Test
    fun `测试4x过采样真峰值检测器捕获采样点间过冲`() {
        val sampleRate = 48000
        val n = 4096
        val left = FloatArray(n)
        val right = FloatArray(n)
        // Nyquist/4 交替模式 [+0.9, +0.9, -0.9, -0.9, ...]：采样点峰值 0.90，但连续波形采样点间真实峰值约为 0.90 * sqrt(2) ≈ 1.27 (> 1.0)
        for (i in 0 until n) {
            val s = if ((i / 2) % 2 == 0) 0.9f else -0.9f
            left[i] = s
            right[i] = s
        }
        val truePeak = EbuR128Calculator.measureTruePeak4x(left, right)
        assertTrue("4x 过采样应检测到采样点间真实峰值 > 1.05，实际: $truePeak", truePeak > 1.05f)
    }

    @Test
    fun `测试11x3s稀疏抽样窗口规划与短曲目全曲回退`() {
        // 参考音频 rN Frums - ハテ.flac 时长 173.376s (> 33s) -> 规划 11 段 × 3s，第 11 段钳位至 [170376, 173376]
        val windows = EbuR128Calculator.planSparseSampleWindows(173_376L)
        assertEquals(11, windows.size)
        assertEquals(0L, windows[0].startMs)
        assertEquals(3000L, windows[0].durationMs)
        assertEquals(170_376L, windows[10].startMs)
        assertEquals(3000L, windows[10].durationMs)
        for (i in 1 until windows.size) {
            assertTrue("窗口应严格递增", windows[i].startMs > windows[i - 1].startMs)
        }

        // 短曲目 <= 33s -> 单窗全曲扫描
        val shortWindows = EbuR128Calculator.planSparseSampleWindows(28_500L)
        assertEquals(1, shortWindows.size)
        assertEquals(0L, shortWindows[0].startMs)
        assertEquals(28_500L, shortWindows[0].durationMs)
    }

    @Test
    fun `测试参考音频rN_Frums_ハテ的FLAC容器SEEKTABLE解析与11段稀疏字节映射`() {
        val candidates = listOf(
            java.io.File("public/assets/aistudio/rN_Frums_-____4de5d3de.flac"),
            java.io.File("../public/assets/aistudio/rN_Frums_-____4de5d3de.flac")
        )
        val refFile = candidates.firstOrNull { it.exists() }
        assertTrue("参考音频文件应存在于工作区", refFile != null)

        val totalSize = refFile!!.length()
        val flacIndex = SparseRangeLoudnessProber.FlacHeaderIndex.parse(
            SparseRangeLoudnessProber.fileReader(refFile),
            totalSize
        )
        assertTrue("应成功解析参考 FLAC 的 STREAMINFO 与 SEEKTABLE", flacIndex != null)
        assertEquals(48000, flacIndex!!.sampleRate)
        assertEquals(2, flacIndex.channels)
        assertEquals(16, flacIndex.bitsPerSample)
        assertTrue("总时长应约为 173.4s，实际: ${flacIndex.durationMs}", flacIndex.durationMs in 173_000L..174_000L)
        assertEquals("应跳过 204KB PICTURE 块并精确解析至音频帧起始偏移 211079", 211_079L, flacIndex.audioDataStartOffset)
        assertEquals("应完整解析 87 个 SEEKTABLE 定位点", 87, flacIndex.seekPoints.size)

        val windows = EbuR128Calculator.planSparseSampleWindows(flacIndex.durationMs)
        var totalPlannedBytes = 0L
        var prevStart = -1L
        for (w in windows) {
            val byteRange = flacIndex.mapWindowToByteRange(w.startMs, w.endMs)
            assertTrue("字节区段起点必须 >= 精确音频帧起始偏移 211079", byteRange.start >= 211_079L)
            assertTrue("字节区段长度必须 > 0", byteRange.endInclusive > byteRange.start)
            assertTrue("各稀疏片段起始字节偏移应单调递增", byteRange.start >= prevStart)
            prevStart = byteRange.start
            totalPlannedBytes += byteRange.length
        }
        val trafficRatio = totalPlannedBytes.toDouble() / totalSize.toDouble()
        assertTrue("11×3s 稀疏拉取流量占比应显著低于完整文件 (期望 < 35%，实际 ${(trafficRatio * 100).toInt()}%)", trafficRatio < 0.35)
    }

    @Test
    fun `测试MP4尾部moov定位与stts_stsc_stco_stsz字节区间映射`() {
        fun putIntBE(out: java.io.ByteArrayOutputStream, v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun box(type: String, payload: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            putIntBE(out, 8 + payload.size)
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(payload)
            return out.toByteArray()
        }

        // 构造含 10 个 1 秒样本的最小音轨 moov（总时长 10s，位于 120KB mdat 尾部之后）
        val mdatStart = 24L
        val mdatSize = 120_000
        val hdlrPayload = ByteArray(16).also {
            "soun".toByteArray(Charsets.US_ASCII).copyInto(it, 8)
        }
        val mdhdPayload = java.io.ByteArrayOutputStream().apply {
            write(ByteArray(12)) // version=0, flags, creation, mod
            putIntBE(this, 1000) // timescale = 1000
            putIntBE(this, 10_000) // duration = 10s
        }.toByteArray()
        val sttsPayload = java.io.ByteArrayOutputStream().apply {
            putIntBE(this, 0) // version/flags
            putIntBE(this, 1) // entryCount
            putIntBE(this, 10) // sampleCount
            putIntBE(this, 1000) // sampleDelta (1s)
        }.toByteArray()
        val stscPayload = java.io.ByteArrayOutputStream().apply {
            putIntBE(this, 0)
            putIntBE(this, 1) // 1 entry
            putIntBE(this, 1) // firstChunk
            putIntBE(this, 1) // samplesPerChunk
            putIntBE(this, 1) // sampleDescIdx
        }.toByteArray()
        val stszPayload = java.io.ByteArrayOutputStream().apply {
            putIntBE(this, 0)
            putIntBE(this, 10_000) // uniform sample size = 10KB
            putIntBE(this, 10) // 10 samples
        }.toByteArray()
        val stcoPayload = java.io.ByteArrayOutputStream().apply {
            putIntBE(this, 0)
            putIntBE(this, 10)
            for (i in 0 until 10) {
                putIntBE(this, (mdatStart + 8 + i * 10_000).toInt())
            }
        }.toByteArray()

        val stbl = box("stbl", box("stts", sttsPayload) + box("stsc", stscPayload) + box("stsz", stszPayload) + box("stco", stcoPayload))
        val minf = box("minf", stbl)
        val mdia = box("mdia", box("hdlr", hdlrPayload) + box("mdhd", mdhdPayload) + minf)
        val trak = box("trak", mdia)
        val moov = box("moov", trak)

        val ftyp = box("ftyp", ByteArray(16))
        val mdat = box("mdat", ByteArray(mdatSize - 8))
        val fullMp4 = ftyp + mdat + moov

        val index = SparseRangeLoudnessProber.Mp4BoxIndex.parse(
            SparseRangeLoudnessProber.byteArrayReader(fullMp4),
            fullMp4.size.toLong()
        )
        assertTrue("应跨越 120KB mdat 成功解析尾部 moov", index != null)
        assertEquals(10_000L, index!!.durationMs)
        val rangeMid = index.mapWindowToByteRange(4_000L, 7_000L)
        assertEquals(mdatStart + 8 + 4 * 10_000, rangeMid.start)
        assertTrue("映射区间应精确覆盖目标样本", rangeMid.endInclusive > rangeMid.start)
    }

    @Test
    fun `测试MP3大尺寸ID3v2跳过与VBRI_TOC解析映射`() {
        // 构造含 80KB ID3v2（大于 64KB 初始探测窗）+ 合法 MPEG1 Layer3 帧头 + Fraunhofer VBRI TOC 的虚拟 MP3
        val id3PayloadSize = 80_000
        val audioStart = 10 + id3PayloadSize
        val totalSize = (audioStart + 1_000_000).toLong()
        val headChunk = ByteArray(512)
        // MPEG1 Layer3 128kbps 44.1kHz stereo frame header: FF FB 90 00
        headChunk[0] = 0xFF.toByte()
        headChunk[1] = 0xFB.toByte()
        headChunk[2] = 0x90.toByte()
        headChunk[3] = 0x00.toByte()
        // VBRI header at offset 4 + 32 = 36
        val v = 36
        "VBRI".toByteArray(Charsets.US_ASCII).copyInto(headChunk, v)
        // totalFrames at v+14 = 3828 (~100s at 44100Hz, 1152 samples/frame)
        val frames = 3828
        headChunk[v + 14] = ((frames ushr 24) and 0xFF).toByte()
        headChunk[v + 15] = ((frames ushr 16) and 0xFF).toByte()
        headChunk[v + 16] = ((frames ushr 8) and 0xFF).toByte()
        headChunk[v + 17] = (frames and 0xFF).toByte()
        // tocEntries = 4, tocScale = 1, entryBytes = 2, framesPerEntry = 957
        headChunk[v + 18] = 0; headChunk[v + 19] = 4
        headChunk[v + 20] = 0; headChunk[v + 21] = 1
        headChunk[v + 22] = 0; headChunk[v + 23] = 2
        // 4 entries of 25000 bytes each (100KB audio increments in TOC)
        for (i in 0 until 4) {
            val delta = 25000
            headChunk[v + 26 + i * 2] = ((delta ushr 8) and 0xFF).toByte()
            headChunk[v + 26 + i * 2 + 1] = (delta and 0xFF).toByte()
        }

        val id3Header = ByteArray(10)
        "ID3".toByteArray(Charsets.US_ASCII).copyInto(id3Header, 0)
        id3Header[3] = 3
        id3Header[6] = ((id3PayloadSize ushr 21) and 0x7F).toByte()
        id3Header[7] = ((id3PayloadSize ushr 14) and 0x7F).toByte()
        id3Header[8] = ((id3PayloadSize ushr 7) and 0x7F).toByte()
        id3Header[9] = (id3PayloadSize and 0x7F).toByte()

        val reader = SparseRangeLoudnessProber.RandomAccessByteReader { offset, length ->
            when {
                offset == 0L -> id3Header.copyOfRange(0, minOf(length, id3Header.size))
                offset == audioStart.toLong() -> headChunk.copyOfRange(0, minOf(length, headChunk.size))
                else -> null
            }
        }

        val mp3Idx = SparseRangeLoudnessProber.Mp3HeaderIndex.parse(reader, totalSize)
        assertTrue("应跳过 80KB ID3v2 并成功解析 VBRI TOC", mp3Idx != null)
        assertEquals(audioStart.toLong(), mp3Idx!!.audioDataStartOffset)
        assertEquals(44100, mp3Idx.sampleRate)
        assertTrue("应解析到 VBRI 累积字节表", mp3Idx.vbriCumulativeBytes != null && mp3Idx.vbriCumulativeBytes!!.size == 5)
        assertTrue("VBRI 时长应约为 100s，实际: ${mp3Idx.durationMs}", mp3Idx.durationMs in 99_000L..101_000L)
        val midRange = mp3Idx.mapWindowToByteRange(50_000L, 53_000L)
        assertTrue("50% 位置映射偏移应围绕 audioStart + 50000 附近", midRange.start in (audioStart + 40_000L)..(audioStart + 55_000L))
    }

    @Test
    fun `测试11x3s双重门控加P75锚点对安静前奏尾奏曲目的精确抗偏移估计`() {
        // 复现参考音频 rN Frums - ハテ.flac 实测 11×3s 真实分段响度分布（改善计划 Section 4.4）：
        // Seg 0 (-21.85) 与 Seg 10 (-55.37) 低于相对门限自动过滤，主体 9 段 -9.16 至 -7.40 LUFS，全曲真值 -8.30 LUFS
        val sampleRate = 48000
        val segTargetLufs = floatArrayOf(
            -21.85f, -9.16f, -7.83f, -8.36f, -7.92f, -7.87f, -9.13f, -7.42f, -7.40f, -9.02f, -55.37f
        )
        val segFrames = sampleRate * 3 // 每段 3s
        val segments = segTargetLufs.mapIndexed { idx, targetLufs ->
            // 1kHz 立体声正弦波幅度 a 对应响度约为 20*log10(a) - 0.04 LUFS
            val amp = 10.0.pow((targetLufs + 0.04f) / 20.0).toFloat().coerceAtMost(0.99f)
            val l = FloatArray(segFrames)
            val r = FloatArray(segFrames)
            for (i in 0 until segFrames) {
                val s = (amp * sin(2.0 * PI * 1000.0 * i / sampleRate)).toFloat()
                l[i] = s
                r[i] = s
            }
            EbuR128Calculator.analyzeSegment(
                segmentIndex = idx,
                startMs = idx * 17_300L,
                endMs = idx * 17_300L + 3000L,
                leftChannel = l,
                rightChannel = r,
                sampleRate = sampleRate
            )
        }

        val analysis = EbuR128Calculator.calculateSparseSegmentsLoudness(segments)
        assertTrue("安静前奏 Seg 0 应被相对门限过滤", analysis.filteredSegmentIndices.contains(0))
        assertTrue("安静尾奏 Seg 10 应被相对门限过滤", analysis.filteredSegmentIndices.contains(10))
        assertEquals(9, analysis.keptSegmentIndices.size)
        // 与全曲真值 -8.30 LUFS 误差必须在 ±0.5 LU 以内
        assertEquals(-8.30f, analysis.loudnessData.integratedLufs, 0.5f)
        assertEquals(LoudnessSource.SPARSE_RANGE_SCAN, analysis.loudnessData.source)
    }

    @Test
    fun `测试MP3帧同步检索与USB旁路状态文本`() {
        val fakeStream = ByteArray(100)
        // 放置一个合法的 MPEG1 Layer3 128kbps 44.1kHz 帧头：FF FB 90 00 于 offset 42
        fakeStream[42] = 0xFF.toByte()
        fakeStream[43] = 0xFB.toByte()
        fakeStream[44] = 0x90.toByte()
        fakeStream[45] = 0x00.toByte()
        assertEquals(42, SparseRangeLoudnessProber.Mp3HeaderIndex.findNextMp3FrameSync(fakeStream))

        val usbBypassed = LoudnessNormalizationState(
            isEnabled = true,
            isBypassedForUsbExclusive = true
        )
        assertTrue(usbBypassed.statusSummary.contains("USB 独占源码旁路"))
    }
}