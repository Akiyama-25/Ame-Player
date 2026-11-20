package Akari.NCM.player.data

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.loudness.LoudnessData
import Akari.NCM.player.core.loudness.LoudnessSource
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoudnessManagerTest {

    private lateinit var context: Context
    private lateinit var loudnessManager: LoudnessManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ame_loudness_cache", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.deleteDatabase("ame_loudness_cache.db")
        val sessionManager = UserSessionManager(context)
        val ncmApi = NcmApi(sessionManager)
        loudnessManager = LoudnessManager(context, ncmApi)
    }

    @Test
    fun `registerOnlineLoudness过滤试听片段与0点001以内占位值并支持vd回退`() {
        assertNull(loudnessManager.registerOnlineLoudness("song_null", null, 1.0f))
        assertNull(loudnessManager.registerOnlineLoudness("song_zero", 0.0f, 1.0f))
        assertNull(loudnessManager.registerOnlineLoudness("song_neg_zero", -0.0f, 1.0f))
        assertNull(loudnessManager.registerOnlineLoudness("song_tiny", -2.0e-4f, 0.9f))
        assertNull(loudnessManager.registerOnlineLoudness("song_boundary", 0.001f, 0.9f))

        // 试听截断片段强制拒绝，哪怕 gain 非零
        assertNull(
            loudnessManager.registerOnlineLoudness(
                songId = "song_trial",
                gainDb = -5.2f,
                peak = 0.98f,
                vdDb = -5.2f,
                isFreeTrial = true
            )
        )

        // gain 有效 -> NETEASE_ONLINE（实测 /song/url/v1 gain 基准为 -18.0 LUFS）
        val valid = loudnessManager.registerOnlineLoudness("song_valid", -9.2841f, 1.0f)
        assertNotNull(valid)
        assertEquals(-8.7159f, valid!!.integratedLufs, 0.02f)
        assertEquals(1.0f, valid.truePeak, 0.001f)
        assertEquals(LoudnessSource.NETEASE_ONLINE, valid.source)

        // 低响度古典曲目正增益样本实测（Clair De Lune: gain=+4.68 -> -18.0 - 4.68 = -22.68 LUFS）
        val quietClassical = loudnessManager.registerOnlineLoudness("song_classical", 4.68f, 0.6694f)
        assertNotNull(quietClassical)
        assertEquals(-22.68f, quietClassical!!.integratedLufs, 0.02f)
        assertEquals(LoudnessSource.NETEASE_ONLINE, quietClassical.source)

        // gain 为无效占位 0.0，但分音质 vd 有效 (-5.6512 dB @ -14.0 LUFS 基准) -> NETEASE_VD (-8.35 LUFS)
        val vdFallback = loudnessManager.registerOnlineLoudness(
            songId = "song_vd_only",
            gainDb = 0.0f,
            peak = 0.96f,
            vdDb = -5.6512f,
            isFreeTrial = false,
            qualityLevel = "lossless",
            fileSignature = "md5_abc_123"
        )
        assertNotNull(vdFallback)
        assertEquals(-8.3488f, vdFallback!!.integratedLufs, 0.02f)
        assertEquals(LoudnessSource.NETEASE_VD, vdFallback.source)
    }

    @Test
    fun `NcmApi正确解析并校准song_detail与song_music_detail分音质vd字段`() {
        assertNull(NcmApi.normalizeNeteaseVdToDb(0.0))
        assertNull(NcmApi.normalizeNeteaseVdToDb(-0.0002))
        assertNull(NcmApi.normalizeNeteaseVdToDb(-2.0, "sq")) // 1e-4 dB 整型占位 -0.0002 dB 必须过滤
        assertEquals(-0.03f, NcmApi.normalizeNeteaseVdToDb(-300.0, "sq")!!, 0.001f) // -300 = -0.03 dB，不可误除 100
        assertEquals(-3.39f, NcmApi.normalizeNeteaseVdToDb(-3.39)!!, 0.001f)
        // 实测高响度参考曲目 rN Frums - ハテ (3444452494) 真实接口值验证：
        // sq.vd = -56512.0 -> -5.6512 dB (@ -14 LUFS) -> -8.35 LUFS（实测全曲 -8.30 LUFS）
        assertEquals(-5.6512f, NcmApi.normalizeNeteaseVdToDb(-56512.0, "sq")!!, 0.001f)
        assertEquals(-8.3488f, NcmApi.calibrateNeteaseVdToLufs(-56512.0, "sq")!!, 0.01f)
        // jm.vd = -9.6721 (@ -18 LUFS 基准) -> 折算至 -14 LUFS 基准为 -5.6721 dB -> -8.33 LUFS
        assertEquals(-5.6721f, NcmApi.normalizeNeteaseVdToDb(-9.6721, "jm")!!, 0.001f)
        assertEquals(-8.3279f, NcmApi.calibrateNeteaseVdToLufs(-9.6721, "jm")!!, 0.01f)
        // 实测低响度古典曲目 Clair De Lune (565816936) l.vd = +86800.0 -> +8.68 dB -> -22.68 LUFS（实测 -22.6 LUFS）
        assertEquals(8.68f, NcmApi.normalizeNeteaseVdToDb(86800.0, "l")!!, 0.001f)
        assertEquals(-22.68f, NcmApi.calibrateNeteaseVdToLufs(86800.0, "l")!!, 0.01f)

        val musicDetailJson = """
            {
              "code": 200,
              "data": {
                "h": {"br": 320000, "vd": -2.5},
                "sq": {"br": 960000, "vd": -56512.0},
                "hr": {"br": 1920000, "vd": 0.0},
                "jm": {"br": 1920000, "vd": -9.6721},
                "sk": {"br": 1920000, "vd": -6.0699}
              }
            }
        """.trimIndent()
        assertEquals(-5.6512f, NcmApi.extractQualityVdFromMusicDetailJson(musicDetailJson, "lossless")!!, 0.01f)
        assertEquals(-2.5f, NcmApi.extractQualityVdFromMusicDetailJson(musicDetailJson, "exhigh")!!, 0.01f)
        // hr 为 0 占位时按回退顺序取有效 sq
        assertEquals(-5.6512f, NcmApi.extractQualityVdFromMusicDetailJson(musicDetailJson, "hires")!!, 0.01f)
        // jymaster 命中 jm 并折算到 -14.0 LUFS 基准
        assertEquals(-5.6721f, NcmApi.extractQualityVdFromMusicDetailJson(musicDetailJson, "jymaster")!!, 0.01f)
        // sky 命中 sk（-14.0 LUFS 基准）
        assertEquals(-6.0699f, NcmApi.extractQualityVdFromMusicDetailJson(musicDetailJson, "sky")!!, 0.01f)

        val songDetailJson = """
            {
              "code": 200,
              "songs": [{
                "id": 3444452494,
                "l": {"vd": -52841.0},
                "m": {"vd": -54104.0},
                "h": {"vd": -56535.0},
                "sq": {"vd": -56512.0}
              }]
            }
        """.trimIndent()
        assertEquals(-5.6512f, NcmApi.extractQualityVdFromSongDetailJson(songDetailJson, "lossless")!!, 0.01f)
        assertEquals(-5.6535f, NcmApi.extractQualityVdFromSongDetailJson(songDetailJson, "exhigh")!!, 0.01f)
        assertEquals(-5.2841f, NcmApi.extractQualityVdFromSongDetailJson(songDetailJson, "standard")!!, 0.01f)
    }

    @Test
    fun `P0本地SQLite缓存支持复合主键持久化与LRU淘汰`() {
        val song = PlayableMedia.Online(
            id = "p0_song",
            title = "P0 Test",
            artist = "Artist",
            album = "Album",
            durationMs = 180_000L
        )
        loudnessManager.saveLoudness(
            mediaId = song.id,
            data = LoudnessData(-8.3f, 1.07f, LoudnessSource.SPARSE_RANGE_SCAN),
            sourcePlatform = LoudnessManager.PLATFORM_NETEASE,
            qualityLevel = "lossless",
            fileSignature = "md5_lossless_1"
        )
        loudnessManager.saveLoudness(
            mediaId = song.id,
            data = LoudnessData(-9.1f, 0.99f, LoudnessSource.SPARSE_RANGE_SCAN),
            sourcePlatform = LoudnessManager.PLATFORM_NETEASE,
            qualityLevel = "exhigh",
            fileSignature = "md5_exhigh_2"
        )

        // 重建全新 LoudnessManager 实例验证冷启动 SQLite P0 零延迟命中与音质区分
        val freshManager = LoudnessManager(context, NcmApi(UserSessionManager(context)))
        val sqHit = freshManager.getCachedLoudnessSync(song, "lossless", "md5_lossless_1")
        val hqHit = freshManager.getCachedLoudnessSync(song, "exhigh", "md5_exhigh_2")
        assertNotNull(sqHit)
        assertNotNull(hqHit)
        assertEquals(-8.3f, sqHit!!.integratedLufs, 0.01f)
        assertEquals(-9.1f, hqHit!!.integratedLufs, 0.01f)

        // 验证数据库 LRU 上限裁剪
        val smallDb = LoudnessCacheDatabase(context, maxEntries = 3)
        smallDb.clearAll()
        for (i in 1..5) {
            smallDb.put("netease", "lru_$i", "sq", "sig_$i", LoudnessData(-10.0f - i, 0.9f, LoudnessSource.SPARSE_RANGE_SCAN))
            Thread.sleep(5)
        }
        assertEquals(3, smallDb.count())
        assertNull(smallDb.get("netease", "lru_1", "sq", "sig_1"))
        assertNotNull(smallDb.get("netease", "lru_5", "sq", "sig_5"))
    }

    @Test
    fun `全曲锁定结果不可被初判或队列均值覆盖且支持初判升级锁定`() {
        val song = PlayableMedia.Online(
            id = "track_upgrade",
            title = "Upgrade Test",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L,
            coverUrl = null
        )

        val preview = LoudnessData(-11.0f, 0.9f, LoudnessSource.FAST_PREVIEW_SCAN)
        loudnessManager.saveLoudness(song.id, preview)
        assertEquals(-11.0f, loudnessManager.getCachedLoudnessSync(song)!!.integratedLufs, 0.01f)

        loudnessManager.saveLoudness(song.id, LoudnessData(-18.0f, 1.0f, LoudnessSource.QUEUE_AVERAGE))
        assertEquals(-11.0f, loudnessManager.getCachedLoudnessSync(song)!!.integratedLufs, 0.01f)

        val locked = LoudnessData(-12.3f, 0.92f, LoudnessSource.BS1770_SCAN)
        loudnessManager.saveLoudness(song.id, locked)
        assertEquals(-12.3f, loudnessManager.getCachedLoudnessSync(song)!!.integratedLufs, 0.01f)
        assertEquals(LoudnessSource.BS1770_SCAN, loudnessManager.getCachedLoudnessSync(song)!!.source)

        loudnessManager.saveLoudness(song.id, LoudnessData(-8.0f, 1.0f, LoudnessSource.FAST_PREVIEW_SCAN))
        assertEquals(-12.3f, loudnessManager.getCachedLoudnessSync(song)!!.integratedLufs, 0.01f)
        assertEquals(LoudnessSource.BS1770_SCAN, loudnessManager.getCachedLoudnessSync(song)!!.source)
    }

    @Test
    fun `computeUpcomingAverageLoudness准确计算未来3首平均响度与已就绪数`() {
        val t1 = PlayableMedia.Online(id = "u1", title = "T1", artist = "A", album = "Al", durationMs = 180_000L)
        val t2 = PlayableMedia.Online(id = "u2", title = "T2", artist = "A", album = "Al", durationMs = 180_000L)
        val t3 = PlayableMedia.Online(id = "u3", title = "T3", artist = "A", album = "Al", durationMs = 180_000L)

        loudnessManager.saveLoudness("u1", LoudnessData(-10.0f, 0.95f, LoudnessSource.NETEASE_ONLINE))
        loudnessManager.saveLoudness("u3", LoudnessData(-14.0f, 0.90f, LoudnessSource.BS1770_SCAN))

        val (avg, readyCount) = loudnessManager.computeUpcomingAverageLoudness(listOf(t1, t2, t3))
        assertNotNull(avg)
        assertEquals(2, readyCount)
        assertEquals(-12.0f, avg!!, 0.01f)
    }

    @Test
    fun `流式前奏初判不可覆盖多点极速初判而高优先级来源可正常升级`() {
        val song = PlayableMedia.Online(
            id = "track_priority",
            title = "Priority Test",
            artist = "Artist",
            album = "Album",
            durationMs = 200_000L,
            coverUrl = null
        )

        val multiPoint = LoudnessData(-6.9f, 0.95f, LoudnessSource.FAST_PREVIEW_SCAN)
        loudnessManager.saveLoudness(song.id, multiPoint)

        val introPreview = LoudnessData(-21.3f, 0.3f, LoudnessSource.INTRO_PREVIEW_SCAN)
        loudnessManager.saveLoudness(song.id, introPreview)
        val afterIntro = loudnessManager.getCachedLoudnessSync(song)!!
        assertEquals(-6.9f, afterIntro.integratedLufs, 0.01f)
        assertEquals(LoudnessSource.FAST_PREVIEW_SCAN, afterIntro.source)

        val song2 = song.copy(id = "track_priority_2")
        loudnessManager.saveLoudness(song2.id, introPreview)
        loudnessManager.saveLoudness(song2.id, multiPoint)
        val upgraded = loudnessManager.getCachedLoudnessSync(song2)!!
        assertEquals(-6.9f, upgraded.integratedLufs, 0.01f)
        assertEquals(LoudnessSource.FAST_PREVIEW_SCAN, upgraded.source)

        val finalLocked = LoudnessData(-9.6f, 0.98f, LoudnessSource.BS1770_SCAN)
        loudnessManager.saveLoudness(song.id, finalLocked)
        val afterFinal = loudnessManager.getCachedLoudnessSync(song)!!
        assertEquals(-9.6f, afterFinal.integratedLufs, 0.01f)
        assertEquals(LoudnessSource.BS1770_SCAN, afterFinal.source)
    }
}
