package com.indicvision.semper.analysis

import android.os.Looper
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.field.RunStop
import com.indicvision.semper.ui.analysis.frames.DeformedFrame
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.analysis.wizard.repointDeformedPathsOnMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.concurrent.thread

/**
 * Wizard state contracts in [AnalysisViewModel].
 *
 * This class is the analysis wizard's brain and the highest-churn file in the
 * app, but it sat in the Kover-excluded `ui` package with no direct coverage.
 * These pin the cheap, load-bearing seams — the compute gate, the working
 * session identity that keeps re-runs on one Home row, and the reset that
 * new inputs depend on — without touching the native solve.
 */
@RunWith(RobolectricTestRunner::class)
class AnalysisViewModelTest {

    private lateinit var vm: AnalysisViewModel

    private fun frames(vararg paths: String) = paths.map { DeformedFrame(it, it.substringAfterLast('/')) }

    @Before
    fun setUp() {
        vm = AnalysisViewModel()
    }

    // ------------------------------------------------------------ compute gate

    @Test
    fun `not ready to compute with neither reference nor deformed frames`() {
        assertFalse(vm.isReadyToCompute())
    }

    @Test
    fun `not ready to compute with a reference but no deformed frames`() {
        vm.refBytes = ByteArray(8)
        assertFalse(vm.isReadyToCompute())
    }

    @Test
    fun `not ready to compute with deformed frames but no reference`() {
        vm.deformedFrames = frames("/tmp/def0.png")
        assertFalse(vm.isReadyToCompute())
    }

    @Test
    fun `ready to compute once a reference and at least one deformed frame exist`() {
        vm.refBytes = ByteArray(8)
        vm.deformedFrames = frames("/tmp/def0.png")
        assertTrue(vm.isReadyToCompute())
    }

    @Test
    fun `defCount tracks the deformed frame list`() {
        assertEquals(0, vm.defCount)
        vm.deformedFrames = frames("/tmp/a.png", "/tmp/b.png", "/tmp/c.png")
        assertEquals(3, vm.defCount)
    }

    // ------------------------------------------------- working session identity

    @Test
    fun `a fresh view model would create a new session row`() {
        assertTrue(vm.wouldCreateNewSession())
    }

    @Test
    fun `re-runs over an existing working id do not create another session row`() {
        vm.workingLocalId = "abc123"
        assertFalse(vm.wouldCreateNewSession())
    }

    // -------------------------------------------------------------- reset logic

    @Test
    fun `clearPreviousResults resets every field a new input set must not inherit`() {
        vm.lastBatchDirPath = "/tmp/batch"
        vm.lastRefPath = "/tmp/ref.png"
        vm.lastDefPath = "/tmp/def.png"
        vm.workingLocalId = "abc123"

        vm.clearPreviousResults()

        assertNull(vm.lastBatchDirPath)
        assertNull(vm.lastRefPath)
        assertNull(vm.lastDefPath)
        assertNull(vm.workingLocalId)
    }

    @Test
    fun `clearing results sends the next run back to a new session row`() {
        vm.workingLocalId = "abc123"
        assertFalse(vm.wouldCreateNewSession())

        vm.clearPreviousResults()

        assertTrue(
            "new inputs must start a fresh Home row rather than overwrite the old one",
            vm.wouldCreateNewSession(),
        )
    }

    // ------------------------------------------- RunResult accessor consistency

    @Test
    fun `the lastX accessors read and write through the runResult snapshot`() {
        vm.lastBatchDirPath = "/tmp/batch"
        vm.lastRefPath = "/tmp/ref.png"
        vm.lastDefPath = "/tmp/def.png"
        vm.lastStop = RunStop.Other(7)
        vm.lastPlannedFrames = 12

        val snapshot = vm.runResult.value
        assertEquals("/tmp/batch", snapshot.batchDirPath)
        assertEquals("/tmp/ref.png", snapshot.refPath)
        assertEquals("/tmp/def.png", snapshot.defPath)
        assertEquals(RunStop.Other(7), snapshot.stop)
        assertEquals(12, snapshot.plannedFrames)
    }

