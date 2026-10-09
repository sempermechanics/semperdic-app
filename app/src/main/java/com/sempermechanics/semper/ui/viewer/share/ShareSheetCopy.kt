package com.sempermechanics.semper.ui.viewer.share

import android.content.res.Resources
import android.view.View
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.SheetShareBinding

/**
 * What the share sheet says about the frame on screen: the header's position
 * ("frame 2 of 5", or "combination 2 of 5" on a sweep, whose frames are
 * parameter combinations), the counted type labels ("5 PNG", "5 GIF") and each
 * row's contentDescription, which says what the row shares now that the rows
 * carry no sub-line. A sweep hides the Animations row: it is not a time series.
 *
 * [position] is the 0-based frame on screen, of [frames]; [frameName] names it
 * and [sourceName] is the image its photos are stamped with.
 */
internal data class ShareSheetCopy(
    val position: Int,
    val frames: Int,
    val isSweep: Boolean,
    val typeString: String,
    val frameName: String,
    val sourceName: String,
) {
    fun applyTo(v: SheetShareBinding, res: Resources) {
        val fields = ShareExportBuilder.FIELDS.size
        val positionFmt = if (isSweep) R.string.share_position_sweep_fmt else R.string.share_position_fmt
        v.tvSharePosition.text = res.getString(positionFmt, position + 1, frames)

        v.tvShareAllPhotosType.text = res.getQuantityString(R.plurals.share_type_png_count_fmt, fields, fields)
        v.tvShareAnimationsType.text = res.getQuantityString(R.plurals.share_type_gif_count_fmt, fields, fields)

        v.rowSharePhoto.contentDescription = res.getString(R.string.share_photo_desc_fmt, typeString, frameName)
        v.rowShareAllPhotos.contentDescription =
            res.getQuantityString(R.plurals.share_all_photos_desc_fmt, fields, fields, sourceName)
        v.rowShareAnimations.contentDescription =
            res.getQuantityString(R.plurals.share_animations_desc_fmt, fields, fields)
        v.rowShareAnimations.visibility = if (isSweep) View.GONE else View.VISIBLE
        v.rowSharePdf.contentDescription = res.getQuantityString(R.plurals.share_pdf_desc_fmt, frames, frames)
        v.rowShareCsv.contentDescription = res.getQuantityString(R.plurals.share_csv_desc_fmt, frames, frames)
        v.rowShareZip.contentDescription =
            res.getString(if (isSweep) R.string.share_zip_sweep_desc else R.string.share_zip_desc)
    }
}
