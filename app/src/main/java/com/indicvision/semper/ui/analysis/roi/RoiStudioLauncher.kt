package com.indicvision.semper.ui.analysis.roi

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import com.indicvision.semper.data.session.CacheJanitor
import com.indicvision.semper.field.ImageSizeExtras
import com.indicvision.semper.field.Roi
import com.indicvision.semper.field.getRoiExtras
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.ui.analysis.RoiDrawActivity
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.common.dialog.Feedback
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
        val bytes = viewModel.refBytes
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
        val tempFile = File(activity.cacheDir, CacheJanitor.TEMP_ROI_REF)
        activity.lifecycleScope.launch {
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
            intent.putExtra(DicKeys.IMAGE_FILE_PATH, tempFile.absolutePath)
            ImageSizeExtras.ROI_EDITOR.put(intent, viewModel.refSize)
            launcher.launch(intent)
        }
    }

    /**
     * Adopt the ROI the studio returned.
     *
     * The freeform mask is read on [io] — one byte per reference pixel, so
     * tens of megabytes on a modern sensor, and reading it inline stalled the
     * very frame that had to draw the updated summary. Everything that depends
     * on the mask stays after the read, in order.
     */
    internal fun applyRoiResult(data: Intent) {
        // After a process death whose draft was lost there is no reference
        // left to measure this ROI against.
        if (viewModel.realRefWidth == 0) {
            Timber.w("ROI result with no reference; ignored")
            return
        }
        val drawn = data.getRoiExtras(default = Roi.full(viewModel.refSize))
        viewModel.roi = drawn
        // A selection covering the whole image counts as no custom ROI.
        viewModel.hasCustomRoi = !drawn.coversFrameOf(viewModel.refSize)

        val maskPath = data.getStringExtra(DicKeys.MASK_FILE_PATH)
        activity.lifecycleScope.launch {
            val mask = maskPath?.let { path -> withContext(io) { readMask(File(path)) } }
            if (mask != null) viewModel.roiMaskBytes = mask
            onRoiChanged()
        }
    }

    /** Clears a custom crop and treats the whole reference frame as the ROI. */
    internal fun applyFullImageRoi() {
        if (viewModel.realRefWidth > 0) {
            viewModel.hasCustomRoi = false
            viewModel.roiMaskBytes = null
            viewModel.roi = Roi.full(viewModel.refSize)
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
