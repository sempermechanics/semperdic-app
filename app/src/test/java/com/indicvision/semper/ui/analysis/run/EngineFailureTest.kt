package com.indicvision.semper.ui.analysis.run

import com.indicvision.semper.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Zero-point runs and the known native codes must name the same cause in both
 * the long dialog and the lattice's one-line label.
 */
class EngineFailureTest {

    @Test
    fun `zero lands on strain window reason positive unknown on unknown code`() {
        assertEquals(R.string.sweep_reason_vsg, EngineFailure.reasonRes(0))
        assertEquals(R.string.sweep_reason_vsg, EngineFailure.shortReasonRes(0))
        assertEquals(R.string.sweep_fail_unknown, EngineFailure.reasonRes(42))
        assertEquals(R.string.sweep_reason_unknown, EngineFailure.shortReasonRes(42))
    }

    @Test
    fun `known negatives keep their long and short strings`() {
        assertEquals(
            R.string.sweep_fail_features,
            EngineFailure.reasonRes(EngineFailure.ENGINE_ERROR_FEATURES),
        )
        assertEquals(
            R.string.sweep_reason_decorrelated,
            EngineFailure.shortReasonRes(EngineFailure.ENGINE_ERROR_FEATURES),
        )
        assertEquals(
            R.string.sweep_fail_roi,
            EngineFailure.reasonRes(EngineFailure.ENGINE_ERROR_ROI),
        )
        assertEquals(
            R.string.sweep_reason_subset_too_big,
            EngineFailure.shortReasonRes(EngineFailure.ENGINE_ERROR_ROI),
        )
        assertEquals(
            R.string.sweep_fail_init,
            EngineFailure.reasonRes(EngineFailure.ENGINE_ERROR_INIT),
        )
        assertEquals(
            R.string.sweep_reason_decode,
            EngineFailure.shortReasonRes(EngineFailure.ENGINE_ERROR_INIT),
        )
        assertEquals(
            R.string.error_low_convergence,
            EngineFailure.reasonRes(AnalysisRunCodes.ERROR_LOW_CONVERGENCE),
        )
        assertEquals(
            R.string.sweep_reason_low_convergence,
            EngineFailure.shortReasonRes(AnalysisRunCodes.ERROR_LOW_CONVERGENCE),
        )
    }

    @Test
    fun `each cause maps to its own FAQ URL resource`() {
        assertEquals(
            R.string.url_faq_engine_features,
            EngineFailure.faqUrlRes(EngineFailure.ENGINE_ERROR_FEATURES),
        )
        assertEquals(
            R.string.url_faq_engine_roi,
            EngineFailure.faqUrlRes(EngineFailure.ENGINE_ERROR_ROI),
        )
        assertEquals(
            R.string.url_faq_engine_init,
            EngineFailure.faqUrlRes(EngineFailure.ENGINE_ERROR_INIT),
        )
        assertEquals(
            R.string.url_faq_engine_convergence,
            EngineFailure.faqUrlRes(AnalysisRunCodes.ERROR_LOW_CONVERGENCE),
        )
        assertEquals(R.string.url_faq_engine_vsg, EngineFailure.faqUrlRes(0))
        assertEquals(R.string.url_faq_engine_vsg, EngineFailure.faqUrlRes(42))
    }

    @Test
    fun `a zero-point frame blames the strain window only when points correlated`() {
        assertEquals(EngineFailure.ZeroPoints.STRAIN_WINDOW, EngineFailure.zeroPoints(441))
        assertEquals(EngineFailure.ZeroPoints.NO_CORRELATION, EngineFailure.zeroPoints(0))
        assertEquals(EngineFailure.ZeroPoints.NOT_RUN, EngineFailure.zeroPoints(-1))

        assertEquals(R.string.url_faq_engine_vsg, EngineFailure.zeroPointsFaqUrlRes(441))
        assertEquals(R.string.url_faq_engine_features, EngineFailure.zeroPointsFaqUrlRes(0))
        assertEquals(R.string.url_faq_engine_init, EngineFailure.zeroPointsFaqUrlRes(-1))
    }
}
