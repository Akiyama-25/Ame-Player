package Akari.NCM.player.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        ThemePrefs.init(context)
        ThemePrefs.autoCheckUpdate = true
        ThemePrefs.updateChannel = UpdateChannel.RELEASE
        AppUpdateManager.resetStartupCheckForTest()
    }

    @After
    fun tearDown() {
        AppUpdateManager.resetStartupCheckForTest()
        ThemePrefs.autoCheckUpdate = true
        ThemePrefs.updateChannel = UpdateChannel.RELEASE
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

    private fun fakeUpdate(
        channel: UpdateChannel,
        newVersion: String,
        branch: String,
        commitSha: String = ""
    ) = UpdateCheckResult.UpdateAvailable(
        channel = channel,
        variant = AppPackageVariant.STANDARD,
        currentVersion = "release-0.5",
        newVersion = newVersion,
        title = newVersion,
        releaseNotes = "notes",
        publishDate = "2026-11-25 12:00",
        downloadUrl = "https://example.com/$newVersion.apk",
        webUrl = "https://example.com/$newVersion",
        buildBranch = branch,
        commitSha = commitSha
    )

    @Test
    fun testActionChannelPrioritizesReleaseWhenBuildBranchMatches() {
        val releaseUpdate = fakeUpdate(UpdateChannel.RELEASE, "release-0.6", branch = "main")
        val actionUpdate = fakeUpdate(UpdateChannel.ACTION, "Action Build #108 (1e15269)", branch = "main", commitSha = "1e152699b1acfc")

        val selected = AppUpdateManager.selectActionChannelUpdate(
            releaseResult = releaseUpdate,
            actionResult = actionUpdate,
            variant = AppPackageVariant.STANDARD,
            currentVersion = "release-0.5"
        )
        assertEquals(releaseUpdate, selected)

        // Also handles refs/heads/ prefix normalization
        val prefixedRelease = releaseUpdate.copy(buildBranch = "refs/heads/main")
        assertEquals(
            prefixedRelease,
            AppUpdateManager.selectActionChannelUpdate(
                releaseResult = prefixedRelease,
                actionResult = actionUpdate,
                variant = AppPackageVariant.STANDARD,
                currentVersion = "release-0.5"
            )
        )

        // Also handles commit SHA as target_commitish
        val shaRelease = releaseUpdate.copy(buildBranch = "1e15269")
        assertEquals(
            shaRelease,
            AppUpdateManager.selectActionChannelUpdate(
                releaseResult = shaRelease,
                actionResult = actionUpdate,
                variant = AppPackageVariant.STANDARD,
                currentVersion = "release-0.5"
            )
        )
    }

    @Test
    fun testActionChannelUsesActionWhenBuildBranchDiffers() {
        val releaseUpdate = fakeUpdate(UpdateChannel.RELEASE, "release-0.6", branch = "release")
        val actionUpdate = fakeUpdate(UpdateChannel.ACTION, "Action Build #108 (1e15269)", branch = "main", commitSha = "1e152699b1acfc")

        val selected = AppUpdateManager.selectActionChannelUpdate(
            releaseResult = releaseUpdate,
            actionResult = actionUpdate,
            variant = AppPackageVariant.STANDARD,
            currentVersion = "release-0.5"
        )
        assertEquals(actionUpdate, selected)
    }

    @Test
    fun testActionChannelFallbacksWhenOnlyOneChannelHasUpdate() {
        val releaseUpdate = fakeUpdate(UpdateChannel.RELEASE, "release-0.6", branch = "main")
        val actionUpdate = fakeUpdate(UpdateChannel.ACTION, "Action Build #109 (abcdef1)", branch = "main")
        val releaseUpToDate = UpdateCheckResult.UpToDate(UpdateChannel.RELEASE, AppPackageVariant.STANDARD, "release-0.6")
        val actionUpToDate = UpdateCheckResult.UpToDate(UpdateChannel.ACTION, AppPackageVariant.STANDARD, "release-0.6")
        val error = UpdateCheckResult.Error("HTTP 403")

        // Only Action has update (e.g., already on latest Release, newer Action commit available)
        assertEquals(
            actionUpdate,
            AppUpdateManager.selectActionChannelUpdate(releaseUpToDate, actionUpdate, AppPackageVariant.STANDARD, "release-0.6")
        )
        // Only Release has update (Action errored or up-to-date)
        assertEquals(
            releaseUpdate,
            AppUpdateManager.selectActionChannelUpdate(releaseUpdate, error, AppPackageVariant.STANDARD, "release-0.5")
        )
        // Release errored, Action up-to-date -> UpToDate(ACTION)
        assertEquals(
            actionUpToDate,
            AppUpdateManager.selectActionChannelUpdate(error, actionUpToDate, AppPackageVariant.STANDARD, "release-0.6")
        )
        // Release up-to-date, Action errored -> Error
        assertEquals(
            error,
            AppUpdateManager.selectActionChannelUpdate(releaseUpToDate, error, AppPackageVariant.STANDARD, "release-0.6")
        )
    }

    @Test
    fun testIsNewerActionBuildDeduplicatesMatchingCommitSha() {
        // Same commit SHA should not be treated as newer even if runNumber differs across workflows
        assertFalse(
            AppUpdateManager.isNewerActionBuild(
                remoteRunNumber = 107,
                remoteHeadSha = "1e152699b1acfc104b0335b7ca4d5b573d52529b",
                currentRunNumber = 6,
                currentCommitSha = "1e15269"
            )
        )
        // Different commit and higher run number -> newer
        assertTrue(
            AppUpdateManager.isNewerActionBuild(
                remoteRunNumber = 108,
                remoteHeadSha = "2f263700c2bdfd215c1446c8db5e6c684e63630c",
                currentRunNumber = 107,
                currentCommitSha = "1e15269"
            )
        )
        // Different commit and lower/equal run number -> not newer
        assertFalse(
            AppUpdateManager.isNewerActionBuild(
                remoteRunNumber = 107,
                remoteHeadSha = "2f263700c2bdfd215c1446c8db5e6c684e63630c",
                currentRunNumber = 107,
                currentCommitSha = "1e15269"
            )
        )
    }

    @Test
    fun testProactiveStartupUpdateCheckLifecycle() = runBlocking {
        ThemePrefs.updateChannel = UpdateChannel.ACTION
        ThemePrefs.autoCheckUpdate = true

        val expectedUpdate = fakeUpdate(UpdateChannel.RELEASE, "release-0.6", branch = "main")
        var invocations = 0
        var receivedChannel: UpdateChannel? = null

        val first = AppUpdateManager.checkUpdateOnStartup(context) { _, channel ->
            invocations++
            receivedChannel = channel
            expectedUpdate
        }
        assertEquals(expectedUpdate, first)
        assertEquals(UpdateChannel.ACTION, receivedChannel)
        assertEquals(expectedUpdate, AppUpdateManager.pendingStartupUpdate.value)
        assertEquals(1, invocations)

        // Second call without force should not re-trigger network check
        val second = AppUpdateManager.checkUpdateOnStartup(context) { _, _ ->
            invocations++
            expectedUpdate
        }
        assertEquals(expectedUpdate, second)
        assertEquals(1, invocations)

        // Dismissing clears pending state
        AppUpdateManager.dismissStartupUpdate()
        assertNull(AppUpdateManager.pendingStartupUpdate.value)
    }

    @Test
    fun testProactiveStartupUpdateCheckRespectsDisablePreferenceAndStaysSilentOnNoUpdate() = runBlocking {
        // Disabled preference skips check completely
        ThemePrefs.autoCheckUpdate = false
        var invocations = 0
        val skipped = AppUpdateManager.checkUpdateOnStartup(context) { _, _ ->
            invocations++
            fakeUpdate(UpdateChannel.RELEASE, "release-0.6", branch = "main")
        }
        assertNull(skipped)
        assertEquals(0, invocations)
        assertNull(AppUpdateManager.pendingStartupUpdate.value)

        // Enabled preference with UpToDate / Error stays silent
        ThemePrefs.autoCheckUpdate = true
        val upToDateRes = AppUpdateManager.checkUpdateOnStartup(context, force = true) { _, _ ->
            invocations++
            UpdateCheckResult.UpToDate(UpdateChannel.RELEASE, AppPackageVariant.STANDARD, "release-0.6")
        }
        assertNull(upToDateRes)
        assertNull(AppUpdateManager.pendingStartupUpdate.value)
        assertEquals(1, invocations)

        val errorRes = AppUpdateManager.checkUpdateOnStartup(context, force = true) { _, _ ->
            invocations++
            UpdateCheckResult.Error("network down")
        }
        assertNull(errorRes)
        assertNull(AppUpdateManager.pendingStartupUpdate.value)
        assertEquals(2, invocations)
    }
}