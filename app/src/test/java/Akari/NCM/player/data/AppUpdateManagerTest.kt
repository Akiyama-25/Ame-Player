package Akari.NCM.player.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdateManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testPackageVariantDetection() {
        assertEquals(AppPackageVariant.STANDARD, AppPackageVariant.fromContext(context))
        assertEquals(AppPackageVariant.STANDARD, AppPackageVariant.entries.find { it.packageName == "Akari.NCM.player" })
        assertEquals(AppPackageVariant.APPLE_MUSIC, AppPackageVariant.entries.find { it.packageName == "com.apple.android.music" })
        assertEquals(AppPackageVariant.SPOTIFY, AppPackageVariant.entries.find { it.packageName == "com.spotify.music" })
        assertEquals(AppPackageVariant.NETEASE, AppPackageVariant.entries.find { it.packageName == "com.netease.cloudmusic" })
    }

    @Test
    fun testReleaseAssetStrictPackageBinding() {
        val standard = AppPackageVariant.STANDARD
        val appleMusic = AppPackageVariant.APPLE_MUSIC
        val spotify = AppPackageVariant.SPOTIFY
        val netease = AppPackageVariant.NETEASE

        // Standard matches only standard/universal APKs
        assertTrue(standard.matchesReleaseAssetName("Ame-Player-release-0.2-universal-signed.apk"))
        assertTrue(standard.matchesReleaseAssetName("Ame-Player-v1.0.0-signed.apk"))
        assertFalse(standard.matchesReleaseAssetName("Ame-Player-v1.0.0-Apple_Music-signed.apk"))
        assertFalse(standard.matchesReleaseAssetName("Ame-Player-v1.0.0-Spotify-signed.apk"))
        assertFalse(standard.matchesReleaseAssetName("Ame-Player-v1.0.0-Netease_Music-signed.apk"))
        assertFalse(standard.matchesReleaseAssetName("SHA256SUMS.txt"))

        // Apple Music matches only Apple Music APKs
        assertTrue(appleMusic.matchesReleaseAssetName("Ame-Player-v1.0.0-Apple_Music-signed.apk"))
        assertFalse(appleMusic.matchesReleaseAssetName("Ame-Player-v1.0.0-signed.apk"))
        assertFalse(appleMusic.matchesReleaseAssetName("Ame-Player-v1.0.0-Spotify-signed.apk"))

        // Spotify matches only Spotify APKs
        assertTrue(spotify.matchesReleaseAssetName("Ame-Player-v1.0.0-Spotify-signed.apk"))
        assertFalse(spotify.matchesReleaseAssetName("Ame-Player-v1.0.0-signed.apk"))
        assertFalse(spotify.matchesReleaseAssetName("Ame-Player-v1.0.0-Apple_Music-signed.apk"))

        // NetEase matches only NetEase APKs
        assertTrue(netease.matchesReleaseAssetName("Ame-Player-v1.0.0-Netease_Music-signed.apk"))
        assertFalse(netease.matchesReleaseAssetName("Ame-Player-v1.0.0-signed.apk"))
        assertFalse(netease.matchesReleaseAssetName("Ame-Player-v1.0.0-Spotify-signed.apk"))
    }

    @Test
    fun testActionArtifactStrictPackageBinding() {
        val standard = AppPackageVariant.STANDARD
        val appleMusic = AppPackageVariant.APPLE_MUSIC
        val spotify = AppPackageVariant.SPOTIFY
        val netease = AppPackageVariant.NETEASE

        // Standard matches
        assertTrue(standard.matchesActionArtifactName("Ame-Player-Standard-signed"))
        assertTrue(standard.matchesActionArtifactName("Ame-Player-Standard-unsigned"))
        assertTrue(standard.matchesActionArtifactName("ame-player-release-signed-45"))
        assertFalse(standard.matchesActionArtifactName("Ame-Player-Apple_Music-signed"))
        assertFalse(standard.matchesActionArtifactName("Ame-Player-Spotify-signed"))
        assertFalse(standard.matchesActionArtifactName("Ame-Player-Netease_Music-signed"))

        // Apple Music matches
        assertTrue(appleMusic.matchesActionArtifactName("Ame-Player-Apple_Music-signed"))
        assertFalse(appleMusic.matchesActionArtifactName("Ame-Player-Standard-signed"))
        assertFalse(appleMusic.matchesActionArtifactName("Ame-Player-Spotify-signed"))

        // Spotify matches
        assertTrue(spotify.matchesActionArtifactName("Ame-Player-Spotify-signed"))
        assertFalse(spotify.matchesActionArtifactName("Ame-Player-Standard-signed"))
        assertFalse(spotify.matchesActionArtifactName("Ame-Player-Netease_Music-signed"))

        // NetEase matches
        assertTrue(netease.matchesActionArtifactName("Ame-Player-Netease_Music-signed"))
        assertFalse(netease.matchesActionArtifactName("Ame-Player-Standard-signed"))
        assertFalse(netease.matchesActionArtifactName("Ame-Player-Apple_Music-signed"))
    }

    @Test
    fun testUpdateChannelEnum() {
        assertEquals(UpdateChannel.RELEASE, UpdateChannel.fromKey("release"))
        assertEquals(UpdateChannel.ACTION, UpdateChannel.fromKey("action"))
        assertEquals(UpdateChannel.RELEASE, UpdateChannel.fromKey("unknown"))
    }
}