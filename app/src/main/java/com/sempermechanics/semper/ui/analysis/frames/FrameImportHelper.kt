package com.sempermechanics.semper.ui.analysis.frames

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.imaging.BitmapDecoder
import com.sempermechanics.semper.imaging.ExifOrientedSize
import com.sempermechanics.semper.util.forEachChunk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * Result of importing a deformed-frame batch into cacheDir/temp_deformed:
 * the frames in path order, each with the name the user picked it as and its
 * measured size. Their dates are unknown until a sort by date asks.
 */
data class ImportedBatch(
    val frames: List<DeformedFrame>,
    val fromVideo: Boolean = false,
) {
    /** The staged path of each of [frames]. */
    val filePaths: List<String> get() = frames.map { it.path }
}

/**
 * URI → temp-file import for deformed frames. Call [importDeformedUris] off the
 * main thread; dimension probes use bounds-only decode when possible, with a
 * JNI fallback on the pinned native dispatcher.
 */
object FrameImportHelper {

    /** Cap [uris] to [maxFrames]; return the capped list (caller shows toast if truncated). */
    fun cappedUris(uris: List<Uri>, maxFrames: Int): List<Uri> = uris.take(maxFrames)

    /** Writes a complete batch through a staging directory, then commits it. */
    suspend fun importDeformedUris(
        context: Context,
        uris: List<Uri>,
        cacheDir: File,
        displayName: (Uri) -> String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportedBatch? {
        val stagingDir = createStagingDir(cacheDir)

        // Each keeps the original picked filename, so exports can use the
        // user's real (default) names instead of the sanitized temp names,
        // and the pixel size, so the reference-match check is free later.
        val staged = mutableListOf<DeformedFrame>()

        try {
            onProgress(0, uris.size)
            for ((index, uri) in uris.withIndex()) {
                currentCoroutineContext().ensureActive()
                val originalName = displayName(uri)
                val isRaw = originalName.endsWith(".dng", true) || originalName.endsWith(".raw", true)

                val sanitizedName = originalName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
                val filename = String.format(Locale.US, "%04d_%s", index, sanitizedName)
                val file = File(stagingDir, filename)

                val frameSize: ImageSize? = if (isRaw) {
                    val size = importRawUri(context, uri, file)
                    currentCoroutineContext().ensureActive()
                    size
                } else {
                    importStreamedUri(context, uri, file)
                }
                currentCoroutineContext().ensureActive()

                onProgress(index + 1, uris.size)

                if (!file.exists() || file.length() == 0L) continue

                staged.add(DeformedFrame(path = file.absolutePath, name = originalName, size = frameSize))
            }

            val stagedBatch = staged.takeIf { it.isNotEmpty() }?.let { frames ->
                ImportedBatch(frames = frames.sortedBy { it.path }, fromVideo = false)
            }
            currentCoroutineContext().ensureActive()
            return commitStagedBatch(cacheDir, stagingDir, stagedBatch)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /**
     * RAW/DNG must become RGBA bytes for the engine — still buffered, but
     * without the unused first-frame preview decode.
     */
    private fun importRawUri(context: Context, uri: Uri, dest: File): ImageSize? =
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapDecoder.writeRgbaFromStream(stream, dest)?.let(ImageSize::of)
        }

    /** Stream URI → file without holding a full ByteArray; probe dims afterwards. */
    private suspend fun importStreamedUri(context: Context, uri: Uri, dest: File): ImageSize? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { copyCancellable(it, dest) }
        currentCoroutineContext().ensureActive()
        return probeImageSize(dest)
    }

    /** Copies [input] to [dest], checking for a cancel before every read. */
    private suspend fun copyCancellable(input: InputStream, dest: File) {
        val job = currentCoroutineContext()
        dest.outputStream().buffered().use { output ->
            job.ensureActive()
            input.forEachChunk { buffer, count ->
                output.write(buffer, 0, count)
                job.ensureActive()
            }
        }
    }

    /**
     * Bounds-only decode when possible; JNI on file bytes if BitmapFactory
     * cannot read the container (rare formats the native stack still accepts).
     *
     * The bounds path is corrected for EXIF orientation and the JNI path is
     * not, on purpose: OpenCV already rotates in `imdecode`, BitmapFactory
     * never does. Both must land on the size the *engine* will see, because
     * this is the number the reference-match check compares against a
     * reference measured through OpenCV. Skipping the correction made a
     * portrait phone photo picked as both reference and deformed frame report
     * a size mismatch against itself. See [ExifOrientedSize].
     */
    private suspend fun probeImageSize(file: File): ImageSize? {
        currentCoroutineContext().ensureActive()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            return ImageSize.of(ExifOrientedSize.applyTo(file, opts.outWidth, opts.outHeight))
        }
        return withContext(SemperNativeLib.nativeDispatcher) {
            currentCoroutineContext().ensureActive()
            runCatching {
                val dims = SemperNativeLib.getImageDimensions(file.readBytes())
                if (dims.size >= 2 && dims[0] > 0 && dims[1] > 0) ImageSize(dims[0], dims[1]) else null
            }.getOrNull()
        }
    }

    /** Cache dir holding the committed import, until a run moves the frames out. */
    const val COMMITTED_DIR_NAME = "temp_deformed"

    /** Previous import, held only for the duration of a commit swap. */
    const val PREVIOUS_DIR_NAME = "temp_deformed_previous"

    /** Prefix of an in-flight import; anything left over is a crashed import. */
    const val STAGING_DIR_PREFIX = "temp_deformed_staging_"

    internal fun createStagingDir(cacheDir: File): File =
        File(cacheDir, "$STAGING_DIR_PREFIX${System.nanoTime()}").apply {
            check(mkdirs()) { "Could not create frame staging directory" }
        }

    /**
     * Swap a complete staged batch into the committed location. The previous
     * batch is renamed aside first and restored if the new rename fails.
     */
    internal fun commitStagedBatch(
        cacheDir: File,
        stagingDir: File,
        batch: ImportedBatch?,
    ): ImportedBatch? {
        val committedDir = File(cacheDir, COMMITTED_DIR_NAME)
        val previousDir = File(cacheDir, PREVIOUS_DIR_NAME)
        previousDir.deleteRecursively()

        if (committedDir.exists()) {
            check(committedDir.renameTo(previousDir)) { "Could not preserve previous imported frames" }
        }
        if (!stagingDir.renameTo(committedDir)) {
            previousDir.renameTo(committedDir)
            error("Could not commit imported frames")
        }
        previousDir.deleteRecursively()

        return batch?.copy(
            frames = batch.frames.map { it.copy(path = File(committedDir, File(it.path).name).absolutePath) },
        )
    }
}
