package Akari.NCM.player.data

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.core.PlayableMedia
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalFolderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionManager: UserSessionManager,
    private val playlistManager: PlaylistManager,
    private val ncmApi: NcmApi
) {
    private val prefs = context.getSharedPreferences("local_folders", Context.MODE_PRIVATE)
    
    fun addDirectory(uri: String) {
        val current = getDirectories().toMutableSet()
        current.add(uri)
        prefs.edit().putStringSet("folders", current).apply()
    }
    
    fun removeDirectory(uri: String) {
        val current = getDirectories().toMutableSet()
        current.remove(uri)
        prefs.edit().putStringSet("folders", current).apply()
    }
    
    fun getDirectories(): Set<String> {
        return prefs.getStringSet("folders", emptySet()) ?: emptySet()
    }
    
    suspend fun scanAllFolders() = withContext(Dispatchers.IO) {
        val dirs = getDirectories()
        val allMedia = mutableListOf<PlayableMedia>()
        val cr = context.contentResolver
        
        for (dirUriStr in dirs) {
            try {
                val treeUri = Uri.parse(dirUriStr)
                try {
                    cr.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    Log.w("LocalFolder", "takePersistableUriPermission failed for $dirUriStr")
                }
                
                val root = DocumentFile.fromTreeUri(context, treeUri) ?: continue
                val queue = mutableListOf(root)
                val audioFiles = mutableListOf<DocumentFile>()
                while (queue.isNotEmpty()) {
                    val currentDir = queue.removeAt(0)
                    for (file in currentDir.listFiles()) {
                        if (file.isDirectory) {
                            queue.add(file)
                        } else if (file.isFile) {
                            val name = file.name?.lowercase() ?: ""
                            if (name.endsWith(".mp3") || name.endsWith(".flac") || name.endsWith(".ncm") || name.endsWith(".wav") || name.endsWith(".m4a")) {
                                audioFiles.add(file)
                            }
                        }
                    }
                }

                for (file in audioFiles) {
                    val media = NcmFileScanner.scanUri(file.uri, file.name ?: "")
                    if (media != null) {
                        allMedia.add(media)
                    }
                }
            } catch (e: Exception) {
                Log.e("LocalFolder", "Error scanning $dirUriStr", e)
            }
        }
        
        playlistManager.saveOrUpdateSyncedPlaylist(
            ncmPlaylistId = -9999L,
            name = "本地扫描文件夹",
            coverImgUrl = null,
            songs = allMedia
        )
    }
}
