// ROI resolution takes the full image+ROI geometry set and clamps it with the
// engine's literal edge buffers; both read clearest passed/inlined directly.
@file:Suppress("LongParameterList", "MagicNumber")

package com.indicvision.semper.ui.analysis

/**
 * Pure ROI math for the analysis wizard: inset a full-frame solve so subsets
 * stay on-image, and cap subset size so the engine still has grid points after
 * its edge buffer.
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
    const val ROI_MARGIN_SLACK_PX = 10

    /**
     * Returns `[x, y, w, h]` for the rectangle the engine solves over, or null
     * if it cannot hold one [subset]-sized window.
     *
     * A custom ROI is clipped to the image first. It is kept across a
     * reference of the same size, and restored from a saved state, so it is
     * never assumed to fit: the engine's grid would otherwise start off the
     * image and the run fail with an ROI error instead of this check.
     */
    fun resolve(
        subset: Int,
        hasCustomRoi: Boolean,
        roiX: Int,
        roiY: Int,
        roiW: Int,
        roiH: Int,
        realRefWidth: Int,
        realRefHeight: Int,
    ): IntArray? {
        val roi = if (hasCustomRoi) {
            clipToImage(roiX, roiY, roiW, roiH, realRefWidth, realRefHeight)
        } else {
            val margin = (subset / 2) + ROI_MARGIN_SLACK_PX
            intArrayOf(
                margin,
                margin,
                realRefWidth - (2 * margin),
                realRefHeight - (2 * margin),
            )
        }
        return roi?.takeIf { it[2] >= subset && it[3] >= subset }
    }

    /**
     * Largest odd subset the loaded image and ROI can actually hold, given
     * [ENGINE_EDGE_BUFFER_PX]. A custom ROI counts only the part inside the
     * image, at its own position. Kept inside [SubsetRecommender]'s slider range.
     */
    fun maxSubsetForRoi(
        hasCustomRoi: Boolean,
        roiX: Int,
        roiY: Int,
        roiW: Int,
        roiH: Int,
        realRefWidth: Int,
        realRefHeight: Int,
    ): Int {
        val w = realRefWidth
        val h = realRefHeight
        if (w <= 0 || h <= 0) return SubsetRecommender.MAX_SUBSET
        val fits = if (hasCustomRoi) {
            val clipped = clipToImage(roiX, roiY, roiW, roiH, w, h)
            minOf(clipped?.get(2) ?: 0, clipped?.get(3) ?: 0) - 2 * ENGINE_EDGE_BUFFER_PX
        } else {
            // Full frame is inset by (subset/2 + slack) a side and must still be
            // one subset wide: imgW - 2*(s/2 + slack) >= s  =>  s <= imgW/2 - slack.
            minOf(w, h) / 2 - ROI_MARGIN_SLACK_PX
        }
        return (fits - 1 or 1).coerceIn(SubsetRecommender.MIN_SUBSET, SubsetRecommender.MAX_SUBSET)
    }

    /**
     * `[x, y, w, h]` of the part of the ROI inside a [width] x [height] image,
     * or null when none of it is (or the image size is unknown).
     */
    fun clipToImage(x: Int, y: Int, w: Int, h: Int, width: Int, height: Int): IntArray? {
        if (width <= 0 || height <= 0) return null
        val left = x.coerceIn(0, width)
        val top = y.coerceIn(0, height)
        val right = (x.toLong() + w).coerceIn(left.toLong(), width.toLong()).toInt()
        val bottom = (y.toLong() + h).coerceIn(top.toLong(), height.toLong()).toInt()
        return if (right > left && bottom > top) intArrayOf(left, top, right - left, bottom - top) else null
    }
}
