package com.sempermechanics.semper.ui.analysis.recommend

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import com.sempermechanics.semper.R

/**
 * The subset sizes that hold enough of the measured speckle, shaded behind
 * the subset slider's track: from the smallest size that meets both the
 * SSSIG criterion and the three-speckle span, up to the slider's end. A
 * larger subset still correlates; it only trades spatial resolution.
 *
 * Drawn as the slider's background, under its track and thumb, so the slider
 * keeps its own touch and drawing.
 */
class SubsetBand private constructor(private val slider: Slider) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(ContextCompat.getColor(slider.context, R.color.sky_primary), BAND_ALPHA)
    }
    private val rect = RectF()

    /** The shaded sizes, or null for none. */
    var range: IntRange? = null
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val band = range ?: return
        val from = slider.valueFrom
        val to = slider.valueTo
        if (to <= from || slider.trackWidth <= 0) return
        val left = bounds.left + slider.trackSidePadding.toFloat()
        val width = slider.trackWidth.toFloat()
        fun x(value: Int) = left + (value.toFloat().coerceIn(from, to) - from) / (to - from) * width
        val half = slider.trackHeight * BAND_HEIGHT_TRACKS / 2f
        val centre = bounds.exactCenterY()
        rect.set(x(band.first), centre - half, x(band.last), centre + half)
        canvas.drawRoundRect(rect, half, half, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private const val BAND_ALPHA = 0x40

        /** The band is this many track heights tall, so it reads past the thumb. */
        private const val BAND_HEIGHT_TRACKS = 3f

        /** Puts a band behind [slider], over whatever background it had, and returns it. */
        fun attach(slider: Slider): SubsetBand {
            val band = SubsetBand(slider)
            val previous = slider.background
            slider.background = if (previous != null) LayerDrawable(arrayOf(band, previous)) else band
            return band
        }

        /**
         * The sizes that hold enough speckle for [rec], within [sizes]: from
         * the larger of its SSSIG size and its three-speckle span. Null when
         * even the largest size falls short.
         */
        fun rangeFor(rec: SubsetRecommender.Recommendation, sizes: IntRange): IntRange? {
            val least = maxOf(rec.subsetSize, rec.subsetSpanningSpeckles ?: rec.subsetSize)
            return (least.coerceAtLeast(sizes.first)..sizes.last).takeUnless { it.isEmpty() }
        }

        /**
         * "Speckle 4.3 px" in the chip beside the subset size, its good-practice
         * band read out to TalkBack, amber outside the band; gone without a
         * measured [diameterPx].
         */
        fun showChip(chip: Chip, diameterPx: Double?) {
            chip.isVisible = diameterPx != null
            if (diameterPx == null) return
            val res = chip.resources
            chip.text = res.getString(R.string.speckle_chip_fmt, diameterPx)
            chip.contentDescription = res.getString(
                R.string.speckle_readout_fmt,
                diameterPx,
                DicGoodPractice.MIN_SPECKLE_PX.toInt(),
                DicGoodPractice.MAX_SPECKLE_PX.toInt(),
            )
            val usable = DicGoodPractice.verdictFor(diameterPx) == DicGoodPractice.Verdict.USABLE
            val color = if (usable) R.color.viewer_chrome_text else R.color.semantic_warning
            chip.setTextColor(chip.context.getColor(color))
        }

        /** "Measuring speckle…" in the chip while a measurement outlasts 300 ms (InlineBusy shows it). */
        fun showMeasuring(chip: Chip) {
            chip.setText(R.string.speckle_measuring)
            chip.contentDescription = null
            chip.setTextColor(chip.context.getColor(R.color.viewer_chrome_text))
        }
    }
}
