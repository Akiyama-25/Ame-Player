package Akari.NCM.player.data

import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.LyricSource
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 本地音频元数据提取与同目录文件名匹配工具：
 * 1. 云端歌单场景（第一优先）：读取本地音频文件自身的内嵌歌词与内嵌封面（支持 FLAC / MP3 / M4A 及解密后的 NCM）。
 * 2. 本地歌单场景（仅按文件名匹配）：在同目录下按音频文件名 stem 精确匹配 .ttml / .lrc 歌词文件与 .jpg/.jpeg/.png/.webp 封面文件，
 *    同时兼容 file:// 路径与 Android SAF content:// 目录树。
 */
object LocalMediaMetadataHelper {
    private const val TAG = "LocalMediaMeta"
    private const val MAX_LYRIC_BYTES = 2 * 1024 * 1024
    private const val MAX_COVER_BYTES = 16 * 1024 * 1024
    private const val MAX_ID3_TAG_BYTES = 16 * 1024 * 1024

    private val LYRIC_EXTENSIONS_PRIORITY = listOf("ttml", "lrc")
    private val COVER_EXTENSIONS_PRIORITY = listOf("jpg", "jpeg", "png", "webp")

    // =========================================================================
    // A. 本地文件自身内嵌元数据提取（供云端歌单第一优先使用）
    // =========================================================================

    fun extractEmbeddedLyrics(context: Context, uri: Uri, isNcm: Boolean): String? {
        // 1. 直接解析容器标签（FLAC VORBIS_COMMENT / MP3 ID3v2 USLT/TXXX），兼容解密后的 NCM 与普通音频
        val directParsed = runCatching {
            val stream = if (isNcm) {
                NcmDataSource.openDecryptedStream(context.contentResolver, uri)
            } else {
                openInputStream(context, uri)
            }
            stream?.use { parseEmbeddedLyricsFromStream(it) }
        }.getOrNull()
        if (!directParsed.isNullOrBlank()) return directParsed

        // 2. 非 NCM 回退 Android MediaMetadataRetriever（覆盖 M4A/MP4 等其他容器）
        if (!isNcm) {
            val retriever = MediaMetadataRetriever()
            return try {
                if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                    retriever.setDataSource(uri.path)
                } else {
                    retriever.setDataSource(context, uri)
                }
                retriever.extractMetadata(1000)?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
        return null
    }

    /**
     * 快速校验字节数组是否具备常见图片格式的合法魔数头（JPEG / PNG / WebP / GIF / BMP），
     * 防止偏移错位或零填充的无效字节被误当成封面写入缓存。
     */
    fun isValidImageHeader(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 4) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF

        // JPEG: FF D8
        if (b0 == 0xFF && b1 == 0xD8) return true
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes.size >= 8 && b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true
        // WebP: RIFF....WEBP
        if (bytes.size >= 12 &&
            b0 == 'R'.code && b1 == 'I'.code && b2 == 'F'.code && b3 == 'F'.code &&
            bytes[8].toInt() == 'W'.code && bytes[9].toInt() == 'E'.code &&
            bytes[10].toInt() == 'B'.code && bytes[11].toInt() == 'P'.code
        ) return true
        // GIF: GIF8
        if (bytes.size >= 6 && b0 == 'G'.code && b1 == 'I'.code && b2 == 'F'.code && b3 == '8'.code) return true
        // BMP: BM
        if (b0 == 'B'.code && b1 == 'M'.code) return true

        return false
    }

