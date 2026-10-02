package com.indicvision.semper.ui.viewer.share

import androidx.annotation.StringRes
import com.indicvision.semper.R
import com.indicvision.semper.util.Mime

/**
 * What the share sheet can export. [wire] is the kind's name in the viewer's
 * saved state (a save-as picked across a rotation or process death) and in a
 * job's id, so it never changes. [progressText] titles the job while it runs;
 * [saveMime] is what the save-as picker is asked to create.
 */
internal enum class ShareKind(val wire: String, @StringRes val progressText: Int, val saveMime: String) {
    /** The current frame's photo; shared straight away, never saved-as. */
    PHOTO("photo", R.string.share_generating, Mime.PNG),

    /** Every field of the current frame, as one PNG each. */
    PHOTOS("photos", R.string.share_generating, Mime.ZIP),

    /** One looping GIF per field. Single-setting only: a sweep is not a time series. */
    GIFS("gifs", R.string.share_generating_gif, Mime.ZIP),

    /** Every frame's report in one PDF. */
    PDF("pdf", R.string.share_generating_pdf, Mime.PDF),

    /** Every frame's points in one CSV. */
    CSV("csv", R.string.share_generating, Mime.CSV),

    /** The complete bundle: CSV, PDF, raw photos, animations and result images. */
    ZIP("zip", R.string.share_generating_pdf, Mime.ZIP),
    ;

    companion object {
        /** The kind written as [wire], or null for anything else (a value from an older or newer build). */
        fun fromWire(wire: String?): ShareKind? = entries.firstOrNull { it.wire == wire }
    }
}
