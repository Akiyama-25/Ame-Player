package Akari.NCM.player.data

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.api.ResolvedSongUrl
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.AudioFormatInfo
import Akari.NCM.player.core.AudioQualityBadge
import Akari.NCM.player.core.AudioQualityClassifier
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.PlaybackState
import Akari.NCM.player.core.PlayerController
import Akari.NCM.player.core.PlayerState
import Akari.NCM.player.core.PlaylistSourceType
import Akari.NCM.player.core.QualityLevel
import Akari.NCM.player.core.RepeatMode
import Akari.NCM.player.core.UsbDeviceDetails
import Akari.NCM.player.core.UserProfile
import Akari.NCM.player.core.VipTier
import Akari.NCM.player.core.loudness.LoudnessNormalizationState
import Akari.NCM.player.ui.screen.PlayerScreen
import Akari.NCM.player.ui.screen.SettingsScreen
import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

private class FakeDownloadTestPlayer(
    track: PlayableMedia,
    formatInfo: AudioFormatInfo = AudioFormatInfo()
) : PlayerState, PlayerController {
    override val currentMedia = MutableStateFlow<PlayableMedia?>(track)
    override val isPlaying = MutableStateFlow(false)
    override val playbackState = MutableStateFlow(PlaybackState.READY)
    override val currentPositionMs = MutableStateFlow(45_000L)
    override val durationMs = MutableStateFlow(225_000L)
    override val volume = MutableStateFlow(1f)
    override val repeatMode = MutableStateFlow(RepeatMode.OFF)
    override val shuffleEnabled = MutableStateFlow(false)
    override val playlist = MutableStateFlow(listOf(track))
    override val currentIndex = MutableStateFlow(0)
    override val error = MutableStateFlow<String?>(null)
    override val audioFormatInfo = MutableStateFlow(formatInfo)
    override val isUsbExclusiveMode = MutableStateFlow(false)
    override val connectedUsbDeviceName = MutableStateFlow<String?>(null)
    override val usbUacVersion = MutableStateFlow<String?>("未连接")
    override val usbDeviceDetails = MutableStateFlow<UsbDeviceDetails?>(null)
    override val loudnessState = MutableStateFlow(LoudnessNormalizationState())
    override val playlistSourceType = MutableStateFlow(PlaylistSourceType.ONLINE)

    override fun load(media: PlayableMedia) {}
    override fun loadPlaylist(items: List<PlayableMedia>, startIndex: Int, sourceType: PlaylistSourceType) {}
    override fun play() {}
    override fun pause() {}
    override fun togglePlayPause() {}
    override fun seekTo(positionMs: Long) {}
    override fun skipToNext() {}
    override fun skipToPrevious() {}
    override fun skipToIndex(index: Int) {}
    override fun setRepeatMode(mode: RepeatMode) {}
    override fun toggleShuffle() {}
    override fun setVolume(volume: Float) {}
    override fun setUsbExclusiveMode(enabled: Boolean) {}
    override fun insertAsNext(media: PlayableMedia) {}
    override fun clearUrlCache() {}
    override fun release() {}
}

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadAndQualityDisplayTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var sessionManager: UserSessionManager
    private lateinit var playlistManager: PlaylistManager
    private lateinit var ncmApi: NcmApi
    private lateinit var downloadsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("ncm_user_session", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("ame_theme_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("local_folders", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "custom_playlists.json").delete()
        File(context.filesDir, "downloaded_tracks.json").delete()
        downloadsDir = File(context.cacheDir, "test_downloads").apply {
            deleteRecursively()
            mkdirs()
        }

        AppLogger.init(context)
        ThemePrefs.init(context)
        sessionManager = UserSessionManager(context)
        playlistManager = PlaylistManager(context)
        ncmApi = NcmApi(sessionManager)
    }

    @Test
    fun testAudioQualityClassifier_localRules_areStrictlyMutuallyExclusiveWithZeroOverlap() {
        val cases = listOf(
            // Dolby Atmos (E-AC-3 / AC-4), even if 6ch or high bitrate -> ONLY DOLBY
            AudioFormatInfo(bitrateKbps = 768, sampleRateHz = 48000, bitDepthBits = 24, mimeType = "audio/eac3-joc", channels = 6) to AudioQualityBadge.DOLBY,
            AudioFormatInfo(bitrateKbps = 512, sampleRateHz = 48000, bitDepthBits = 16, mimeType = "audio/ac4", channels = 2) to AudioQualityBadge.DOLBY,
            // Non-Dolby multi-channel (5.1 / 7.1) -> ONLY SKY
            AudioFormatInfo(bitrateKbps = 3200, sampleRateHz = 96000, bitDepthBits = 24, mimeType = "audio/flac", channels = 6) to AudioQualityBadge.SKY,
            AudioFormatInfo(bitrateKbps = 640, sampleRateHz = 48000, bitDepthBits = 16, mimeType = "audio/mp4a-latm", channels = 8) to AudioQualityBadge.SKY,
            // Lossless stereo 24-bit >= 176.4 kHz -> ONLY JYMASTER
            AudioFormatInfo(bitrateKbps = 4600, sampleRateHz = 192000, bitDepthBits = 24, mimeType = "audio/flac", channels = 2) to AudioQualityBadge.JYMASTER,
            AudioFormatInfo(bitrateKbps = 4200, sampleRateHz = 176400, bitDepthBits = 24, mimeType = "audio/wav", channels = 2) to AudioQualityBadge.JYMASTER,
            // Lossless stereo 24-bit < 176.4 kHz -> ONLY HIRES
            AudioFormatInfo(bitrateKbps = 2304, sampleRateHz = 96000, bitDepthBits = 24, mimeType = "audio/flac", channels = 2) to AudioQualityBadge.HIRES,
            AudioFormatInfo(bitrateKbps = 1500, sampleRateHz = 48000, bitDepthBits = 24, mimeType = "audio/flac", channels = 2) to AudioQualityBadge.HIRES,
            AudioFormatInfo(bitrateKbps = 1411, sampleRateHz = 44100, bitDepthBits = 24, mimeType = "audio/alac", channels = 2) to AudioQualityBadge.HIRES,
            // Lossless stereo < 24-bit (16-bit FLAC / WAV) -> ONLY LOSSLESS
            AudioFormatInfo(bitrateKbps = 950, sampleRateHz = 44100, bitDepthBits = 16, mimeType = "audio/flac", channels = 2) to AudioQualityBadge.LOSSLESS,
            AudioFormatInfo(bitrateKbps = 1411, sampleRateHz = 44100, bitDepthBits = 16, mimeType = "audio/wav", channels = 2) to AudioQualityBadge.LOSSLESS,
            // Lossy stereo partitioned by disjoint bitrate intervals
            AudioFormatInfo(bitrateKbps = 320, sampleRateHz = 44100, bitDepthBits = 16, mimeType = "audio/mpeg", channels = 2) to AudioQualityBadge.EXHIGH,
            AudioFormatInfo(bitrateKbps = 256, sampleRateHz = 44100, bitDepthBits = 16, mimeType = "audio/mp4a-latm", channels = 2) to AudioQualityBadge.EXHIGH,
            AudioFormatInfo(bitrateKbps = 192, sampleRateHz = 44100, bitDepthBits = 16, mimeType = "audio/mpeg", channels = 2) to AudioQualityBadge.HIGHER,
            AudioFormatInfo(bitrateKbps = 128, sampleRateHz = 44100, bitDepthBits = 16, mimeType = "audio/mpeg", channels = 2) to AudioQualityBadge.STANDARD
        )

        for ((info, expected) in cases) {
            val snapshot = AudioQualityClassifier.buildLocalFeatureSnapshot(info)
            val matchedRules = AudioQualityClassifier.localRules.filter { it.matches(snapshot) }
            assertEquals("Expected exactly 1 non-overlapping rule for $info, got ${matchedRules.map { it.badge }}", 1, matchedRules.size)
            assertEquals("Unexpected badge for $info", expected, matchedRules.first().badge)
        }
    }

    @Test
    fun testLocalAudioFileInspector_parsesFlacWavMp3HeadersAccurately() {
        // 1. 16-bit / 44.1 kHz / 2ch FLAC -> LOSSLESS
        val flac16 = buildSyntheticFlacBytes(sampleRate = 44100, channels = 2, bitsPerSample = 16, totalSamples = 44100L * 180L)
        val info16 = LocalAudioFileInspector.parseAudioStreamInfo(ByteArrayInputStream(flac16), fileSizeBytes = 20_000_000L)
        assertNotNull(info16)
        assertEquals(16, info16!!.bitDepthBits)
        assertEquals(44100, info16.sampleRateHz)
        assertEquals(2, info16.channels)
        assertEquals("audio/flac", info16.mimeType)
        assertEquals(AudioQualityBadge.LOSSLESS, AudioQualityClassifier.classifyLocal(info16))

        // 2. 24-bit / 96 kHz / 2ch FLAC -> HIRES
        val flac24 = buildSyntheticFlacBytes(sampleRate = 96000, channels = 2, bitsPerSample = 24, totalSamples = 96000L * 180L)
        val info24 = LocalAudioFileInspector.parseAudioStreamInfo(ByteArrayInputStream(flac24), fileSizeBytes = 55_000_000L)
        assertNotNull(info24)
        assertEquals(24, info24!!.bitDepthBits)
        assertEquals(96000, info24.sampleRateHz)
        assertEquals(AudioQualityBadge.HIRES, AudioQualityClassifier.classifyLocal(info24))

        // 3. 24-bit / 192 kHz / 2ch FLAC -> JYMASTER
        val flacMaster = buildSyntheticFlacBytes(sampleRate = 192000, channels = 2, bitsPerSample = 24, totalSamples = 192000L * 180L)
        val infoMaster = LocalAudioFileInspector.parseAudioStreamInfo(ByteArrayInputStream(flacMaster), fileSizeBytes = 110_000_000L)
        assertNotNull(infoMaster)
        assertEquals(24, infoMaster!!.bitDepthBits)
        assertEquals(192000, infoMaster.sampleRateHz)
        assertEquals(AudioQualityBadge.JYMASTER, AudioQualityClassifier.classifyLocal(infoMaster))

        // 4. 24-bit / 48 kHz / 6ch FLAC -> SKY (surround)
        val flacSurround = buildSyntheticFlacBytes(sampleRate = 48000, channels = 6, bitsPerSample = 24, totalSamples = 48000L * 180L)
        val infoSurround = LocalAudioFileInspector.parseAudioStreamInfo(ByteArrayInputStream(flacSurround), fileSizeBytes = 60_000_000L)
        assertNotNull(infoSurround)
        assertEquals(6, infoSurround!!.channels)
        assertEquals(AudioQualityBadge.SKY, AudioQualityClassifier.classifyLocal(infoSurround))

        // 5. WAV 24-bit / 96 kHz -> HIRES
        val wav24 = buildSyntheticWavBytes(sampleRate = 96000, channels = 2, bitsPerSample = 24)
        val wavInfo = LocalAudioFileInspector.parseAudioStreamInfo(ByteArrayInputStream(wav24), fileSizeBytes = 40_000_000L)
        assertNotNull(wavInfo)
        assertEquals(24, wavInfo!!.bitDepthBits)
        assertEquals(96000, wavInfo.sampleRateHz)
        assertEquals(AudioQualityBadge.HIRES, AudioQualityClassifier.classifyLocal(wavInfo))

        // 6. MP3 128k / 320k -> STANDARD / EXHIGH
        val mp3_128 = buildSyntheticMp3Bytes(bitrateIndex = 9) // 128 kbps
        val info128 = LocalAudioFileInspector.parseAudioStreamInfo(ByteArrayInputStream(mp3_128), fileSizeBytes = 3_000_000L)
        assertNotNull(info128)
        assertEquals(128, info128!!.bitrateKbps)
        assertEquals(AudioQualityBadge.STANDARD, AudioQualityClassifier.classifyLocal(info128))

        val mp3_320 = buildSyntheticMp3Bytes(bitrateIndex = 14) // 320 kbps
        val info320 = LocalAudioFileInspector.parseAudioStreamInfo(ByteArrayInputStream(mp3_320), fileSizeBytes = 8_000_000L)
        assertNotNull(info320)
        assertEquals(320, info320!!.bitrateKbps)
        assertEquals(AudioQualityBadge.EXHIGH, AudioQualityClassifier.classifyLocal(info320))
    }

    @Test
    fun testUserSessionManager_downloadSettingsDefaultsAndIndependence() {
        // Default values required by spec:
        // - auto download on favorite: OFF by default
        // - Wi-Fi only: ON by default
        // - separate default download quality
        assertFalse(sessionManager.isAutoDownloadOnFavoriteEnabled())
        assertTrue(sessionManager.isAutoDownloadWifiOnlyEnabled())
        assertEquals(QualityLevel.EXHIGH, sessionManager.getDownloadQualityLevel())

        // Log in as VIP user
        sessionManager.saveCookie("MUSIC_U=test_vip_cookie;")
        sessionManager.saveUserProfile(UserProfile(userId = 10L, nickname = "VipUser", vipType = 11, isSvip = false))

        // Playback quality and download quality must remain completely independent
        sessionManager.saveQualityLevel(QualityLevel.STANDARD)
        sessionManager.saveDownloadQualityLevel(QualityLevel.HIRES)
        assertEquals(QualityLevel.STANDARD, sessionManager.getEffectiveQualityLevel())
        assertEquals(QualityLevel.HIRES, sessionManager.getEffectiveDownloadQualityLevel())

        // VIP clamping applies independently to download quality
        sessionManager.saveDownloadQualityLevel(QualityLevel.JYMASTER)
        assertEquals(QualityLevel.HIRES, sessionManager.getEffectiveDownloadQualityLevel())
        assertEquals(QualityLevel.STANDARD, sessionManager.getEffectiveQualityLevel())

        // Persisting toggles works across manager instances
        sessionManager.setAutoDownloadOnFavoriteEnabled(true)
        sessionManager.setAutoDownloadWifiOnlyEnabled(false)
        assertEquals(
            "/storage/emulated/0/Download/netease/cloudmusic/Music/",
            sessionManager.getDownloadPath()
        )
        sessionManager.saveDownloadPath("/storage/emulated/0/Music/AmeCustom")
        assertEquals("/storage/emulated/0/Music/AmeCustom/", sessionManager.getDownloadPath())

        val reloaded = UserSessionManager(context)
        assertTrue(reloaded.isAutoDownloadOnFavoriteEnabled())
        assertFalse(reloaded.isAutoDownloadWifiOnlyEnabled())
        assertEquals(QualityLevel.JYMASTER, reloaded.getDownloadQualityLevel())
        assertEquals(QualityLevel.STANDARD, reloaded.getQualityLevel())
        assertEquals("/storage/emulated/0/Music/AmeCustom/", reloaded.getDownloadPath())

        // Reset restores NetEase Cloud Music default path
        reloaded.resetDownloadPath()
        assertEquals(
            "/storage/emulated/0/Download/netease/cloudmusic/Music/",
            reloaded.getDownloadPath()
        )

        // SAF tree URI display formatting
        assertEquals(
            "/storage/emulated/0/Music/HiRes/",
            UserSessionManager.formatDownloadPathForDisplay(
                "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FHiRes"
            )
        )

        // Android 10+ MediaStore relative path mapping for default NetEase path & custom Music path
        val defaultTarget = AudioDownloadManager.resolveMediaStoreTarget(UserSessionManager.DEFAULT_DOWNLOAD_PATH)
        assertNotNull(defaultTarget)
        assertEquals("Download/netease/cloudmusic/Music/", defaultTarget!!.second)

        val musicTarget = AudioDownloadManager.resolveMediaStoreTarget("/storage/emulated/0/Music/AmeCustom/")
        assertNotNull(musicTarget)
        assertEquals("Music/AmeCustom/", musicTarget!!.second)
    }

    @Test
    fun testAudioDownloadManager_manualDownloadAndAutoDownloadPolicies() = runBlocking {
        // Prepare synthetic 24-bit FLAC source file for local file:// download test
        val srcFlac = File(context.cacheDir, "mock_source_24bit.flac").apply {
            writeBytes(buildSyntheticFlacBytes(sampleRate = 96000, channels = 2, bitsPerSample = 24, totalSamples = 96000L * 180L))
        }
        val requestedQualities = CopyOnWriteArrayList<QualityLevel>()
        var isWifi = false

        sessionManager.saveCookie("MUSIC_U=test_vip_cookie;")
        sessionManager.saveUserProfile(UserProfile(userId = 20L, nickname = "VipUser", vipType = 11, isSvip = false))
        sessionManager.saveQualityLevel(QualityLevel.STANDARD) // Playback quality
        sessionManager.saveDownloadQualityLevel(QualityLevel.HIRES) // Separate default download quality

        val manager = AudioDownloadManager(
            context = context,
            ncmApi = ncmApi,
            sessionManager = sessionManager,
            playlistManager = playlistManager,
            downloadsDirOverride = downloadsDir,
            isWifiConnectedProvider = { isWifi },
            songUrlResolver = { _, q ->
                requestedQualities.add(q)
                ResolvedSongUrl(
                    url = Uri.fromFile(srcFlac).toString(),
                    bitrate = 2304,
                    sampleRate = 96000,
                    formatType = "flac",
                    level = q.value,
                    size = srcFlac.length(),
                    isFreeTrial = false
                )
            }
        )

        val song1 = PlayableMedia.Online(
            id = "10001",
            title = "Rainy Melody",
            artist = "Ame Artist",
            album = "Blue Sky",
            durationMs = 180_000L
        )

        // 1. Auto-download disabled by default -> SKIPPED_DISABLED
        assertEquals(AutoDownloadTriggerResult.SKIPPED_DISABLED, manager.maybeAutoDownloadOnFavorite(song1))
        assertTrue(requestedQualities.isEmpty())

        // 2. Enable auto-download, Wi-Fi-only still true, Wi-Fi disconnected -> SKIPPED_NOT_WIFI
        sessionManager.setAutoDownloadOnFavoriteEnabled(true)
        assertEquals(AutoDownloadTriggerResult.SKIPPED_NOT_WIFI, manager.maybeAutoDownloadOnFavorite(song1))
        assertTrue(requestedQualities.isEmpty())

        // 3. Connect Wi-Fi -> STARTED and uses DEFAULT DOWNLOAD QUALITY (HIRES), NOT playback quality (STANDARD)
        isWifi = true
        assertEquals(AutoDownloadTriggerResult.STARTED, manager.maybeAutoDownloadOnFavorite(song1))
        // Wait briefly for coroutine download to finish
        val deadline = System.currentTimeMillis() + 5_000L
        while (!manager.isDownloaded(song1.id) && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertTrue(manager.isDownloaded(song1.id))
        assertEquals(listOf(QualityLevel.HIRES), requestedQualities.toList())

        val record = manager.getDownloadedRecord(song1.id)
        assertNotNull(record)
        assertEquals(24, record!!.bitDepthBits)
        assertEquals(96000, record.sampleRateHz)
        assertTrue(File(record.filePath).exists())

        // Downloaded song must also be locally matchable via PlaylistManager
        val localMatch = playlistManager.findLocalMatch(song1)
        assertNotNull(localMatch)
        assertEquals(record.uri, localMatch!!.uri)

        // 4. Favoriting an already-downloaded track skips duplicate download
        assertEquals(AutoDownloadTriggerResult.SKIPPED_ALREADY_DOWNLOADED, manager.maybeAutoDownloadOnFavorite(song1))

        // 5. Disable Wi-Fi-only -> auto-download works even when Wi-Fi is off
        isWifi = false
        sessionManager.setAutoDownloadWifiOnlyEnabled(false)
        val song2 = song1.copy(id = "10002", title = "Cellular Track")
        assertEquals(AutoDownloadTriggerResult.STARTED, manager.maybeAutoDownloadOnFavorite(song2))
        val deadline2 = System.currentTimeMillis() + 5_000L
        while (!manager.isDownloaded(song2.id) && System.currentTimeMillis() < deadline2) {
            Thread.sleep(25)
        }
        assertTrue(manager.isDownloaded(song2.id))

        // 6. Manual blocking download with explicit quality (LOSSLESS) succeeds and overrides quality
        val song3 = song1.copy(id = "10003", title = "Manual Track")
        val manualRes = manager.downloadSongBlocking(song3, QualityLevel.LOSSLESS)
        assertTrue(manualRes.isSuccess)
        assertEquals("lossless", manualRes.getOrNull()?.requestedQuality)
        assertTrue(manager.isDownloaded(song3.id))

        // 7. Delete downloaded track removes file and record
        assertTrue(manager.deleteDownloadedTrack(song3.id))
        assertFalse(manager.isDownloaded(song3.id))
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun testPlayerScreen_progressBarDisplaysQualityBadgeForOnlineAndLocalTracks() {
        val onlineTrack = PlayableMedia.Online(
            id = "5001",
            title = "Online Hi-Res",
            artist = "Artist",
            album = "Album",
            durationMs = 225_000L
        )
        val controller = FakeDownloadTestPlayer(
            track = onlineTrack,
            formatInfo = AudioFormatInfo(
                bitrateKbps = 2304,
                sampleRateHz = 96000,
                bitDepthBits = 24,
                mimeType = "audio/flac",
                qualityLevel = "hires"
            )
        )

        composeTestRule.setContent {
            PlayerScreen(
                playerState = controller,
                playerController = controller,
                ncmApi = ncmApi,
                playlistManager = playlistManager,
                sessionManager = sessionManager,
                onBackClick = {},
                onAddToPlaylistClick = {},
                onToggleQueueClick = {}
            )
        }

        // Online Hi-Res badge between 00:45 and 03:45
        val startBounds = composeTestRule.onNodeWithText("00:45", useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val endBounds = composeTestRule.onNodeWithText("03:45", useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val badgeBounds = composeTestRule.onNodeWithTag("player_progress_quality_badge", useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        composeTestRule.onNodeWithText("Hi-Res", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "Quality badge ($badgeBounds) should sit horizontally between start ($startBounds) and end ($endBounds)",
            badgeBounds.left >= startBounds.right && badgeBounds.right <= endBounds.left
        )

        // Switch to Local 16-bit FLAC -> "无损"
        controller.currentMedia.value = PlayableMedia.LocalNcm(
            id = "local_16",
            title = "Local CD Flac",
            artist = "Local Artist",
            album = "Local Album",
            uri = "file:///music/cd.flac",
            format = AudioFormat.FLAC
        )
        controller.audioFormatInfo.value = AudioFormatInfo(
            bitrateKbps = 950,
            sampleRateHz = 44100,
            bitDepthBits = 16,
            mimeType = "audio/flac",
            channels = 2,
            qualityLevel = null
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("player_progress_quality_badge", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("无损", useUnmergedTree = true).assertIsDisplayed()

        // Switch to Local 24-bit 96kHz FLAC -> "Hi-Res"
        controller.audioFormatInfo.value = AudioFormatInfo(
            bitrateKbps = 2400,
            sampleRateHz = 96000,
            bitDepthBits = 24,
            mimeType = "audio/flac",
            channels = 2,
            qualityLevel = null
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hi-Res", useUnmergedTree = true).assertIsDisplayed()

        // Switch to Local 320kbps MP3 -> "极高"
        controller.currentMedia.value = PlayableMedia.LocalNcm(
            id = "local_mp3",
            title = "Local MP3",
            artist = "Local Artist",
            album = "Local Album",
            uri = "file:///music/high.mp3",
            format = AudioFormat.MP3
        )
        controller.audioFormatInfo.value = AudioFormatInfo(
            bitrateKbps = 320,
            sampleRateHz = 44100,
            bitDepthBits = 16,
            mimeType = "audio/mpeg",
            channels = 2,
            qualityLevel = null
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("极高", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w400dp-h1600dp")
    fun testPlayerScreen_downloadPromptsQualityDialogBeforeDownloading() {
        sessionManager.saveCookie("MUSIC_U=test_vip_cookie;")
        sessionManager.saveUserProfile(UserProfile(userId = 30L, nickname = "VipUser", vipType = 11, isSvip = false))
        sessionManager.saveDownloadQualityLevel(QualityLevel.LOSSLESS)

        val capturedQualities = CopyOnWriteArrayList<QualityLevel>()
        val srcFile = File(context.cacheDir, "mock_dl.flac").apply {
            writeBytes(buildSyntheticFlacBytes(sampleRate = 96000, channels = 2, bitsPerSample = 24, totalSamples = 96000L * 60L))
        }
        val downloadManager = AudioDownloadManager(
            context = context,
            ncmApi = ncmApi,
            sessionManager = sessionManager,
            playlistManager = playlistManager,
            downloadsDirOverride = downloadsDir,
            songUrlResolver = { _, q ->
                capturedQualities.add(q)
                ResolvedSongUrl(
                    url = Uri.fromFile(srcFile).toString(),
                    formatType = "flac",
                    level = q.value,
                    size = srcFile.length(),
                    isFreeTrial = false
                )
            }
        )

        val onlineTrack = PlayableMedia.Online(
            id = "6001",
            title = "Prompt Quality Song",
            artist = "Ame",
            album = "Album",
            durationMs = 200_000L
        )
        val controller = FakeDownloadTestPlayer(onlineTrack)

        composeTestRule.setContent {
            PlayerScreen(
                playerState = controller,
                playerController = controller,
                ncmApi = ncmApi,
                playlistManager = playlistManager,
                sessionManager = sessionManager,
                audioDownloadManager = downloadManager,
                onBackClick = {},
                onAddToPlaylistClick = {},
                onToggleQueueClick = {}
            )
        }

        // Open quick functions and click "下载音频"
        composeTestRule.onNodeWithTag("btn_quick_functions").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("quick_card_download_audio").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Quality prompt dialog MUST appear BEFORE any download starts
        composeTestRule.onNodeWithTag("download_quality_dialog").assertIsDisplayed()
        assertTrue(capturedQualities.isEmpty())

        // Pick Hi-Res and confirm download
        composeTestRule.onNodeWithTag("download_quality_option_hires").performClick()
        composeTestRule.onNodeWithTag("btn_confirm_download_quality").performClick()
        composeTestRule.waitForIdle()

        val deadline = System.currentTimeMillis() + 5_000L
        while (!downloadManager.isDownloaded(onlineTrack.id) && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertTrue(downloadManager.isDownloaded(onlineTrack.id))
        assertEquals(listOf(QualityLevel.HIRES), capturedQualities.toList())
    }

    @Test
    @Config(qualifiers = "w400dp-h3200dp")
    fun testSettingsScreen_downloadSettingsDefaultStatesAndIndependentQualitySelection() {
        sessionManager.saveCookie("MUSIC_U=test_vip_cookie;")
        sessionManager.saveUserProfile(UserProfile(userId = 40L, nickname = "VipUser", vipType = 11, isSvip = false))
        sessionManager.saveQualityLevel(QualityLevel.STANDARD)
        val localFolderManager = LocalFolderManager(context, sessionManager, playlistManager, ncmApi)

        composeTestRule.setContent {
            SettingsScreen(
                sessionManager = sessionManager,
                ncmApi = ncmApi,
                localFolderManager = localFolderManager,
                onBack = {}
            )
        }

        // Default states: auto-download OFF, Wi-Fi-only ON
        composeTestRule.onNodeWithTag("switch_auto_download_favorite").assertIsOff()
        composeTestRule.onNodeWithTag("switch_auto_download_wifi_only").assertIsOn()

        // Toggle auto-download ON and Wi-Fi-only OFF
        composeTestRule.onNodeWithTag("switch_auto_download_favorite").performClick()
        composeTestRule.onNodeWithTag("switch_auto_download_wifi_only").performClick()
        composeTestRule.waitForIdle()
        assertTrue(sessionManager.isAutoDownloadOnFavoriteEnabled())
        assertFalse(sessionManager.isAutoDownloadWifiOnlyEnabled())

        // Select independent default download quality -> does not modify playback quality
        composeTestRule.onNodeWithTag("setting_download_quality").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("选择默认下载音质").assertIsDisplayed()
        composeTestRule.onNodeWithTag("quality_option_lossless").performClick()
        composeTestRule.waitForIdle()

        assertEquals(QualityLevel.LOSSLESS, sessionManager.getDownloadQualityLevel())
        assertEquals(QualityLevel.STANDARD, sessionManager.getQualityLevel())

        // Verify default download path is NetEase original path and custom path dialog opens cleanly
        composeTestRule.onNodeWithText("/storage/emulated/0/Download/netease/cloudmusic/Music/").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setting_download_path").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("dialog_download_path").assertIsDisplayed()
        composeTestRule.onNodeWithTag("btn_save_download_path").performClick()
        composeTestRule.waitForIdle()
        assertEquals(UserSessionManager.DEFAULT_DOWNLOAD_PATH, sessionManager.getDownloadPath())
    }

    // ── Synthetic binary audio header builders ──

    private fun buildSyntheticFlacBytes(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        totalSamples: Long
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        // Last metadata block (0x80) | type 0 (STREAMINFO), length = 34
        out.write(byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22))
        val info = ByteArray(34)
        // Min/max block size (4096)
        info[0] = 0x10; info[1] = 0x00
        info[2] = 0x10; info[3] = 0x00
        // Sample rate (20 bits), channels-1 (3 bits), bps-1 (5 bits), totalSamples (36 bits)
        val chMinus1 = (channels - 1) and 0x07
        val bpsMinus1 = (bitsPerSample - 1) and 0x1F
        info[10] = ((sampleRate ushr 12) and 0xFF).toByte()
        info[11] = ((sampleRate ushr 4) and 0xFF).toByte()
        info[12] = (((sampleRate and 0x0F) shl 4) or (chMinus1 shl 1) or ((bpsMinus1 ushr 4) and 0x01)).toByte()
        info[13] = (((bpsMinus1 and 0x0F) shl 4) or (((totalSamples ushr 32) and 0x0FL).toInt())).toByte()
        info[14] = ((totalSamples ushr 24) and 0xFFL).toByte()
        info[15] = ((totalSamples ushr 16) and 0xFFL).toByte()
        info[16] = ((totalSamples ushr 8) and 0xFFL).toByte()
        info[17] = (totalSamples and 0xFFL).toByte()
        out.write(info)
        // Dummy audio payload
        out.write(ByteArray(64) { 0x55 })
        return out.toByteArray()
    }

    private fun buildSyntheticWavBytes(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(int32LE(36 + 64))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(int32LE(16))
        out.write(int16LE(1)) // PCM
        out.write(int16LE(channels))
        out.write(int32LE(sampleRate))
        out.write(int32LE(byteRate))
        out.write(int16LE(blockAlign))
        out.write(int16LE(bitsPerSample))
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(int32LE(64))
        out.write(ByteArray(64))
        return out.toByteArray()
    }

    private fun buildSyntheticMp3Bytes(bitrateIndex: Int): ByteArray {
        // ID3v2.3 empty header (10 bytes, size = 0) + MPEG-1 Layer III 44.1kHz stereo frame header
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 0))
        // 0xFF, 0xFB (sync + MPEG1 + Layer III + no CRC), (bitrateIndex << 4) | 0x00 (44.1kHz), 0x00 (Stereo)
        out.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), ((bitrateIndex and 0x0F) shl 4).toByte(), 0x00))
        out.write(ByteArray(128))
        return out.toByteArray()
    }

    private fun int16LE(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    private fun int32LE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )
}
