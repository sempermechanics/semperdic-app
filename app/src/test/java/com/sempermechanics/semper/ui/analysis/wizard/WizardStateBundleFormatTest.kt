package com.sempermechanics.semper.ui.analysis.wizard

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import com.sempermechanics.semper.ui.analysis.sweep.VsgStudy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The saved-state Bundle a process death leaves (ADR-005) is a persisted
 * format: a Bundle an older build saved must restore in a newer one, and the
 * newer one must write the same keys with the same value types.
 *
 * [baseBundle] is that Bundle written out by hand, key by key, as the build
 * before the wizard's value types (`Roi`, `ImageSize`, `SweepRanges`,
 * `WizardStep`) wrote it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WizardStateBundleFormatTest {

    /** Every key the base build wrote, with the type it wrote it as. */
    private fun baseBundle(): Bundle = Bundle().apply {
        putInt("step", 3)
        putBoolean("settingsReviewed", true)
        putBoolean("subsetUserModified", true)
        putInt("refW", 400)
        putInt("refH", 300)
        putString("refName", "ref.png")
        putBoolean("hasReference", false)
        putBoolean("hasMask", false)
        putBoolean("hasCustomRoi", true)
        putIntArray("roi", intArrayOf(10, 20, 300, 200))
        putInt("frameCount", 0)
        putString("orderMode", "DATE")
        putString("orderDirection", "DESCENDING")
        putString("frameSizeError", "sizes differ")
        putBoolean("fromVideo", true)
        putBoolean("sweepMode", true)
        putIntArray("sweepRanges", intArrayOf(21, 61, 9, 31, 4, 5, 4))
        putDouble("subsetOverlap", 0.75)
        putBoolean("lineCutHorizontal", false)
        putInt("vsgFrameIndex", 1)
        putString("workingLocalId", "abc123")
        putString("framesFingerprint", WizardState.fingerprint(WizardState.encodeFrames(WizardState.Frames())))
    }

    @Test
    fun `a Bundle the base build saved restores and saves again unchanged`() {
        val restored = AnalysisViewModel(SavedStateHandle(mapOf(WizardState.KEY to baseBundle())))

        assertSameBundle(baseBundle(), restored.saveWizardState())
    }

    @Test
    fun `a fresh wizard saves the base build's defaults`() {
        val expected = Bundle().apply {
            putInt("step", 1)
            putBoolean("settingsReviewed", false)
            putBoolean("subsetUserModified", false)
            putInt("refW", 0)
            putInt("refH", 0)
            putString("refName", AnalysisViewModel.NO_REFERENCE_NAME)
            putBoolean("hasReference", false)
            putBoolean("hasMask", false)
            putBoolean("hasCustomRoi", false)
            putIntArray("roi", intArrayOf(0, 0, 0, 0))
            putInt("frameCount", 0)
            putString("orderMode", "NAME")
            putString("orderDirection", "ASCENDING")
            putString("frameSizeError", null)
            putBoolean("fromVideo", false)
            putBoolean("sweepMode", false)
            putIntArray("sweepRanges", intArrayOf(0, 0, 0, 0, 3, 3, 3))
            putDouble("subsetOverlap", VsgStudy.overlapForDenominator(VsgStudy.DEFAULT_STEP_DENOM))
            putBoolean("lineCutHorizontal", true)
            putInt("vsgFrameIndex", -1)
            putString("workingLocalId", null)
            putString("framesFingerprint", WizardState.fingerprint(WizardState.encodeFrames(WizardState.Frames())))
        }

        assertSameBundle(expected, AnalysisViewModel().saveWizardState())
    }

    @Test
    fun `a malformed roi or sweep array leaves the defaults, as the base build did`() {
        val malformed = baseBundle().apply {
            putIntArray("roi", intArrayOf(1, 2, 3))
            putIntArray("sweepRanges", intArrayOf(1, 2))
        }
        val vm = AnalysisViewModel(SavedStateHandle(mapOf(WizardState.KEY to malformed)))

        val saved = vm.saveWizardState()
        assertArrayEquals(intArrayOf(0, 0, 0, 0), saved.getIntArray("roi"))
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 3, 3, 3), saved.getIntArray("sweepRanges"))
    }

    @Suppress("DEPRECATION") // Bundle.get: the untyped read is the point, it shows the stored type
    private fun assertSameBundle(expected: Bundle, actual: Bundle) {
        assertEquals(expected.keySet(), actual.keySet())
        for (key in expected.keySet()) {
            val want = expected.get(key)
            val got = actual.get(key)
            assertEquals("type of $key", want?.javaClass, got?.javaClass)
            if (want is IntArray) {
                assertArrayEquals("value of $key", want, got as IntArray)
            } else {
                assertEquals("value of $key", want, got)
            }
        }
    }
}
