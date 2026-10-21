package Akari.NCM.player.data

import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.PlayableMedia
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
class PlaylistMatchingTest {

    private lateinit var context: Context
    private lateinit var playlistManager: PlaylistManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        playlistManager = PlaylistManager(context)
    }

    @Test
    fun testNcmIdHasHighestPriority() {
        // 本地扫描歌单包含一个 NCM 文件，其 ID 匹配云端歌曲 ID
        val localNcm = PlayableMedia.LocalNcm(
            id = "18400001",
            title = "Different Title In Local File",
            artist = "Different Artist",
            album = "",
            uri = "content://media/1",
            format = AudioFormat.FLAC,
            isNcm = true
        )
        playlistManager.saveOrUpdateSyncedPlaylist(
            ncmPlaylistId = -9999L,
            name = "本地扫描文件夹",
            coverImgUrl = null,
            songs = listOf(localNcm)
        )

        val online = PlayableMedia.Online(
            id = "18400001",
            title = "Official Title",
            artist = "Official Artist",
            album = "Official Album"
        )

        val matched = playlistManager.findLocalMatch(online)
        assertNotNull(matched)
        assertEquals(localNcm.id, matched?.id)
        assertEquals(localNcm.uri, matched?.uri)
    }

    @Test
    fun testLayer1ExactMatch() {
        val localMp3 = PlayableMedia.LocalNcm(
            id = "local_hash_1",
            title = "Halo",
            artist = "Beyonce",
            album = "",
            uri = "file:///storage/emulated/0/Music/Beyonce - Halo.mp3",
            format = AudioFormat.MP3,
            isNcm = false
        )
        playlistManager.saveOrUpdateSyncedPlaylist(
            ncmPlaylistId = -9999L,
            name = "本地扫描文件夹",
            coverImgUrl = null,
            songs = listOf(localMp3)
        )

        val online = PlayableMedia.Online(
            id = "99999",
            title = "Halo",
            artist = "Beyonce",
            album = "I Am... Sasha Fierce"
        )

        val matched = playlistManager.findLocalMatch(online)
        assertNotNull(matched)
        assertEquals("local_hash_1", matched?.id)
    }

    @Test
    fun testLayer2SlashToSpaceMatch() {
        // 云端制作人是用 '/' 分隔的，本地文件将其替换为空格
        val localFlac = PlayableMedia.LocalNcm(
            id = "local_hash_2",
            title = "Stay",
            artist = "The Kid LAROI Justin Bieber",
            album = "",
            uri = "file:///storage/emulated/0/Music/Stay.flac",
            format = AudioFormat.FLAC,
            isNcm = false
        )
        playlistManager.saveOrUpdateSyncedPlaylist(
            ncmPlaylistId = -9999L,
            name = "本地扫描文件夹",
            coverImgUrl = null,
            songs = listOf(localFlac)
        )

        val online = PlayableMedia.Online(
            id = "88888",
            title = "Stay",
            artist = "The Kid LAROI/Justin Bieber",
            album = "F*CK LOVE 3"
        )

        val matched = playlistManager.findLocalMatch(online)
        assertNotNull(matched)
        assertEquals("local_hash_2", matched?.id)
    }

    @Test
    fun testLayer3SpecialCharToSpaceMatch() {
        // 用户给出的典型场景：“*Luna/ゆある/ねんね - イグニッション” 对应本地 “ Luna ゆある ねんね - イグニッション.ncm / mp3”
        val localMedia = PlayableMedia.LocalNcm(
            id = "local_hash_3",
            title = "イグニッション",
            artist = " Luna ゆある ねんね",
            album = "",
            uri = "file:///storage/emulated/0/Music/ Luna ゆある ねんね - イグニッション.mp3",
            format = AudioFormat.MP3,
            isNcm = false
        )
        playlistManager.saveOrUpdateSyncedPlaylist(
            ncmPlaylistId = -9999L,
            name = "本地扫描文件夹",
            coverImgUrl = null,
            songs = listOf(localMedia)
        )

        val online = PlayableMedia.Online(
            id = "77777",
            title = "イグニッション",
            artist = "*Luna/ゆある/ねんね",
            album = "イグニッション"
        )

        val matched = playlistManager.findLocalMatch(online)
        assertNotNull(matched)
        assertEquals("local_hash_3", matched?.id)
    }

    @Test
    fun testNoMatchReturnsNull() {
        val localMedia = PlayableMedia.LocalNcm(
            id = "local_hash_4",
            title = "Song A",
            artist = "Artist A",
            album = "",
            uri = "file:///storage/emulated/0/Music/Song A.mp3",
            format = AudioFormat.MP3,
            isNcm = false
        )
        playlistManager.saveOrUpdateSyncedPlaylist(
            ncmPlaylistId = -9999L,
            name = "本地扫描文件夹",
            coverImgUrl = null,
            songs = listOf(localMedia)
        )

        val online = PlayableMedia.Online(
            id = "66666",
            title = "Completely Different Song",
            artist = "Different Artist",
            album = "Album"
        )

        val matched = playlistManager.findLocalMatch(online)
        assertNull(matched)
    }

    @Test
    fun testNumericArtistScanAndMatch() {
        val localBuzz = NcmFileScanner.scanUri(
            uri = android.net.Uri.parse("file:///storage/emulated/0/Music/BUZZ_CUTZ.flac"),
            displayName = "7_7 A2 akimalo Aquestion Chinatsu DJ Raisei fuluca maezawa Negimiso Sar7 - BUZZ CUTZ.flac"
        ) as PlayableMedia.LocalNcm

        val localAmanojaku = NcmFileScanner.scanUri(
            uri = android.net.Uri.parse("file:///storage/emulated/0/Music/Amanojaku.flac"),
            displayName = "164 GUMI - 天ノ弱.flac"
        ) as PlayableMedia.LocalNcm

        assertEquals(
            "7_7 A2 akimalo Aquestion Chinatsu DJ Raisei fuluca maezawa Negimiso Sar7",
            localBuzz.artist
        )
        assertEquals("BUZZ CUTZ", localBuzz.title)
        assertEquals("164 GUMI", localAmanojaku.artist)
        assertEquals("天ノ弱", localAmanojaku.title)

        playlistManager.saveOrUpdateSyncedPlaylist(
            ncmPlaylistId = -9999L,
            name = "本地扫描文件夹",
            coverImgUrl = null,
            songs = listOf(localBuzz, localAmanojaku)
        )

        val onlineBuzz = PlayableMedia.Online(
            id = "55551",
            title = "BUZZ CUTZ",
            artist = "7_7/A2/akimalo/Aquestion/Chinatsu/DJ Raisei/fuluca/maezawa/Negimiso/Sar7",
            album = "BUZZ CUTZ"
        )
        val onlineAmanojaku = PlayableMedia.Online(
            id = "55552",
            title = "天ノ弱",
            artist = "164/GUMI",
            album = "THEORY"
        )

        assertNotNull(playlistManager.findLocalMatch(onlineBuzz))
        assertNotNull(playlistManager.findLocalMatch(onlineAmanojaku))
    }

    @Test
    fun testLayer4LegacyTruncatedNumericArtistMatch() {
        // 兼容旧版扫描器已将开头数字截断后缓存在 custom_playlists.json 中的情况
        val legacyLocalBuzz = PlayableMedia.LocalNcm(
            id = "legacy_buzz",
            title = "BUZZ CUTZ",
            artist = "_7 A2 akimalo Aquestion Chinatsu DJ Raisei fuluca maezawa Negimiso Sar7",
            album = "",
            uri = "file:///storage/emulated/0/Music/BUZZ_CUTZ.flac",
            format = AudioFormat.FLAC,
            isNcm = false
        )
        val legacyLocalAmanojaku = PlayableMedia.LocalNcm(
            id = "legacy_amanojaku",
            title = "天ノ弱",
            artist = "GUMI",
            album = "",
            uri = "file:///storage/emulated/0/Music/Amanojaku.flac",
            format = AudioFormat.FLAC,
            isNcm = false
        )

        playlistManager.saveOrUpdateSyncedPlaylist(
            ncmPlaylistId = -9999L,
            name = "本地扫描文件夹",
            coverImgUrl = null,
            songs = listOf(legacyLocalBuzz, legacyLocalAmanojaku)
        )

        val onlineBuzz = PlayableMedia.Online(
            id = "55551",
            title = "BUZZ CUTZ",
            artist = "7_7/A2/akimalo/Aquestion/Chinatsu/DJ Raisei/fuluca/maezawa/Negimiso/Sar7",
            album = "BUZZ CUTZ"
        )
        val onlineAmanojaku = PlayableMedia.Online(
            id = "55552",
            title = "天ノ弱",
            artist = "164/GUMI",
            album = "THEORY"
        )

        assertEquals("legacy_buzz", playlistManager.findLocalMatch(onlineBuzz)?.id)
        assertEquals("legacy_amanojaku", playlistManager.findLocalMatch(onlineAmanojaku)?.id)
    }

    @Test
    fun testTitleSlashAndFullWidthVariantMatch() {
        // 场景 1：歌曲名中含有 '/' ("D/N/A")，本地文件名替换为空格 ("D N A")，且歌手名以数字 "25" 开头
        val localDna = NcmFileScanner.scanUri(
            uri = android.net.Uri.parse("file:///storage/emulated/0/Music/DNA.mp3"),
            displayName = "25時、ナイトコードで。 Azari 鏡音リン - D N A【25時、ナイトコードで。 × 鏡音リン】.mp3"
        ) as PlayableMedia.LocalNcm

        // 场景 2：云端为半角 '+' ("DIALOGUE+")，本地文件名为全角 '＋' ("DIALOGUE＋")
        val localSparkle = NcmFileScanner.scanUri(
            uri = android.net.Uri.parse("file:///storage/emulated/0/Music/Sparkle.mp3"),
            displayName = "愛美 伊藤美来 小笠原仁 梶原岳人 鈴木愛理 DIALOGUE＋ TrySail 仲村宗悟 花澤香菜 halca - Sparkle.mp3"
        ) as PlayableMedia.LocalNcm

        playlistManager.saveOrUpdateSyncedPlaylist(
            ncmPlaylistId = -9999L,
            name = "本地扫描文件夹",
            coverImgUrl = null,
            songs = listOf(localDna, localSparkle)
        )

        val onlineDna = PlayableMedia.Online(
            id = "66601",
            title = "D/N/A【25時、ナイトコードで。 × 鏡音リン】",
            artist = "25時、ナイトコードで。/Azari/鏡音リン",
            album = "D/N/A"
        )
        val onlineSparkle = PlayableMedia.Online(
            id = "66602",
            title = "Sparkle",
            artist = "愛美/伊藤美来/小笠原仁/梶原岳人/鈴木愛理/DIALOGUE+/TrySail/仲村宗悟/花澤香菜/halca",
            album = "Sparkle"
        )

        assertNotNull(playlistManager.findLocalMatch(onlineDna))
        assertNotNull(playlistManager.findLocalMatch(onlineSparkle))
    }
}