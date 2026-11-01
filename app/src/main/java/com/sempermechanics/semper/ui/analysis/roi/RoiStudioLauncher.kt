package com.sempermechanics.semper.ui.analysis.roi

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.CacheJanitor
import com.sempermechanics.semper.field.ImageSizeExtras
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.getRoiExtras
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.RoiDrawActivity
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.common.dialog.Feedback
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Opens the ROI studio ([RoiDrawActivity]) on the reference and adopts the
 * ROI and mask it returns; a cancelled studio falls back to the full frame.
 * [onRoiChanged] runs once the view model holds the new ROI (and mask).
 *
 * Construct it in `onCreate`: it registers its result launcher.
 */
class RoiStudioLauncher(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val onRoiChanged: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK) {
                data?.let { applyRoiResult(it) }
            } else {
                // Cancelled editor → fall back to full-image ROI.
                applyFullImageRoi()
            }
        }

    /** Opens the studio on the reference, or says to load one first. */
    fun open() {
        val bytes = viewModel.inputs.value.refBytes
        if (bytes == null) {
            Feedback.toast(activity, R.string.load_image_first)
        } else {
            openStudio(bytes)
        }
    }

    /**
     * Hand the reference image to [RoiDrawActivity] through a cache file.
     *
     * The copy runs on [io]: [bytes] is the decoded reference, tens of
     * megabytes for a RAW frame, and writing that from the click handler
     * froze the wizard for the length of the write.
     */
    private fun openStudio(bytes: ByteArray) {
        activity.lifecycleScope.launch {
            // cacheDir checks the directory on disk: resolved on [io] too (readiness TE1-103).
            val tempFile = withContext(io) { File(activity.cacheDir, CacheJanitor.TEMP_ROI_REF) }
            val written = withContext(io) {
                try {
                    tempFile.writeBytes(bytes)
                    true
                } catch (e: IOException) {
                    Timber.e(e, "Failed to write temp ROI reference file")
                    false
                }
            }
            if (!written) {
                Feedback.toast(activity, R.string.failed_save_temp_file)
                return@launch
            }
            val intent = Intent(activity, RoiDrawActivity::class.java)
            intent.putExtra(IntentKeys.IMAGE_FILE_PATH, tempFile.absolutePath)
            ImageSizeExtras.ROI_EDITOR.put(intent, viewModel.inputs.value.refSize)
            launcher.launch(intent)
        }
    }

    /**
     * Adopt the ROI the studio returned, and its mask, in one edit.
     *
     * The freeform mask is read on [io] — one byte per reference pixel, so
     * tens of megabytes on a modern sensor, and reading it inline stalled the
     * very frame that had to draw the updated summary. The ROI waits for it:
     * applied before the read, a Compute tapped meanwhile solved the new ROI
     * with the old mask. A mask the studio wrote but that cannot be read
     * refuses the result, ROI and all, rather than keep the old mask with the
     * new ROI: the wizard stays as it was and says to draw it again. A result
     * with no mask at all has none.
     */
    internal fun applyRoiResult(data: Intent) {
        // After a process death whose draft was lost there is no reference
        // left to measure this ROI against.
        val refSize = viewModel.inputs.value.refSize
        if (refSize.width == 0) {
            Timber.w("ROI result with no reference; ignored")
            return
        }
        val drawn = data.getRoiExtras(default = Roi.full(refSize))
        val maskPath = data.getStringExtra(IntentKeys.MASK_FILE_PATH)
        activity.lifecycleScope.launch {
            val mask = maskPath?.let { path -> withContext(io) { readMask(File(path)) } }
            if (maskPath != null && mask == null) {
                Feedback.toast(activity, R.string.roi_mask_read_failed)
                return@launch
            }
            // Drawn on a reference another one has replaced during the read.
            if (viewModel.inputs.value.refSize != refSize) return@launch
            // A selection covering the whole image counts as no custom ROI.
            viewModel.edit { it.copy(roi = drawn, hasCustomRoi = !drawn.coversFrameOf(refSize), roiMaskBytes = mask) }
            onRoiChanged()
        }
    }

    /** Clears a custom crop and treats the whole reference frame as the ROI. */
    internal fun applyFullImageRoi() {
        if (viewModel.inputs.value.refSize.width > 0) {
            viewModel.edit { it.copy(hasCustomRoi = false, roiMaskBytes = null, roi = Roi.full(it.refSize)) }
        }
        onRoiChanged()
    }

    private fun readMask(file: File): ByteArray? = file.takeIf(File::exists)?.let {
        try {
            it.readBytes()
        } catch (e: IOException) {
            Timber.e(e, "Failed to read ROI mask")
            null
        }
    }
}
