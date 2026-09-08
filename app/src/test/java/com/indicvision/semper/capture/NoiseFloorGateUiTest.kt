package com.indicvision.semper.capture

import android.graphics.Rect
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.R
import com.indicvision.semper.ui.capture.NoiseFloorGateUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * What the user is offered when the burst produced frames that would not
 * correlate with one another.
 *
 * This path used to be routed to the test-shot failure — "no photo was saved.
 * Try again" — and stopped the run. Both halves were wrong: six photos *were*
 * saved, and [com.indicvision.semper.ui.analysis.NoiseFloorStats.Outcome.INSUFFICIENT]
 * is documented as reporting and never blocking. A retry cannot fix a pattern
 * the recording size does not resolve; changing the size can.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoiseFloorGateUiTest {

    @Test
    fun `an uncorrelated burst offers the size that would resolve the speckle`() {
        val seen = Recorder()
        val gate = seen.gateFor(speckleDiameter = SPECKLE_ON_TEST_SHOT_PX)

        gate.showUncorrelatedDialog(PLAN_LONG_EDGE, PLAN_SHORT_EDGE) {}

        // The speckle measures 8 px on a 4000 px test shot, so it lands at 2 px
        // on a 1000 px recording — under the 3 px minimum, which is exactly why
        // nothing correlated. Five pixels needs 2.5x the long edge.
        clickPositive()
        assertEquals(listOf(2500), seen.resolutions)
        assertTrue("the burst did not fail", seen.burstFailures == 0)
    }

    @Test
    fun `an uncorrelated burst is not the test shot failing`() {
        val seen = Recorder()
        val gate = seen.gateFor(speckleDiameter = SPECKLE_ON_TEST_SHOT_PX)

        gate.showUncorrelatedDialog(PLAN_LONG_EDGE, PLAN_SHORT_EDGE) {}

        val shown = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue("a dialog is up", shown.isShowing)
        assertEquals(0, seen.burstFailures)
    }

    @Test
    fun `an uncorrelated burst does not block the run`() {
        val seen = Recorder()
        val gate = seen.gateFor(speckleDiameter = SPECKLE_ON_TEST_SHOT_PX)
        var proceeded = false

        gate.showUncorrelatedDialog(PLAN_LONG_EDGE, PLAN_SHORT_EDGE) { proceeded = true }
        assertFalse("not overridden until asked", gate.overridden)

        clickNegative()
        assertTrue("the user can record anyway", proceeded)
        assertTrue("and the session records that they did", gate.overridden)
    }

    @Test
    fun `with no speckle measured the offer is setup itself, not a retry`() {
        // The speckle could not be measured, so there is no size to recommend.
        // Re-opening setup with the plan intact is still a better offer than a
        // retry that will fail the same way.
        val seen = Recorder()
        val gate = seen.gateFor(speckleDiameter = null)

        gate.showUncorrelatedDialog(PLAN_LONG_EDGE, PLAN_SHORT_EDGE) {}

        clickPositive()
        assertEquals(listOf(0), seen.resolutions)
        assertEquals(0, seen.burstFailures)
    }

    private fun clickPositive() = click(AlertDialog.BUTTON_POSITIVE)

    private fun clickNegative() = click(AlertDialog.BUTTON_NEGATIVE)

    private fun click(which: Int) {
        val button = (ShadowDialog.getLatestDialog() as AlertDialog).getButton(which)
        assertNotNull("button $which", button)
        assertTrue("button $which fired", button.performClick())
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Which callbacks the gate reached for, so a test can name the wrong one. */
    private class Recorder {
        val resolutions = mutableListOf<Int>()
        var burstFailures = 0

        fun gateFor(speckleDiameter: Double?): NoiseFloorGateUi {
            val activity = Robolectric.buildActivity(AppCompatActivity::class.java)
                .create()
                .also { it.get().setTheme(R.style.Theme_Semper) }
                .start()
                .resume()
                .get()
            return NoiseFloorGateUi(
                activity = activity,
                onRetry = {},
                onBurstFailed = { burstFailures++ },
                onChangeResolution = { resolutions += it },
                onProceed = {},
                onFrameCost = {},
            ).apply {
                onSpeckleChecked(
                    roi = Rect(0, 0, TEST_SHOT_SHORT_EDGE, TEST_SHOT_LONG_EDGE),
                    imageWidth = TEST_SHOT_SHORT_EDGE,
                    imageHeight = TEST_SHOT_LONG_EDGE,
                    subsetSize = 17,
                    speckleDiameter = speckleDiameter,
                )
            }
        }
    }

    private companion object {
        /** A portrait test shot, as the vendor camera app hands one back. */
        const val TEST_SHOT_SHORT_EDGE = 3000
        const val TEST_SHOT_LONG_EDGE = 4000

        const val PLAN_LONG_EDGE = 1000
        const val PLAN_SHORT_EDGE = 750

        /** Comfortably inside the band on the test shot, and lost on the plan. */
        const val SPECKLE_ON_TEST_SHOT_PX = 8.0
    }
}
