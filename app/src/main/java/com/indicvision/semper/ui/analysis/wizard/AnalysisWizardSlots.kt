package com.indicvision.semper.ui.analysis.wizard

import android.graphics.Bitmap
import android.view.View
import androidx.core.view.isVisible
import com.indicvision.semper.R
import com.indicvision.semper.databinding.ActivityStaticAnalysisBinding
import com.indicvision.semper.databinding.WizardStepSettingsContentBinding
import com.indicvision.semper.ui.analysis.frames.FrameOrderAdapter
import com.indicvision.semper.ui.analysis.frames.FrameOrderMode
import com.indicvision.semper.ui.common.WarnChip

/**
 * Load-frames and confirm-settings slot chrome: dropzones vs filled cards,
 * lossy-format warning, ROI subtitle.
 *
 * Readiness / Compute enablement stays in [AnalysisReadyGate].
 */
class AnalysisWizardSlots(
    private val viewModel: AnalysisViewModel,
    private val binding: ActivityStaticAnalysisBinding,
    private val settings: WizardStepSettingsContentBinding,
    private val formatChip: WarnChip,
    private val frameOrderAdapter: FrameOrderAdapter,
    private val onLineCutPreview: () -> Unit,
) {
    private val activity = binding.root.context

    /** Reference slot: dropzone when empty, summary card when filled. */
    fun refreshRefSlot(preview: Bitmap?) {
        val hasRef = viewModel.refBytes != null
        binding.refDropzone.visibility = if (hasRef) View.GONE else View.VISIBLE
        binding.refCard.visibility = if (hasRef) View.VISIBLE else View.GONE
        if (hasRef) {
            binding.tvRefName.text = viewModel.refName
            binding.tvRefMeta.text = activity.getString(
                R.string.reference_meta_fmt,
                viewModel.realRefWidth,
                viewModel.realRefHeight,
            )
            preview?.let { binding.ivRefThumb.setImageBitmap(it) }
        }
        updateFormatChip()
    }

    /** Deformed slot: dropzone when empty, count card + order strip when filled. */
    fun refreshDefSlot() {
        val n = viewModel.defFilePaths.size
        binding.defDropzone.visibility = if (n > 0) View.GONE else View.VISIBLE
        binding.defCard.visibility = if (n > 0) View.VISIBLE else View.GONE
        if (n > 0) {
            binding.tvDefName.text = activity.resources.getQuantityString(R.plurals.def_count_fmt, n, n)
            binding.tvDefMeta.text = deformedRangeLabel(viewModel.defFilePaths, viewModel.defOriginalNames)
            // Match the icon to what the user actually picked — the frames are
            // image files either way, so only the source tells them apart.
            binding.ivDefIcon.setImageResource(
                if (viewModel.defFromVideo) R.drawable.ic_video else R.drawable.ic_photos_share,
            )
            binding.rvFrameOrder.isVisible = true
            frameOrderAdapter.submit(viewModel.defFilePaths)
            val showSort = n > 1 && !viewModel.defFromVideo
            binding.btnFrameOrderSort.visibility = if (showSort) View.VISIBLE else View.GONE
            frameOrderAdapter.dragEnabled =
                showSort &&
                viewModel.defOrderMode == FrameOrderMode.MANUAL
        } else {
            binding.rvFrameOrder.isVisible = false
            binding.btnFrameOrderSort.isVisible = false
            frameOrderAdapter.submit(emptyList())
        }
        updateFormatChip()
    }

    /** ROI card subtitle reflecting the current selection. */
    fun updateRoiSummary() {
        settings.tvInstruction.text = if (!viewModel.hasCustomRoi) {
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
        val message = activity.getString(R.string.lossy_format_warning_fmt, lossy.joinToString(", "))
        formatChip.showOrHide(message.takeIf { lossy.isNotEmpty() })
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
