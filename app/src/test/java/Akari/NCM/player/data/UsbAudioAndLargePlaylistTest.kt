package Akari.NCM.player.data

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.player.AmePlayerEngine
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsbAudioAndLargePlaylistTest {

    private lateinit var context: Context
    private lateinit var sessionManager: UserSessionManager
    private lateinit var ncmApi: NcmApi
    private val enginesToRelease = mutableListOf<AmePlayerEngine>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ame_theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "custom_playlists.json").delete()
        AppLogger.init(context)
        ThemePrefs.init(context)
        sessionManager = UserSessionManager(context)
        ncmApi = NcmApi(sessionManager)
    }

    @After
    fun tearDown() {
        enginesToRelease.forEach { runCatching { it.release() } }
        enginesToRelease.clear()
    }

    private fun buildSampleTracks(count: Int): List<PlayableMedia.LocalNcm> {
        return (0 until count).map { idx ->
            PlayableMedia.LocalNcm(
                id = "track_$idx",
                title = "Track $idx",
                artist = "Artist ${idx % 20}",
                album = "Album ${idx % 10}",
                uri = "file:///storage/emulated/0/Music/track_$idx.flac",
                format = AudioFormat.FLAC,
                isNcm = false,
                durationMs = 180_000L
            )
        }
    }

    @Test
    fun testPlaylistManager_persistsAndReloadsOver500Tracks() {
        val tracks = buildSampleTracks(650)
        val manager1 = PlaylistManager(context)
        manager1.saveOrUpdateSyncedPlaylist(
            ncmPlaylistId = 987654L,
            name = "Large Synced Playlist",
            coverImgUrl = null,
            songs = tracks
        )
        assertEquals(650, manager1.playlists.value.first().songs.size)

        val manager2 = PlaylistManager(context)
        val reloaded = manager2.playlists.value.find { it.ncmPlaylistId == 987654L }
        assertEquals(650, reloaded?.songs?.size)
        assertEquals("track_649", reloaded?.songs?.last()?.id)
    }

    @Test
    fun testAmePlayerEngine_persistsAndRestoresOver500TracksWithoutTruncation() {
        val tracks = buildSampleTracks(650)
        val usbAudioManager = UsbAudioManager(context)
        val engine1 = AmePlayerEngine(context, ncmApi, usbAudioManager, sessionManager)
        enginesToRelease += engine1

        engine1.loadPlaylist(tracks, startIndex = 600)
        assertEquals(650, engine1.playlist.value.size)
        assertEquals(600, engine1.currentIndex.value)
        assertEquals("track_600", engine1.currentMedia.value?.id)
        engine1.release()

        // Simulate app restart restoring saved playback state
        val engine2 = AmePlayerEngine(context, ncmApi, usbAudioManager, sessionManager)
        enginesToRelease += engine2

        assertEquals(650, engine2.playlist.value.size)
        assertEquals(600, engine2.currentIndex.value)
        assertEquals("track_600", engine2.currentMedia.value?.id)
        assertEquals("track_649", engine2.playlist.value.last().id)
    }

    @Test
    fun testUsbAudioManager_resetsStaleExclusiveSwitchOnInitWithoutAuthorizedDevice() {
        ThemePrefs.usbExclusiveMode = true
        val manager = UsbAudioManager(context)

        assertFalse(
            "Exclusive switch must reset to OFF on startup when no authorized USB DAC is connected",
            manager.isExclusiveModeEnabled.value
        )
        assertFalse(
            "ThemePrefs.usbExclusiveMode must also be reset when no authorized USB DAC is connected",
            ThemePrefs.usbExclusiveMode
        )
    }

    @Test
    fun testUsbAudioManager_doesNotAutoEnableOnUnauthorizedUsbAttachBroadcast() {
        val manager = UsbAudioManager(context)
        assertFalse(manager.isExclusiveModeEnabled.value)

        // Simulate stale/pre-armed preference before an unauthorized USB attach broadcast
        ThemePrefs.usbExclusiveMode = true
        val attachIntent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(attachIntent)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse(
            "Attaching an unauthorized USB device must not turn ON exclusive mode",
            manager.isExclusiveModeEnabled.value
        )
        assertFalse(ThemePrefs.usbExclusiveMode)
    }

    @Test
    fun testUsbAudioManager_setExclusiveModeWithoutDeviceDoesNotClaimHardwareExclusive() {
        val manager = UsbAudioManager(context)
        manager.setExclusiveModeEnabled(true)

        // Without any connected & permitted device, exclusive hardware claim cannot be true
        assertFalse(manager.usbDeviceDetails.value?.isExclusiveClaimed == true)

        val detachIntent = Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(detachIntent)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse(manager.isExclusiveModeEnabled.value)
        assertFalse(ThemePrefs.usbExclusiveMode)
    }
}
