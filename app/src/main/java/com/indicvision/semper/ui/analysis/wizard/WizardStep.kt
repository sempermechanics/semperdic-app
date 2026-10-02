package com.indicvision.semper.ui.analysis.wizard

/** The wizard's pages, in order. */
enum class WizardStep {
    IMAGES,
    SETTINGS,
    SWEEP,
    ;

    /**
     * The page's 1-based number: what the saved state holds
     * ([AnalysisViewModel.wizardStep]) and what the toolbar shows.
     */
    val number: Int get() = ordinal + 1

    /** The page before this one; null on the first. */
    val previous: WizardStep? get() = entries.getOrNull(ordinal - 1)

    companion object {
        /** The page numbered [number]: below the first is the first, past the last the last. */
        fun of(number: Int): WizardStep = entries[(number - 1).coerceIn(entries.indices)]

        /** The page the wizard shows for [requested]: the sweep page only in sweep mode. */
        fun shown(requested: WizardStep, sweepMode: Boolean): WizardStep =
            if (requested == SWEEP && !sweepMode) SETTINGS else requested

        /** How many pages the wizard has in this mode. */
        fun count(sweepMode: Boolean): Int = if (sweepMode) SWEEP.number else SETTINGS.number
    }
}
