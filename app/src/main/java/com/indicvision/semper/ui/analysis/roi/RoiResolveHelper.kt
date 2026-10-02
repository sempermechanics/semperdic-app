package com.indicvision.semper.ui.analysis.roi

import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.field.Roi
import com.indicvision.semper.ui.analysis.recommend.SubsetRecommender

/**
 * ROI math for the analysis wizard, over the wizard's loose `roiX..roiH` and
 * reference size: inset a full-frame solve so subsets stay on-image, and cap
 * subset size so the engine still has grid points after its edge buffer.
 * The geometry itself is [Roi]'s.
 */
object RoiResolveHelper {

    /**
     * Clearance the engine demands around a grid point on top of half its
     * subset: 4 px of interpolation buffer plus a 15 px deformation buffer
     * (SemperJNI.cpp, `absolute_boundary_buffer`). Points inside it are
     * dropped, and a solve with no points left returns an ROI engine error.
     */
    const val ENGINE_EDGE_BUFFER_PX = 19

    /** Slack [resolve] adds beyond half a subset when insetting a frame. */
    const val ROI_MARGIN_SLACK_PX = Roi.FULL_FRAME_SLACK_PX

    /**
     * Returns `[x, y, w, h]` for the rectangle the engine solves over, or null
     * if it cannot hold one [subset]-sized window ([Roi.forSolve]).
     *
     * A custom ROI is clipped to the image first. It is kept across a
     * reference of the same size, and restored from a saved state, so it is
     * never assumed to fit: the engine's grid would otherwise start off the
     * image and the run fail with an ROI error instead of this check.
     */
    @Suppress("LongParameterList") // the wizard's loose ROI and image fields
    fun resolve(
        subset: Int,
        hasCustomRoi: Boolean,
        roiX: Int,
        roiY: Int,
        roiW: Int,
        roiH: Int,
        realRefWidth: Int,
        realRefHeight: Int,
    ): IntArray? = Roi.forSolve(
        subset = subset,
        hasCustomRoi = hasCustomRoi,
        drawn = Roi(roiX, roiY, roiW, roiH),
        size = ImageSize(realRefWidth, realRefHeight),
    )?.toXywh()

    /**
     * Largest odd subset the loaded image and ROI can actually hold, given
     * [ENGINE_EDGE_BUFFER_PX]. A custom ROI counts only the part inside the
     * image, at its own position. Kept inside [SubsetRecommender]'s slider range.
     */
    @Suppress("LongParameterList") // the wizard's loose ROI and image fields
    fun maxSubsetForRoi(
        hasCustomRoi: Boolean,
        roiX: Int,
        roiY: Int,
        roiW: Int,
        roiH: Int,
        realRefWidth: Int,
        realRefHeight: Int,
    ): Int = maxSubsetForRoi(hasCustomRoi, Roi(roiX, roiY, roiW, roiH), ImageSize(realRefWidth, realRefHeight))

    private fun maxSubsetForRoi(hasCustomRoi: Boolean, roi: Roi, size: ImageSize): Int {
        if (!size.isKnown) return SubsetRecommender.MAX_SUBSET
        val fits = if (hasCustomRoi) {
            val clipped = roi.clampTo(size)
            minOf(clipped?.w ?: 0, clipped?.h ?: 0) - 2 * ENGINE_EDGE_BUFFER_PX
        } else {
            // Full frame is inset by (subset/2 + slack) a side and must still be
            // one subset wide: imgW - 2*(s/2 + slack) >= s  =>  s <= imgW/2 - slack.
            minOf(size.width, size.height) / 2 - ROI_MARGIN_SLACK_PX
        }
        return (fits - 1 or 1).coerceIn(SubsetRecommender.MIN_SUBSET, SubsetRecommender.MAX_SUBSET)
    }
}
