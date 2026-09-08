package com.indicvision.semper.ui.capture

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import kotlin.math.abs

/**
 * The resolution spinner and the list behind it.
 *
 * The catalogue is [CameraCapabilities.Info.yuvSizes], not the vendor JPEG
 * sizes: the locked session captures YUV_420_888 (see [LockedCameraSession])
 * and encodes that to lossless PNG, so a JPEG-only size would name a
 * resolution the still pipeline cannot actually produce. That list is
 * already 4:3-only and bounded by [CameraCapabilities.sustainableCeiling].
 *
 * Resolution feeds the rate: a larger frame costs more to read out and more to
 * encode, so [onChanged] has to rebuild the offered rates, not just relabel.
 */
internal class CaptureResolutionPicker(
    private val spinner: Spinner,
    sizes: List<CameraCapabilities.Resolution>,
    private val onChanged: () -> Unit,
) {

    private val sizes = sizes.ifEmpty { listOf(CameraCapabilities.LAST_RESORT) }

    init {
        spinner.adapter = ArrayAdapter(
            spinner.context,
            android.R.layout.simple_spinner_dropdown_item,
            this.sizes.map { it.label },
        )
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                onChanged()

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    val selected: CameraCapabilities.Resolution
        get() = sizes.getOrElse(spinner.selectedItemPosition) { sizes.first() }

    /**
     * Move the spinner to the offered size closest to [longEdgePx], and say
     * which one that was.
     *
     * The recommendation comes back from the capture screen as the long edge
     * that would put this specimen's speckle at the size DIC wants, which is
     * an arbitrary number rather than one of this camera's sizes — so the job
     * here is to land on the nearest thing the camera will actually record.
     *
     * Nearest on the **long edge**, because that is the axis the recommendation
     * is expressed on and the catalogue is a single aspect ratio: comparing
     * widths would sort a portrait entry against a landscape one and pick by
     * orientation rather than by size.
     *
     * Selecting fires the spinner's own listener, so the rate ladder rebuilds
     * for the new size exactly as it would for a user's tap. Returns null only
     * when there is nothing to select.
     */
    fun select(longEdgePx: Int): CameraCapabilities.Resolution? {
        val index = if (longEdgePx <= 0) {
            null
        } else {
            sizes.indices.minByOrNull { i -> abs(maxOf(sizes[i].width, sizes[i].height) - longEdgePx) }
        }
        return index?.let {
            spinner.setSelection(it)
            sizes[it]
        }
    }
}
