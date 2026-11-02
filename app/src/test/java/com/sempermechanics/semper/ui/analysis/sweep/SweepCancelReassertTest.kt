package com.sempermechanics.semper.ui.analysis.sweep

import android.app.Application
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.ui.analysis.run.SemperEngine
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisCancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The engine clears its cancel flag as each solve starts
 * (`engine/src/pipeline/full_field_solver.cpp`), so a Cancel confirmed just
 * before a solve used to be wiped and the combination solved to its end. The
 * stand-in engine here does what the real one does: clears the flag on entry,
 * ticks its progress callback, and stops with -99 if the flag is set again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SweepCancelReassertTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val native = SemperEngine.solver
    private val cancelToEngine = AnalysisCancel.toEngine

    /** The engine's own flag, as `setCancelRequested` writes it. */
    private var engineFlag = false
    private var solves = 0

    @Before
    fun setUp() {
        AnalysisCancel.toEngine = { engineFlag = it }
        SweepStudyRunner.cancelRequested = false
    }

    @After
    fun restore() {
        SweepStudyRunner.cancelRequested = false
        SemperEngine.solver = native
        AnalysisCancel.toEngine = cancelToEngine
    }

    private val plan = listOf(
        SweepStudy.Point(subset = 21, step = 7, window = 5),
        SweepStudy.Point(subset = 31, step = 10, window = 5),
    )

    private fun params() = SweepStudyRunner.Params(
        plan = plan,
        defFramePath = "",
        roiX = 0,
        roiY = 0,
        roiW = 140,
        roiH = 140,
        maskData = ByteArray(0),
        use6x6 = false,
        debugDir = null,
        outputDir = dir.newFolder("sweep"),
    )

    /** The real engine's entry: clear the flag, solve with progress ticks, -99 if cancelled meanwhile. */
    private fun engine(beforeClear: () -> Unit = {}) {
        SemperEngine.solver = SemperEngine.FullFieldSolver { _, _, _, _, _, _, _, _, _, _, _, _, callback, _ ->
            solves++
            beforeClear()
            engineFlag = false
            callback.onProgressUpdate(50)
            if (engineFlag) RunStop.Cancelled.wireCode else 100
        }
    }

    @Test
    fun `a Cancel confirmed after the loop's check never starts the next solve`() {
        engine()
        // The start tick comes after the loop's check and before the solve.
        SweepStudyRunner.solvePlan(byteArrayOf(1), byteArrayOf(2), params()) { tick ->
            if (tick.runIndex == 0 && tick.percent == 0) SweepStudyRunner.cancelRequested = true
        }

        assertEquals("no solve started after the cancel", 0, solves)
    }

    @Test
    fun `a Cancel the engine's solve start wiped is put back from its progress tick`() {
        // Confirmed in the window between the check and the engine's clear.
        engine(beforeClear = { if (solves == 1) SweepStudyRunner.cancelRequested = true })

        val result = SweepStudyRunner.solvePlan(byteArrayOf(1), byteArrayOf(2), params()) { }

        assertTrue("the engine was told again", engineFlag)
        assertTrue("the combination stopped instead of solving", result.runs.isEmpty())
        assertEquals(RunStop.Cancelled.wireCode, result.engineErrorCode)
        assertEquals("the plan stopped at the cancel", 1, solves)
    }

    @Test
    fun `no cancel, nothing is told to the engine`() {
        engine()
        assertFalse(AnalysisCancel.reassertIfRequested())
        SweepStudyRunner.solvePlan(byteArrayOf(1), byteArrayOf(2), params()) { }
        assertFalse(engineFlag)
        assertEquals(2, solves)
    }
}
