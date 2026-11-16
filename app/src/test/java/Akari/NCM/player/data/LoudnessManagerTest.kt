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
        val sessionManager = UserSessionManager(context)
        val ncmApi = NcmApi(sessionManager)
        loudnessManager = LoudnessManager(context, ncmApi)
    }

    @Test
    fun `registerOnlineLoudness过滤零值与空占位增益并保留有效云端增益`() {
        assertNull(loudnessManager.registerOnlineLoudness("song_null", null, 1.0f))
        assertNull(loudnessManager.registerOnlineLoudness("song_zero", 0.0f, 1.0f))
        assertNull(loudnessManager.registerOnlineLoudness("song_tiny", 0.002f, 0.9f))

        val valid = loudnessManager.registerOnlineLoudness("song_valid", -4.5f, 0.95f)
        assertNotNull(valid)
        // -14.0 - (-4.5) = -9.5 LUFS
        assertEquals(-9.5f, valid!!.integratedLufs, 0.01f)
        assertEquals(0.95f, valid.truePeak, 0.001f)
        assertEquals(LoudnessSource.NETEASE_ONLINE, valid.source)
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

        // 1. 先保存 Stage 1 初判
        val preview = LoudnessData(-11.0f, 0.9f, LoudnessSource.FAST_PREVIEW_SCAN)
        loudnessManager.saveLoudness(song.id, preview)
        assertEquals(-11.0f, loudnessManager.getCachedLoudnessSync(song)!!.integratedLufs, 0.01f)

        // 2. QUEUE_AVERAGE 不允许写入持久化缓存
        loudnessManager.saveLoudness(song.id, LoudnessData(-18.0f, 1.0f, LoudnessSource.QUEUE_AVERAGE))
        assertEquals(-11.0f, loudnessManager.getCachedLoudnessSync(song)!!.integratedLufs, 0.01f)

        // 3. Stage 2 全曲锁定可覆盖初判
        val locked = LoudnessData(-12.3f, 0.92f, LoudnessSource.BS1770_SCAN)
        loudnessManager.saveLoudness(song.id, locked)
        assertEquals(-12.3f, loudnessManager.getCachedLoudnessSync(song)!!.integratedLufs, 0.01f)
        assertEquals(LoudnessSource.BS1770_SCAN, loudnessManager.getCachedLoudnessSync(song)!!.source)

        // 4. 已有全曲锁定时不被后续初判覆盖
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

        // 1. 先保存 3 锚点多点抽样 (-6.9 LUFS, FAST_PREVIEW_SCAN)
        val multiPoint = LoudnessData(-6.9f, 0.95f, LoudnessSource.FAST_PREVIEW_SCAN)
        loudnessManager.saveLoudness(song.id, multiPoint)

        // 2. 低优先级前奏初判 (-21.3 LUFS, INTRO_PREVIEW_SCAN) 不得覆盖多点初判
        val introPreview = LoudnessData(-21.3f, 0.3f, LoudnessSource.INTRO_PREVIEW_SCAN)
        loudnessManager.saveLoudness(song.id, introPreview)
        val afterIntro = loudnessManager.getCachedLoudnessSync(song)!!
        assertEquals(-6.9f, afterIntro.integratedLufs, 0.01f)
        assertEquals(LoudnessSource.FAST_PREVIEW_SCAN, afterIntro.source)

        // 3. 若先写入 INTRO_PREVIEW_SCAN，后续 FAST_PREVIEW_SCAN 可升级覆盖
        val song2 = song.copy(id = "track_priority_2")
        loudnessManager.saveLoudness(song2.id, introPreview)
        loudnessManager.saveLoudness(song2.id, multiPoint)
        val upgraded = loudnessManager.getCachedLoudnessSync(song2)!!
        assertEquals(-6.9f, upgraded.integratedLufs, 0.01f)
        assertEquals(LoudnessSource.FAST_PREVIEW_SCAN, upgraded.source)

        // 4. 全曲 BS.1770 锁定可升级多点初判
        val finalLocked = LoudnessData(-9.6f, 0.98f, LoudnessSource.BS1770_SCAN)
        loudnessManager.saveLoudness(song.id, finalLocked)
        val afterFinal = loudnessManager.getCachedLoudnessSync(song)!!
        assertEquals(-9.6f, afterFinal.integratedLufs, 0.01f)
        assertEquals(LoudnessSource.BS1770_SCAN, afterFinal.source)
    }
}
