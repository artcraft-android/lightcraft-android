package ai.storyteller.lightcraft.engine

import ai.storyteller.lightcraft.core.MobileException
import ai.storyteller.lightcraft.core.MobileSession
import ai.storyteller.lightcraft.core.RenderedImage
import ai.storyteller.lightcraft.core.initLogging
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The LightCraft engine (Rust: `apps/lightcraft-android`) as the app uses it: one [MobileSession]
 * on the library in the app's files, and the threads its blocking calls run on. Never call the
 * session from the main thread: commands are quick, renders and imports are not.
 */
class Engine private constructor(
    val session: MobileSession,
    /** Where picked files are copied before the import moves them into the library (same volume). */
    val stagingDir: File,
    /** The app-private folder where denoise models are installed. */
    val denoiseModelsDir: File,
    /** The library's folder (null: the in-memory demo). */
    val libraryDir: File?,
    /** Why the library on disk could not be used, when the session is the in-memory demo instead. */
    val problem: String?,
    /**
     * The longest a zoomed view's frame may be (px): a phone with room for it decodes the photo's
     * every pixel for a sharp view to 100 % and beyond; others stay with the 2560 px preview the
     * view is made from (an original decoded whole takes about half a gigabyte more).
     */
    val zoomEdge: Int,
) {
    /** Commands run one at a time, in the order the UI issued them. */
    val commands: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** The editor's view renders: one at a time, the latest request wins. */
    val viewRenders: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Grid thumbnails (renders of up to 512 px, or the cache). */
    val thumbnails: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

    /**
     * What the open editor shows besides its view: preset, profile and version previews, the masks'
     * pictures, the quick view. Small renders, never queued behind [prefetch] or the grid's.
     */
    val looks: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

    /** Neighbours readied ahead (a full source decode and a screen-size render each): one at a time. */
    val prefetch: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Run [block] on the session, after the commands issued before it. */
    suspend fun <T> command(block: MobileSession.() -> T): T = withContext(commands) { session.block() }

    fun trimMemory() = session.releaseView()

    companion object {
        const val LIBRARY_FOLDER = "LightCraft Library"

        fun open(context: Context): Engine {
            initLogging()
            val library = File(context.filesDir, LIBRARY_FOLDER)
            val staging = File(context.filesDir, "Import").apply { mkdirs() }
            val denoiseModels = File(context.filesDir, "Denoise Models")
            var problem: String? = null
            val session = try {
                // a new library starts with the procedural demo photos, so there is something to edit
                MobileSession.open(library.absolutePath, true)
            } catch (e: MobileException) {
                problem = e.message
                MobileSession.demo()
            }
            session.setDenoiseModelsDir(denoiseModels.absolutePath)
            // The engine budgets a quarter of RAM for its caches (at most 1.5 GB); less on phones
            // the system calls low-RAM.
            val am = context.getSystemService(ActivityManager::class.java)
            if (am?.isLowRamDevice == true) session.setMemoryBudget(256u)
            val ram = am?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo).totalMem } ?: 0L
            val zoomEdge = if (ram >= 7L shl 30) Int.MAX_VALUE else 2560
            return Engine(session, staging, denoiseModels, library.takeIf { problem == null }, problem, zoomEdge)
        }
    }
}

/** The pixels as a bitmap: the engine's RGBA bytes are the `ARGB_8888` memory layout. */
fun RenderedImage.toImageBitmap(): ImageBitmap {
    val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
    return bitmap.asImageBitmap()
}
