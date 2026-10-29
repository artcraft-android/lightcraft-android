package ai.storyteller.lightcraft.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File

/**
 * The folder library's side of the file system (Preferences ▸ Library folder): the engine edits the
 * photos of a folder in place, so it needs the folder as a path and the app needs "All files
 * access" to read and write there.
 */
object LibraryFolder {
    /** Whether the folder library can be used on this Android version (it needs "All files access", from Android 11). */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Whether the person has granted "All files access". */
    fun allowed(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /**
     * The folder a tree [uri] from the system's folder picker is, as a path, or null when it isn't
     * one (a cloud provider's, say) or its volume can't be found. The picker gives
     * `…/tree/primary:DCIM/Camera`: the volume's id (`primary` is the phone's own storage, the
     * others are SD cards and USB drives named by their uuid), a colon and the path below it.
     */
    fun pathOf(context: Context, uri: Uri): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || uri.authority != EXTERNAL_STORAGE_DOCUMENTS) return null
        val document = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val volume = document.substringBefore(':')
        val below = document.substringAfter(':', "")
        val root = if (volume == "primary") {
            Environment.getExternalStorageDirectory()
        } else {
            context.getSystemService(StorageManager::class.java)?.storageVolumes?.firstOrNull { it.uuid.equals(volume, ignoreCase = true) }?.directory
        } ?: return null
        // no `..` to climb out of the volume
        val folder = File(root, below).canonicalFile
        return folder.takeIf { it.isDirectory && it.path.startsWith(root.canonicalPath) }?.path
    }

    private const val EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents"
}
