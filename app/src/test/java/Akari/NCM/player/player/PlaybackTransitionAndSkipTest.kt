package Akari.NCM.player.player

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.data.ThemePrefs
import Akari.NCM.player.data.UsbAudioManager
import Akari.NCM.player.data.UserSessionManager
import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackTransitionAndSkipTest {

    private lateinit var context: Context
    private lateinit var sessionManager: UserSessionManager
    private lateinit var ncmApi: NcmApi
    private lateinit var usbAudioManager: UsbAudioManager
    private lateinit var playerEngine: AmePlayerEngine

    private val samplePlaylist = listOf(
        PlayableMedia.LocalNcm(
            id = "track_0",
            title = "First Song",
            artist = "Artist A",
            album = "Album A",
            uri = "file:///storage/emulated/0/Music/first.flac",
            format = AudioFormat.FLAC,
            isNcm = false,
            durationMs = 180_000L
        ),
        PlayableMedia.LocalNcm(
            id = "track_1",
            title = "Second Song",
            artist = "Artist B",
            album = "Album B",
            uri = "file:///storage/emulated/0/Music/second.flac",
            format = AudioFormat.FLAC,
            isNcm = false,
            durationMs = 200_000L
        ),
        PlayableMedia.LocalNcm(
            id = "track_2",
            title = "Third Song",
            artist = "Artist C",
            album = "Album C",
            uri = "file:///storage/emulated/0/Music/third.flac",
            format = AudioFormat.FLAC,
            isNcm = false,
            durationMs = 210_000L
        )
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ame_theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        AppLogger.init(context)
        ThemePrefs.init(context)

        sessionManager = UserSessionManager(context)
        ncmApi = NcmApi(sessionManager)
        usbAudioManager = UsbAudioManager(context)
        playerEngine = AmePlayerEngine(context, ncmApi, usbAudioManager, sessionManager)
    }

    @After
    fun tearDown() {
        playerEngine.release()
    }

    @Test
    fun skipToPrevious_jumpsDirectlyToPreviousTrackOnFirstClickEvenPast3Seconds() {
        playerEngine.loadPlaylist(samplePlaylist, startIndex = 1)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, playerEngine.currentIndex.value)
        assertEquals("track_1", playerEngine.currentMedia.value?.id)

        // Simulate playback having progressed past 3000ms (15s into track_1)
        playerEngine.seekTo(15_000L)
        shadowOf(Looper.getMainLooper()).idle()

        // First click on Previous must immediately switch to track_0 instead of restarting track_1
        playerEngine.skipToPrevious()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, playerEngine.currentIndex.value)
        assertEquals("track_0", playerEngine.currentMedia.value?.id)
        assertEquals(0, playerEngine.player.currentMediaItemIndex)
    }

    @Test
    fun skipToPrevious_atFirstTrackWrapsToLastTrackOnFirstClick() {
        playerEngine.loadPlaylist(samplePlaylist, startIndex = 0)
        shadowOf(Looper.getMainLooper()).idle()

        playerEngine.seekTo(12_000L)
        shadowOf(Looper.getMainLooper()).idle()

        playerEngine.skipToPrevious()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, playerEngine.currentIndex.value)
        assertEquals("track_2", playerEngine.currentMedia.value?.id)
        assertEquals(2, playerEngine.player.currentMediaItemIndex)
    }

    @Test
    fun autoTransition_keepsContinuousPlaybackWithoutInterTrackPauseOrMute() {
        playerEngine.loadPlaylist(samplePlaylist, startIndex = 0)
        shadowOf(Looper.getMainLooper()).idle()

        // Ensure playback is active with full volume (disable fade so play() sets playWhenReady=true immediately)
        ThemePrefs.playbackFadeEnabled = false
        playerEngine.setVolume(0.8f)
        playerEngine.play()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(playerEngine.player.playWhenReady)
        assertEquals(0.8f, playerEngine.player.volume, 0.001f)

        // Re-enable fade (default user setting) to verify auto-transition does NOT pause or reset volume to 0
        ThemePrefs.playbackFadeEnabled = true

        // Simulate ExoPlayer firing natural AUTO media item transition to track_1
        val listenersField = playerEngine.player.javaClass.getDeclaredField("listeners")
        listenersField.isAccessible = true
        val listenerSet = listenersField.get(playerEngine.player)
        val nextMediaItem = playerEngine.player.getMediaItemAt(1)
        val queueEventMethod = listenerSet.javaClass.getDeclaredMethod(
            "sendEvent",
            Int::class.javaPrimitiveType,
            Class.forName("androidx.media3.common.util.ListenerSet\$Event")
        )
        // Invoke Player.Listener.onMediaItemTransition via ExoPlayer's ListenerSet
        val eventProxy = java.lang.reflect.Proxy.newProxyInstance(
            playerEngine.javaClass.classLoader,
            arrayOf(Class.forName("androidx.media3.common.util.ListenerSet\$Event"))
        ) { _, method, args ->
            if (method.name == "invoke") {
                val listener = args[0] as Player.Listener
                listener.onMediaItemTransition(nextMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            }
            null
        }
        queueEventMethod.invoke(listenerSet, Player.EVENT_MEDIA_ITEM_TRANSITION, eventProxy)
        shadowOf(Looper.getMainLooper()).idle()

        // ExoPlayer must stay playing continuously with no forced inter-track pause and unchanged volume
        assertTrue(
            "Natural auto-transition must NOT pause ExoPlayer",
            playerEngine.player.playWhenReady
        )
        assertFalse(
            "Natural auto-transition must NOT enter inter-track waiting state",
            playerEngine.loudnessState.value.isInterTrackWaiting
        )
        assertEquals(
            "Natural auto-transition must keep full volume without fade-in mute",
            0.8f,
            playerEngine.player.volume,
            0.001f
        )
        assertEquals("track_1", playerEngine.currentMedia.value?.id)
    }
}
