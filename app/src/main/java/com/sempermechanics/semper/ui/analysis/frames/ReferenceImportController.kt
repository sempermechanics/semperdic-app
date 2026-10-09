package com.sempermechanics.semper.ui.analysis.frames

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.imaging.BitmapDecoder
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.ReferencePreviewLoader
import com.sempermechanics.semper.ui.common.SerialJob
import com.sempermechanics.semper.ui.common.dialog.FaqRedirect
import com.sempermechanics.semper.ui.common.media.displayNameOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream

/**
 * Loads a picked reference image into the view model, decoding it on [io]:
 * a RAW/DNG pick to an RGBA blob, anything else through the native decoder,
 * which applies EXIF as the engine does. [onLoaded] gets the preview once the
 * view model holds the new reference.
 *
 * A pick cancels the one still loading, so the last image picked is the one
 * that lands, not the last one to finish decoding. While it decodes, [busy]
 * (when given) lays the skeleton over the slot.
 */
class ReferenceImportController(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val onLoaded: (preview: Bitmap?) -> Unit,
    private val busy: ReferenceSlotBusy? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val pick = SerialJob()

    /** Loads [uri] as the reference; a failure is the import's snackbar. */
    @Suppress("TooGenericExceptionCaught") // any failure becomes the import's snackbar
    fun load(uri: Uri) {
        pick.launch(activity.lifecycleScope) {
            try {
                val name = withContext(io) { displayNameOf(activity.contentResolver, uri) }
                val isRaw = name.endsWith(".dng", true) || name.endsWith(".raw", true)
                val loaded = if (busy != null) busy.decoding(isRaw) { read(uri, isRaw, busy) } else read(uri, isRaw)
                if (loaded == null) {
                    FaqRedirect.snackbar(
                        activity,
                        if (isRaw) R.string.failed_decode_raw else R.string.failed_load_reference,
                        R.string.url_faq_import_reference,
                    )
                    return@launch
                }
                viewModel.applyNewReference(loaded.bytes, name, loaded.size)
                onLoaded(loaded.preview)
            } catch (e: CancellationException) {
                // A newer pick, or the screen closing: nothing failed.
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to load reference image")
                FaqRedirect.snackbar(activity, R.string.failed_load_reference, R.string.url_faq_import_reference)
            }
        }
    }

    /** Stops the pick still loading, if any: a video's first frame is about to become the reference. */
    fun cancel() {
        pick.cancel()
    }

    /** Reads and decodes [uri]; a RAW's size goes to [busy] first, for the slot's line. */
    private suspend fun read(uri: Uri, isRaw: Boolean, busy: ReferenceSlotBusy? = null): LoadedReference? {
        if (isRaw && busy != null) busy.decodingSize(withContext(io) { rawSizeOf(activity.contentResolver, uri) })
        return withContext(io) {
            activity.contentResolver.openInputStream(uri)?.use { stream -> decode(stream, isRaw) }
        }
    }

    private class LoadedReference(val bytes: ByteArray, val size: ImageSize, val preview: Bitmap?)

    /** Decode / dimension / preview work for a reference pick. Null when it cannot be decoded. */
    @WorkerThread
    private suspend fun decode(stream: InputStream, isRaw: Boolean): LoadedReference? {
        if (isRaw) {
            return BitmapDecoder.rgbaAndPreviewFromStream(stream)?.let { decoded ->
                LoadedReference(decoded.rgba, ImageSize(decoded.width, decoded.height), decoded.preview)
            }
        }
        val bytes = stream.readBytes()
        // Sizes from the native decoder, which applies EXIF as the engine does.
        val loaded = ReferencePreviewLoader.load(
            ReferencePreviewLoader.Request(bytes, 0, 0, BitmapDecoder.PREVIEW_MAX_EDGE),
        )
        return LoadedReference(bytes, ImageSize(loaded.width, loaded.height), loaded.bitmap)
            .takeIf { loaded.width > 0 && loaded.height > 0 }
    }
}

/**
 * A RAW/DNG's pixel size from its header tags, without decoding it; null when
 * the tags are missing or unreadable. Only for a file the provider can seek:
 * a pipe (no size) would be read through to find them.
 */
@WorkerThread
internal fun rawSizeOf(resolver: ContentResolver, uri: Uri): ImageSize? = runCatching {
    resolver.openFileDescriptor(uri, "r")?.use { fd ->
        if (fd.statSize < 0) return@use null
        val exif = ExifInterface(fd.fileDescriptor)
        ImageSize(
            exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
            exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
        ).takeIf { it.isKnown }
    }
}.getOrNull()
