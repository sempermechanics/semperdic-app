package com.sempermechanics.semper.field

/**
 * The solver parameters of every frame of one result.
 *
 * An ordinary analysis solves each frame with [base]. A parameter sweep
 * varies the parameters instead of the image, so it carries per-frame lists,
 * index-aligned with the solved frames; there [base] describes only the first
 * frame. Today the lists live on `SessionRecord` (`sweepSubsets`,
 * `sweepSteps`, `sweepStrainWindows`), `ViewerSweepArgs`, the viewer's
 * `IntArray?` fields and `ShareCenter.Snapshot`'s `*PerFrame` arrays.
 *
 * [at] is the lookup each of those sites spells out: the list's value at the
 * index, else the base value. A record's empty list and the viewer's null
 * array both mean "not a sweep" and fall back the same way.
 *
 * The lists are copied on construction, so a caller's mutable list cannot
 * change an instance afterwards.
 */
class FrameParams(
    val base: DicParams,
    subsets: List<Int> = emptyList(),
    steps: List<Int> = emptyList(),
    strainWindows: List<Int> = emptyList(),
) {
    val subsets: List<Int> = subsets.toList()
    val steps: List<Int> = steps.toList()
    val strainWindows: List<Int> = strainWindows.toList()

    /** True when the frames are parameter combinations; `SessionRecord.isSweep`'s `sweepSteps.isNotEmpty()`. */
    val isSweep: Boolean get() = steps.isNotEmpty()

    /**
     * Frame [index]'s parameters. Each of the three falls back to [base]
     * separately when its list is empty or too short:
     * `sweepSubsets.getOrElse(i) { subset }` and so on.
     */
    fun at(index: Int): DicParams = DicParams(
        subset = subsets.getOrElse(index) { base.subset },
        step = steps.getOrElse(index) { base.step },
        strainWindow = strainWindows.getOrElse(index) { base.strainWindow },
    )

    override fun equals(other: Any?): Boolean = other is FrameParams &&
        base == other.base &&
        subsets == other.subsets &&
        steps == other.steps &&
        strainWindows == other.strainWindows

    override fun hashCode(): Int = listOf(base, subsets, steps, strainWindows).hashCode()

    override fun toString(): String =
        "FrameParams(base=$base, subsets=$subsets, steps=$steps, strainWindows=$strainWindows)"

    companion object {
        /** From the viewer's nullable arrays; null reads as an empty list, which is `getOrNull(i) ?: base`. */
        fun of(base: DicParams, subsets: IntArray?, steps: IntArray?, strainWindows: IntArray?): FrameParams =
            FrameParams(
                base = base,
                subsets = subsets?.toList().orEmpty(),
                steps = steps?.toList().orEmpty(),
                strainWindows = strainWindows?.toList().orEmpty(),
            )
    }
}
