package com.sempermechanics.semper.ui.analysis.wizard

import android.os.Bundle
import com.sempermechanics.semper.data.prefs.WizardDraft
import com.sempermechanics.semper.field.ImageSize
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * A wizard's tie to the process's one [WizardDraft] (ADR-005): where its
 * heavy inputs are mirrored, and whether it still owns the draft.
 *
 * Ownership is a generation: each fresh [attach] takes the next one, so a
 * wizard that is going can tell its draft was taken and its queued writes
 * and delete then do nothing.
 */
internal class WizardDraftBinding {

    /** Where the heavy inputs are mirrored; null until [attach] (and in JVM tests). */
    private var draft: WizardDraft? = null

    /** False while a restore puts back what the draft already holds. */
    var mirror = true

    /** The Bundle a process death left, until [takeRestore] hands it over with the draft behind it. */
    var pendingRestore: Bundle? = null

    /** This wizard's claim on the draft: the [owner] value [attach] took. */
    private var generation = 0

    /** Set by [discard]; read on the draft's lane, where a write still queued checks it. */
    @Volatile
    private var discarded = false

    /**
     * Queues [write] on the draft's lane; it outlives the view model (see
     * [WizardDraft.queue]). It runs only while this wizard still owns the
     * draft: there is one draft per process, and a wizard opened since owns it.
     */
    fun stage(write: (WizardDraft) -> Unit) {
        val target = draft?.takeIf { mirror && !discarded } ?: return
        val mine = generation
        WizardDraft.queue(target) { if (!discarded && owner.get() == mine) write(it) }
    }

    /**
     * Starts mirroring the inputs into [target].
     *
     * A wizard that is not being restored takes the draft from any wizard
     * that had it, and empties it: whatever is there belongs to one that is
     * gone. A restored wizard carries on the draft of the one whose state it
     * was handed, so that wizard's last writes, still queued, land for it.
     *
     * The restored wizard does that by taking `owner.get()`, the current
     * generation, rather than the next one. That is the predecessor's
     * generation only while no fresh wizard attached in between, which holds
     * because one screen launches the wizard: HomeActivity (the only
     * `StaticAnalysisActivity` intent), one at a time, from under the wizard
     * it would replace. A restore recreates that wizard before Home can be
     * reached again. A second launcher, or a wizard opened over another,
     * would let the restored one share a newer wizard's generation; it would
     * then need the generation in its saved state instead.
     */
    fun attach(target: WizardDraft) {
        if (draft != null) return
        draft = target
        if (pendingRestore == null) {
            generation = owner.incrementAndGet()
            stage(WizardDraft::clear)
        } else {
            generation = owner.get()
        }
    }

    /** The saved state and the draft to read it back from, once; null when there is nothing to restore. */
    fun takeRestore(): Pair<Bundle, WizardDraft>? {
        val state = pendingRestore
        val source = draft
        if (state == null || source == null) return null
        pendingRestore = null
        return state to source
    }

    /**
     * The wizard was left for good: nothing will restore from the draft.
     * Returns at once, and stops any write this wizard still has queued. The
     * files are deleted on the draft's lane, unless a wizard opened since
     * (whose `onCreate` can run before this one's `onDestroy`) has taken the
     * draft: then they are that wizard's.
     */
    fun discard() {
        val target = draft ?: return
        discarded = true
        val mine = generation
        WizardDraft.queue(target) { if (owner.get() == mine) it.clear() }
    }

    private companion object {
        /** Which wizard owns the process's one [WizardDraft]: bumped by each fresh [attach]. */
        val owner = AtomicInteger()
    }
}

/**
 * Reads the reference, mask and frame list back from the draft after a process
 * death; see [AnalysisViewModel.restoreDraft].
 */
internal suspend fun AnalysisViewModel.restoreFromDraft(): DraftRestore {
    val (state, source) = drafts.takeRestore() ?: return DraftRestore.NONE
    val inputs = withContext(WizardDraft.io) { WizardState.readInputs(state, source) }
    return if (inputs == null) {
        Timber.w("Wizard draft incomplete after a process death; starting over")
        clearInputs()
        drafts.stage(WizardDraft::clear)
        DraftRestore.LOST
    } else {
        drafts.mirror = false
        refBytes = inputs.reference
        roiMaskBytes = inputs.mask
        drafts.mirror = true
        deformedFrames = inputs.frames.toDeformedFrames()
        DraftRestore.RESTORED
    }
}

/** Back to an empty wizard. Sweep ranges and the line-cut choice stay. */
private fun AnalysisViewModel.clearInputs() {
    refBytes = null
    roiMaskBytes = null
    deformedFrames = emptyList()
    frameSizeError = null
    defFromVideo = false
    refSize = ImageSize.UNKNOWN
    refName = AnalysisViewModel.NO_REFERENCE_NAME
    hasCustomRoi = false
    roi = AnalysisViewModel.NO_ROI
    step = WizardStep.IMAGES
    settingsReviewed = false
    subsetRecommendation = null
    subsetRecommendationKey = null
    subsetUserModified = false
    workingLocalId = null
}

/**
 * What the system saves as the Activity stops: the scalars, with the frame
 * list queued for the draft.
 *
 * The list is written on the draft's lane, not here: the main thread used
 * to block on the draft's lock behind a reference write still in flight.
 * A restore reads on the same lane, after it. The Bundle carries a
 * fingerprint of the list it queued, so a process killed before that
 * write lands restores as LOST (the draft's list is not the one the
 * Bundle names), never as an older list with the newer scalars.
 */
internal fun AnalysisViewModel.saveWizardState(): Bundle {
    val framesJson = WizardState.encodeFrames(WizardState.frames(this))
    drafts.stage { it.writeFrames(framesJson) }
    return WizardState.save(this, framesJson)
}