    @Test
    fun `each setter leaves the other RunResult fields untouched`() {
        vm.lastPlannedFrames = 12
        vm.lastRefPath = "/tmp/ref.png"

        // A later, unrelated write must not clobber the earlier ones.
        vm.lastStop = RunStop.InitFailed

        assertEquals(12, vm.lastPlannedFrames)
        assertEquals("/tmp/ref.png", vm.lastRefPath)
        assertEquals(RunStop.InitFailed, vm.lastStop)
    }

    // ------------------------------------------------------------ new reference

    /** A wizard that has run once on a 640 x 480 reference with a drawn ROI and mask. */
    private fun ranWithCustomRoi() = vm.apply {
        applyNewReference(ByteArray(8), "first.png", ImageSize(640, 480))
        hasCustomRoi = true
        roiX = 400
        roiY = 300
        roiW = 200
        roiH = 150
        roiMaskBytes = ByteArray(640 * 480)
        workingLocalId = "abc123"
        lastBatchDirPath = "/tmp/batch"
    }

    @Test
    fun `a new reference of another size drops the drawn ROI and mask`() {
        ranWithCustomRoi()

        vm.applyNewReference(ByteArray(8), "second.png", ImageSize(320, 240))

        assertFalse(vm.hasCustomRoi)
        assertEquals(listOf(0, 0, 320, 240), listOf(vm.roiX, vm.roiY, vm.roiW, vm.roiH))
        assertNull("a 640 x 480 mask on a 320 x 240 image", vm.roiMaskBytes)
        assertEquals(320, vm.realRefWidth)
        assertEquals("second.png", vm.refName)
    }

    @Test
    fun `a new reference of the same size keeps the drawn ROI and mask`() {
        ranWithCustomRoi()
        val mask = vm.roiMaskBytes

        vm.applyNewReference(ByteArray(8), "second.png", ImageSize(640, 480))

        assertTrue(vm.hasCustomRoi)
        assertEquals(listOf(400, 300, 200, 150), listOf(vm.roiX, vm.roiY, vm.roiW, vm.roiH))
        assertSame(mask, vm.roiMaskBytes)
    }

    @Test
    fun `a new reference starts a new Home row`() {
        ranWithCustomRoi()
        assertFalse(vm.wouldCreateNewSession())

        vm.applyNewReference(ByteArray(8), "second.png", ImageSize(640, 480))

        assertTrue("a new reference overwrote the previous one's session", vm.wouldCreateNewSession())
        assertNull(vm.lastBatchDirPath)
    }

    // ------------------------------------------------------- run on the native thread

    @Test
    fun `a run moving the frames repoints them on the main thread, not its own`() {
        vm.deformedFrames = frames("/cache/0000_a.png", "/cache/0001_b.png").map { it.copy(size = ImageSize(4, 3)) }

        thread { vm.repointDeformedPathsOnMain(listOf("/session/a.png", "/session/b.png")) }.join()
        assertEquals(
            "the native thread wrote the wizard's paths itself",
            listOf("/cache/0000_a.png", "/cache/0001_b.png"),
            vm.defFilePaths,
        )

        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("/session/a.png", "/session/b.png"), vm.defFilePaths)
        assertEquals(mapOf("/session/a.png" to (4 to 3), "/session/b.png" to (4 to 3)), vm.defFrameSizes)
    }

    @Test
    fun `a fresh run result starts empty`() {
        val snapshot = vm.runResult.value
        assertNull(snapshot.batchDirPath)
        assertNull(snapshot.spec)
        assertNull(snapshot.settings)
        assertEquals(RunStop.Finished, snapshot.stop)
        assertEquals(0, snapshot.plannedFrames)
    }
}
