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
    fun testResolveActionQueryBranch() {
        assertEquals("main", AppUpdateManager.resolveActionQueryBranch("main", "branch"))
        assertEquals("dev/audio", AppUpdateManager.resolveActionQueryBranch("refs/heads/dev/audio", "branch"))
        // Tag / unknown / detached HEAD builds fall back to main
        assertEquals("main", AppUpdateManager.resolveActionQueryBranch("release-0.6", "tag"))
        assertEquals("main", AppUpdateManager.resolveActionQueryBranch("unknown", "branch"))
        assertEquals("main", AppUpdateManager.resolveActionQueryBranch("HEAD", "branch"))
    }

    @Test
    fun testIsNewerReleaseBuildUsesCommitAndBranchNotJustVersionName() {
        // Installed Action branch build already has versionName=release-0.6, then Release tag release-0.6 is published on a newer commit
        assertTrue(
            AppUpdateManager.isNewerReleaseBuild(
                remoteTag = "release-0.6",
                remoteCommitSha = "c0f13d9d6b1c25907f62a9707b991c048fe2d426",
                remoteCommitTimestamp = 1795663000L,
                currentVersion = "release-0.6",
                currentCommitSha = "efdd9eed1d472f990a131507f73ccf00d16511a8",
                currentCommitTimestamp = 1795660000L,
                currentBranch = "main",
                currentRefType = "branch"
            )
        )
        // Installed Action branch build moved AHEAD of Release tag release-0.6 (newer commit timestamp) -> do not downgrade
        assertFalse(
            AppUpdateManager.isNewerReleaseBuild(
                remoteTag = "release-0.6",
                remoteCommitSha = "c0f13d9d6b1c25907f62a9707b991c048fe2d426",
                remoteCommitTimestamp = 1795663000L,
                currentVersion = "release-0.6",
                currentCommitSha = "aaaaaaa1234567890",
                currentCommitTimestamp = 1795669000L,
                currentBranch = "main",
                currentRefType = "branch"
            )
        )
        // Exact same commit SHA -> UpToDate
        assertFalse(
            AppUpdateManager.isNewerReleaseBuild(
                remoteTag = "release-0.6",
                remoteCommitSha = "c0f13d9d6b1c25907f62a9707b991c048fe2d426",
                remoteCommitTimestamp = 1795663000L,
                currentVersion = "release-0.6",
                currentCommitSha = "c0f13d9",
                currentCommitTimestamp = 1795663000L,
                currentBranch = "main",
                currentRefType = "branch"
            )
        )
        // Older Release tag (release-0.5.1 < release-0.6) -> UpToDate
        assertFalse(
            AppUpdateManager.isNewerReleaseBuild(
                remoteTag = "release-0.5.1",
                remoteCommitSha = "876ecc0d636388c348b9b7d7e1f31903964db090",
                remoteCommitTimestamp = 1795200000L,
                currentVersion = "release-0.6",
                currentCommitSha = "efdd9ee",
                currentCommitTimestamp = 1795660000L,
                currentBranch = "main",
                currentRefType = "branch"
            )
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
        // Different commit with older timestamp than installed build -> not newer
        assertFalse(
            AppUpdateManager.isNewerActionBuild(
                remoteRunNumber = 108,
                remoteHeadSha = "2f263700c2bdfd215c1446c8db5e6c684e63630c",
                currentRunNumber = null,
                currentCommitSha = "1e15269",
                remoteCommitTimestamp = 1795660000L,
                currentCommitTimestamp = 1795665000L
            )
        )
        // Different commit with newer timestamp -> newer even without runNumber
        assertTrue(
            AppUpdateManager.isNewerActionBuild(
                remoteRunNumber = 109,
                remoteHeadSha = "c0f13d9d6b1c25907f62a9707b991c048fe2d426",
                currentRunNumber = null,
                currentCommitSha = "efdd9ee",
                remoteCommitTimestamp = 1795665000L,
                currentCommitTimestamp = 1795660000L
            )
        )
    }

    @Test
    fun testInstalledReleaseBuildOnActionChannelDoesNotLoopEvenIfTagCommitDiffers() {
        // Installed Release APK embeds commit branch "main", buildChannel="release", releaseTag="release-0.6"
        // Even if remote tag release-0.6 points to a newer docs/skip-ci commit, it must NOT re-prompt release-0.6
        assertFalse(
            AppUpdateManager.isNewerReleaseBuild(
                remoteTag = "release-0.6",
                remoteCommitSha = "988127411ccaf15c8f3afac7d26c1323b88d0777",
                remoteCommitTimestamp = 1795665270L,
                currentVersion = "release-0.6",
                currentCommitSha = "33391af724b129fee544427e5af4144c821b48df",
                currentCommitTimestamp = 1795663848L,
                currentBranch = "main",
                currentRefType = "branch",
                remoteBranch = "main",
                currentBuildChannel = "release",
                currentReleaseTag = "release-0.6"
            )
        )

        // Next Release tag (release-0.7) IS still detected as newer
        assertTrue(
            AppUpdateManager.isNewerReleaseBuild(
                remoteTag = "release-0.7",
                remoteCommitSha = "deadbeef1234567890",
                remoteCommitTimestamp = 1795700000L,
                currentVersion = "release-0.6",
                currentCommitSha = "33391af724b129fee544427e5af4144c821b48df",
                currentCommitTimestamp = 1795663848L,
                currentBranch = "main",
                currentRefType = "branch",
                remoteBranch = "main",
                currentBuildChannel = "release",
                currentReleaseTag = "release-0.6"
            )
        )

        // Release build's runNumber (#7) must NOT be compared against Action workflow's runNumber (#110)
        assertFalse(
            AppUpdateManager.isNewerActionBuild(
                remoteRunNumber = 110,
                remoteHeadSha = "33391af724b129fee544427e5af4144c821b48df",
                currentRunNumber = 7,
                currentCommitSha = "aaaaaaa1234567890",
                remoteCommitTimestamp = 1795663848L,
                currentCommitTimestamp = 1795665000L,
                currentRefType = "branch",
                currentBuildChannel = "release",
                remoteBranch = "main",
                currentBranch = "main"
            )
        )
        // Even if timestamps are equal/missing, Release build must not treat Action runNumber #110 > #7 as newer
        assertFalse(
            AppUpdateManager.isNewerActionBuild(
                remoteRunNumber = 110,
                remoteHeadSha = "33391af724b129fee544427e5af4144c821b48df",
                currentRunNumber = 7,
                currentCommitSha = "aaaaaaa1234567890",
                remoteCommitTimestamp = 0L,
                currentCommitTimestamp = 0L,
                currentRefType = "branch",
                currentBuildChannel = "release",
                remoteBranch = "main",
                currentBranch = "main"
            )
        )

        // A genuinely newer Action commit on the same commit branch IS still detected
        assertTrue(
            AppUpdateManager.isNewerActionBuild(
                remoteRunNumber = 111,
                remoteHeadSha = "bbbbbbb1234567890",
                currentRunNumber = 7,
                currentCommitSha = "aaaaaaa1234567890",
                remoteCommitTimestamp = 1795670000L,
                currentCommitTimestamp = 1795665000L,
                currentRefType = "branch",
                currentBuildChannel = "release",
                remoteBranch = "main",
                currentBranch = "main"
            )
        )
    }

    @Test
    fun testCommitBranchIsUsedAsDetectionBasisForReleaseAndAction() {
        // Release build built from a feature branch embeds that commit branch and queries it for Action updates
        assertEquals(
            "dev/audio",
            AppUpdateManager.resolveActionQueryBranch(
                currentBranch = "dev/audio",
                currentRefType = "branch",
                currentReleaseTag = "release-0.6"
            )
        )
        // Action build on a different branch ignores Release on main when Action branch is up to date
        val mainRelease = fakeUpdate(UpdateChannel.RELEASE, "release-0.6", branch = "main")
        val devUpToDate = UpdateCheckResult.UpToDate(UpdateChannel.ACTION, AppPackageVariant.STANDARD, "release-0.6")
        assertEquals(
            devUpToDate,
            AppUpdateManager.selectActionChannelUpdate(
                releaseResult = mainRelease,
                actionResult = devUpToDate,
                variant = AppPackageVariant.STANDARD,
                currentVersion = "release-0.6",
                currentBranch = "dev/audio"
            )
        )
        // Action build on a different commit branch does not treat same-version main Release as newer
        assertFalse(
            AppUpdateManager.isNewerReleaseBuild(
                remoteTag = "release-0.6",
                remoteCommitSha = "c0f13d9d6b1c25907f62a9707b991c048fe2d426",
                remoteCommitTimestamp = 1795665000L,
                currentVersion = "release-0.6",
                currentCommitSha = "efdd9ee",
                currentCommitTimestamp = 1795660000L,
                currentBranch = "dev/audio",
                currentRefType = "branch",
                remoteBranch = "main",
                currentBuildChannel = "action",
                currentReleaseTag = ""
            )
        )
        // Action build on main does not treat a newer run from another branch as newer
        assertFalse(
            AppUpdateManager.isNewerActionBuild(
                remoteRunNumber = 120,
                remoteHeadSha = "ccccccc1234567890",
                currentRunNumber = 110,
                currentCommitSha = "33391af",
                remoteCommitTimestamp = 1795680000L,
                currentCommitTimestamp = 1795660000L,
                currentRefType = "branch",
                currentBuildChannel = "action",
                remoteBranch = "dev/audio",
                currentBranch = "main"
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