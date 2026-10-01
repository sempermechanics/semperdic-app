@file:Suppress("LongParameterList")

package com.indicvision.semper.ui.analysis.wizard

import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.indicvision.semper.R
import com.indicvision.semper.ui.analysis.frames.FrameOrderAdapter
import com.indicvision.semper.ui.analysis.frames.FrameOrderMode

/**
 * Load-frames and confirm-settings slot chrome: dropzones vs filled cards,
 * lossy-format warning, ROI subtitle.
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
    private val formatWarnRow: View,
    private val rvFrameOrder: View,
    private val btnFrameOrderSort: View,
    private val frameOrderAdapter: FrameOrderAdapter,
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
        updateFormatChip()
    }

    /** Deformed slot: dropzone when empty, count card + order strip when filled. */
    fun refreshDefSlot() {
        val n = viewModel.defFilePaths.size
        defDropzone.visibility = if (n > 0) View.GONE else View.VISIBLE
        defCard.visibility = if (n > 0) View.VISIBLE else View.GONE
        if (n > 0) {
            tvDefName.text = activity.resources.getQuantityString(R.plurals.def_count_fmt, n, n)
            tvDefMeta.text = deformedRangeLabel(viewModel.defFilePaths, viewModel.defOriginalNames)
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
        updateFormatChip()
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

    /**
     * Inline, non-blocking accuracy warning naming whichever formats in the
     * set are not lossless — the reference counts too, so a PNG frame set
     * behind a camera-app JPEG reference still says "JPEG", not "PNG".
     */
    fun updateFormatChip() {
        val lossy = LossyFormatCheck.lossyLabels(
            listOf(viewModel.refName) + viewModel.defOriginalNames.ifEmpty { viewModel.defFilePaths },
        )
        formatWarnRow.isVisible = lossy.isNotEmpty()
        if (lossy.isEmpty()) return
        formatWarnRow.findViewById<TextView>(R.id.tvWarnText).text =
            activity.getString(R.string.lossy_format_warning_fmt, lossy.joinToString(", "))
    }
}

/**
 * The deformed card's "first … last": the frames as the user named them
 * ([originalNames], index-aligned with [paths]), not the staged cache copies
 * ("0000_IMG_1234.JPG"). A staged name stands in only where no original is known.
 */
internal fun deformedRangeLabel(paths: List<String>, originalNames: List<String>): String {
    if (paths.isEmpty()) return ""
    val aligned = originalNames.size == paths.size
    fun nameAt(i: Int): String =
        originalNames.getOrNull(i)?.takeIf { aligned && it.isNotBlank() } ?: paths[i].substringAfterLast('/')
    val first = nameAt(0)
    return if (paths.size == 1) first else "$first … ${nameAt(paths.lastIndex)}"
}
