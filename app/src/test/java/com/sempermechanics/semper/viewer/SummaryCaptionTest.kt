package com.sempermechanics.semper.viewer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.ReportBuilder
import com.sempermechanics.semper.ui.viewer.summary.SummaryCaption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The summary caption quotes the colour bar's ends. It used to call them
 * "max" / "min" while the bar itself says "≥" / "≤" — and with a custom scale
 * they are the user's numbers, not the data's extremes at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SummaryCaptionTest {

    private val res = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun `the caption quotes the scale ends with the scale bar's signs`() {
        val text = SummaryCaption.text(res, -0.002f to 0.003f, DicResult.IDX_EXX, "mε")

        val top = ReportBuilder.formatMetric(0.003f * DicResult.strainMultiplier(DicResult.IDX_EXX))
        val bottom = ReportBuilder.formatMetric(-0.002f * DicResult.strainMultiplier(DicResult.IDX_EXX))
        assertEquals("colour scale ≥ $top · ≤ $bottom mε", text)
        assertFalse(text.contains("max") || text.contains("min"))
    }

    @Test
    fun `displacement ends are not scaled to millistrain`() {
        val text = SummaryCaption.text(res, -1.5f to 2.5f, DicResult.IDX_U, "px")

        assertEquals(
            "colour scale ≥ ${ReportBuilder.formatMetric(2.5f)} · ≤ ${ReportBuilder.formatMetric(-1.5f)} px",
            text,
        )
    }

    @Test
    fun `unknown bounds show the placeholder at both ends`() {
        assertEquals("colour scale ≥ -- · ≤ -- px", SummaryCaption.text(res, null, DicResult.IDX_U, "px"))
    }
}
