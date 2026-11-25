package Akari.NCM.player.data

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.api.ResolvedSongUrl
import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.AudioFormat
import Akari.NCM.player.core.PlayableMedia
import Akari.NCM.player.core.QualityLevel
import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
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
import java.io.OutputStream
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

    fun getConfiguredDownloadPath(): String {
        return downloadsDirOverride?.absolutePath?.let { UserSessionManager.normalizeDownloadPath(it) }
            ?: sessionManager.getDownloadPath()
    }

    fun getDownloadsDirectory(): File {
        val dir = downloadsDirOverride ?: File(sessionManager.getDownloadPath())
        if (!dir.exists()) {
            runCatching { dir.mkdirs() }
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
        return recordExists(record)
    }

    fun getDownloadedRecord(songId: String): DownloadedTrackRecord? {
        val record = _downloadedTracks.value.firstOrNull { it.songId == songId } ?: return null
        return record.takeIf { recordExists(it) }
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
            val mimeType = mimeForExtension(ext)
            val fileName = buildSafeFileName(song, ext)

            // 1. 先流式下载到应用私有临时文件，确保断点/失败不污染目标目录，同时便于完整读取文件头
            val tmpDir = File(context.cacheDir, "download_tmp").apply { if (!exists()) mkdirs() }
            tempFile = File(tmpDir, "${song.id}_${System.currentTimeMillis()}.$ext.part")
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

            val finalSizeBytes = tempFile.length()
            val formatEnum = if (ext.equals("flac", ignoreCase = true)) AudioFormat.FLAC else AudioFormat.MP3
            val inspected = LocalAudioFileInspector.inspectFile(
                file = tempFile,
                isNcm = false,
                fallbackFormat = formatEnum,
                knownDurationMs = song.durationMs
            )

            // 2. 删除同一 songId 的历史下载文件（若用户重新下载了不同音质）
            val existingRecord = _downloadedTracks.value.firstOrNull { it.songId == song.id }
            if (existingRecord != null) {
                deleteRecordFile(existingRecord)
            }

            // 3. 写入目标路径（支持默认网易云路径 /storage/emulated/0/Download/netease/cloudmusic/Music/、自定义路径与 SAF 目录）
            val savedTarget = persistTempFileToConfiguredPath(
                tempFile = tempFile,
                fileName = fileName,
                mimeType = mimeType
            )

            val record = DownloadedTrackRecord(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                coverUrl = song.coverUrl,
                durationMs = song.durationMs,
                filePath = savedTarget.filePath,
                uri = savedTarget.uriString,
                format = formatEnum.name,
                requestedQuality = targetQuality.value,
                actualQualityLevel = resolved.level ?: targetQuality.value,
                bitrateKbps = inspected?.bitrateKbps?.takeIf { it > 0 } ?: (resolved.bitrate ?: 0),
                sampleRateHz = inspected?.sampleRateHz?.takeIf { it > 0 } ?: (resolved.sampleRate ?: 0),
                bitDepthBits = inspected?.bitDepthBits?.takeIf { it > 0 } ?: 16,
                channels = inspected?.channels?.takeIf { it > 0 } ?: 2,
                mimeType = inspected?.mimeType ?: mimeType,
                fileSizeBytes = finalSizeBytes,
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
                    bytesDownloaded = finalSizeBytes,
                    totalBytes = finalSizeBytes
                )
            )
            AppLogger.i(TAG, "Downloaded '${song.title}' -> ${savedTarget.filePath} ($finalSizeBytes bytes, requested=${targetQuality.value}, actual=${record.actualQualityLevel})")
            Result.success(record)
        } catch (e: CancellationException) {
            removeTask(song.id)
            throw e
        } catch (e: Exception) {
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
        } finally {
            runCatching { tempFile?.delete() }
        }
    }

    fun deleteDownloadedTrack(songId: String): Boolean {
        synchronized(recordsLock) {
            val target = _downloadedTracks.value.firstOrNull { it.songId == songId } ?: return false
            deleteRecordFile(target)
            val updated = _downloadedTracks.value.filterNot { it.songId == songId }
            _downloadedTracks.value = updated
            saveRecordsLocked(updated)
            syncDownloadedPlaylist(updated)
            removeTask(songId)
            return true
        }
    }

    fun totalDownloadedSizeBytes(): Long {
        return _downloadedTracks.value.sumOf { rec -> recordSizeBytes(rec) }
    }

    private data class SavedFileLocation(
        val filePath: String,
        val uriString: String
    )

    private fun persistTempFileToConfiguredPath(
        tempFile: File,
        fileName: String,
        mimeType: String
    ): SavedFileLocation {
        // 1. 显式目录覆盖（如单元测试）
        if (downloadsDirOverride != null) {
            return saveToFileSystemDir(tempFile, downloadsDirOverride, fileName)
        }

        val configuredPath = sessionManager.getDownloadPath()

        // 2. Android SAF 目录树 URI（用户通过系统文件夹选择器自定义路径）
        if (configuredPath.startsWith("content://", ignoreCase = true)) {
            return saveToSafTreeUri(tempFile, Uri.parse(configuredPath), fileName, mimeType)
        }

        // 3. Android 10+ 公共 Download/ 或 Music/ 目录（含默认路径 /storage/emulated/0/Download/netease/cloudmusic/Music/）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaStoreTarget = resolveMediaStoreTarget(configuredPath)
            if (mediaStoreTarget != null) {
                val msSaved = runCatching {
                    saveToMediaStore(
                        tempFile = tempFile,
                        collectionUri = mediaStoreTarget.first,
                        relativePath = mediaStoreTarget.second,
                        configuredDirPath = configuredPath,
                        fileName = fileName,
                        mimeType = mimeType
                    )
                }.getOrNull()
                if (msSaved != null) {
                    return msSaved
                }
            }
        }

        // 4. 直接文件系统写入（Android 8-9、可写自定义路径或 MediaStore 不可用时的兜底）
        val targetDir = File(configuredPath)
        return try {
            saveToFileSystemDir(tempFile, targetDir, fileName)
        } catch (e: IOException) {
            throw IOException("无法写入下载目录「$configuredPath」(${e.message})，请在设置中通过「选择系统文件夹」授权自定义下载路径", e)
        }
    }

    private fun saveToFileSystemDir(
        tempFile: File,
        targetDir: File,
        fileName: String
    ): SavedFileLocation {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("无法创建目录: ${targetDir.absolutePath}")
        }
        val targetFile = File(targetDir, fileName)
        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.inputStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return SavedFileLocation(
            filePath = targetFile.absolutePath,
            uriString = Uri.fromFile(targetFile).toString()
        )
    }

    private fun saveToSafTreeUri(
        tempFile: File,
        treeUri: Uri,
        fileName: String,
        mimeType: String
    ): SavedFileLocation {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("无法访问已选择的自定义下载文件夹，请重新授权")
        root.findFile(fileName)?.delete()
        val doc = root.createFile(mimeType, fileName)
            ?: throw IOException("无法在自定义文件夹中创建音频文件: $fileName")
        val out = context.contentResolver.openOutputStream(doc.uri, "w")
            ?: throw IOException("无法打开目标文件输出流")
        out.use { output ->
            tempFile.inputStream().use { input ->
                input.copyTo(output)
            }
            output.flush()
        }
        val displayDir = UserSessionManager.formatDownloadPathForDisplay(treeUri.toString())
        return SavedFileLocation(
            filePath = "$displayDir$fileName",
            uriString = doc.uri.toString()
        )
    }

    private fun saveToMediaStore(
        tempFile: File,
        collectionUri: Uri,
        relativePath: String,
        configuredDirPath: String,
        fileName: String,
        mimeType: String
    ): SavedFileLocation? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val insertedUri = cr.insert(collectionUri, values) ?: return null
        try {
            val out = cr.openOutputStream(insertedUri, "w")
                ?: throw IOException("MediaStore 输出流为空")
            out.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                output.flush()
            }
            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            cr.update(insertedUri, publishValues, null, null)
            val normalizedDir = UserSessionManager.normalizeDownloadPath(configuredDirPath)
            return SavedFileLocation(
                filePath = "$normalizedDir$fileName",
                uriString = insertedUri.toString()
            )
        } catch (e: Exception) {
            runCatching { cr.delete(insertedUri, null, null) }
            throw e
        }
    }

    private fun recordExists(record: DownloadedTrackRecord): Boolean {
        if (record.uri.startsWith("content://", ignoreCase = true)) {
            val contentUri = Uri.parse(record.uri)
            val fdExists = runCatching {
                context.contentResolver.openFileDescriptor(contentUri, "r")?.use { it.statSize > 0L }
            }.getOrNull()
            if (fdExists == true) return true
            val docExists = runCatching {
                DocumentFile.fromSingleUri(context, contentUri)?.exists() == true
            }.getOrDefault(false)
            if (docExists) return true
        }
        if (record.filePath.isNotBlank() && !record.filePath.startsWith("content://", ignoreCase = true)) {
            val f = File(record.filePath)
            if (f.exists() && f.length() > 0L) return true
        }
        return false
    }

    private fun recordSizeBytes(record: DownloadedTrackRecord): Long {
        if (record.filePath.isNotBlank() && !record.filePath.startsWith("content://", ignoreCase = true)) {
            val f = File(record.filePath)
            if (f.exists()) return f.length()
        }
        if (record.uri.startsWith("content://", ignoreCase = true)) {
            val statSize = runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(record.uri), "r")?.use { it.statSize }
            }.getOrNull()
            if (statSize != null && statSize > 0L) return statSize
        }
        return record.fileSizeBytes.coerceAtLeast(0L)
    }

    private fun deleteRecordFile(record: DownloadedTrackRecord) {
        if (record.uri.startsWith("content://", ignoreCase = true)) {
            val uri = Uri.parse(record.uri)
            runCatching {
                val docDeleted = DocumentFile.fromSingleUri(context, uri)?.delete() == true
                if (!docDeleted) {
                    context.contentResolver.delete(uri, null, null)
                }
            }
        }
        if (record.filePath.isNotBlank() && !record.filePath.startsWith("content://", ignoreCase = true)) {
            runCatching { File(record.filePath).delete() }
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
        output: OutputStream,
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
            val existing = loaded.filter { recordExists(it) }
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
        const val DOWNLOADED_PLAYLIST_NAME = "已下载音乐"

        internal fun resolveMediaStoreTarget(rawPath: String): Pair<Uri, String>? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            val normalized = UserSessionManager.normalizeDownloadPath(rawPath)
            if (normalized.startsWith("content://", ignoreCase = true)) return null

            val prefixes = listOf("/storage/emulated/0/", "/sdcard/")
            val relFromRoot = prefixes.firstOrNull { normalized.startsWith(it, ignoreCase = true) }
                ?.let { normalized.substring(it.length) }
                ?: return null

            val cleanRel = relFromRoot.trimStart('/')
            return when {
                cleanRel.equals("Download/", ignoreCase = true) ||
                    cleanRel.startsWith("Download/", ignoreCase = true) -> {
                    val sub = cleanRel.substringAfter('/', "").trim('/')
                    val relativePath = if (sub.isEmpty()) {
                        "${Environment.DIRECTORY_DOWNLOADS}/"
                    } else {
                        "${Environment.DIRECTORY_DOWNLOADS}/$sub/"
                    }
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI to relativePath
                }
                cleanRel.equals("Music/", ignoreCase = true) ||
                    cleanRel.startsWith("Music/", ignoreCase = true) -> {
                    val sub = cleanRel.substringAfter('/', "").trim('/')
                    val relativePath = if (sub.isEmpty()) {
                        "${Environment.DIRECTORY_MUSIC}/"
                    } else {
                        "${Environment.DIRECTORY_MUSIC}/$sub/"
                    }
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to relativePath
                }
                else -> null
            }
        }

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
