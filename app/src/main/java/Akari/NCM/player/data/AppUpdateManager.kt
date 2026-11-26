package Akari.NCM.player.data

import Akari.NCM.player.BuildConfig
import Akari.NCM.player.core.AppLogger
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

enum class AppPackageVariant(
    val packageName: String,
    val displayName: String,
    val identifier: String
) {
    STANDARD("Akari.NCM.player", "标准版", "Standard"),
    APPLE_MUSIC("com.apple.android.music", "Apple Music 伪装版", "Apple_Music"),
    SPOTIFY("com.spotify.music", "Spotify 伪装版", "Spotify"),
    NETEASE("com.netease.cloudmusic", "网易云音乐 伪装版", "Netease_Music");

    companion object {
        fun fromContext(context: Context): AppPackageVariant {
            val pkg = context.packageName
            return entries.find { it.packageName.equals(pkg, ignoreCase = true) } ?: STANDARD
        }
    }
}

fun AppPackageVariant.matchesReleaseAssetName(fileName: String): Boolean {
    val name = fileName.lowercase()
    if (!name.endsWith(".apk")) return false
    return when (this) {
        AppPackageVariant.STANDARD -> {
            !name.contains("apple_music") &&
            !name.contains("spotify") &&
            !name.contains("netease_music") &&
            !name.contains("32bit") &&
            !name.contains("armeabi")
        }
        AppPackageVariant.APPLE_MUSIC -> name.contains("apple_music")
        AppPackageVariant.SPOTIFY -> name.contains("spotify")
        AppPackageVariant.NETEASE -> name.contains("netease_music")
    }
}

fun AppPackageVariant.matchesActionArtifactName(artifactName: String): Boolean {
    val name = artifactName.lowercase()
    return when (this) {
        AppPackageVariant.STANDARD -> {
            name.contains("standard") ||
            (name.contains("release-signed") && !name.contains("apple") && !name.contains("spotify") && !name.contains("netease"))
        }
        AppPackageVariant.APPLE_MUSIC -> name.contains("apple_music")
        AppPackageVariant.SPOTIFY -> name.contains("spotify")
        AppPackageVariant.NETEASE -> name.contains("netease_music")
    }
}

sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val channel: UpdateChannel,
        val variant: AppPackageVariant,
        val currentVersion: String,
        val newVersion: String,
        val title: String,
        val releaseNotes: String,
        val publishDate: String,
        val downloadUrl: String,
        val webUrl: String,
        val fileSize: Long = 0L,
        val fileName: String = "",
        val buildBranch: String = "",
        val commitSha: String = ""
    ) : UpdateCheckResult()

    data class UpToDate(
        val channel: UpdateChannel,
        val variant: AppPackageVariant,
        val currentVersion: String
    ) : UpdateCheckResult()

    data class Error(
        val message: String
    ) : UpdateCheckResult()
}

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val GITHUB_REPO = "Akiyama-25/Ame-Player"

    private val startupCheckTriggered = AtomicBoolean(false)
    private val _pendingStartupUpdate = MutableStateFlow<UpdateCheckResult.UpdateAvailable?>(null)
    val pendingStartupUpdate: StateFlow<UpdateCheckResult.UpdateAvailable?> = _pendingStartupUpdate.asStateFlow()

    fun dismissStartupUpdate() {
        _pendingStartupUpdate.value = null
    }

    suspend fun checkUpdateOnStartup(
        context: Context,
        force: Boolean = false,
        checker: suspend (Context, UpdateChannel) -> UpdateCheckResult = ::checkUpdate
    ): UpdateCheckResult.UpdateAvailable? {
        if (!ThemePrefs.autoCheckUpdate) {
            AppLogger.i(TAG, "Proactive startup update check skipped: autoCheckUpdate is disabled")
            return null
        }
        if (!force && !startupCheckTriggered.compareAndSet(false, true)) {
            return _pendingStartupUpdate.value
        }

        val channel = ThemePrefs.updateChannel
        AppLogger.i(TAG, "Starting proactive startup update check: channel=${channel.name}")
        val result = runCatching {
            checker(context, channel)
        }.getOrElse { e ->
            AppLogger.w(TAG, "Proactive startup update check failed silently: ${e.message}")
            return null
        }

        return when (result) {
            is UpdateCheckResult.UpdateAvailable -> {
                _pendingStartupUpdate.value = result
                result
            }
            is UpdateCheckResult.UpToDate -> {
                AppLogger.i(TAG, "Proactive startup update check: already up to date (${result.currentVersion})")
                null
            }
            is UpdateCheckResult.Error -> {
                AppLogger.w(TAG, "Proactive startup update check error (silent): ${result.message}")
                null
            }
        }
    }

    internal fun resetStartupCheckForTest() {
        startupCheckTriggered.set(false)
        _pendingStartupUpdate.value = null
    }

    internal fun setPendingStartupUpdateForTest(update: UpdateCheckResult.UpdateAvailable?) {
        _pendingStartupUpdate.value = update
    }

    suspend fun checkUpdate(context: Context, channel: UpdateChannel): UpdateCheckResult = withContext(Dispatchers.IO) {
        val variant = AppPackageVariant.fromContext(context)
        val currentVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: BuildConfig.VERSION_NAME

        AppLogger.i(TAG, "Starting update check: channel=${channel.name}, package=${variant.packageName} (${variant.displayName}), currentVersion=$currentVersion")

        try {
            when (channel) {
                UpdateChannel.RELEASE -> checkReleaseUpdate(variant, currentVersion)
                UpdateChannel.ACTION -> checkActionChannelUpdate(variant, currentVersion)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Check update failed: ${e.message}", e)
            UpdateCheckResult.Error(e.message ?: "检查更新网络异常，请重试")
        }
    }

    private suspend fun checkActionChannelUpdate(
        variant: AppPackageVariant,
        currentVersion: String
    ): UpdateCheckResult = coroutineScope {
        val releaseDeferred = async(Dispatchers.IO) {
            runCatching { checkReleaseUpdate(variant, currentVersion) }
                .getOrElse { e ->
                    AppLogger.w(TAG, "Release channel check during Action check failed: ${e.message}")
                    UpdateCheckResult.Error(e.message ?: "GitHub Release 检查异常")
                }
        }
        val actionDeferred = async(Dispatchers.IO) {
            runCatching { checkActionUpdate(variant, currentVersion) }
                .getOrElse { e ->
                    AppLogger.w(TAG, "Action channel check failed: ${e.message}")
                    UpdateCheckResult.Error(e.message ?: "GitHub Actions 检查异常")
                }
        }

        val releaseResult = releaseDeferred.await()
        val actionResult = actionDeferred.await()
        selectActionChannelUpdate(releaseResult, actionResult, variant, currentVersion)
    }

    internal fun normalizeBranchName(branch: String): String =
        branch.trim().removePrefix("refs/heads/").trim()

    internal fun isSameBuildBranch(
        releaseBranch: String,
        actionBranch: String,
        actionCommitSha: String = ""
    ): Boolean {
        val normRelease = normalizeBranchName(releaseBranch)
        val normAction = normalizeBranchName(actionBranch)
        if (normRelease.isNotBlank() && normAction.isNotBlank() &&
            normRelease.equals(normAction, ignoreCase = true)
        ) {
            return true
        }
        val normSha = actionCommitSha.trim()
        if (normRelease.isNotBlank() && normSha.isNotBlank() &&
            (normSha.startsWith(normRelease, ignoreCase = true) ||
                normRelease.startsWith(normSha, ignoreCase = true))
        ) {
            return true
        }
        return false
    }

    internal fun selectActionChannelUpdate(
        releaseResult: UpdateCheckResult,
        actionResult: UpdateCheckResult,
        variant: AppPackageVariant,
        currentVersion: String
    ): UpdateCheckResult {
        return when {
            releaseResult is UpdateCheckResult.UpdateAvailable &&
                actionResult is UpdateCheckResult.UpdateAvailable -> {
                if (isSameBuildBranch(releaseResult.buildBranch, actionResult.buildBranch, actionResult.commitSha)) {
                    AppLogger.i(
                        TAG,
                        "Action channel: both Release (${releaseResult.newVersion}, branch=${releaseResult.buildBranch}) " +
                            "and Action (${actionResult.newVersion}, branch=${actionResult.buildBranch}) available on same branch; prioritizing Release"
                    )
                    releaseResult
                } else {
                    AppLogger.i(
                        TAG,
                        "Action channel: Release (branch=${releaseResult.buildBranch}) and Action (branch=${actionResult.buildBranch}) differ; using Action"
                    )
                    actionResult
                }
            }
            releaseResult is UpdateCheckResult.UpdateAvailable -> releaseResult
            actionResult is UpdateCheckResult.UpdateAvailable -> actionResult
            actionResult is UpdateCheckResult.UpToDate ->
                UpdateCheckResult.UpToDate(UpdateChannel.ACTION, variant, currentVersion)
            actionResult is UpdateCheckResult.Error -> actionResult
            releaseResult is UpdateCheckResult.UpToDate ->
                UpdateCheckResult.UpToDate(UpdateChannel.ACTION, variant, currentVersion)
            else -> releaseResult
        }
    }

    internal fun isNewerActionBuild(
        remoteRunNumber: Int,
        remoteHeadSha: String,
        currentRunNumber: Int?,
        currentCommitSha: String
    ): Boolean {
        val cleanCurrentSha = currentCommitSha.trim()
        val cleanRemoteSha = remoteHeadSha.trim()
        if (cleanCurrentSha.isNotBlank() && cleanRemoteSha.isNotBlank() &&
            (cleanRemoteSha.startsWith(cleanCurrentSha, ignoreCase = true) ||
                cleanCurrentSha.startsWith(cleanRemoteSha, ignoreCase = true))
        ) {
            return false
        }
        return when {
            currentRunNumber != null && currentRunNumber > 0 -> remoteRunNumber > currentRunNumber
            cleanCurrentSha.isNotBlank() && cleanRemoteSha.isNotBlank() -> true
            else -> true
        }
    }

    private fun checkReleaseUpdate(variant: AppPackageVariant, currentVersion: String): UpdateCheckResult {
        val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "AmePlayer-App")
        }

        if (conn.responseCode != 200) {
            return UpdateCheckResult.Error("GitHub Release 服务异常: HTTP ${conn.responseCode}")
        }

        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val releases = JSONArray(text)
        if (releases.length() == 0) {
            return UpdateCheckResult.UpToDate(UpdateChannel.RELEASE, variant, currentVersion)
        }

        var latestRelease: JSONObject? = null
        for (i in 0 until releases.length()) {
            val rel = releases.getJSONObject(i)
            if (!rel.optBoolean("draft", false)) {
                latestRelease = rel
                break
            }
        }

        if (latestRelease == null) {
            return UpdateCheckResult.UpToDate(UpdateChannel.RELEASE, variant, currentVersion)
        }

        val tagName = latestRelease.getString("tag_name")
        val title = latestRelease.optString("name", tagName)
        val body = latestRelease.optString("body", "暂无更新日志")
        val publishedAt = latestRelease.optString("published_at", "")
        val htmlUrl = latestRelease.optString("html_url", "https://github.com/$GITHUB_REPO/releases")
        val targetCommitish = normalizeBranchName(latestRelease.optString("target_commitish", ""))
        val assets = latestRelease.optJSONArray("assets") ?: JSONArray()

        var matchingAsset: JSONObject? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (variant.matchesReleaseAssetName(name)) {
                matchingAsset = asset
                break
            }
        }

        if (matchingAsset == null) {
            AppLogger.w(TAG, "No matching APK asset found for variant ${variant.displayName} in release $tagName")
            return UpdateCheckResult.Error("最新正式版本 ($tagName) 暂未找到针对包名「${variant.packageName}」的安装包")
        }

        val isNewer = isNewerVersion(tagName, currentVersion)
        if (isNewer) {
            return UpdateCheckResult.UpdateAvailable(
                channel = UpdateChannel.RELEASE,
                variant = variant,
                currentVersion = currentVersion,
                newVersion = tagName,
                title = title,
                releaseNotes = body,
                publishDate = formatIsoDate(publishedAt),
                downloadUrl = matchingAsset.getString("browser_download_url"),
                webUrl = htmlUrl,
                fileSize = matchingAsset.optLong("size", 0L),
                fileName = matchingAsset.getString("name"),
                buildBranch = targetCommitish
            )
        } else {
            return UpdateCheckResult.UpToDate(UpdateChannel.RELEASE, variant, currentVersion)
        }
    }

    private fun checkActionUpdate(variant: AppPackageVariant, currentVersion: String): UpdateCheckResult {
        // Query latest successful runs on main branch
        val runsUrl = URL("https://api.github.com/repos/$GITHUB_REPO/actions/workflows/build-signed-apk.yml/runs?branch=main&status=success&per_page=5")
        val runsConn = (runsUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "AmePlayer-App")
        }

        if (runsConn.responseCode != 200) {
            return UpdateCheckResult.Error("GitHub Actions 服务异常: HTTP ${runsConn.responseCode}")
        }

        val text = runsConn.inputStream.bufferedReader().use { it.readText() }
        val runsObj = JSONObject(text)
        val workflowRuns = runsObj.optJSONArray("workflow_runs") ?: JSONArray()
        if (workflowRuns.length() == 0) {
            return UpdateCheckResult.UpToDate(UpdateChannel.ACTION, variant, currentVersion)
        }

        val latestRun = workflowRuns.getJSONObject(0)
        val runId = latestRun.getLong("id")
        val runNumber = latestRun.getInt("run_number")
        val headBranch = normalizeBranchName(latestRun.optString("head_branch", ""))
        val headSha = latestRun.optString("head_sha", "")
        val headCommit = latestRun.optJSONObject("head_commit")
        val commitMsg = headCommit?.optString("message", "")?.lines()?.firstOrNull() ?: "CI 测试构建"
        val createdAt = latestRun.optString("created_at", "")
        val runHtmlUrl = latestRun.optString("html_url", "https://github.com/$GITHUB_REPO/actions")

        // Query artifacts of this run
        val artUrl = URL("https://api.github.com/repos/$GITHUB_REPO/actions/runs/$runId/artifacts")
        val artConn = (artUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "AmePlayer-App")
        }

        var matchingArtifact: JSONObject? = null
        if (artConn.responseCode == 200) {
            val artText = artConn.inputStream.bufferedReader().use { it.readText() }
            val artObj = JSONObject(artText)
            val artifacts = artObj.optJSONArray("artifacts") ?: JSONArray()
            for (i in 0 until artifacts.length()) {
                val art = artifacts.getJSONObject(i)
                val name = art.getString("name")
                if (variant.matchesActionArtifactName(name)) {
                    matchingArtifact = art
                    break
                }
            }
        }

        if (matchingArtifact == null) {
            AppLogger.w(TAG, "No matching artifact found for variant ${variant.displayName} in run #$runNumber")
            return UpdateCheckResult.Error("最新测试构建 (#$runNumber) 暂未就绪包名「${variant.packageName}」的构建产物")
        }

        val currentRunNum = BuildConfig.GITHUB_RUN_NUMBER.toIntOrNull()
        val currentCommitSha = BuildConfig.GIT_COMMIT_SHA
        val isNewer = isNewerActionBuild(runNumber, headSha, currentRunNum, currentCommitSha)

        val artifactName = matchingArtifact.getString("name")
        val directDownloadUrl = "https://nightly.link/$GITHUB_REPO/workflows/build-signed-apk.yml/main/$artifactName.zip"

        if (isNewer) {
            return UpdateCheckResult.UpdateAvailable(
                channel = UpdateChannel.ACTION,
                variant = variant,
                currentVersion = currentVersion,
                newVersion = "Action Build #$runNumber (${headSha.take(7)})",
                title = "测试构建 #$runNumber",
                releaseNotes = commitMsg,
                publishDate = formatIsoDate(createdAt),
                downloadUrl = directDownloadUrl,
                webUrl = runHtmlUrl,
                fileSize = matchingArtifact.optLong("size_in_bytes", 0L),
                fileName = "$artifactName.zip",
                buildBranch = headBranch,
                commitSha = headSha
            )
        } else {
            return UpdateCheckResult.UpToDate(UpdateChannel.ACTION, variant, currentVersion)
        }
    }

    private fun formatIsoDate(iso: String): String {
        return try {
            if (iso.length >= 16) {
                iso.substring(0, 10) + " " + iso.substring(11, 16)
            } else iso
        } catch (_: Exception) {
            iso
        }
    }

    private fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        val cleanRemote = remoteTag.removePrefix("v").removePrefix("release-").trim()
        val cleanCurrent = currentVersion.removePrefix("v").removePrefix("release-").trim()
        if (cleanRemote == cleanCurrent) return false

        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return remoteTag != currentVersion
    }
}