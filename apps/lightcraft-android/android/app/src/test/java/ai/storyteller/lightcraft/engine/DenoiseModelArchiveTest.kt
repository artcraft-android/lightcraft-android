package ai.storyteller.lightcraft.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class DenoiseModelArchiveTest {
    @Test
    fun extractsModelAndManifestFromOneNestedFolder() {
        val root = Files.createTempDirectory("denoise-model-test").toFile()
        try {
            val destination = File(root, "staged").apply { mkdirs() }
            DenoiseModelArchive.extract(
                zip(
                    "package/model.onnx" to byteArrayOf(1, 2, 3),
                    "package/denoise-model.json" to "{}".toByteArray(),
                    "package/README.txt" to "ignored".toByteArray(),
                ),
                destination,
            )
            assertEquals(listOf("denoise-model.json", "model.onnx"), destination.list()?.sorted())
            assertEquals(byteArrayOf(1, 2, 3).toList(), File(destination, "model.onnx").readBytes().toList())
            assertEquals("{}", File(destination, "denoise-model.json").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsafePathsMismatchedFoldersAndMissingFiles() {
        val root = Files.createTempDirectory("denoise-model-test").toFile()
        try {
            val destination = File(root, "staged").apply { mkdirs() }
            expectIOException {
                DenoiseModelArchive.extract(
                    zip(
                        "../model.onnx" to byteArrayOf(1),
                        "../denoise-model.json" to "{}".toByteArray(),
                    ),
                    destination,
                )
            }
            expectIOException {
                DenoiseModelArchive.extract(
                    zip(
                        "one/model.onnx" to byteArrayOf(1),
                        "two/denoise-model.json" to "{}".toByteArray(),
                    ),
                    destination,
                )
            }
            expectIOException {
                DenoiseModelArchive.extract(zip("model.onnx" to byteArrayOf(1)), destination)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents)
                    zip.closeEntry()
                }
            }
            ByteArrayInputStream(bytes.toByteArray())
        }

    private fun expectIOException(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            return
        }
        throw AssertionError("Expected IOException")
    }
}
