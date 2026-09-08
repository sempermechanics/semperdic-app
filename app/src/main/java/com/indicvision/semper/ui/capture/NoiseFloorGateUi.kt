package com.indicvision.semper.ui.capture

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R
import com.indicvision.semper.data.CaptureNoiseFloor
import com.indicvision.semper.data.NoiseFloorText
import com.indicvision.semper.ui.analysis.DicGoodPractice
import com.indicvision.semper.ui.analysis.NoiseFloorStats
import com.indicvision.semper.ui.analysis.SpeckleScale
import com.indicvision.semper.ui.common.FaqRedirect
import timber.log.Timber

/**
 * The screen-side half of the noise-floor gate: carries the ROI across from the
 * speckle check, runs the burst, and turns its verdict into what the user sees.
 *
 * Split out of the capture activity because it is a self-contained decision with
 * its own carried state — the ROI, the subset, the measured floor and whether
 * the user overrode it — and folding that into the activity would leave the
 * gate's logic interleaved with camera setup, preview transforms and recording.
 *
 * The activity keeps everything that needs the camera; this keeps everything
 * that needs the verdict.
 */
@Suppress("TooManyFunctions")
internal class NoiseFloorGateUi(
    private val activity: Activity,
    /** Take the test shot again; the same path the speckle check's retry uses. */
    private val onRetry: () -> Unit,
    /** The burst produced nothing, which is the existing test-shot failure. */
    private val onBurstFailed: () -> Unit,
    /**
     * Frames were captured but would not correlate with one another. Carries
     * the long edge that would put the measured speckle at the recommended
     * size, or zero when the speckle could not be measured; the capture screen
     * hands it back to setup so the resolution can be changed.
     */
    private val onChangeResolution: (Int) -> Unit,
    /** The user chose to record past a failing floor: re-enable the start button. */
    private val onProceed: () -> Unit,
    /** Measured per-frame cost, for the rate ladder. */
    private val onFrameCost: (Long) -> Unit,
) {

    /**
     * The contrast ROI as fractions of the test shot, and the subset measured in
     * it, carried from the speckle check to the burst.
     *
     * Fractions rather than pixels: the burst is captured at the run's own
     * resolution, not the vendor camera app's, so a pixel rectangle would land
     * somewhere else entirely.
     */
    private var roiNorm: RectF? = null
    private var subset = 0
    private var sourceWidth = 0
    private var sourceHeight = 0

    /**
     * Speckle diameter measured on the test shot, in that shot's own pixels,
     * or null when it could not be measured. Kept unscaled so it can be put
     * onto whichever frame size is being judged.
     */
    private var speckleDiameterPx: Double? = null

    /** Which way round the test shot was, so the burst can check it still is. */
    private var sourceLandscape = false

    /** Guards the one collapsed pipeline warning against repeating per retry. */
    private var warned = false

    /** The measured floor, kept so the run carries the number it was taken at. */
    var floor: NoiseFloorGate.Result? = null
        private set

    /** True when the user chose to record past a failing floor. */
    var overridden = false
        private set

    /** Persisted floor for session/report, or null before a burst completes. */
    @Suppress("ReturnCount")
    fun measured(): CaptureNoiseFloor? {
        restoredFloor?.let { return it }
        val result = floor ?: return null
        return result.toCaptureNoiseFloor(overridden)
    }

    /** Restore a floor measured before process death without re-running the burst. */
    fun restorePersistedFloor(saved: CaptureNoiseFloor, wasOverridden: Boolean) {
        restoredFloor = saved
        overridden = wasOverridden
    }

    private var restoredFloor: CaptureNoiseFloor? = null

    /** Called with the speckle check's result, which is where the ROI comes from. */
    fun onSpeckleChecked(
        roi: Rect,
        imageWidth: Int,
        imageHeight: Int,
        subsetSize: Int,
        speckleDiameter: Double? = null,
    ) {
        roiNorm = NoiseFloorGate.normalize(roi, imageWidth, imageHeight)
        subset = subsetSize
        sourceWidth = imageWidth
        sourceHeight = imageHeight
        speckleDiameterPx = speckleDiameter
        sourceLandscape = imageWidth > imageHeight
        Timber.i(
            "noise floor roi: test shot %dx%d roi=%s subset=%d speckle=%s px",
            imageWidth,
            imageHeight,
            roi.toShortString(),
            subsetSize,
            speckleDiameter?.let { "%.1f".format(it) } ?: "?",
        )
    }

    /**
     * Take the static burst, calibrate pacing from it, and let it stop the run
     * if the setup cannot resolve the strain about to be applied.
     *
     * This does two jobs that used to be one throwaway frame. The first burst
     * frame is the warm-up: timing the PNG encode alone misses the camera round
     * trip (request → sensor → YUV frame → write) that dominates on real
     * hardware, so pacing has to come from a real still through the exact path
     * the sequence uses. The rest of the burst is the noise-floor measurement —
     * free, because nothing is loaded yet and these are already frames of a
     * static scene.
     *
     * @return false when the caller should stop; a dialog is already up.
     */
    @Suppress("ReturnCount") // no ROI, no burst, then the pass and refuse verdicts
    suspend fun run(session: LockedCameraSession, planWidth: Int, planHeight: Int): Boolean {
        val roi = roiNorm
        if (roi == null || subset <= 0) {
            // Nothing to measure against. Not a failure worth stopping for —
            // the run is no worse off than it was before this check existed.
            Timber.w("noise floor: no contrast ROI; skipping the check")
            return true
        }
        val scaled = NoiseFloorGate.rescaleSubset(subset, sourceWidth, sourceHeight, planWidth, planHeight)
        val result = runCatching {
            NoiseFloorGate.measure(activity, session, roi, scaled, sourceLandscape)
        }.onFailure { Timber.w(it, "noise floor: burst failed") }.getOrNull()

        if (result == null) {
            onBurstFailed()
            return false
        }
        // Feeds the setup screen's frame-count offers next time, so they reflect
        // this device rather than Camera2's unrelated JPEG stall.
        CaptureCalibration.record(activity, planWidth, planHeight, result.firstFrameMs)
        onFrameCost(result.firstFrameMs)
        // Frames that could not be correlated with each other are not a floor
        // of "unknown" to pass through quietly. But they are also not the burst
        // failing to happen: six photos were taken and processed, and telling
        // the user "no photo was saved" offers a remedy that cannot fix what
        // actually went wrong. Correlation failed, and at this resolution the
        // most likely reason is that the speckle is not resolved.
        if (result.verdict.outcome == NoiseFloorStats.Outcome.INSUFFICIENT) {
            showUncorrelatedDialog(session, planWidth, planHeight)
            return false
        }
        // Assigned before either dialog, because the dialog reads the gauge
        // off it to put the colour scale in the same unit as the verdict.
        floor = result
        // A high floor no longer stops the run, but it must not pass unseen
        // either: it is shown, and the same verdict is stamped on the session so
        // the report and the CSV carry it.
        if (!result.verdict.blocking && !result.verdict.floorExceeded) {
            showPassDialog(session, result)
            return false
        }
        showVerdict(session, result)
        return false
    }

    /**
     * The burst correlated with nothing: frames exist, the solve returned no
     * points.
     *
     * Its own dialog rather than the test-shot failure, because the enum this
     * arrives on says so — [NoiseFloorStats.Outcome.INSUFFICIENT] is documented
     * as "too few usable frames to conclude anything; reports, never blocks" —
     * and because the two have different fixes. Nothing was wrong with the
     * photographs; what failed is that at this recording size the pattern is
     * not resolved, which is a resolution problem and not a retry.
     *
     * So the primary action is **Change resolution**, carrying the size that
     * would put the measured speckle where DIC wants it. Where the speckle
     * could not be measured the recommendation is zero and setup simply
     * re-opens with the plan intact, which is still better than a retry that
     * will fail the same way.
     */
    private fun showUncorrelatedDialog(session: LockedCameraSession, planWidth: Int, planHeight: Int) {
        val longEdge = maxOf(planWidth, planHeight)
        val onPlan = speckleDiameterPx?.let {
            SpeckleScale.scaledTo(it, maxOf(sourceWidth, sourceHeight), longEdge)
        }
        val recommended = onPlan
            ?.let { DicGoodPractice.usefulLongEdges(it, longEdge) }
            ?.recommended
            ?: 0
        Timber.w(
            "noise floor: burst uncorrelated at %dx%d; speckle %s px, recommending long edge %d",
            planWidth,
            planHeight,
            onPlan?.let { "%.1f".format(it) } ?: "?",
            recommended,
        )
        val body = if (onPlan == null) {
            activity.getString(R.string.capture_noise_uncorrelated_body)
        } else {
            activity.getString(
                R.string.capture_noise_uncorrelated_speckle_body,
                onPlan,
                DicGoodPractice.MIN_SPECKLE_PX.toInt(),
                DicGoodPractice.MAX_SPECKLE_PX.toInt(),
            )
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.capture_noise_uncorrelated_title)
            .setMessage(body)
            .setCancelable(false)
            .setPositiveButton(R.string.capture_change_resolution) { _, _ -> onChangeResolution(recommended) }
            // Not a door: the user may know something this proxy does not, and
            // the same override the floor verdict offers belongs here too.
            .setNegativeButton(R.string.capture_record_anyway) { _, _ ->
                overridden = true
                warnAboutPipeline(session)
                onProceed()
            }
            .setNeutralButton(R.string.action_why, null)
        if (activity.isFinishing || activity.isDestroyed) return
        val alert = dialog.show()
        alert.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            FaqRedirect.confirm(activity, R.string.url_faq_speckle)
        }
    }

    /**
     * A passing floor is information, not a failure — so the FAQ is an ⓘ in
     * the title (like wizard chips), not a **Why?** button. Continue is still
     * required before Start recording appears.
     */
    private fun showPassDialog(session: LockedCameraSession, result: NoiseFloorGate.Result) {
        val label = NoiseFloorText.floorLabel(result.verdict.floorMicrostrain)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(
                floorDialogContent(
                    label,
                    body = activity.getText(R.string.capture_noise_floor_body),
                    showFaq = true,
                    map = result.sigmaMap,
                ),
            )
            .setCancelable(false)
            .setPositiveButton(R.string.capture_noise_continue) { _, _ ->
                onProceed()
                warnAboutPipeline(session)
            }
        if (!activity.isFinishing && !activity.isDestroyed) dialog.show()
    }

    /**
     * Large floor value with plain-language body — the number is the hero, not a
     * cramped title line.
     */
    private fun floorDialogContent(
        label: String,
        headlineRes: Int? = null,
        body: CharSequence,
        showFaq: Boolean = false,
        map: NoiseFloorSigmaMap? = null,
    ): View {
        @SuppressLint("InflateParams")
        val view = activity.layoutInflater.inflate(R.layout.dialog_noise_floor_content, null)
        view.findViewById<TextView>(R.id.tvNoiseFloorValue).text = label
        val headline = view.findViewById<TextView>(R.id.tvNoiseFloorHeadline)
        if (headlineRes != null) {
            headline.setText(headlineRes)
            headline.visibility = View.VISIBLE
        }
        val bodyView = view.findViewById<TextView>(R.id.tvNoiseFloorBody)
        bodyView.text = body
        val faq = view.findViewById<ImageButton>(R.id.btnNoiseFloorFaq)
        faq.visibility = if (showFaq) View.VISIBLE else View.GONE
        if (showFaq) {
            faq.setOnClickListener {
                FaqRedirect.confirm(activity, R.string.url_faq_noise_floor)
            }
        }
        showMap(view, map)
        return view
    }

    /**
     * The scatter map, when the burst produced one.
     *
     * One number cannot say *where* a setup is weak: a glare patch, a soft
     * corner and a thin band of speckle all reduce to the same slightly-worse
     * floor, and all three have different answers. The map is what turns the
     * verdict from a grade into somewhere to look.
     *
     * The legend quotes both ends of the colour scale twice — in pixels, which
     * is what was measured, and in microstrain at the gauge the verdict itself
     * is quoted at, which is what the number above the map is in. A colour
     * scale in a unit the reader has to convert is a colour scale they will
     * read wrong.
     *
     * Hidden, not omitted, when there is no field: the text-only dialog stays a
     * valid state rather than becoming a degraded one.
     */
    private fun showMap(view: View, map: NoiseFloorSigmaMap?) {
        val group = view.findViewById<View>(R.id.groupNoiseFloorMap)
        if (map == null) {
            group.visibility = View.GONE
            return
        }
        val gauge = floor?.vsgPx ?: return
        view.findViewById<ImageView>(R.id.imgNoiseFloorMap).setImageBitmap(map.image)
        view.findViewById<TextView>(R.id.tvNoiseFloorMapLegend).text = activity.getString(
            R.string.capture_noise_map_legend_fmt,
            map.minSigmaPx,
            map.maxSigmaPx,
            NoiseFloorStats.microstrainFor(map.minSigmaPx, gauge),
            NoiseFloorStats.microstrainFor(map.maxSigmaPx, gauge),
        )
        group.visibility = View.VISIBLE
    }

    /**
     * The one line the user sees about the pipeline, whichever way the gate
     * went.
     *
     * Two findings compete for it and only one is shown. Smoothing wins when it
     * was measured, because it is the worse news and the more surprising: a
     * refused key is a setting the phone declined, while smoothing is a setting
     * the phone *accepted* and then ignored — nothing in the read-back can
     * catch it, so if this line does not say it, nothing will.
     */
    fun warnAboutPipeline(session: LockedCameraSession) {
        if (floor?.let { measuredFloor -> denoiseWarned(measuredFloor) } == true) return
        warnAboutRefusedSettings(session)
    }

    /**
     * Shows the smoothing warning if the burst found smoothing, returning
     * whether it did. Guarded like the refusal warning so a retry does not
     * repeat it.
     */
    private fun denoiseWarned(result: NoiseFloorGate.Result): Boolean {
        val correlation = result.verdict.noiseCorrelation
        val smoothed = CaptureNoiseFloor.denoisedByCorrelation(correlation)
        if (smoothed && !warned) {
            warned = true
            Timber.w("pipeline: frames are smoothed, neighbour correlation %.3f", correlation)
            FaqRedirect.snackbar(
                activity,
                activity.getString(R.string.capture_denoise_warn),
                R.string.url_faq_imaging_pipeline,
            )
        }
        return smoothed
    }

    /**
     * The one warning about settings this phone would not hold.
     *
     * Shown after the read-back rather than from the capability lists, because a
     * HAL can accept a key and ignore it — and a warning for a setting that was
     * in fact honoured is as much a defect as a missing one. Collapsed to a
     * single line naming the costliest refusal: a LEGACY device refuses half a
     * dozen at once, and six snackbars is noise, not information.
     */
    fun warnAboutRefusedSettings(session: LockedCameraSession) {
        if (warned) return
        val shortfall = session.ispShortfall
        val worst = CaptureIspWarning.headline(shortfall) ?: return
        warned = true
        val effect = activity.getString(CaptureIspWarning.effectOf(worst))
        val message = if (shortfall.size > 1) {
            activity.resources.getQuantityString(
                R.plurals.capture_isp_warn_more,
                shortfall.size - 1,
                effect,
                shortfall.size - 1,
            )
        } else {
            effect
        }
        Timber.w("isp shortfall: %s", shortfall.joinToString())
        FaqRedirect.snackbar(activity, message, R.string.url_faq_imaging_pipeline)
    }

    /**
     * The floor verdict: a warning the user acts on, not a door they are held
     * behind.
     *
     * Recording twenty minutes of a loaded specimen that cannot resolve the
     * strain being applied wastes the specimen, not just the time, and a
     * specimen is often not repeatable — so the warning is written to be
     * unmissable. But this is a proxy, and the user knows things it does not:
     * that this is a shakedown, that the expected strain is 50 me and a 2 me
     * floor is fine, that the fixture cannot be re-mounted. So a floor above the
     * limit always leaves **Record anyway** as the primary action, and the
     * honesty is bought by recording the number rather than by refusing.
     *
     * Two outcomes still stop to ask — a burst that would not settle, and one
     * that drifted. There the measurement failed to measure itself, so retrying
     * costs seconds and buys a number worth having; the floor beside it cannot
     * be trusted either. Those keep **Retry test shot** as the primary action,
     * with the override on the other side.
     */
    private fun showVerdict(session: LockedCameraSession, result: NoiseFloorGate.Result) {
        val verdict = result.verdict
        val refused = session.ispShortfall.size
        val label = NoiseFloorText.floorLabel(verdict.floorMicrostrain)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setCancelable(false)
        describe(dialog, verdict, refused, label, result.sigmaMap)
        val proceed = { _: DialogInterface, _: Int ->
            // Recorded, so an export months later still says the run was
            // captured below the usable floor and by how much.
            Timber.w("noise floor override: recording at %s (%s)", label, verdict.outcome)
            overridden = true
            warnAboutPipeline(session)
            onProceed()
        }
        val retry = { _: DialogInterface, _: Int -> onRetry() }
        // Whichever action is the recommended one sits on the right, where the
        // eye lands; the other is one deliberate tap away on the left.
        if (verdict.blocking) {
            dialog.setPositiveButton(R.string.capture_retry_test_shot, retry)
            dialog.setNegativeButton(R.string.capture_record_anyway, proceed)
        } else {
            dialog.setPositiveButton(R.string.capture_record_anyway, proceed)
            dialog.setNegativeButton(R.string.capture_retry_test_shot, retry)
        }
        // Neutral placeholder: a real listener would dismiss the dialog, and
        // returning from the FAQ would leave no Continue / Record anyway.
        dialog.setNeutralButton(R.string.action_why, null)
        if (activity.isFinishing || activity.isDestroyed) return
        val alert = dialog.show()
        alert.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            FaqRedirect.confirm(activity, R.string.url_faq_noise_floor)
        }
    }

    /**
     * Title and body for the verdict. Each outcome gets its own, because they
     * call for different actions: more light, a steadier rig, or waiting.
     */
    private fun describe(
        dialog: MaterialAlertDialogBuilder,
        verdict: NoiseFloorStats.Verdict,
        refused: Int,
        label: String,
        map: NoiseFloorSigmaMap?,
    ) {
        when {
            verdict.outcome == NoiseFloorStats.Outcome.NOT_SETTLING ->
                dialog
                    .setTitle(R.string.capture_noise_unsettled_title)
                    .setMessage(R.string.capture_noise_unsettled_body)

            verdict.outcome == NoiseFloorStats.Outcome.DRIFTING ->
                dialog
                    .setTitle(R.string.capture_noise_drift_title)
                    .setMessage(R.string.capture_noise_drift_body)

            // Two independent signals pointing the same way — a floor over the
            // limit *and* settings the phone would not hold — earn a plainer
            // sentence than either does alone, since the refusals are part of
            // why the floor is where it is.
            verdict.floorExceeded && refused >= REFUSALS_WORTH_NAMING ->
                dialog.setView(
                    floorDialogContent(
                        label = label,
                        headlineRes = R.string.capture_noise_erroneous_title,
                        body = activity.resources.getQuantityString(
                            R.plurals.capture_noise_erroneous_refused_body,
                            refused,
                            refused,
                        ),
                        map = map,
                    ),
                )

            verdict.floorExceeded ->
                dialog.setView(
                    floorDialogContent(
                        label = label,
                        headlineRes = R.string.capture_noise_erroneous_title,
                        body = activity.getText(R.string.capture_noise_erroneous_body),
                        map = map,
                    ),
                )

            else ->
                dialog.setView(
                    floorDialogContent(
                        label = label,
                        body = activity.getText(R.string.capture_noise_floor_body),
                        map = map,
                    ),
                )
        }
    }

    private companion object {
        /**
         * Refusals worth naming alongside a high floor.
         *
         * Below this the refusals are incidental — one fallback on a mid-range
         * phone explains nothing about the floor, and naming it would make the
         * warning longer for no gain. At three or more the pipeline is not
         * frozen in any meaningful sense and the two findings belong in one
         * sentence.
         */
        const val REFUSALS_WORTH_NAMING = 3
    }
}
