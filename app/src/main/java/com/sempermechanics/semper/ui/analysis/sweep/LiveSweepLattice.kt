package com.sempermechanics.semper.ui.analysis.sweep

import android.view.View

/**
 * The run overlay's sweep lattice: every combination of the plan, drawn as a
 * waiting ring until its solve ends, then filled (solved) or a red ring
 * (skipped). The combination being solved now is ringed.
 */
class LiveSweepLattice(private val group: View, private val lattice: SweepLatticeView) {

    private var plan: List<SweepStudy.Point> = emptyList()

    /** Shows [plan] with nothing run yet; an empty plan hides the lattice. */
    fun show(plan: List<SweepStudy.Point>) {
        this.plan = plan
        lattice.setNodes(liveLatticeNodes(plan, emptyList(), current = 0))
        group.visibility = if (plan.isEmpty()) View.GONE else View.VISIBLE
    }

    fun hide() {
        plan = emptyList()
        group.visibility = View.GONE
    }

    /** Redraws the lattice as of [tick], which carries every combination's outcome. */
    fun apply(tick: SweepStudyRunner.Progress) {
        if (plan.isEmpty()) return
        lattice.setNodes(liveLatticeNodes(plan, tick.outcomes, tick.runIndex))
    }
}

/**
 * A running sweep's lattice: every combination of [plan], drawn as [outcomes]
 * has it so far (waiting until a tick says otherwise), with [current] ringed
 * while it is being solved.
 */
internal fun liveLatticeNodes(
    plan: List<SweepStudy.Point>,
    outcomes: List<SweepStudyRunner.NodeOutcome>,
    current: Int,
): List<SweepLatticeView.Node> = plan.mapIndexed { i, point ->
    val outcome = outcomes.getOrElse(i) { SweepStudyRunner.NodeOutcome.PENDING }
    val pending = outcome == SweepStudyRunner.NodeOutcome.PENDING
    SweepLatticeView.Node(
        subset = point.subset,
        step = point.step,
        window = point.window,
        vsg = point.vsg,
        solved = outcome == SweepStudyRunner.NodeOutcome.SOLVED,
        pending = pending,
        current = pending && i == current,
    )
}
