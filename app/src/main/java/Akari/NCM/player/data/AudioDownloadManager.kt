package Akari.NCM.player.data

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.api.ResolvedSongUrl
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.QualityLevel
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED
}

data class DownloadTaskState(
    val songId: String,
    val title: String,
    val artist: String,
    val targetQuality: QualityLevel,
    val status: DownloadStatus,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null
) {
    val progress: Float
        get() = if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

@Serializable
data class DownloadedTrackRecord(
    val songId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val coverUrl: String? = null,
    val durationMs: Long = 0L,
    val filePath: String,
    val uri: String,
    val format: String = AudioFormat.MP3.name,
    val requestedQuality: String = QualityLevel.EXHIGH.value,
    val actualQualityLevel: String? = null,
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val bitDepthBits: Int = 16,
    val channels: Int = 2,
    val mimeType: String? = null,
    val fileSizeBytes: Long = 0L,
    val downloadedAt: Long = System.currentTimeMillis()
) {
    fun toLocalPlayableMedia(): PlayableMedia.LocalNcm {
        val resolvedFormat = if (format.equals(AudioFormat.FLAC.name, ignoreCase = true) ||
            filePath.endsWith(".flac", ignoreCase = true)
        ) {
            AudioFormat.FLAC
        } else {
            AudioFormat.MP3
        }
        return PlayableMedia.LocalNcm(
            id = songId,
            title = title,
            artist = artist,
            album = album,
            coverUrl = coverUrl,
            durationMs = durationMs,
            uri = uri,
            format = resolvedFormat,
            isNcm = false
        )
    }
}

enum class AutoDownloadTriggerResult {
    STARTED,
    SKIPPED_DISABLED,
    SKIPPED_NOT_WIFI,
    SKIPPED_LOCAL_ONLY,
    SKIPPED_ALREADY_DOWNLOADED,
    SKIPPED_ALREADY_IN_PROGRESS
}

class AudioDownloadManager(
    private val context: Context,
    private val ncmApi: NcmApi,
    private val sessionManager: UserSessionManager,
    private val playlistManager: PlaylistManager,
    private val downloadsDirOverride: File? = null,
    private val isWifiConnectedProvider: (() -> Boolean)? = null,
    private val songUrlResolver: (suspend (PlayableMedia.Online, QualityLevel) -> ResolvedSongUrl?)? = null,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val recordsFile = File(context.filesDir, RECORDS_FILE_NAME)
    private val recordsLock = Any()

    private val _activeTasks = MutableStateFlow<Map<String, DownloadTaskState>>(emptyMap())
    val activeTasks: StateFlow<Map<String, DownloadTaskState>> = _activeTasks.asStateFlow()

    private val _downloadedTracks = MutableStateFlow<List<DownloadedTrackRecord>>(emptyList())
    val downloadedTracks: StateFlow<List<DownloadedTrackRecord>> = _downloadedTracks.asStateFlow()

    init {
        loadRecords()
    }

    fun getDownloadsDirectory(): File {
        val dir = downloadsDirOverride
            ?: context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { File(it, DOWNLOADS_SUBDIR) }
            ?: File(context.filesDir, DOWNLOADS_SUBDIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun isWifiConnected(): Boolean {
        isWifiConnectedProvider?.let { return it.invoke() }
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }.getOrDefault(false)
    }

    fun isDownloaded(songId: String): Boolean {
        val record = _downloadedTracks.value.firstOrNull { it.songId == songId } ?: return false
        return File(record.filePath).let { it.exists() && it.length() > 0L }
    }

    fun getDownloadedRecord(songId: String): DownloadedTrackRecord? {
        val record = _downloadedTracks.value.firstOrNull { it.songId == songId } ?: return null
        return record.takeIf { File(it.filePath).let { f -> f.exists() && f.length() > 0L } }
    }

    fun isDownloading(songId: String): Boolean {
        val status = _activeTasks.value[songId]?.status
        return status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING
    }

    /**
     * 启动异步音频下载任务（通常在用户手动选择目标音质后调用）。
     */
    fun enqueueDownload(
        song: PlayableMedia.Online,
        targetQuality: QualityLevel,
        onComplete: ((Result<DownloadedTrackRecord>) -> Unit)? = null
    ): Boolean {
        if (isDownloading(song.id)) {
            return false
        }
        updateTask(
            DownloadTaskState(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                targetQuality = targetQuality,
                status = DownloadStatus.QUEUED
            )
        )
        scope.launch {
            val result = downloadSongBlocking(song, targetQuality)
            if (onComplete != null) {
                withContext(Dispatchers.Main) {
                    onComplete(result)
                }
            }
        }
        return true
    }

    /**
     * 收藏自动下载入口：
     * - 遵循“收藏自动下载”总开关（默认关闭）
     * - 遵循“仅在 Wi-Fi 下自动下载”开关（默认开启）
     * - 使用独立的“默认下载音质”设置，不修改在线播放音质
     */
    fun maybeAutoDownloadOnFavorite(
        media: PlayableMedia,
        onComplete: ((Result<DownloadedTrackRecord>) -> Unit)? = null
    ): AutoDownloadTriggerResult {
        if (!sessionManager.isAutoDownloadOnFavoriteEnabled()) {
            return AutoDownloadTriggerResult.SKIPPED_DISABLED
        }
        val online = media as? PlayableMedia.Online ?: return AutoDownloadTriggerResult.SKIPPED_LOCAL_ONLY
        if (isDownloaded(online.id)) {
            return AutoDownloadTriggerResult.SKIPPED_ALREADY_DOWNLOADED
        }
        if (isDownloading(online.id)) {
            return AutoDownloadTriggerResult.SKIPPED_ALREADY_IN_PROGRESS
        }
        if (sessionManager.isAutoDownloadWifiOnlyEnabled() && !isWifiConnected()) {
            AppLogger.i(TAG, "Skip auto-download for '${online.title}': Wi-Fi only is enabled and device is not on Wi-Fi")
            return AutoDownloadTriggerResult.SKIPPED_NOT_WIFI
        }

        val downloadQuality = sessionManager.getEffectiveDownloadQualityLevel()
        AppLogger.i(TAG, "Auto-downloading favorited song '${online.title}' (id=${online.id}) with quality=${downloadQuality.value}")
        enqueueDownload(online, downloadQuality, onComplete)
        return AutoDownloadTriggerResult.STARTED
    }

    /**
     * 挂起执行音频下载（便于协程调用与单元测试直接验证完整落盘结果）。
     */
    suspend fun downloadSongBlocking(
        song: PlayableMedia.Online,
        targetQuality: QualityLevel
    ): Result<DownloadedTrackRecord> = withContext(Dispatchers.IO) {
        updateTask(
            DownloadTaskState(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                targetQuality = targetQuality,
                status = DownloadStatus.DOWNLOADING
            )
        )

        var tempFile: File? = null
        try {
            val resolved = (songUrlResolver?.invoke(song, targetQuality)
                ?: ncmApi.getSongUrlDetails(song, targetQuality))
                ?: throw IOException("未能获取歌曲下载链接，请检查网络或登录状态")

            if (resolved.isFreeTrial) {
                throw IOException("该歌曲当前仅返回试听片段，无法下载完整音频")
            }

            val ext = resolveFileExtension(resolved, targetQuality)
            val dir = getDownloadsDirectory()
            val fileName = buildSafeFileName(song, ext)
            val targetFile = File(dir, fileName)
            tempFile = File(dir, "$fileName.part")
            if (tempFile.exists()) {
                tempFile.delete()
            }

            val downloadedBytes = downloadUrlToFile(
                url = resolved.url,
                destFile = tempFile,
                expectedSize = resolved.size ?: 0L
            ) { written, total ->
                updateTask(
                    DownloadTaskState(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        targetQuality = targetQuality,
                        status = DownloadStatus.DOWNLOADING,
                        bytesDownloaded = written,
                        totalBytes = total
                    )
                )
            }

            if (downloadedBytes <= 0L || !tempFile.exists() || tempFile.length() <= 0L) {
                throw IOException("下载的音频文件为空")
            }

            // 删除旧版同名或不同扩展名的历史下载文件（同一 songId 重新下载其他音质时）
            val existingRecord = _downloadedTracks.value.firstOrNull { it.songId == song.id }
            if (existingRecord != null && existingRecord.filePath != targetFile.absolutePath) {
                runCatching { File(existingRecord.filePath).delete() }
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            tempFile = null

            val formatEnum = if (ext.equals("flac", ignoreCase = true)) AudioFormat.FLAC else AudioFormat.MP3
            val inspected = LocalAudioFileInspector.inspectFile(
                file = targetFile,
                isNcm = false,
                fallbackFormat = formatEnum,
                knownDurationMs = song.durationMs
            )

            val record = DownloadedTrackRecord(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                coverUrl = song.coverUrl,
                durationMs = song.durationMs,
                filePath = targetFile.absolutePath,
                uri = Uri.fromFile(targetFile).toString(),
                format = formatEnum.name,
                requestedQuality = targetQuality.value,
                actualQualityLevel = resolved.level ?: targetQuality.value,
                bitrateKbps = inspected?.bitrateKbps?.takeIf { it > 0 } ?: (resolved.bitrate ?: 0),
                sampleRateHz = inspected?.sampleRateHz?.takeIf { it > 0 } ?: (resolved.sampleRate ?: 0),
                bitDepthBits = inspected?.bitDepthBits?.takeIf { it > 0 } ?: 16,
                channels = inspected?.channels?.takeIf { it > 0 } ?: 2,
                mimeType = inspected?.mimeType ?: mimeForExtension(ext),
                fileSizeBytes = targetFile.length(),
                downloadedAt = System.currentTimeMillis()
            )

            upsertRecord(record)
            updateTask(
                DownloadTaskState(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    targetQuality = targetQuality,
                    status = DownloadStatus.COMPLETED,
                    bytesDownloaded = targetFile.length(),
                    totalBytes = targetFile.length()
                )
            )
            AppLogger.i(TAG, "Downloaded '${song.title}' -> ${targetFile.absolutePath} (${targetFile.length()} bytes, requested=${targetQuality.value}, actual=${record.actualQualityLevel})")
            Result.success(record)
        } catch (e: CancellationException) {
            tempFile?.delete()
            removeTask(song.id)
            throw e
        } catch (e: Exception) {
            tempFile?.delete()
            val message = e.message ?: "音频下载失败"
            AppLogger.e(TAG, "Download failed for '${song.title}' (id=${song.id}): $message", e)
            updateTask(
                DownloadTaskState(
                    songId = song.id,
                    title = song.title,
                    artist = song.artist,
                    targetQuality = targetQuality,
                    status = DownloadStatus.FAILED,
                    errorMessage = message
                )
            )
            Result.failure(e)
        }
    }

    fun deleteDownloadedTrack(songId: String): Boolean {
        synchronized(recordsLock) {
            val target = _downloadedTracks.value.firstOrNull { it.songId == songId } ?: return false
            runCatching { File(target.filePath).delete() }
            val updated = _downloadedTracks.value.filterNot { it.songId == songId }
            _downloadedTracks.value = updated
            saveRecordsLocked(updated)
            syncDownloadedPlaylist(updated)
            removeTask(songId)
            return true
        }
    }

    fun totalDownloadedSizeBytes(): Long {
        return _downloadedTracks.value.sumOf { rec ->
            val f = File(rec.filePath)
            if (f.exists()) f.length() else 0L
        }
    }

    private fun downloadUrlToFile(
        url: String,
        destFile: File,
        expectedSize: Long,
        onProgress: (Long, Long) -> Unit
    ): Long {
        if (url.startsWith("file://")) {
            val src = File(Uri.parse(url).path ?: throw IOException("无效的本地路径"))
            if (!src.exists()) throw IOException("源文件不存在")
            val total = src.length()
            src.inputStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    return copyWithProgress(input, output, total, onProgress)
                }
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AmePlayer/1.0")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载服务器返回错误 (${response.code})")
            }
            val body = response.body ?: throw IOException("下载响应体为空")
            val contentLength = body.contentLength().takeIf { it > 0L } ?: expectedSize
            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    return copyWithProgress(input, output, contentLength, onProgress)
                }
            }
        }
    }

    private fun copyWithProgress(
        input: java.io.InputStream,
        output: FileOutputStream,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit
    ): Long {
        val buf = ByteArray(16 * 1024)
        var written = 0L
        var lastReported = 0L
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            output.write(buf, 0, read)
            written += read
            if (written - lastReported >= 64 * 1024L || (totalBytes > 0L && written >= totalBytes)) {
                lastReported = written
                onProgress(written, totalBytes)
            }
        }
        output.flush()
        onProgress(written, maxOf(totalBytes, written))
        return written
    }

    private fun upsertRecord(record: DownloadedTrackRecord) {
        synchronized(recordsLock) {
            val updated = listOf(record) + _downloadedTracks.value.filterNot { it.songId == record.songId }
            _downloadedTracks.value = updated
            saveRecordsLocked(updated)
            syncDownloadedPlaylist(updated)
        }
    }

    private fun loadRecords() {
        synchronized(recordsLock) {
            if (!recordsFile.exists()) {
                _downloadedTracks.value = emptyList()
                return
            }
            val loaded = runCatching {
                json.decodeFromString<List<DownloadedTrackRecord>>(recordsFile.readText())
            }.getOrDefault(emptyList())
            val existing = loaded.filter { File(it.filePath).let { f -> f.exists() && f.length() > 0L } }
            _downloadedTracks.value = existing
            if (existing.size != loaded.size) {
                saveRecordsLocked(existing)
            }
            if (existing.isNotEmpty()) {
                syncDownloadedPlaylist(existing)
            }
        }
    }

    private fun saveRecordsLocked(records: List<DownloadedTrackRecord>) {
        runCatching {
            recordsFile.writeText(json.encodeToString(records))
        }.onFailure { e ->
            AppLogger.e(TAG, "Failed to persist downloaded tracks index: ${e.message}", e)
        }
    }

    private fun syncDownloadedPlaylist(records: List<DownloadedTrackRecord>) {
        val existingPlaylist = playlistManager.playlists.value
            .firstOrNull { it.ncmPlaylistId == PlaylistManager.DOWNLOADED_PLAYLIST_ID }
        if (records.isEmpty()) {
            if (existingPlaylist != null) {
                playlistManager.deletePlaylist(existingPlaylist.id)
            }
            return
        }
        playlistManager.saveOrUpdateSyncedPlaylist(
            ncmPlaylistId = PlaylistManager.DOWNLOADED_PLAYLIST_ID,
            name = DOWNLOADED_PLAYLIST_NAME,
            coverImgUrl = records.firstOrNull()?.coverUrl,
            songs = records.map { it.toLocalPlayableMedia() }
        )
    }

    private fun updateTask(task: DownloadTaskState) {
        _activeTasks.value = _activeTasks.value + (task.songId to task)
    }

    private fun removeTask(songId: String) {
        _activeTasks.value = _activeTasks.value - songId
    }

    companion object {
        private const val TAG = "AME_DOWNLOAD"
        private const val RECORDS_FILE_NAME = "downloaded_tracks.json"
        private const val DOWNLOADS_SUBDIR = "AmeDownloads"
        const val DOWNLOADED_PLAYLIST_NAME = "已下载音乐"

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        internal fun resolveFileExtension(resolved: ResolvedSongUrl, requestedQuality: QualityLevel): String {
            val fromType = resolved.formatType?.trim()?.lowercase()?.removePrefix(".")
            if (!fromType.isNullOrBlank() && fromType in KNOWN_EXTENSIONS) {
                return fromType
            }
            val pathExt = resolved.url
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('/', "")
                .substringAfterLast('.', "")
                .lowercase()
            if (pathExt in KNOWN_EXTENSIONS) {
                return pathExt
            }
            return when (requestedQuality) {
                QualityLevel.STANDARD, QualityLevel.EXHIGH -> "mp3"
                QualityLevel.DOLBY -> "m4a"
                else -> "flac"
            }
        }

        internal fun buildSafeFileName(song: PlayableMedia, extension: String): String {
            val cleanArtist = sanitizeSegment(song.artist)
            val cleanTitle = sanitizeSegment(song.title).ifBlank { "track_${song.id}" }
            val cleanId = sanitizeSegment(song.id)
            val base = if (cleanArtist.isNotBlank()) {
                "$cleanArtist - $cleanTitle"
            } else {
                cleanTitle
            }
            val truncatedBase = if (base.length > 96) base.substring(0, 96).trim() else base
            val suffix = if (cleanId.isNotBlank()) " [$cleanId]" else ""
            return "$truncatedBase$suffix.$extension"
        }

        private fun sanitizeSegment(raw: String): String {
            return raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trim('.')
        }

        private fun mimeForExtension(ext: String): String {
            return when (ext.lowercase()) {
                "flac" -> "audio/flac"
                "mp3" -> "audio/mpeg"
                "m4a", "mp4", "aac" -> "audio/mp4a-latm"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "opus" -> "audio/opus"
                "ec3", "eac3" -> "audio/eac3"
                "ac3" -> "audio/ac3"
                "ac4" -> "audio/ac4"
                else -> "audio/mpeg"
            }
        }

        private val KNOWN_EXTENSIONS = setOf("flac", "mp3", "m4a", "mp4", "aac", "wav", "ogg", "opus", "ec3", "eac3", "ac3", "ac4")
    }
}
