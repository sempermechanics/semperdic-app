package com.sempermechanics.semper.ui.analysis.wizard

import android.app.Activity
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.SkippedNode
import com.sempermechanics.semper.navigation.AppIntents
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import com.sempermechanics.semper.ui.viewer.ViewerArgs
import com.sempermechanics.semper.ui.viewer.ViewerSweepArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Navigation helpers extracted from [StaticAnalysisActivity] so limit-gate and
 * result-open flows do not keep growing the Activity.
 */
object AnalysisNavHelper {

    fun openSessionLimit(host: Activity) {
        host.startActivity(AppIntents.sessionLimit(host))
    }

    fun openSeatRequired(host: Activity) {
        host.startActivity(AppIntents.seatRequired(host))
    }

    /**
     * Every reason a new analysis may not start, in one call.
     *
     * Returns false having already navigated to whichever gate applies. Both
     * checks are guarded by [AnalysisViewModel.wouldCreateNewSession], so a
     * **run already in flight, or a re-run of an existing session, always
     * passes** — losing a seat mid-analysis must never abort work.
     */
    suspend fun ensureCanStart(
        host: Activity,
        viewModel: AnalysisViewModel,
    ): Boolean = ensureSeat(host, viewModel) && ensureSessionQuota(host, viewModel)

    /**
     * Hard stop when this account holds no floating seat.
     *
     * A **separate** gate from the quota one rather than a widening of it: an
     * institution member is licensed, so [TokenStore.isSessionLimitReached] is
     * false for them by definition and they would otherwise pass every
     * existing check.
     *
     * Only a floating institution license can fail here. Assigned licenses,
     * individual licenses and demo accounts all return true and fall through
     * to the quota gate, which is what actually limits them.
     */
    @Suppress("ReturnCount") // early-outs for re-run / not-floating / blocked
    suspend fun ensureSeat(
        host: Activity,
        viewModel: AnalysisViewModel,
    ): Boolean {
        if (!viewModel.wouldCreateNewSession()) return true
        if (!LicenseEntitlements.isSeatRequiredToStart(host)) return true
        openSeatRequired(host)
        return false
    }

    /**
     * Hard stop for a new session when the quota is **known and full**. Returns
     * false after navigating to the limit screen; re-runs of an existing session
     * still pass, and an unknown quota does not block — analysis is on-device and
     * only its upload is gated (see [com.sempermechanics.semper.data.cloud.CloudSync]).
     * Reads the local session index off the main thread.
     */
    @Suppress("ReturnCount") // early-outs for re-run / under-quota / blocked
    suspend fun ensureSessionQuota(
        host: Activity,
        viewModel: AnalysisViewModel,
    ): Boolean {
        if (!viewModel.wouldCreateNewSession()) return true
        val localCount = withContext(Dispatchers.IO) {
            SessionStore.list(host).size
        }
        TokenStore.refreshSessionLimit(host, localCount)
        if (!TokenStore.isSessionLimitReached(host)) return true
        openSessionLimit(host)
        return false
    }

    fun openResults(
        host: Activity,
        viewModel: AnalysisViewModel,
        sweep: Boolean,
        frameNames: ArrayList<String>,
    ) {
        host.startActivity(resultArgs(viewModel, sweep, frameNames).toIntent(host))
    }

    /**
     * The viewer arguments for the run that just finished (TD-61). Settings and
     * ROI come from the run itself ([RunResult]): the ones the
     * saved session recorded, so opening from here and reopening from Home show
     * the same values. The session id is the local one Home opens with.
     */
    fun resultArgs(viewModel: AnalysisViewModel, sweep: Boolean, frameNames: List<String>): ViewerArgs {
        val run = viewModel.runResult.value
        val settings = checkNotNull(run.viewerSettings()) { "no run to show" }
        val plan = viewModel.sweepPlan
        return ViewerArgs(
            imgW = viewModel.realRefWidth,
            imgH = viewModel.realRefHeight,
            step = settings.step,
            refName = viewModel.refName,
            refPath = run.refPath ?: "",
            batchDirPath = run.batchDirPath,
            frameNames = frameNames,
            stopCode = run.stop.wireCode,
            plannedFrames = run.plannedFrames,
            sessionId = viewModel.workingLocalId,
            sessionLocalId = viewModel.workingLocalId,
            subsetSize = settings.subset,
            strainWindow = settings.strainWin,
            engineStats = run.engineStats?.toList(),
            roiX = settings.roiX,
            roiY = settings.roiY,
            roiW = settings.roiW,
            roiH = settings.roiH,
            sweep = if (sweep) {
                ViewerSweepArgs(
                    subsets = plan.map { it.subset },
                    steps = plan.map { it.step },
                    strainWindows = plan.map { it.vsg },
                    lineCutHorizontal = run.spec?.sweep?.lineCutHorizontal ?: viewModel.lineCutHorizontal,
                    skippedJson = SkippedNode.encodeJson(viewModel.sweepSkippedNodes),
                )
            } else {
                null
            },
            defPath = run.defPath,
            defFilePaths = viewModel.defFilePaths,
        )
    }
}
