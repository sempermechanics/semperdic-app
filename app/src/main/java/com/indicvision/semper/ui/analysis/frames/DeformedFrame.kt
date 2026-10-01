package com.indicvision.semper.ui.analysis.frames

import com.indicvision.semper.field.ImageSize

/**
 * One deformed frame the wizard holds: its staged [path], the [name] the user
 * picked it as, its best-effort capture [date] ([UNKNOWN_DATE] when unknown,
 * which sorts last), and its pixel [size] (null until measured).
 *
 * Today a batch is four index-aligned fields: `defFilePaths`,
 * `defOriginalNames`, `defFrameDates` and `defFrameSizes` (a map keyed by
 * path) on `AnalysisViewModel`, re-declared by `WizardState.Frames`,
 * `FrameOrderHelper.OrderedBatch` and `ImportedBatch`. [zip] and [unzip]
 * convert between those lists and a `List<DeformedFrame>`.
 */
data class DeformedFrame(
    val path: String,
    val name: String,
    val date: Long = UNKNOWN_DATE,
    val size: ImageSize? = null,
) {

    /** The four parallel fields of a batch, in the view model's shapes. */
    data class Lists(
        val paths: List<String>,
        val names: List<String>,
        val dates: List<Long>,
        val sizes: Map<String, Pair<Int, Int>>,
    )

    companion object {
        /** `defFrameDates`' marker for a frame whose date is unknown. */
        const val UNKNOWN_DATE = Long.MAX_VALUE

        /**
         * One frame per path. The lists are meant to be index-aligned; a
         * [names] or [dates] list that runs short pads with "" and
         * [UNKNOWN_DATE], so [unzip] of the result is aligned even when the
         * input was not. A path with no entry in [sizes] has a null size.
         */
        fun zip(
            paths: List<String>,
            names: List<String>,
            dates: List<Long>,
            sizes: Map<String, Pair<Int, Int>>,
        ): List<DeformedFrame> = paths.mapIndexed { i, path ->
            DeformedFrame(
                path = path,
                name = names.getOrElse(i) { "" },
                date = dates.getOrElse(i) { UNKNOWN_DATE },
                size = sizes[path]?.let(ImageSize::of),
            )
        }

        /** The four parallel fields of [frames]; frames with no size are left out of the map. */
        fun unzip(frames: List<DeformedFrame>): Lists = Lists(
            paths = frames.map { it.path },
            names = frames.map { it.name },
            dates = frames.map { it.date },
            sizes = frames.mapNotNull { f -> f.size?.let { f.path to it.toPair() } }.toMap(),
        )
    }
}
