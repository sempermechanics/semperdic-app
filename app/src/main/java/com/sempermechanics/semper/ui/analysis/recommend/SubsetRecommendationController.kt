package com.sempermechanics.semper.ui.analysis.recommend

import android.graphics.Rect
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.databinding.ActivityStaticAnalysisBinding
import com.sempermechanics.semper.databinding.WizardStepSettingsContentBinding
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.toRect
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisWizardHost
import com.sempermechanics.semper.ui.analysis.wizard.snapToSlider
import com.sempermechanics.semper.ui.common.dialog.WarnChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Initial subset size from the SSSIG criterion (Pan et al., Opt. Express 16,
 * 7037 (2008)) — see [SubsetRecommender]. The reference speckle decides it, so
 * it is measured whenever the reference image or the ROI changes, and stops
 * seeding the slider once the user sets a size of their own.
 *
 * Owns the speckle feedback too: the low-texture and speckle-size chips on
 * page 1, the span chip and the readout on page 2.
 */
class SubsetRecommendationController(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    binding: ActivityStaticAnalysisBinding,
    private val settings: WizardStepSettingsContentBinding,
    private val host: AnalysisWizardHost,
) {
    private val lowTextureChip = WarnChip(binding.lowTextureWarnRow.root, host::confirmOpenFaq)
        .apply { setFaq(activity.getString(R.string.url_faq_speckle)) }
    private val speckleChip = WarnChip(binding.speckleWarnRow.root, host::confirmOpenFaq)
    private val speckleSpanChip = WarnChip(settings.speckleSpanWarnRow.root, host::confirmOpenFaq)

    /**
     * Measures the reference over the ROI, unless that measurement is already
     * held, then seeds the slider with it. Nothing to measure without a reference.
     */
    fun request() {
        val bytes = viewModel.refBytes
        val roi = currentSamplingRoi()
        if (bytes == null || roi == null) return
        val key = "${viewModel.refName}|${bytes.size}|${roi.toShortString()}"
        if (key == viewModel.subsetRecommendationKey) {
            apply()
            return
        }
        viewModel.subsetRecommendationKey = key
        viewModel.subsetRecommendation = null

        // Read off the slider here: the measurement runs on the native thread,
        // which must not touch views.
        val tuning = SubsetRecommender.Tuning(
            sizes = settings.sliderSubsetSize.valueFrom.toInt()..settings.sliderSubsetSize.valueTo.toInt(),
        )

        activity.lifecycleScope.launch(SemperNativeLib.nativeDispatcher) {
            val result = runCatching {
                SubsetRecommender.recommend(
                    refBytes = bytes,
                    imgW = viewModel.realRefWidth,
                    imgH = viewModel.realRefHeight,
                    roi = roi,
                    tuning = tuning,
                )
            }.onFailure { Timber.w(it, "Subset recommendation failed") }.getOrNull()

            // A newer reference/ROI landed while we were measuring.
            withContext(Dispatchers.Main) {
                if (viewModel.subsetRecommendationKey != key) return@withContext
                viewModel.subsetRecommendation = result
                apply()
            }
        }
    }

    /**
     * The subset size an untouched form shows: the SSSIG recommendation for
     * the loaded reference image, or the historical 41 px before one exists.
     */
    fun defaultSubsetSize(): Int {
        val rec = viewModel.subsetRecommendation ?: return DicParams.DEFAULT_SUBSET
        return snapToSlider(settings.sliderSubsetSize, rec.subsetSize)
    }

    /** Seeds the slider with the recommendation, until the user overrides it. */
    fun apply() {
        val rec = viewModel.subsetRecommendation ?: run {
            lowTextureChip.hide()
            speckleChip.hide()
            speckleSpanChip.hide()
            settings.tvSpeckleReadout.isVisible = false
            return
        }
        // The one thing the measurement knows that the slider cannot show: even
        // the largest allowed subset misses the accuracy target on this pattern.
        lowTextureChip.showOrHide(activity.getString(R.string.subset_low_texture).takeIf { rec.lowTexture })
        if (!viewModel.subsetUserModified) {
            val snapped = snapToSlider(settings.sliderSubsetSize, rec.subsetSize)
            if (settings.sliderSubsetSize.value.toInt() != snapped) {
                host.commitParamFields()
                settings.sliderSubsetSize.value = snapped.toFloat()
            }
        }
        // A new recommendation re-seeds the sweep's suggested inputs (unless the
        // user has already set their own).
        host.onSweepInputsChanged()
        // After the slider has been seeded, so the span check judges the size
        // that will actually be run.
        showSpeckleFeedback()
    }

    /**
     * Reports the measured speckle size against the iDICs *Good Practices
     * Guide* band, so the user learns something about their specimen rather
     * than only about the slider.
     *
     * Three surfaces, each placed where its fix is. The readout under the
     * subset slider is shown whenever a measurement exists, good news included
     * — it is the number the recommendation rests on, and a user who can see
     * it can judge their own pattern before spending a run on it. The size
     * chip sits on step 1 with the images, because a pattern outside the band
     * is fixed by a different photograph and by nothing on step 2. The span
     * chip sits on step 2 under the slider, because that slider is its fix and
     * a warning the user cannot watch clear is a warning they will not trust.
     *
     * At most one chip shows: a pattern too fine or too coarse to resolve
     * makes the subset-span question moot, so the size verdict is reported
     * ahead of it and suppresses the span chip entirely.
     */
    fun showSpeckleFeedback() {
        val diameter = viewModel.subsetRecommendation?.speckleDiameterPx
        if (diameter == null) {
            // No measurable pattern in any sample patch. The low-texture chip
            // already covers the case where that is the user's problem; saying
            // nothing here is better than reporting a number we do not have.
            speckleChip.hide()
            speckleSpanChip.hide()
            settings.tvSpeckleReadout.isVisible = false
            return
        }

        settings.tvSpeckleReadout.text = activity.getString(
            R.string.speckle_readout_fmt,
            diameter,
            DicGoodPractice.MIN_SPECKLE_PX.toInt(),
            DicGoodPractice.MAX_SPECKLE_PX.toInt(),
        )
        settings.tvSpeckleReadout.isVisible = true

        val sizeMessage = sizeMessage(diameter)
        // Read off the slider, not off the recommendation: the user may have
        // moved it since, and a chip naming a size they are no longer using is
        // worse than no chip. Suppressed outright while the size chip is up —
        // spanning three speckles is not the problem on a pattern that cannot
        // be resolved at all.
        val spanMessage = if (sizeMessage != null) null else spanMessage()

        val faqUrl = activity.getString(R.string.url_faq_speckle)
        speckleChip.showOrHide(sizeMessage, faqUrl)
        speckleSpanChip.showOrHide(spanMessage, faqUrl)
    }

    private fun sizeMessage(diameter: Double): String? = when (DicGoodPractice.verdictFor(diameter)) {
        DicGoodPractice.Verdict.UNDER_RESOLVED -> activity.getString(
            R.string.speckle_under_resolved_fmt,
            diameter,
            DicGoodPractice.MIN_SPECKLE_PX.toInt(),
        )
        DicGoodPractice.Verdict.OVER_RESOLVED -> activity.getString(
            R.string.speckle_over_resolved_fmt,
            diameter,
            DicGoodPractice.MAX_SPECKLE_PX.toInt(),
        )
        DicGoodPractice.Verdict.USABLE -> null
    }

    private fun spanMessage(): String? {
        val inUse = settings.sliderSubsetSize.value.toInt()
        val wanted = viewModel.subsetRecommendation?.subsetSpanningSpeckles
        return if (wanted != null && wanted > inUse) {
            activity.getString(R.string.speckle_subset_span_fmt, inUse, wanted)
        } else {
            null
        }
    }

    /** Region the recommendation samples: the ROI when set, else the frame. */
    private fun currentSamplingRoi(): Rect? =
        viewModel.roi.orFullFrame(viewModel.hasCustomRoi, viewModel.refSize)?.toRect()
}
