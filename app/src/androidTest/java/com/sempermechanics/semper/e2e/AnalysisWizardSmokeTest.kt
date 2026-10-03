@file:Suppress("MagicNumber")

package com.sempermechanics.semper.e2e

import android.graphics.Bitmap
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.CoachPrefs
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import com.sempermechanics.semper.ui.analysis.wizard.WizardStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented UI smoke: open the analysis screen and assert cold-start wizard
 * chrome. Step 1 shows Next + toolbar; Back / Compute / settings instruction
 * stay in the hierarchy as [View.GONE]. Not a full E2E (no fixture pick).
 */
@RunWith(AndroidJUnit4::class)
class AnalysisWizardSmokeTest {

    @get:Rule
    val scenarioRule = ActivityScenarioRule(StaticAnalysisActivity::class.java)

    @Before
    fun markCoachSeen() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        CoachPrefs.Screen.entries.forEach { CoachPrefs.markSeen(ctx, it) }
    }

    @Test
    fun wizardShowsNextWithoutCrashing() {
        onView(withId(R.id.btnNext)).check(matches(isDisplayed()))
    }

    @Test
    fun wizardShowsToolbar() {
        onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
    }

    @Test
    fun wizardKeepsInstructionOnSettingsPage() {
        scenarioRule.scenario.onActivity { activity ->
            val instruction = activity.findViewById<TextView>(R.id.tvInstruction)
            assertNotNull(instruction)
            // Settings page is not showing on step 1, so the row is not shown.
            assertEquals(false, instruction.isShown)
        }
    }

    @Test
    fun wizardHidesBackOnFirstStep() {
        scenarioRule.scenario.onActivity { activity ->
            val back = activity.findViewById<View>(R.id.btnBack)
            assertNotNull(back)
            assertEquals(View.GONE, back.visibility)
        }
    }

    @Test
    fun wizardHidesComputeOnFirstStep() {
        scenarioRule.scenario.onActivity { activity ->
            val compute = activity.findViewById<View>(R.id.btnCalculateFullField)
            assertNotNull(compute)
            assertEquals(View.GONE, compute.visibility)
        }
    }

    @Test
    fun wizardShowsSingleAndSweepSettingsOnStep2AndSummaryOnStep3() {
        goToWizardStep(WizardStep.SETTINGS)
        onView(withId(R.id.rgAnalysisMode)).check(matches(isDisplayed()))
        onView(withId(R.id.etOverlapValue)).check(matches(isDisplayed()))
        scenarioRule.scenario.onActivity { activity ->
            assertTrue(activity.findViewById<View>(R.id.advancedParamsCard).isVisible)
            assertFalse(activity.findViewById<View>(R.id.sweepSettingsCard).isVisible)
        }
        captureWizardShot("step2-single.png")

        onView(withId(R.id.rbModeSweep)).perform(click())
        scenarioRule.scenario.onActivity { activity ->
            assertFalse(activity.findViewById<View>(R.id.advancedParamsCard).isVisible)
            assertTrue(activity.findViewById<View>(R.id.sweepSettingsCard).isVisible)
            assertTrue(activity.findViewById<View>(R.id.tilStepDepth).isVisible)
            val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
            assertEquals(activity.getString(R.string.step_of_fmt, 2, 3), toolbar.subtitle)
        }
        captureWizardShot("step2-sweep.png")

        goToWizardStep(WizardStep.SWEEP)
        scenarioRule.scenario.onActivity { activity ->
            assertTrue(activity.findViewById<View>(R.id.plannedLatticeCard).isShown)
            assertTrue(activity.findViewById<View>(R.id.lineCutPreviewCard).isShown)
        }
        captureWizardShot("step3-summary.png")
    }

    private fun goToWizardStep(step: WizardStep) {
        scenarioRule.scenario.onActivity { activity -> activity.goToStep(step, animate = false) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun captureWizardShot(name: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(400)
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        File(dir, name).outputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
