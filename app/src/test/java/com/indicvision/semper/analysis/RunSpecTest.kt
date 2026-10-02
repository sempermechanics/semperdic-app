package com.indicvision.semper.analysis

import com.indicvision.semper.data.session.SessionRecordSettings
import com.indicvision.semper.field.DicParams
import com.indicvision.semper.field.Roi
import com.indicvision.semper.field.RunStop
import com.indicvision.semper.fixtures.sessionRecord
import com.indicvision.semper.ui.analysis.run.RunSpec
import com.indicvision.semper.ui.analysis.sweep.VsgStudy
import com.indicvision.semper.ui.analysis.wizard.AnalysisNavHelper
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.home.SessionOpenHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [RunSpec] (ADR-004): the engine gets exactly what it got before the spec
 * existed, and the viewer a run opens shows the settings its saved session
 * records (TD-61).
 */
@RunWith(RobolectricTestRunner::class)
class RunSpecTest {

    private val debugDir = File("debug")
    private val mask = byteArrayOf(1, 0, 1)

    // The ROI the user drew, and the one the engine solves after the inset.
    private val requested = intArrayOf(0, 0, 2000, 435)
    private val resolved = intArrayOf(20, 20, 1960, 395)

    private val spec = RunSpec.of(
        subset = 21,
        step = 5,
        strainWindow = 15,
        roi = resolved,
        mask = mask,
        use6x6 = true,
        debugDir = debugDir,
    )

    @Test
    fun `the spec holds exactly the values the engine is handed`() {
        assertEquals(DicParams(subset = 21, step = 5, strainWindow = 15), spec.params)
        assertEquals(Roi(resolved[0], resolved[1], resolved[2], resolved[3]), spec.roi)
        assertEquals(true, spec.use6x6)
        assertSame(debugDir, spec.debugDir)
        assertSame("the engine reads the mask the user drew, not a copy", mask, spec.mask)
    }

    @Test
    fun `the saved record gets the settings the engine solved with`() {
        // What DicBatchRunner built from the params before ADR-004.
        val before = SessionRecordSettings(
            subset = 21,
            step = 5,
            strainWin = 15,
            roiX = resolved[0],
            roiY = resolved[1],
            roiW = resolved[2],
            roiH = resolved[3],
            use6x6 = true,
        )

        assertEquals(before, spec.recordSettings())
    }

    @Test
    fun `no mask is an empty one, as the engine expects`() {
        val noMask = RunSpec.of(21, 5, 15, resolved, mask = null, use6x6 = false, debugDir = null)

        assertEquals(0, noMask.mask.size)
    }

    @Test
    fun `a sweep's first combination stands in for the scalar settings`() {
        val plan = listOf(VsgStudy.Point(31, 10, 9), VsgStudy.Point(41, 14, 15))
        val sweep = RunSpec.Sweep(plan, listOf("a", "b"), lineCutHorizontal = false, frameIndex = 1)

        val spec = RunSpec.sweep(sweep, Roi.fromXywh(resolved)!!, mask, use6x6 = false, debugDir = null)

        assertEquals(31, spec.params.subset)
        assertEquals(10, spec.params.step)
        // 9 points at step 10: the engine gets the VSG, (9 - 1) * 10 + 1 px.
        assertEquals(81, spec.params.strainWindow)
        assertEquals(sweep, spec.sweep)
    }

    // --------------------------------------------------------------- TD-61

    private fun finishedRun(): AnalysisViewModel = AnalysisViewModel().apply {
        realRefWidth = 2000
        realRefHeight = 435
        refName = "steel_24.png"
        workingLocalId = "f0f0406f-8c2"
        // The wizard's editing state: the requested ROI, as the user drew it.
        roiX = requested[0]
        roiY = requested[1]
        roiW = requested[2]
        roiH = requested[3]
        resetRunResult("/sessions/f0f0406f-8c2", spec)
        recordRunSettings(spec.recordSettings())
        lastRefPath = "/sessions/f0f0406f-8c2/reference.png"
        lastStop = RunStop.Finished
        lastPlannedFrames = 2
    }

    private fun savedRecord(settings: SessionRecordSettings) = sessionRecord(
        id = "f0f0406f-8c2",
        name = "steel_24",
        frameCount = 2,
        subset = settings.subset,
        step = settings.step,
        strainWindow = settings.strainWin,
        imgW = 2000,
        imgH = 435,
        roiX = settings.roiX,
        roiY = settings.roiY,
        roiW = settings.roiW,
        roiH = settings.roiH,
        refPath = "/sessions/f0f0406f-8c2/reference.png",
        refName = "steel_24.png",
        sessionDir = "/sessions/f0f0406f-8c2",
    ).copy(plannedFrameCount = 2)

    @Test
    fun `opening from the run and reopening from Home show the same settings`() {
        val vm = finishedRun()
        val fresh = AnalysisNavHelper.resultArgs(vm, sweep = false, frameNames = emptyList())
        val home = SessionOpenHelper.argsFor(savedRecord(spec.recordSettings()))

        // The fields ADR-004 owns; the remaining entry-path differences
        // (frame names, engine stats, deformed paths) are ADR-003's.
        assertEquals(home.roiX, fresh.roiX)
        assertEquals(home.roiY, fresh.roiY)
        assertEquals(home.roiW, fresh.roiW)
        assertEquals(home.roiH, fresh.roiH)
        assertEquals(home.step, fresh.step)
        assertEquals(home.subsetSize, fresh.subsetSize)
        assertEquals(home.strainWindow, fresh.strainWindow)
        assertEquals(home.sessionId, fresh.sessionId)
        assertEquals(home.sessionLocalId, fresh.sessionLocalId)
        assertEquals(home.plannedFrames, fresh.plannedFrames)
        assertEquals(home.refPath, fresh.refPath)
        assertEquals(home.batchDirPath, fresh.batchDirPath)
    }

    @Test
    fun `the viewer gets the solved ROI, not the one still being edited`() {
        val vm = finishedRun()
        vm.roiX = 300 // the user moves the ROI before tapping View

        val args = AnalysisNavHelper.resultArgs(vm, sweep = false, frameNames = emptyList())

        assertEquals(resolved.toList(), listOf(args.roiX, args.roiY, args.roiW, args.roiH))
    }

    @Test
    fun `a sweep that solved nothing shows its plan's settings`() {
        val plan = listOf(VsgStudy.Point(31, 10, 9))
        val sweep = RunSpec.Sweep(plan, listOf("a"), lineCutHorizontal = false, frameIndex = 0)
        val vm = AnalysisViewModel().apply {
            lastStop = RunStop.Other(-7) // a previous run's
            resetRunResult(
                "/sessions/x",
                RunSpec.sweep(sweep, Roi.fromXywh(resolved)!!, null, use6x6 = false, debugDir = null),
            )
        }

        val args = AnalysisNavHelper.resultArgs(vm, sweep = true, frameNames = emptyList())

        assertEquals(31, args.subsetSize)
        assertEquals(10, args.step)
        assertEquals(81, args.strainWindow) // the VSG of 9 points at step 10
        assertEquals("no stale stop code from the previous run", 0, args.stopCode)
        assertEquals(false, args.sweep?.lineCutHorizontal)
        assertNull(vm.runResult.value.settings)
    }
}
