package com.sempermechanics.semper.ui.analysis.sweep

import com.sempermechanics.semper.data.session.SkippedNode
import com.sempermechanics.semper.field.DicParams

/**
 * The skipped-node record of a sweep combination the engine could not solve:
 * its subset, step and VSG in px ([VsgStudy.Point.vsg], not the window in
 * points), with the engine's [code]. The construction `AnalysisViewModel` and
 * `StaticAnalysisActivity` each spell out today.
 */
fun VsgStudy.Point.toSkippedNode(code: Int): SkippedNode =
    SkippedNode(subset = subset, step = step, strainWindow = vsg, code = code)

/** The engine parameters of this combination: its strain window as the VSG in px the engine is handed. */
fun VsgStudy.Point.toDicParams(): DicParams = DicParams(subset = subset, step = step, strainWindow = vsg)
