package Akari.NCM.player.data

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.LyricSource
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.PlaylistSourceType
import Akari.NCM.player.core.loudness.LoudnessData
import Akari.NCM.player.core.loudness.LoudnessSource
import Akari.NCM.player.player.AmePlayerEngine
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackMetadataStrategyTest {

    private lateinit var context: Context
    private lateinit var ncmApi: NcmApi
    private lateinit var loudnessManager: LoudnessManager
    private lateinit var lyricsManager: LyricsManager
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ame_loudness_cache", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("ame_loudness_cache.db")
        val sessionManager = UserSessionManager(context)
        ncmApi = NcmApi(sessionManager)
        loudnessManager = LoudnessManager(context, ncmApi)
        lyricsManager = LyricsManager(context, ncmApi)
        tempDir = File(context.cacheDir, "meta_strategy_test_${System.nanoTime()}").apply { mkdirs() }
    }

    private fun buildTestFlac(
        comments: List<Pair<String, String>> = emptyList(),
        coverBytes: ByteArray? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))

        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        // Block 0: minimal 34-byte STREAMINFO
        blocks.add(0 to ByteArray(34))

        if (comments.isNotEmpty()) {
            val vc = ByteArrayOutputStream()
            fun writeIntLE(v: Int) {
                val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
                vc.write(b)
            }
            val vendor = "AmeTest".toByteArray(Charsets.UTF_8)
            writeIntLE(vendor.size)
            vc.write(vendor)
            writeIntLE(comments.size)
            for ((k, v) in comments) {
                val entry = "$k=$v".toByteArray(Charsets.UTF_8)
                writeIntLE(entry.size)
                vc.write(entry)
            }
            blocks.add(4 to vc.toByteArray())
        }

        if (coverBytes != null) {
            val pic = ByteArrayOutputStream()
            fun writeIntBE(v: Int) {
                pic.write((v ushr 24) and 0xFF)
                pic.write((v ushr 16) and 0xFF)
                pic.write((v ushr 8) and 0xFF)
                pic.write(v and 0xFF)
            }
            val mime = "image/jpeg".toByteArray(Charsets.US_ASCII)
            writeIntBE(3) // Cover (front)
            writeIntBE(mime.size)
            pic.write(mime)
            writeIntBE(0) // desc len
            writeIntBE(100); writeIntBE(100); writeIntBE(24); writeIntBE(0)
            writeIntBE(coverBytes.size)
            pic.write(coverBytes)
            blocks.add(6 to pic.toByteArray())
        }

        for ((idx, pair) in blocks.withIndex()) {
            val (type, body) = pair
            val isLast = idx == blocks.lastIndex
            val hdr0 = (if (isLast) 0x80 else 0x00) or (type and 0x7F)
            out.write(hdr0)
            out.write((body.size ushr 16) and 0xFF)
            out.write((body.size ushr 8) and 0xFF)
            out.write(body.size and 0xFF)
            out.write(body)
        }
        return out.toByteArray()
    }

    @Test
    fun `云端歌单优先读取本地文件自身的内嵌歌词封面与响度标签`() = runBlocking {
        val fakeCover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0x33, 0xFF.toByte(), 0xD9.toByte())
        val embeddedLrc = "[00:01.00]Embedded First Line\n[00:05.00]Embedded Second Line"
        val flacFile = File(tempDir, "cloud_matched_track.flac")
        flacFile.writeBytes(
            buildTestFlac(
                comments = listOf(
                    "LYRICS" to embeddedLrc,
                    "REPLAYGAIN_TRACK_GAIN" to "-6.50 dB",
                    "REPLAYGAIN_TRACK_PEAK" to "0.95"
                ),
                coverBytes = fakeCover
            )
        )
        // 同目录即使放了同名 .lrc，云端歌单第一优先也应读本地文件自身内嵌元数据
        File(tempDir, "cloud_matched_track.lrc").writeText("[00:01.00]Sibling LRC Line")

        // 预先写入一条不同值的云端响度缓存，验证第一优先仍选中本地文件自身内嵌标签
        loudnessManager.registerOnlineLoudness("999001", gainDb = -2.0f, peak = 0.9f)

        val media = PlayableMedia.LocalNcm(
            id = "999001",
            title = "Matched Cloud Song",
            artist = "Artist",
            album = "Album",
            coverUrl = "https://p1.music.126.net/cloud_cover.jpg",
            uri = Uri.fromFile(flacFile).toString(),
            format = AudioFormat.FLAC,
            isNcm = false
        )

        // 1. 歌词：优先读取本地文件内嵌歌词
        val lyricResult = lyricsManager.loadLyrics(media, PlaylistSourceType.ONLINE)
        assertEquals(LyricSource.EMBEDDED, lyricResult.source)
        assertEquals(2, lyricResult.lines.size)
        assertEquals("Embedded First Line", lyricResult.lines[0].text)

        // 2. 封面：优先提取本地文件内嵌封面
        val extractedCover = LocalMediaMetadataHelper.extractEmbeddedCoverBytes(context, Uri.fromFile(flacFile), isNcm = false)
        assertNotNull(extractedCover)
        assertArrayEquals(fakeCover, extractedCover)

        // 3. 响度：第一优先读取本地文件内嵌 ReplayGain 标签 (-18.0 - (-6.50) = -11.5 LUFS)
        val loudness = loudnessManager.getLoudness(
            media = media,
            qualityLevel = "local",
            fileSignature = "size:${flacFile.length()}",
            sourceType = PlaylistSourceType.ONLINE
        )
        assertNotNull(loudness)
        assertEquals(LoudnessSource.REPLAYGAIN_TAG, loudness!!.source)
        assertEquals(-11.5f, loudness.integratedLufs, 0.02f)
    }

    @Test
    fun `云端歌单本地文件无内嵌响度标签时第二优先读取云端响度缓存且本地和云端均缺失时返回null走本地计算`() = runBlocking {
        val plainFlac = File(tempDir, "plain_no_tags.flac")
        plainFlac.writeBytes(buildTestFlac())

        // 预先缓存云端响度 (-18.0 - (-9.0) = -9.0 LUFS)
        loudnessManager.registerOnlineLoudness("888002", gainDb = -9.0f, peak = 0.98f)

        val matchedWithCloudCache = PlayableMedia.LocalNcm(
            id = "888002",
            title = "Plain Local With Cloud Loudness",
            artist = "Artist",
            album = "Album",
            uri = Uri.fromFile(plainFlac).toString(),
            format = AudioFormat.FLAC,
            isNcm = false
        )

        val cloudFallback = loudnessManager.getLoudness(
            media = matchedWithCloudCache,
            qualityLevel = "local",
            fileSignature = "size:${plainFlac.length()}",
            sourceType = PlaylistSourceType.ONLINE
        )
        assertNotNull("无本地标签时第二优先应命中云端音量均衡值", cloudFallback)
        assertEquals(LoudnessSource.NETEASE_ONLINE, cloudFallback!!.source)
        assertEquals(-9.0f, cloudFallback.integratedLufs, 0.02f)

        // 歌曲 ID 非有效云端数字且无本地标签与缓存 -> 返回 null 交由第三优先本地计算
        val unmatchedNoCloud = matchedWithCloudCache.copy(id = "non_numeric_id")
        val localComputeTrigger = loudnessManager.getLoudness(
            media = unmatchedNoCloud,
            qualityLevel = "local",
            fileSignature = "size:${plainFlac.length()}",
            sourceType = PlaylistSourceType.ONLINE
        )
        assertNull("本地与云端均无响度值时应返回 null 触发本地计算", localComputeTrigger)

        // 云端歌单严禁通过歌名搜索歌词：无效 ID 且无内嵌歌词必须返回 NONE
        val noSearchLyrics = lyricsManager.loadLyrics(unmatchedNoCloud, PlaylistSourceType.ONLINE)
        assertEquals(LyricSource.NONE, noSearchLyrics.source)
        assertTrue(noSearchLyrics.lines.isEmpty())
    }

    @Test
    fun `本地歌单仅通过文件名匹配歌词与封面且响度忽略云端仅走本地计算`() = runBlocking {
        val fakeEmbeddedCover = byteArrayOf(1, 2, 3, 4)
        val siblingCoverBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        val audioFile = File(tempDir, "rN Frums - ハテ.flac")
        audioFile.writeBytes(
            buildTestFlac(
                comments = listOf(
                    "LYRICS" to "[00:01.00]Embedded Should Be Ignored In Local Playlist",
                    "REPLAYGAIN_TRACK_GAIN" to "-5.00 dB"
                ),
                coverBytes = fakeEmbeddedCover
            )
        )

        // 即使云端缓存中已有该 songId 的响度，本地歌单也不应采纳
        loudnessManager.registerOnlineLoudness("3444452494", gainDb = -9.28f, peak = 1.0f)

        val localTrack = PlayableMedia.LocalNcm(
            id = "3444452494",
            title = "rN Frums - ハテ",
            artist = "",
            album = "",
            coverUrl = "https://p1.music.126.net/should_not_be_used.jpg",
            uri = Uri.fromFile(audioFile).toString(),
            format = AudioFormat.FLAC,
            isNcm = false
        )

        // 1. 同目录无同名歌词/封面文件时：不读取内嵌元数据，均返回空
        val emptyLyrics = lyricsManager.loadLyrics(localTrack, PlaylistSourceType.LOCAL)
        assertEquals(LyricSource.NONE, emptyLyrics.source)
        assertNull(LocalMediaMetadataHelper.findSiblingCover(context, Uri.fromFile(audioFile), localTrack.title))

        // 2. 在同目录放置同名 .lrc 与 .png 文件后，按文件名精确匹配成功
        File(tempDir, "rN Frums - ハテ.lrc").writeText("[00:02.00]Filename Matched LRC")
        File(tempDir, "rN Frums - ハテ.png").writeBytes(siblingCoverBytes)

        val lrcResult = lyricsManager.loadLyrics(localTrack, PlaylistSourceType.LOCAL)
        assertEquals(LyricSource.LOCAL_LRC, lrcResult.source)
        assertEquals("Filename Matched LRC", lrcResult.lines.first().text)

        val coverMatch = LocalMediaMetadataHelper.findSiblingCover(context, Uri.fromFile(audioFile), localTrack.title)
        assertNotNull(coverMatch)
        assertArrayEquals(siblingCoverBytes, coverMatch!!.second)

        // 3. 本地歌单响度：忽略云端缓存与内嵌标签，返回 null 走本地计算；写入本地计算结果后可命中
        val beforeLocalScan = loudnessManager.getLoudness(
            media = localTrack,
            qualityLevel = "local",
            fileSignature = "size:${audioFile.length()}",
            sourceType = PlaylistSourceType.LOCAL
        )
        assertNull("本地歌单不得使用云端响度缓存，应返回 null 交由本地计算", beforeLocalScan)
        val (upcomingAvgBefore, upcomingReadyBefore) = loudnessManager.computeUpcomingAverageLoudness(
            listOf(localTrack),
            PlaylistSourceType.LOCAL
        )
        assertNull(upcomingAvgBefore)
        assertEquals(0, upcomingReadyBefore)

        loudnessManager.saveLoudness(
            mediaId = localTrack.id,
            data = LoudnessData(-8.30f, 1.07f, LoudnessSource.SPARSE_RANGE_SCAN),
            sourcePlatform = LoudnessManager.PLATFORM_LOCAL,
            qualityLevel = "local",
            fileSignature = "size:${audioFile.length()}"
        )
        val afterLocalScan = loudnessManager.getLoudness(
            media = localTrack,
            qualityLevel = "local",
            fileSignature = "size:${audioFile.length()}",
            sourceType = PlaylistSourceType.LOCAL
        )
        assertNotNull(afterLocalScan)
        assertEquals(LoudnessSource.SPARSE_RANGE_SCAN, afterLocalScan!!.source)
        assertEquals(-8.30f, afterLocalScan.integratedLufs, 0.01f)
        val (upcomingAvgAfter, upcomingReadyAfter) = loudnessManager.computeUpcomingAverageLoudness(
            listOf(localTrack),
            PlaylistSourceType.LOCAL
        )
        assertNotNull(upcomingAvgAfter)
        assertEquals(1, upcomingReadyAfter)
        assertEquals(-8.30f, upcomingAvgAfter!!, 0.01f)
    }

    @Test
    fun `提取参考音频rN_Frums_ハテ的FLAC内嵌封面字节成功`() {
        val candidates = listOf(
            File("public/assets/aistudio/rN_Frums_-____4de5d3de.flac"),
            File("../public/assets/aistudio/rN_Frums_-____4de5d3de.flac")
        )
        val refFile = candidates.firstOrNull { it.exists() }
        assertNotNull("参考音频应存在", refFile)
        val cover = LocalMediaMetadataHelper.extractEmbeddedCoverBytes(context, Uri.fromFile(refFile!!), isNcm = false)
        assertNotNull("应直接从参考 FLAC 的 PICTURE 块提取到封面字节", cover)
        assertTrue("封面 JPEG 体积应超过 100KB，实际: ${cover!!.size}", cover.size > 100_000)
        // 验证 JPEG SOI 魔数 FF D8
        assertEquals(0xFF.toByte(), cover[0])
        assertEquals(0xD8.toByte(), cover[1])
    }

    @Test
    fun `旧版持久化为LOCAL的云端同步歌单自动迁移为ONLINE且纯本地歌单保持LOCAL`() {
        val cloudMatchedLocalFlac = PlayableMedia.LocalNcm(
            id = "2723338530",
            title = "ムリムリ進化論",
            artist = "七音阿卡莉/ナユタン星人",
            album = "ムリムリ進化論",
            coverUrl = "https://p1.music.126.net/example_cover.jpg",
            uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fsong.flac",
            format = AudioFormat.FLAC,
            isNcm = false
        )
        assertEquals(
            PlaylistSourceType.ONLINE,
            AmePlayerEngine.resolveRestoredSourceType(PlaylistSourceType.LOCAL, listOf(cloudMatchedLocalFlac))
        )

        val pureLocalFlac = cloudMatchedLocalFlac.copy(id = "12345", artist = "", album = "", coverUrl = null)
        assertEquals(
            PlaylistSourceType.LOCAL,
            AmePlayerEngine.resolveRestoredSourceType(PlaylistSourceType.LOCAL, listOf(pureLocalFlac))
        )

        val pureLocalNcm = cloudMatchedLocalFlac.copy(isNcm = true)
        assertEquals(
            PlaylistSourceType.LOCAL,
            AmePlayerEngine.resolveRestoredSourceType(PlaylistSourceType.LOCAL, listOf(pureLocalNcm))
        )

        // LOCAL 模式下 sanitizeMediaForSource 必须清理云端 http(s) 封面，与 buildMediaItem 保持一致
        val sanitizedLocal = AmePlayerEngine.sanitizeMediaForSource(cloudMatchedLocalFlac, PlaylistSourceType.LOCAL)
        assertNull((sanitizedLocal as PlayableMedia.LocalNcm).coverUrl)
        // ONLINE 模式下保留云端封面
        val keptOnline = AmePlayerEngine.sanitizeMediaForSource(cloudMatchedLocalFlac, PlaylistSourceType.ONLINE)
        assertEquals("https://p1.music.126.net/example_cover.jpg", (keptOnline as PlayableMedia.LocalNcm).coverUrl)
    }

    @Test
    fun `SAF父目录ID推导准确且不错误截取根路径`() {
        assertEquals(
            "primary:Download/netease/cloudmusic/Music",
            LocalMediaMetadataHelper.deriveParentDocumentId(
                "primary:Download/netease/cloudmusic/Music/あたらよ - 「僕は...」.ncm"
            )
        )
        assertEquals(
            "primary:",
            LocalMediaMetadataHelper.deriveParentDocumentId("primary:track.flac")
        )
        assertNull(LocalMediaMetadataHelper.deriveParentDocumentId("invalid_doc_id"))
    }

    private fun buildTestNcmStreamBytes(
        musicId: Long = 2627117817L,
        musicName: String = "I Wanna",
        coverBytes: ByteArray,
        extraFramePadding: Int = 0
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeUint32LE(value: Int) {
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }

        // 1. Magic "CTENFDAM" + 2-byte gap
        out.write("CTENFDAM".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x70, 0x00))

        // 2. Dummy key block (scanMetadata skips key block)
        val dummyKey = ByteArray(16) { 0x11 }
        writeUint32LE(dummyKey.size)
        out.write(dummyKey)

        // 3. Valid AES-ECB encrypted meta block ("163 key(Don't modify):" + Base64(AES_ECB("music:{...}"))) XOR 0x63
        val metaJson = """music:{"musicId":$musicId,"musicName":"$musicName","artist":[["TestArtist",1]],"album":"TestAlbum","albumPic":"https://p1.music.126.net/test.jpg","format":"flac","duration":200000}"""
        val metaKey = byteArrayOf(
            0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
            0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28
        )
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(metaKey, "AES"))
        val encryptedJson = cipher.doFinal(metaJson.toByteArray(Charsets.UTF_8))
        val b64 = android.util.Base64.encodeToString(encryptedJson, android.util.Base64.NO_WRAP)
        val metaPlain = ("163 key(Don't modify):$b64").toByteArray(Charsets.US_ASCII)
        val metaXor = ByteArray(metaPlain.size) { i -> (metaPlain[i].toInt() xor 0x63).toByte() }
        writeUint32LE(metaXor.size)
        out.write(metaXor)

        // 4. CRC32 (4B) + gap0 (1B) + imageFrameSize (4B) + imageSize (4B) + image bytes + optional padding
        writeUint32LE(0x12345678) // CRC32
        out.write(0x00) // gap byte 0
        writeUint32LE(coverBytes.size + extraFramePadding) // imageFrameSize
        writeUint32LE(coverBytes.size) // imageSize
        out.write(coverBytes)
        if (extraFramePadding > 0) {
            out.write(ByteArray(extraFramePadding))
        }
        return out.toByteArray()
    }

    @Test
    fun `NCM内嵌封面跳过完整9字节头精确读取图片字节且校验魔数`() {
        val jpegCover = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0xFF.toByte(), 0xD9.toByte()
        )
        val ncmBytes = buildTestNcmStreamBytes(coverBytes = jpegCover, extraFramePadding = 16)
        val result = NcmDataSource.scanMetadataFromStream(java.io.ByteArrayInputStream(ncmBytes))

        assertNotNull("应成功解析 NCM 元数据", result)
        assertEquals(2627117817L, result!!.first.musicId)
        assertEquals("I Wanna", result.first.musicName)
        assertNotNull("应提取到精确对齐的 NCM 内嵌封面", result.third)
        assertArrayEquals(jpegCover, result.third)

        // 若为 0 字节封面或非法非图片字节（如 4 字节错位头），必须返回 null 以便回退云端封面
        val emptyCoverNcm = buildTestNcmStreamBytes(coverBytes = ByteArray(0), extraFramePadding = 32)
        val emptyResult = NcmDataSource.scanMetadataFromStream(java.io.ByteArrayInputStream(emptyCoverNcm))
        assertNotNull(emptyResult)
        assertNull(emptyResult!!.third)

        val shiftedCorruptBytes = byteArrayOf(0x78, 0x24, 0x05, 0x00, 0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        assertEquals(false, LocalMediaMetadataHelper.isValidImageHeader(shiftedCorruptBytes))
        assertTrue(LocalMediaMetadataHelper.isValidImageHeader(jpegCover))
    }
}
