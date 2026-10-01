package com.indicvision.semper.analysis

import com.indicvision.semper.data.session.SessionRecord.SyncState
import com.indicvision.semper.data.session.SessionRecordSettings
import com.indicvision.semper.fixtures.sessionRecord
import com.indicvision.semper.ui.analysis.run.UnsavedRerun
import com.indicvision.semper.ui.analysis.run.afterUnsavedRerun
import com.indicvision.semper.ui.analysis.wizard.AnalysisNavHelper
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A re-run deletes the previous frames before it starts. When it then saves
 * nothing, the Home row must stop describing frames that are gone.
 */
@RunWith(RobolectricTestRunner::class)
class UnsavedRerunTest {

    private fun previous(state: SyncState) = sessionRecord(
        id = "s1",
        name = "run",
        createdAt = 0,
        frameCount = 12,
        subset = 21,
        imgW = 64,
        imgH = 64,
        refPath = "/sessions/s1/reference.png",
        syncState = state,
    ).copy(headline = "εxx 1.2 mε", engineStats = listOf(1f, 2f))

    /** What the re-run solved with: not what the previous run did. */
    private val rerunSettings = SessionRecordSettings(
        subset = 31,
        step = 7,
        strainWin = 43,
        roiX = 4,
        roiY = 6,
        roiW = 50,
        roiH = 40,
        use6x6 = true,
    )

    private fun run(framesOnDisk: Int, stopCode: Int = -2) =
        UnsavedRerun(framesOnDisk, stopCode, plannedFrames = 12, settings = rerunSettings, defNames = listOf("a.png"))

    @Test
    fun `nothing on disk and backed up keeps the row, which now opens the cloud copy`() {
        val synced = previous(SyncState.SYNCED)
        assertSame(synced, afterUnsavedRerun(synced, run(framesOnDisk = 0)))
    }

    @Test
    fun `nothing on disk and nothing in the cloud drops the row`() {
        assertNull(afterUnsavedRerun(previous(SyncState.PENDING), run(0)))
        assertNull(afterUnsavedRerun(previous(SyncState.LOCAL_ONLY), run(0)))
    }

    @Test
    fun `frames on disk are what the row describes, without the gone run's numbers`() {
        val after = afterUnsavedRerun(previous(SyncState.SYNCED), run(framesOnDisk = 3))!!
        assertEquals(3, after.frameCount)
        assertEquals(-2, after.stopCode)
        assertEquals(12, after.plannedFrameCount)
        assertEquals("", after.headline)
        assertEquals(emptyList<Float>(), after.engineStats)
        assertEquals(SyncState.LOCAL_ONLY, after.syncState)
    }

    @Test
    fun `the row takes the settings and names of the run whose frames it now holds`() {
        val after = afterUnsavedRerun(previous(SyncState.LOCAL_ONLY), run(framesOnDisk = 3))!!
        assertEquals(
            listOf(31, 7, 43, 4, 6, 50, 40),
            with(after) { listOf(subset, step, strainWindow, roiX, roiY, roiW, roiH) },
        )
        assertEquals(true, after.use6x6)
        assertEquals(listOf("a.png"), after.defNames)
    }

    @Test
    fun `a sweep row re-run as a single analysis stops being a sweep`() {
        val sweep = previous(SyncState.LOCAL_ONLY).copy(
            sweepSubsets = listOf(21, 31),
            sweepSteps = listOf(5, 7),
            sweepStrainWindows = listOf(15, 21),
            sweepLabels = listOf("a", "b"),
        )
        val after = afterUnsavedRerun(sweep, run(framesOnDisk = 2))!!
        assertFalse(after.isSweep)
        assertEquals(emptyList<String>(), after.sweepLabels)
    }

    @Test
    fun `the viewer a kept row opens gets its reference, stop and size, not blanks`() {
        // BatchRunController opens the viewer on a partial run whose frames
        // went into the previous row: it used to get refPath "", stop 0, planned 0.
        val row = afterUnsavedRerun(previous(SyncState.LOCAL_ONLY), run(framesOnDisk = 3, stopCode = -7))!!
        val vm = AnalysisViewModel().apply {
            realRefWidth = 64
            realRefHeight = 64
        }

        vm.recordKeptRow(row)
        val args = AnalysisNavHelper.resultArgs(vm, sweep = false, frameNames = listOf("a.png"))

        assertEquals("/sessions/s1/reference.png", args.refPath)
        assertEquals(-7, args.stopCode)
        assertEquals(12, args.plannedFrames)
        assertEquals(listOf(31, 7, 43), listOf(args.subsetSize, args.step, args.strainWindow))
        assertEquals(listOf(4, 6, 50, 40), listOf(args.roiX, args.roiY, args.roiW, args.roiH))
    }
}
