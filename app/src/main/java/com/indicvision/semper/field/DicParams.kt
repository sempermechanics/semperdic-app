package com.indicvision.semper.field

import android.content.Intent
import com.indicvision.semper.navigation.DicKeys
import org.json.JSONObject

/**
 * The solver parameters one frame is computed with: [subset] (px, odd), grid
 * [step] (px) and [strainWindow], the VSG diameter in **px** that the engine is
 * handed (`VsgStudy.vsgFor(points, step)`), never the window in data points.
 *
 * The same three ints travel today as `subset/step/strainWindow` (`RunSpec`,
 * `SessionRecord`, `SkippedNode`, CSV frames), `subset/step/strainWin`
 * (`SessionRecordSettings`, the JNI call),
 * `subsetSize/step/strainWindow` (`ViewerArgs`, `ReportBuildParams`),
 * `subset/step/vsg` (`VsgStudy.Point`, `ParamClipboard`, lattice nodes), the
 * `SUBSET_SIZE` / `STEP` / `STRAIN_WINDOW` extras and the `engine` object of
 * `metadata.json`. This is a view over those fields; it writes the same keys.
 *
 * `use6x6` and the strain method are not here: each travels on only one side
 * of a run (engine inputs vs. outputs), never per frame.
 */
data class DicParams(val subset: Int, val step: Int, val strainWindow: Int) {

    /**
     * Appends `subset`, `step`, `strainWindow` to a `metadata.json` object.
     * Only these three keys: `engineJson` follows them with `strainMethod`,
     * `use6x6`, the image size and `roi`, so a caller rebuilding that object
     * must keep its order to keep its bytes.
     */
    fun putInto(json: JSONObject): JSONObject = json
        .put(JSON_SUBSET, subset)
        .put(JSON_STEP, step)
        .put(JSON_STRAIN_WINDOW, strainWindow)

    /**
     * Puts the viewer's three extras, [DicKeys.SUBSET_SIZE], [DicKeys.STEP],
     * [DicKeys.STRAIN_WINDOW]. Write-only: the viewer reads them through
     * `ViewerArgs.Reader`, which falls back to the session record.
     */
    fun putViewerExtras(intent: Intent): Intent = intent
        .putExtra(DicKeys.SUBSET_SIZE, subset)
        .putExtra(DicKeys.STEP, step)
        .putExtra(DicKeys.STRAIN_WINDOW, strainWindow)

    companion object {
        /** `ViewerArgs.DEFAULT_SUBSET` and `CloudRestore`'s fallback. */
        const val DEFAULT_SUBSET = 41

        /** `ViewerArgs.DEFAULT_STEP` and `CloudRestore`'s fallback. */
        const val DEFAULT_STEP = 5

        /**
         * `ViewerArgs.DEFAULT_STRAIN_WINDOW` and `CloudRestore`'s fallback, in px.
         * The wizard's own default is now 5 points at step 5, a 21 px VSG; this
         * value predates it and is kept so readers stay unchanged.
         */
        const val DEFAULT_STRAIN_WINDOW = 15

        /** What a reader assumes when neither the source nor a record says. */
        val DEFAULT = DicParams(DEFAULT_SUBSET, DEFAULT_STEP, DEFAULT_STRAIN_WINDOW)

        const val JSON_SUBSET = "subset"
        const val JSON_STEP = "step"
        const val JSON_STRAIN_WINDOW = "strainWindow"

        /**
         * From a `metadata.json` `engine` object; an absent key reads [default]'s
         * value, as `SessionMetadataDoc.toRecord` does with 41 / 5 / 15.
         */
        fun fromJson(json: JSONObject, default: DicParams = DEFAULT): DicParams = DicParams(
            json.optInt(JSON_SUBSET, default.subset),
            json.optInt(JSON_STEP, default.step),
            json.optInt(JSON_STRAIN_WINDOW, default.strainWindow),
        )
    }
}

/**
 * The strain methods the engine has. Only VSG exists. Records keep the method
 * as a free `String` (blank in rows that predate it), so this is the choice,
 * not the stored form: [wireName] is what gets written.
 */
enum class StrainMethod(val wireName: String) {
    /** Virtual strain gauge: a least-squares plane fit over the window. */
    VSG("VSG"),
    ;

    companion object {
        /**
         * The stored method as shown and exported: blank reads as [VSG], any
         * other value is kept as is. `SessionUploadBundler`'s `ifBlank { "VSG" }`.
         */
        fun displayName(stored: String?): String = stored?.ifBlank { null } ?: VSG.wireName
    }
}
