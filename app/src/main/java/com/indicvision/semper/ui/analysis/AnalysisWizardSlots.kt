@file:Suppress("LongParameterList")

package com.indicvision.semper.ui.analysis

import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.indicvision.semper.R

/**
 * Load-frames and confirm-settings slot chrome: dropzones vs filled cards,
 * JPEG warning, inputs summary, ROI subtitle.
 *
 * Readiness / Compute enablement stays in [AnalysisReadyGate].
 */
class AnalysisWizardSlots(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val refDropzone: View,
    private val refCard: View,
    private val ivRefThumb: ImageView,
    private val tvRefName: TextView,
    private val tvRefMeta: TextView,
    private val defDropzone: View,
    private val defCard: View,
    private val ivDefIcon: ImageView,
    private val tvDefName: TextView,
    private val tvDefMeta: TextView,
    private val jpegWarnRow: View,
    private val rvFrameOrder: View,
    private val btnFrameOrderSort: View,
    private val frameOrderAdapter: FrameOrderAdapter,
    private val tvInputsTitle: TextView,
    private val tvInputsMeta: TextView,
    private val ivInputsThumb: ImageView,
    private val tvInstruction: TextView,
    private val onLineCutPreview: () -> Unit,
) {

    /** Reference slot: dropzone when empty, summary card when filled. */
    fun refreshRefSlot(preview: Bitmap?) {
        val hasRef = viewModel.refBytes != null
        refDropzone.visibility = if (hasRef) View.GONE else View.VISIBLE
        refCard.visibility = if (hasRef) View.VISIBLE else View.GONE
        if (hasRef) {
            tvRefName.text = viewModel.refName
            tvRefMeta.text = activity.getString(
                R.string.reference_meta_fmt,
                viewModel.realRefWidth,
                viewModel.realRefHeight,
            )
            preview?.let { ivRefThumb.setImageBitmap(it) }
        }
        updateJpegChip()
    }

    /** Deformed slot: dropzone when empty, count card + order strip when filled. */
    fun refreshDefSlot() {
        val n = viewModel.defFilePaths.size
        defDropzone.visibility = if (n > 0) View.GONE else View.VISIBLE
        defCard.visibility = if (n > 0) View.VISIBLE else View.GONE
        if (n > 0) {
            tvDefName.text = activity.resources.getQuantityString(R.plurals.def_count_fmt, n, n)
            val first = viewModel.defFilePaths.first().substringAfterLast('/')
            val last = viewModel.defFilePaths.last().substringAfterLast('/')
            tvDefMeta.text = if (n == 1) first else "$first … $last"
            // Match the icon to what the user actually picked — the frames are
            // image files either way, so only the source tells them apart.
            ivDefIcon.setImageResource(
                if (viewModel.defFromVideo) R.drawable.ic_video else R.drawable.ic_photos_share,
            )
            rvFrameOrder.isVisible = true
            frameOrderAdapter.submit(viewModel.defFilePaths)
            val showSort = n > 1 && !viewModel.defFromVideo
            btnFrameOrderSort.visibility = if (showSort) View.VISIBLE else View.GONE
            frameOrderAdapter.dragEnabled =
                showSort &&
                viewModel.defOrderMode == FrameOrderMode.MANUAL
        } else {
            rvFrameOrder.isVisible = false
            btnFrameOrderSort.isVisible = false
            frameOrderAdapter.submit(emptyList())
        }
        updateJpegChip()
    }

    /** Confirm-settings inputs summary card. */
    fun refreshInputsCard(preview: Bitmap?) {
        tvInputsTitle.text = viewModel.refName
        tvInputsMeta.text = activity.resources.getQuantityString(
            R.plurals.inputs_meta_fmt,
            viewModel.defFilePaths.size,
            viewModel.defFilePaths.size,
        )
        preview?.let { ivInputsThumb.setImageBitmap(it) }
    }

    /** ROI card subtitle reflecting the current selection. */
    fun updateRoiSummary() {
        tvInstruction.text = if (!viewModel.hasCustomRoi) {
            activity.getString(R.string.roi_full_fmt, viewModel.realRefWidth, viewModel.realRefHeight)
        } else {
            activity.getString(
                R.string.roi_custom_fmt,
                viewModel.roiW,
                viewModel.roiH,
                viewModel.roiX,
                viewModel.roiY,
            )
        }
        onLineCutPreview()
    }

    /** Inline, non-blocking JPEG accuracy warning. */
    fun updateJpegChip() {
        val jpeg = viewModel.refName.endsWith(".jpg", true) ||
            viewModel.refName.endsWith(".jpeg", true) ||
            viewModel.defFilePaths.any { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) }
        jpegWarnRow.visibility = if (jpeg) View.VISIBLE else View.GONE
    }
}
