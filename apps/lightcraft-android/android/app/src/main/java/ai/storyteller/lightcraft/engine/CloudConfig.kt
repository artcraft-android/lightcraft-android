package ai.storyteller.lightcraft.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File

/**
 * The cloud library's side of the file system (Preferences ▸ Cloud library): the rclone config file
 * the person picked, kept in the app's own files, and the names that go with the connection.
 */
object CloudConfig {
    /** The rclone config the connection uses: a copy of the picked file, in the app's private files. */
    fun file(context: Context): File = File(context.filesDir, "cloud/rclone.conf")

    /** Copy the config file at [uri] over the app's copy (a config is small; anything over 1 MB is not one). */
    fun import(context: Context, uri: Uri): File {
        val target = file(context)
        target.parentFile?.mkdirs()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_CONFIG + 1) } ?: error("the file couldn't be read")
        if (bytes.size > MAX_CONFIG) error("that isn't an rclone config file (too large)")
        target.writeBytes(bytes)
        return target
    }

    /** The `[section]` names of an rclone config: its remotes, in file order. */
    fun remotes(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.length > 2 && it.startsWith("[") && it.endsWith("]") }
        .map { it.substring(1, it.length - 1).trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    /** This phone's name for the PC's inbox: [Build.MODEL] cut to what the cloud accepts (`[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}`). */
    fun defaultDeviceName(): String = sanitiseDevice(Build.MODEL.orEmpty())

    fun sanitiseDevice(name: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trimStart('.').take(64).trim('-')
        return cleaned.ifEmpty { "phone" }
    }

    /** Whether [name] is a device name the cloud accepts. */
    fun validDevice(name: String): Boolean = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}").matches(name)

    /** The rclone path of the library: the remote, a colon, and the folder on it (empty: the remote's root). */
    fun remotePath(remote: String, folder: String): String = "$remote:${folder.trim().trim('/')}"

    private const val MAX_CONFIG = 1 shl 20
}
