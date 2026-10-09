package com.sempermechanics.semper.ui.analysis.frames

import android.content.Context
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.ActivityStaticAnalysisBinding
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.ui.common.InlineBusy
import kotlin.math.roundToInt

/**
 * Page 1's reference slot while a pick is read, laid over the dropzone or the
 * card: a skeleton thumbnail and "Decoding RAW · 24 MP" while an image
 * decodes ([ReferenceImportController]), a spinner and "Reading video…" while
 * a video's metadata is read ([VideoSamplingSheet]). Each shows only past
 * [InlineBusy.SHOW_AFTER_MS].
 */
class ReferenceSlotBusy(owner: LifecycleOwner, private val views: ActivityStaticAnalysisBinding) {

    private val decoding = InlineBusy(owner, views.refBusy, pulse = views.refBusySkeleton)
    private val reading = InlineBusy(owner, views.refBusy, spinner = views.refBusySpinner)

    /** The decode's line; [decodingSize] can refine it while the wait runs. */
    private var decodingLine = ""

    /** Runs [block], the decode of a pick, under the skeleton and [decodingMeta]'s line. */
    suspend fun <T> decoding(isRaw: Boolean, block: suspend () -> T): T {
        decodingLine = decodingMeta(views.root.context, isRaw, size = null)
        return decoding.around(onShow = {
            views.refBusySkeleton.isVisible = true
            views.tvRefBusy.text = decodingLine
        }) { block() }
    }

    /** The RAW's size is known: the line adds its megapixels. */
    fun decodingSize(size: ImageSize?) {
        decodingLine = decodingMeta(views.root.context, isRaw = true, size = size)
        if (decoding.isShown) views.tvRefBusy.text = decodingLine
    }

    /** Runs [block], reading a video's metadata, under the spinner and "Reading video…". */
    suspend fun <T> readingVideo(block: suspend () -> T): T = reading.around(onShow = {
        views.refBusySkeleton.isVisible = false
        views.tvRefBusy.setText(R.string.video_reading)
    }) { block() }

    companion object {
        private const val PIXELS_PER_MP = 1_000_000.0

        /**
         * "Decoding RAW · 24 MP" for a RAW/DNG whose [size] is known, "Decoding
         * RAW" before then, "Decoding image" for anything else.
         */
        fun decodingMeta(context: Context, isRaw: Boolean, size: ImageSize?): String {
            return when {
                !isRaw -> context.getString(R.string.reference_decoding_image)
                size == null || !size.isKnown -> context.getString(R.string.reference_decoding_raw)
                else -> context.getString(
                    R.string.reference_decoding_raw_mp_fmt,
                    (size.width.toDouble() * size.height / PIXELS_PER_MP).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }
}
