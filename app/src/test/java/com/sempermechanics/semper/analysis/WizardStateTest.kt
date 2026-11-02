package com.sempermechanics.semper.analysis

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.prefs.WizardDraft
import com.sempermechanics.semper.data.session.CacheJanitor
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderDirection
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderMode
import com.sempermechanics.semper.ui.analysis.sweep.SweepFrame
import com.sempermechanics.semper.ui.analysis.sweep.SweepRanges
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.DraftRestore
import com.sempermechanics.semper.ui.analysis.wizard.RunKind
import com.sempermechanics.semper.ui.analysis.wizard.WizardState
import com.sempermechanics.semper.ui.analysis.wizard.WizardStep
import com.sempermechanics.semper.ui.analysis.wizard.WorkingSession
import com.sempermechanics.semper.ui.analysis.wizard.saveWizardState
import com.sempermechanics.semper.ui.analysis.wizard.toDeformedFrames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * The wizard across a process death (ADR-005): the scalars through the
 * saved-state Bundle, the rest through the [WizardDraft], and the janitor
 * keeping a live draft's staged frames.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: SemperApp.onCreate starts its own startup sweep, which
// would race these tests for cacheDir/temp_deformed.
@Config(application = Application::class)
class WizardStateTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var frames: File
    private lateinit var draft: WizardDraft

    @Before
    fun setUp() {
        frames = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply { mkdirs() }
        draft = WizardDraft(ctx)
        draft.clear()
    }

    @After
    fun tearDown() {
        frames.deleteRecursively()
        draft.clear()
    }

    /** A wizard at step 3 with every kind of input, as the user left it. */
    private fun editedWizard(): AnalysisViewModel = AnalysisViewModel().apply {
        val paths = listOf("f1.png", "f2.png").map { File(frames, it).apply { writeBytes(byteArrayOf(1)) }.path }
        edit {
            it.copy(
                refBytes = REF,
                roiMaskBytes = MASK,
                refSize = ImageSize(400, 300),
                refName = "ref.png",
                hasCustomRoi = true,
                roi = Roi(10, 20, 300, 200),
                deformedFrames = listOf(
                    DeformedFrame(paths[0], "IMG_1.png", 100L, ImageSize(400, 300)),
                    DeformedFrame(paths[1], "IMG_2.png", Long.MAX_VALUE),
                ),
                defOrderMode = FrameOrderMode.DATE,
                defOrderDirection = FrameOrderDirection.DESCENDING,
                step = WizardStep.of(3),
                settingsReviewed = true,
                subsetUserModified = true,
                sweepMode = true,
                sweepRanges = SweepRanges(
                    subsetMin = 21,
                    subsetMax = 61,
                    strainWinMin = 9,
                    strainWinMax = 31,
                    subsetSamples = 4,
                    strainWinSamples = 5,
                    stepDenominator = 4,
                ),
                subsetOverlap = 0.75,
                lineCutHorizontal = false,
                sweepFrame = SweepFrame.At(1),
                working = WorkingSession("abc123", RunKind.SWEEP),
            )
        }
    }

    /** What the system hands back after the kill, with the draft the stop wrote. */
    private fun afterProcessDeath(before: AnalysisViewModel): AnalysisViewModel {
        draft.writeReference(before.inputs.value.refBytes)
        draft.writeMask(before.inputs.value.roiMaskBytes)
        // What saveWizardState writes when a draft is attached.
        draft.writeFrames(WizardState.encodeFrames(WizardState.frames(before)))
        val saved = before.saveWizardState()
        return AnalysisViewModel(SavedStateHandle(mapOf(WizardState.KEY to saved))).also { it.attachDraft(draft) }
    }

    @Test
    fun `the scalars are back before the draft is read`() {
        val before = editedWizard()
        val after = afterProcessDeath(before)

        assertEquals(3, after.inputs.value.step.number)
        assertEquals(Roi(10, 20, 300, 200), after.inputs.value.roi)
        assertTrue(after.inputs.value.hasCustomRoi)
        assertEquals(400, after.inputs.value.refSize.width)
        assertEquals("ref.png", after.inputs.value.refName)
        assertEquals(FrameOrderMode.DATE, after.inputs.value.defOrderMode)
        assertEquals(FrameOrderDirection.DESCENDING, after.inputs.value.defOrderDirection)
        assertTrue(after.inputs.value.run { sweepMode && settingsReviewed && subsetUserModified })
        assertEquals(
            listOf(21, 61, 9, 31, 4, 5, 4),
            with(after.inputs.value.sweepRanges) {
                listOf(subsetMin, subsetMax, strainWinMin, strainWinMax)
                    .plus(listOf(subsetSamples, strainWinSamples, stepDenominator))
            },
        )
        assertEquals(0.75, after.inputs.value.subsetOverlap, 0.0)
        assertFalse(after.inputs.value.lineCutHorizontal)
        assertEquals(SweepFrame.At(1), after.inputs.value.sweepFrame)
        // The re-run keeps its Home row instead of making a second one.
        // ...and its kind, so a single run after the sweep still makes its own.
        assertEquals(WorkingSession("abc123", RunKind.SWEEP), after.inputs.value.working)
        // Not yet: the heavy inputs wait for restoreDraft.
        assertNull(after.inputs.value.refBytes)
        assertTrue(after.defFilePaths.isEmpty())
    }

    @Test
    fun `the draft brings back the reference, mask and frames`() {
        val before = editedWizard()
        val after = afterProcessDeath(before)

        assertEquals(DraftRestore.RESTORED, runBlocking { after.restoreDraft() })
        assertArrayEquals(REF, after.inputs.value.refBytes)
        assertArrayEquals(MASK, after.inputs.value.roiMaskBytes)
        assertEquals(before.defFilePaths, after.defFilePaths)
        assertEquals(before.defOriginalNames, after.defOriginalNames)
        assertEquals(before.defFrameDates, after.defFrameDates)
        assertEquals(before.defFrameSizes, after.defFrameSizes)
        assertEquals(3, after.inputs.value.step.number)
        // Once only.
        assertEquals(DraftRestore.NONE, runBlocking { after.restoreDraft() })
    }

    @Test
    fun `a video's clip name and frame times come back with its frames`() {
        val before = editedWizard().apply {
            edit { current ->
                current.copy(
                    deformedFrames = current.deformedFrames.mapIndexed { i, f -> f.copy(timeMs = 40L * (i + 1)) },
                    defFromVideo = true,
                    defVideoName = "tensile_03",
                )
            }
        }
        val after = afterProcessDeath(before)

        assertEquals("tensile_03", after.inputs.value.defVideoName)
        assertEquals(DraftRestore.RESTORED, runBlocking { after.restoreDraft() })
        assertEquals(listOf(40L, 80L), after.defFrameTimesMs)
        assertEquals(before.inputs.value.deformedFrames, after.inputs.value.deformedFrames)
    }

    @Test
    fun `picked images save no frame times, and a time list of another length is dropped`() {
        val images = editedWizard()
        assertTrue(WizardState.frames(images).timesMs.isEmpty())
        assertNull(images.defFrameTimesMs)

        val short = WizardState.frames(images).copy(timesMs = listOf(40L))
        assertTrue(short.toDeformedFrames().all { it.timeMs == null })
    }

    @Test
    fun `a frame the OS evicted loses the whole draft`() {
        val before = editedWizard()
        val after = afterProcessDeath(before)
        File(before.defFilePaths[1]).delete()

        assertEquals(DraftRestore.LOST, runBlocking { after.restoreDraft() })
        assertEquals(1, after.inputs.value.step.number)
        assertNull(after.inputs.value.refBytes)
        assertNull(after.inputs.value.roiMaskBytes)
        assertTrue(after.defFilePaths.isEmpty())
        assertEquals(0, after.inputs.value.refSize.width)
        assertEquals(AnalysisViewModel.NO_REFERENCE_NAME, after.inputs.value.refName)
        assertNull(after.inputs.value.working)
        // The sweep ranges are choices, not inputs; they stay.
        assertEquals(21, after.inputs.value.sweepRanges.subsetMin)
    }

    @Test
    fun `a missing reference loses the draft`() {
        val before = editedWizard()
        val after = afterProcessDeath(before)
        draft.writeReference(null)

        assertEquals(DraftRestore.LOST, runBlocking { after.restoreDraft() })
    }

    /** What a stop leaves when the draft still holds [reference] and [mask] from before the last edit. */
    private fun staleDraft(before: AnalysisViewModel, reference: ByteArray?, mask: ByteArray?): AnalysisViewModel {
        draft.writeReference(reference)
        draft.writeMask(mask)
        draft.writeFrames(WizardState.encodeFrames(WizardState.frames(before)))
        // No draft attached to before: its new reference or mask never lands.
        val saved = before.saveWizardState()
        return AnalysisViewModel(SavedStateHandle(mapOf(WizardState.KEY to saved))).also { it.attachDraft(draft) }
    }

    @Test
    fun `a reference whose write never landed loses the draft`() {
        val before = editedWizard()
        // The previous reference, as many bytes as the new one.
        val after = staleDraft(before, reference = REF.reversedArray(), mask = MASK)

        assertEquals(DraftRestore.LOST, runBlocking { after.restoreDraft() })
        assertNull(after.inputs.value.refBytes)
    }

    @Test
    fun `a mask whose write never landed loses the draft`() {
        val before = editedWizard()
        val after = staleDraft(before, reference = REF, mask = ByteArray(MASK.size))

        assertEquals(DraftRestore.LOST, runBlocking { after.restoreDraft() })
        assertNull(after.inputs.value.roiMaskBytes)
    }

    @Test
    fun `a Bundle saved before the digests restores on the parts being there`() {
        val before = editedWizard()
        draft.writeReference(REF)
        draft.writeMask(MASK)
        draft.writeFrames(WizardState.encodeFrames(WizardState.frames(before)))
        val saved = before.saveWizardState().apply {
            remove("referenceDigest")
            remove("maskDigest")
        }
        val after = AnalysisViewModel(SavedStateHandle(mapOf(WizardState.KEY to saved))).also { it.attachDraft(draft) }

        assertEquals(DraftRestore.RESTORED, runBlocking { after.restoreDraft() })
        assertArrayEquals(REF, after.inputs.value.refBytes)
    }

    @Test
    fun `a frame list whose write never landed loses the draft, even at the same count`() {
        val before = editedWizard()
        // What the previous stop wrote.
        draft.writeReference(REF)
        draft.writeMask(MASK)
        draft.writeFrames(WizardState.encodeFrames(WizardState.frames(before)))
        // Reordered since: same frames, same count, other order.
        before.edit { it.copy(deformedFrames = before.inputs.value.deformedFrames.reversed()) }
        // No draft attached to before: this stop's frame-list write never lands.
        val saved = before.saveWizardState()
        val after = AnalysisViewModel(SavedStateHandle(mapOf(WizardState.KEY to saved))).also { it.attachDraft(draft) }

        assertEquals(DraftRestore.LOST, runBlocking { after.restoreDraft() })
        assertTrue(after.defFilePaths.isEmpty())
    }

    @Test
    fun `a Bundle an older app saved restores on the frame count alone`() {
        val before = editedWizard()
        draft.writeReference(REF)
        draft.writeMask(MASK)
        draft.writeFrames(WizardState.encodeFrames(WizardState.frames(before)))
        val saved = before.saveWizardState().apply { remove("framesFingerprint") }
        val after = AnalysisViewModel(SavedStateHandle(mapOf(WizardState.KEY to saved))).also { it.attachDraft(draft) }

        assertEquals(DraftRestore.RESTORED, runBlocking { after.restoreDraft() })
        assertEquals(before.defFilePaths, after.defFilePaths)
    }

    @Test
    fun `the frame list a stop queued is written after its view model is cleared`() {
        val store = ViewModelStore()
        val factory = viewModelFactory { initializer { editedWizard() } }
        val vm = ViewModelProvider(store, factory)[AnalysisViewModel::class.java]
        vm.attachDraft(draft)
        drainDraftLane()
        // Keep the lane busy, so the stop's write is still queued when the
        // view model goes ("Don't keep activities").
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        CoroutineScope(WizardDraft.io).launch {
            started.countDown()
            release.await()
        }
        started.await()
        vm.saveWizardState()
        store.clear()
        release.countDown()
        drainDraftLane()

        assertEquals(WizardState.encodeFrames(WizardState.frames(vm)), draft.readFrames())
    }

    @Test
    fun `a fresh wizard restores nothing`() {
        val vm = AnalysisViewModel().also { it.attachDraft(draft) }
        assertEquals(DraftRestore.NONE, runBlocking { vm.restoreDraft() })
    }

    @Test
    fun `startup keeps a live draft's frames and drops a stale draft with them`() {
        val frame = File(frames, "f1.png").apply { writeBytes(byteArrayOf(1)) }
        draft.writeFrames("{}")

        CacheJanitor.sweepOnStartup(ctx)
        assertTrue("a live draft's frames were reclaimed", frame.isFile)

        File(WizardDraft.dirIn(ctx.filesDir), "live")
            .setLastModified(System.currentTimeMillis() - WizardDraft.MAX_AGE_MS - 1)
        CacheJanitor.sweepOnStartup(ctx)
        assertFalse(frame.exists())
        assertFalse(WizardDraft.dirIn(ctx.filesDir).exists())
    }

    @Test
    fun `startup without a draft still reclaims the import`() {
        val frame = File(frames, "f1.png").apply { writeBytes(byteArrayOf(1)) }
        CacheJanitor.sweepOnStartup(ctx)
        assertFalse(frame.exists())
    }

    @Test
    fun `leaving the wizard deletes its draft`() {
        val vm = AnalysisViewModel().also { it.attachDraft(draft) }
        vm.edit { it.copy(refBytes = REF) }
        drainDraftLane()
        assertArrayEquals(REF, draft.readReference())

        vm.discardDraft()
        vm.edit { it.copy(roiMaskBytes = MASK) }
        drainDraftLane()
        assertFalse(WizardDraft.dirIn(ctx.filesDir).exists())
    }

    @Test
    fun `an edit that drops the mask drops it from the draft too`() {
        val vm = AnalysisViewModel().also { it.attachDraft(draft) }
        vm.edit { it.copy(refBytes = REF, roiMaskBytes = MASK, refSize = ImageSize(400, 300), hasCustomRoi = true) }
        drainDraftLane()
        assertArrayEquals(MASK, draft.readMask())

        // A reference of another size: the mask goes with the ROI, in the same edit.
        val other = REF.copyOf()
        vm.applyNewReference(other, "other.png", ImageSize(200, 100))
        drainDraftLane()

        assertNull(draft.readMask())
        assertArrayEquals(other, draft.readReference())
    }

    @Test
    fun `a wizard left after the next one opened does not delete the next one's draft`() {
        // The next wizard's onCreate runs before the old one's onDestroy.
        val gone = AnalysisViewModel().also { it.attachDraft(draft) }
        val next = AnalysisViewModel().also { it.attachDraft(WizardDraft(ctx)) }
        next.edit { it.copy(refBytes = REF) }
        gone.discardDraft()
        drainDraftLane()

        assertArrayEquals(REF, draft.readReference())
    }

    @Test
    fun `an import that lands in the old wizard does not overwrite the next one's reference`() {
        val gone = AnalysisViewModel().also { it.attachDraft(draft) }
        val next = AnalysisViewModel().also { it.attachDraft(WizardDraft(ctx)) }
        next.edit { it.copy(refBytes = REF) }
        // A video import's result is applied even after its screen is gone.
        gone.edit { it.copy(refBytes = MASK) }
        gone.discardDraft()
        drainDraftLane()

        assertArrayEquals(REF, draft.readReference())
    }

    @Test
    fun `a wizard restored in the same process gets the frame list its predecessor's stop queued`() {
        val before = editedWizard().also { it.attachDraft(draft) }
        drainDraftLane()
        draft.writeReference(before.inputs.value.refBytes)
        draft.writeMask(before.inputs.value.roiMaskBytes)
        // Keep the lane busy, so the stop's write is still queued when the
        // rebuilt view model attaches ("Don't keep activities").
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        CoroutineScope(WizardDraft.io).launch {
            started.countDown()
            release.await()
        }
        started.await()
        val saved = before.saveWizardState()
        val after = AnalysisViewModel(SavedStateHandle(mapOf(WizardState.KEY to saved))).also { it.attachDraft(draft) }
        release.countDown()

        assertEquals(DraftRestore.RESTORED, runBlocking { after.restoreDraft() })
        assertEquals(before.defFilePaths, after.defFilePaths)
    }

    /** Waits for every draft write and delete queued so far. */
    private fun drainDraftLane() = runBlocking { withContext(WizardDraft.io) {} }

    /**
     * Holds the draft's lock, as a reference write of tens of megabytes does,
     * while [onMain] runs on another thread; true when [onMain] returned
     * without waiting for it.
     */
    private fun returnsWhileDraftIsBusy(onMain: () -> Unit): Boolean {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = thread {
            synchronized(draft) {
                held.countDown()
                release.await()
            }
        }
        held.await()
        val main = thread { onMain() }
        main.join(BUSY_WAIT_MS)
        val returned = !main.isAlive
        release.countDown()
        writer.join()
        main.join()
        return returned
    }

    @Test
    fun `leaving the wizard does not wait for a draft write in flight`() {
        val vm = AnalysisViewModel().also { it.attachDraft(draft) }
        drainDraftLane()
        draft.writeReference(REF)

        assertTrue("discard blocked on the draft's lock", returnsWhileDraftIsBusy { vm.discardDraft() })
        drainDraftLane()
        assertFalse(WizardDraft.dirIn(ctx.filesDir).exists())
    }

    @Test
    fun `stopping the wizard does not wait for a draft write in flight`() {
        val vm = editedWizard().also { it.attachDraft(draft) }
        drainDraftLane()
        // Set before the draft was attached, so not mirrored: what the setters write.
        draft.writeReference(REF)
        draft.writeMask(MASK)

        var saved: android.os.Bundle? = null
        val returned = returnsWhileDraftIsBusy { saved = vm.saveWizardState() }
        assertTrue("saveWizardState blocked on the draft's lock", returned)
        drainDraftLane()

        assertEquals(2, saved?.getInt("frameCount"))
        val after = AnalysisViewModel(SavedStateHandle(mapOf(WizardState.KEY to saved))).also { it.attachDraft(draft) }
        assertEquals(DraftRestore.RESTORED, runBlocking { after.restoreDraft() })
        assertEquals(vm.defFilePaths, after.defFilePaths)
    }

    private companion object {
        val REF = ByteArray(64) { it.toByte() }
        val MASK = ByteArray(16) { 1 }
        const val BUSY_WAIT_MS = 2_000L
    }
}
