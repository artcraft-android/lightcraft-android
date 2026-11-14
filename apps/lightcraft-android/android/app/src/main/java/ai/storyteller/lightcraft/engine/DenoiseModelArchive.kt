package ai.storyteller.lightcraft.engine

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

internal object DenoiseModelArchive {
    private const val MODEL = "model.onnx"
    private const val MANIFEST = "denoise-model.json"
    private const val MODEL_LIMIT = 2L * 1024 * 1024 * 1024
    private const val MANIFEST_LIMIT = 64L * 1024
    private const val EXTRA_LIMIT = 16L * 1024 * 1024

    fun extract(input: InputStream, destination: File) {
        val copied = mutableSetOf<String>()
        var commonParent: String? = null
        var expandedBytes = 0L

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.replace('\\', '/')
                if (
                    path.startsWith("/") ||
                    path.matches(Regex("^[A-Za-z]:.*")) ||
                    path.split('/').any { it == ".." }
                ) {
                    throw IOException("The ZIP contains an unsafe path")
                }

                val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
                val name = parts.lastOrNull()
                val outputName = when {
                    entry.isDirectory -> null
                    name?.equals(MANIFEST, ignoreCase = true) == true -> MANIFEST
                    name?.endsWith(".onnx", ignoreCase = true) == true -> MODEL
                    else -> null
                }
                if (outputName != null) {
                    val folder = parts.dropLast(1).joinToString("/")
                    if (commonParent != null && commonParent != folder) {
                        throw IOException("The ONNX model and denoise-model.json must be in the same folder in the ZIP")
                    }
                    commonParent = folder
                    if (!copied.add(outputName)) throw IOException("The ZIP contains more than one $outputName")
                    val limit = if (outputName == MODEL) MODEL_LIMIT else MANIFEST_LIMIT
                    if (entry.size > limit) throw IOException("$name is larger than expected")
                }

                val output = outputName?.let { FileOutputStream(File(destination, it)) }
                output.use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var entryBytes = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        expandedBytes += count
                        if (expandedBytes > MODEL_LIMIT + EXTRA_LIMIT) {
                            throw IOException("The ZIP expands beyond the allowed denoise model size")
                        }
                        if (outputName != null) {
                            val limit = if (outputName == MODEL) MODEL_LIMIT else MANIFEST_LIMIT
                            if (entryBytes > limit) throw IOException("$name is larger than expected")
                            out?.write(buffer, 0, count)
                        }
                    }
                    if (outputName != null && entry.size >= 0 && entryBytes != entry.size) {
                        throw IOException("$name is incomplete in the ZIP")
                    }
                    if (outputName != null && entryBytes == 0L) throw IOException("$name is empty")
                }
                zip.closeEntry()
            }
        }

        val missing = setOf(MODEL, MANIFEST) - copied
        if (missing.isNotEmpty()) throw IOException("The ZIP is missing ${missing.sorted().joinToString()}")
    }
}