    fun extractEmbeddedCoverBytes(context: Context, uri: Uri, isNcm: Boolean): ByteArray? {
        if (isNcm) {
            // 1. NCM 文件头自带的内嵌封面图片字节
            val ncmHeaderCover = runCatching {
                NcmDataSource.scanMetadata(context.contentResolver, uri)?.third
            }.getOrNull()
            if (isValidImageHeader(ncmHeaderCover)) {
                return ncmHeaderCover
            }
            // 2. 解密音频流中的 FLAC PICTURE / MP3 APIC
            val streamCover = runCatching {
                NcmDataSource.openDecryptedStream(context.contentResolver, uri)?.use {
                    parseEmbeddedCoverFromStream(it)
                }
            }.getOrNull()
            return streamCover?.takeIf { isValidImageHeader(it) }
        }

        // 非 NCM：先直接解析 FLAC PICTURE / MP3 APIC（不依赖设备编解码器，对 Hi-Res FLAC 也稳定）
        val directCover = runCatching {
            openInputStream(context, uri)?.use { parseEmbeddedCoverFromStream(it) }
        }.getOrNull()
        if (isValidImageHeader(directCover)) {
            return directCover
        }

        // 回退 MediaMetadataRetriever.embeddedPicture（覆盖 M4A/MP4/OGG 等容器）
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            retriever.embeddedPicture?.takeIf { isValidImageHeader(it) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // =========================================================================
    // B. 同目录按文件名精确匹配歌词与封面（供本地歌单使用）
    // =========================================================================

    fun resolveFileStem(context: Context, uri: Uri, fallbackTitle: String): String {
        if (uri.scheme == "file" || uri.scheme.isNullOrBlank()) {
            val path = uri.path ?: uri.toString()
            val name = File(path).name
            if (name.isNotBlank()) {
                return stripExtension(name)
            }
        }

        if (uri.scheme == "content") {
            val displayName = queryDisplayName(context, uri)
                ?: runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
            if (!displayName.isNullOrBlank()) {
                return stripExtension(displayName)
            }
            val decodedLast = runCatching { Uri.decode(uri.lastPathSegment ?: "") }.getOrDefault("")
            val leaf = decodedLast.substringAfterLast('/').substringAfterLast(':')
            if (leaf.isNotBlank()) {
                return stripExtension(leaf)
            }
        }

        return stripExtension(fallbackTitle).ifBlank { fallbackTitle.trim() }
    }

    fun findSiblingLyrics(
        context: Context,
        uri: Uri,
        fallbackTitle: String
    ): Pair<String, LyricSource>? {
        val stem = resolveFileStem(context, uri, fallbackTitle)
        if (stem.isBlank()) return null

        val match = findSiblingFile(context, uri, stem, LYRIC_EXTENSIONS_PRIORITY) ?: return null
        val text = runCatching {
            match.openStream()?.use { readBytesLimited(it, MAX_LYRIC_BYTES) }
                ?.toString(Charsets.UTF_8)
        }.getOrNull()
        if (text.isNullOrBlank()) return null

        val source = if (match.extension.equals("ttml", ignoreCase = true)) {
            LyricSource.TTML
        } else {
            LyricSource.LOCAL_LRC
        }
        return text to source
    }

    fun findSiblingCover(
        context: Context,
        uri: Uri,
        fallbackTitle: String
    ): Pair<Uri, ByteArray>? {
        val stem = resolveFileStem(context, uri, fallbackTitle)
        if (stem.isBlank()) return null

        val match = findSiblingFile(context, uri, stem, COVER_EXTENSIONS_PRIORITY) ?: return null
        val bytes = runCatching {
            match.openStream()?.use { readBytesLimited(it, MAX_COVER_BYTES) }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return null
        return match.uri to bytes
    }

    private data class SiblingMatch(
        val uri: Uri,
        val extension: String,
        val openStream: () -> InputStream?
    )

    private sealed interface ParentDirQueryResult {
        data class Matched(val match: SiblingMatch) : ParentDirQueryResult
        data object Empty : ParentDirQueryResult
        data object Unavailable : ParentDirQueryResult
    }

    private const val MAX_FALLBACK_TREE_DIRS = 64

    private fun querySiblingViaDocumentsContract(
        cr: android.content.ContentResolver,
        audioUri: Uri,
        stem: String,
        extensionsPriority: List<String>
    ): ParentDirQueryResult {
        return try {
            val docId = DocumentsContract.getDocumentId(audioUri)
            val parentDocId = deriveParentDocumentId(docId) ?: return ParentDirQueryResult.Unavailable
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(audioUri, parentDocId)
            val cursor = cr.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            ) ?: return ParentDirQueryResult.Unavailable

            val foundByExt = HashMap<String, Uri>()
            cursor.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (idIdx < 0 || nameIdx < 0) return ParentDirQueryResult.Unavailable
                while (c.moveToNext()) {
                    val childName = c.getString(nameIdx) ?: continue
                    val childExt = childName.substringAfterLast('.', "").lowercase()
                    if (childExt !in extensionsPriority) continue
                    val childStem = stripExtension(childName)
                    if (childStem.equals(stem, ignoreCase = true) && !foundByExt.containsKey(childExt)) {
                        val childDocId = c.getString(idIdx) ?: continue
                        foundByExt[childExt] = DocumentsContract.buildDocumentUriUsingTree(audioUri, childDocId)
                    }
                }
            }

            for (ext in extensionsPriority) {
                val matchedUri = foundByExt[ext]
                if (matchedUri != null) {
                    return ParentDirQueryResult.Matched(
                        SiblingMatch(matchedUri, ext) { cr.openInputStream(matchedUri) }
                    )
                }
            }
            // 父目录查询已成功完成且同目录确实没有同名文件：直接返回 Empty，严禁回退遍历整棵曲库目录树
            ParentDirQueryResult.Empty
        } catch (_: Exception) {
            ParentDirQueryResult.Unavailable
        }
    }

    private fun findSiblingFile(
        context: Context,
        audioUri: Uri,
        stem: String,
        extensionsPriority: List<String>
    ): SiblingMatch? {
        // 1. file:// 本地文件系统直接同目录匹配
        if (audioUri.scheme == "file" || audioUri.scheme.isNullOrBlank()) {
            val path = audioUri.path ?: return null
            val parent = File(path).parentFile ?: return null
            if (!parent.exists() || !parent.isDirectory) return null
            val files = parent.listFiles() ?: return null
            for (ext in extensionsPriority) {
                val exact = File(parent, "$stem.$ext")
                if (exact.exists() && exact.isFile) {
                    return SiblingMatch(Uri.fromFile(exact), ext) { exact.inputStream() }
                }
                val ciMatch = files.firstOrNull { f ->
                    f.isFile &&
                        f.extension.equals(ext, ignoreCase = true) &&
                        f.nameWithoutExtension.equals(stem, ignoreCase = true)
                }
                if (ciMatch != null) {
                    return SiblingMatch(Uri.fromFile(ciMatch), ext) { ciMatch.inputStream() }
                }
            }
            return null
        }

        // 2. Android SAF content:// 目录树同目录匹配
        if (audioUri.scheme == "content") {
            val cr = context.contentResolver
            // 2a. 直接通过 DocumentsContract 推导父目录 ID（一次目录查询，零递归）
            when (val directResult = querySiblingViaDocumentsContract(cr, audioUri, stem, extensionsPriority)) {
                is ParentDirQueryResult.Matched -> return directResult.match
                ParentDirQueryResult.Empty -> return null
                ParentDirQueryResult.Unavailable -> Unit
            }

            // 2b. 仅当非标准 DocumentProvider 无法直接查询父目录时，才有限回退遍历已授权的本地扫描目录树
            val prefs = context.getSharedPreferences("local_folders", Context.MODE_PRIVATE)
            val dirs = prefs.getStringSet("folders", emptySet()).orEmpty()
            for (dirStr in dirs) {
                if (Thread.currentThread().isInterrupted) return null
                val matched = runCatching {
                    val root = DocumentFile.fromTreeUri(context, Uri.parse(dirStr)) ?: return@runCatching null
                    findSiblingInDocumentTree(root, audioUri, stem, extensionsPriority, cr)
                }.getOrNull()
                if (matched != null) return matched
            }
        }
        return null
    }

    private fun findSiblingInDocumentTree(
        root: DocumentFile,
        audioUri: Uri,
        stem: String,
        extensionsPriority: List<String>,
        cr: android.content.ContentResolver
    ): SiblingMatch? {
        val queue = ArrayDeque<DocumentFile>()
        queue.add(root)
        var visitedDirs = 0
        while (queue.isNotEmpty() && visitedDirs < MAX_FALLBACK_TREE_DIRS) {
            if (Thread.currentThread().isInterrupted) return null
            visitedDirs++
            val dir = queue.removeFirst()
            val children = dir.listFiles()
            val containsAudio = children.any { it.isFile && it.uri == audioUri }
            if (containsAudio) {
                for (ext in extensionsPriority) {
                    val sibling = children.firstOrNull { f ->
                        val name = f.name ?: return@firstOrNull false
                        f.isFile &&
                            name.substringAfterLast('.', "").equals(ext, ignoreCase = true) &&
                            stripExtension(name).equals(stem, ignoreCase = true)
                    }
                    if (sibling != null) {
                        return SiblingMatch(sibling.uri, ext) { cr.openInputStream(sibling.uri) }
                    }
                }
                return null
            }
            for (child in children) {
                if (child.isDirectory && visitedDirs + queue.size < MAX_FALLBACK_TREE_DIRS) {
                    queue.add(child)
                }
            }
        }
        return null
    }

    internal fun deriveParentDocumentId(docId: String): String? {
        val lastSlash = docId.lastIndexOf('/')
        if (lastSlash > 0) return docId.substring(0, lastSlash)
        val colonIdx = docId.lastIndexOf(':')
        if (colonIdx >= 0 && colonIdx + 1 < docId.length) {
            return docId.substring(0, colonIdx + 1)
        }
        return null
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }

    private fun stripExtension(name: String): String {
        val trimmed = name.trim()
        val dot = trimmed.lastIndexOf('.')
        return if (dot > 0) trimmed.substring(0, dot) else trimmed
    }

    private fun openInputStream(context: Context, uri: Uri): InputStream? {
        return if (uri.scheme == "file" && !uri.path.isNullOrBlank()) {
            val f = File(uri.path!!)
            if (f.exists() && f.isFile) f.inputStream() else null
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }

    // =========================================================================
    // C. 二进制容器内嵌歌词与封面解析（FLAC VORBIS_COMMENT / PICTURE & MP3 ID3v2 USLT / APIC）
    // =========================================================================

    internal fun parseEmbeddedLyricsFromStream(stream: InputStream): String? {
        val header = ByteArray(10)
        val read = readFullyUpTo(stream, header, 10)
        if (read < 4) return null
        return when {
            header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte() -> {
                // 已多读了 6 字节，拼回前导缓冲继续解析 FLAC 元数据块
                val pushed = java.io.SequenceInputStream(
                    java.io.ByteArrayInputStream(header, 4, (read - 4).coerceAtLeast(0)),
                    stream
                )
                parseFlacEmbeddedLyrics(pushed)
            }
            read >= 10 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte() -> {
                parseId3EmbeddedLyrics(header, stream)
            }
            else -> null
        }
    }

    internal fun parseEmbeddedCoverFromStream(stream: InputStream): ByteArray? {
        val header = ByteArray(10)
        val read = readFullyUpTo(stream, header, 10)
        if (read < 4) return null
        return when {
            header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte() -> {
                val pushed = java.io.SequenceInputStream(
                    java.io.ByteArrayInputStream(header, 4, (read - 4).coerceAtLeast(0)),
                    stream
                )
                parseFlacEmbeddedCover(pushed)
            }
            read >= 10 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte() -> {
                parseId3EmbeddedCover(header, stream)
            }
            else -> null
        }
    }

    private fun parseFlacEmbeddedLyrics(stream: InputStream): String? {
        var isLast = false
        var guard = 0
        while (!isLast && guard++ < 128) {
            val blk = ByteArray(4)
            if (readFullyUpTo(stream, blk, 4) < 4) break
            val b0 = blk[0].toInt() and 0xFF
            isLast = (b0 and 0x80) != 0
            val blockType = b0 and 0x7F
            val length = ((blk[1].toInt() and 0xFF) shl 16) or
                ((blk[2].toInt() and 0xFF) shl 8) or
                (blk[3].toInt() and 0xFF)
            if (blockType == 4 && length in 8..MAX_LYRIC_BYTES) {
                val data = ByteArray(length)
                if (readFullyUpTo(stream, data, length) < length) break
                val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                if (buf.remaining() < 4) break
                val vendorLen = buf.int
                if (vendorLen < 0 || vendorLen > buf.remaining()) break
                buf.position(buf.position() + vendorLen)
                if (buf.remaining() < 4) break
                val count = buf.int
                for (i in 0 until count) {
                    if (buf.remaining() < 4) break
                    val len = buf.int
                    if (len <= 0 || len > buf.remaining()) break
                    val strBytes = ByteArray(len)
                    buf.get(strBytes)
                    val s = String(strBytes, Charsets.UTF_8)
                    val eq = s.indexOf('=')
                    if (eq > 0) {
                        val key = s.substring(0, eq).trim().uppercase()
                        if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "LYRIC" || key == "SYNCEDLYRICS") {
                            val value = s.substring(eq + 1).trim()
                            if (value.isNotBlank()) return value
                        }
                    }
                }
            } else {
                skipFully(stream, length.toLong())
            }
        }
        return null
    }

    private fun parseFlacEmbeddedCover(stream: InputStream): ByteArray? {
        var isLast = false
        var guard = 0
        var fallbackCover: ByteArray? = null
        while (!isLast && guard++ < 128) {
            val blk = ByteArray(4)
            if (readFullyUpTo(stream, blk, 4) < 4) break
            val b0 = blk[0].toInt() and 0xFF
            isLast = (b0 and 0x80) != 0
            val blockType = b0 and 0x7F
            val length = ((blk[1].toInt() and 0xFF) shl 16) or
                ((blk[2].toInt() and 0xFF) shl 8) or
                (blk[3].toInt() and 0xFF)
            if (blockType == 6 && length in 32..MAX_COVER_BYTES) {
                val data = ByteArray(length)
                if (readFullyUpTo(stream, data, length) < length) break
                val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                if (buf.remaining() < 32) continue
                val picType = buf.int
                val mimeLen = buf.int
                if (mimeLen < 0 || mimeLen > buf.remaining()) continue
                buf.position(buf.position() + mimeLen)
                if (buf.remaining() < 4) continue
                val descLen = buf.int
                if (descLen < 0 || descLen > buf.remaining()) continue
                buf.position(buf.position() + descLen)
                if (buf.remaining() < 20) continue
                buf.int; buf.int; buf.int; buf.int // width, height, depth, indexed colors
                val imgLen = buf.int
                if (imgLen > 0 && imgLen <= buf.remaining()) {
                    val img = ByteArray(imgLen)
                    buf.get(img)
                    if (picType == 3) return img // Cover (front)
                    if (fallbackCover == null) fallbackCover = img
                }
            } else {
                skipFully(stream, length.toLong())
            }
        }
        return fallbackCover
    }

    private fun parseId3EmbeddedLyrics(header10: ByteArray, stream: InputStream): String? {
        val versionMajor = header10[3].toInt() and 0xFF
        val tagSize = syncSafeInt(header10, 6)
        if (tagSize <= 0 || tagSize > MAX_ID3_TAG_BYTES) return null
        val tagBytes = ByteArray(tagSize)
        val total = readFullyUpTo(stream, tagBytes, tagSize)
        if (total < 10) return null

        val buf = ByteBuffer.wrap(tagBytes, 0, total)
        while (buf.remaining() >= 10) {
            val idBytes = ByteArray(4)
            buf.get(idBytes)
            if (idBytes[0].toInt() == 0) break
            val frameId = String(idBytes, Charsets.US_ASCII)
            val frameSize = if (versionMajor >= 4) {
                val s = ByteArray(4)
                buf.get(s)
                syncSafeInt(s, 0)
            } else {
                buf.int
            }
            buf.short // flags
            if (frameSize <= 0 || frameSize > buf.remaining()) break
            val frameData = ByteArray(frameSize)
            buf.get(frameData)

            if (frameId == "USLT" && frameData.size > 4) {
                val encoding = frameData[0].toInt() and 0xFF
                // Skip encoding(1) + language(3)
                val body = decodeId3StringAfterDescriptor(frameData, 4, encoding)
                if (!body.isNullOrBlank()) return body
            } else if (frameId == "TXXX" && frameData.size > 2) {
                val encoding = frameData[0].toInt() and 0xFF
                val text = decodeId3Text(frameData, 1, frameData.size - 1, encoding)
                val sep = text.indexOf('\u0000')
                if (sep > 0) {
                    val desc = text.substring(0, sep).trim().uppercase()
                    if (desc == "LYRICS" || desc == "UNSYNCEDLYRICS" || desc == "LYRIC") {
                        val value = text.substring(sep + 1).trim()
                        if (value.isNotBlank()) return value
                    }
                }
            }
        }
        return null
    }

    private fun parseId3EmbeddedCover(header10: ByteArray, stream: InputStream): ByteArray? {
        val versionMajor = header10[3].toInt() and 0xFF
        val tagSize = syncSafeInt(header10, 6)
        if (tagSize <= 0 || tagSize > MAX_ID3_TAG_BYTES) return null
        val tagBytes = ByteArray(tagSize)
        val total = readFullyUpTo(stream, tagBytes, tagSize)
        if (total < 10) return null

        val buf = ByteBuffer.wrap(tagBytes, 0, total)
        var fallback: ByteArray? = null
        while (buf.remaining() >= 10) {
            val idBytes = ByteArray(4)
            buf.get(idBytes)
            if (idBytes[0].toInt() == 0) break
            val frameId = String(idBytes, Charsets.US_ASCII)
            val frameSize = if (versionMajor >= 4) {
                val s = ByteArray(4)
                buf.get(s)
                syncSafeInt(s, 0)
            } else {
                buf.int
            }
            buf.short // flags
            if (frameSize <= 0 || frameSize > buf.remaining()) break
            val frameData = ByteArray(frameSize)
            buf.get(frameData)

            if (frameId == "APIC" && frameData.size > 4) {
                val encoding = frameData[0].toInt() and 0xFF
                var pos = 1
                // Skip null-terminated MIME type (ISO-8859-1)
                while (pos < frameData.size && frameData[pos].toInt() != 0) pos++
                pos++ // skip NUL
                if (pos >= frameData.size) continue
                val picType = frameData[pos].toInt() and 0xFF
                pos++
                // Skip null-terminated description
                pos = skipNullTerminated(frameData, pos, encoding)
                if (pos < frameData.size) {
                    val img = frameData.copyOfRange(pos, frameData.size)
                    if (img.isNotEmpty()) {
                        if (picType == 3) return img
                        if (fallback == null) fallback = img
                    }
                }
            }
        }
        return fallback
    }

    private fun decodeId3StringAfterDescriptor(data: ByteArray, start: Int, encoding: Int): String? {
        val bodyStart = skipNullTerminated(data, start, encoding)
        if (bodyStart >= data.size) return null
        return decodeId3Text(data, bodyStart, data.size - bodyStart, encoding).trim().takeIf { it.isNotBlank() }
    }

    private fun skipNullTerminated(data: ByteArray, start: Int, encoding: Int): Int {
        var pos = start
        return if (encoding == 1 || encoding == 2) {
            while (pos + 1 < data.size) {
                if (data[pos].toInt() == 0 && data[pos + 1].toInt() == 0) {
                    return pos + 2
                }
                pos += 2
            }
            data.size
        } else {
            while (pos < data.size && data[pos].toInt() != 0) pos++
            if (pos < data.size) pos + 1 else data.size
        }
    }

    private fun decodeId3Text(data: ByteArray, offset: Int, length: Int, encoding: Int): String {
        if (length <= 0 || offset >= data.size) return ""
        val safeLen = minOf(length, data.size - offset)
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(data, offset, safeLen, charset)
    }

    private fun syncSafeInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun readFullyUpTo(stream: InputStream, dst: ByteArray, count: Int): Int {
        var total = 0
        while (total < count) {
            val n = stream.read(dst, total, count - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        val discard = ByteArray(4096)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val r = stream.read(discard, 0, minOf(remaining, discard.size.toLong()).toInt())
                if (r <= 0) break
                remaining -= r.toLong()
            }
        }
    }

    private fun readBytesLimited(stream: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val toRead = minOf(buf.size, maxBytes - total)
            val n = stream.read(buf, 0, toRead)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
