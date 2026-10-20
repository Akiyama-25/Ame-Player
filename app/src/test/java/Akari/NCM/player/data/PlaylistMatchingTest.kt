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
}