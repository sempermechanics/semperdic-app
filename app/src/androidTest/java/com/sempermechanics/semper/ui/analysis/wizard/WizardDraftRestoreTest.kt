package com.sempermechanics.semper.ui.analysis.wizard

import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.data.prefs.WizardDraft
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * ADR-005 on a device: the view model mirrors its inputs into the real
 * `filesDir` draft as they change, its saved state survives a real Parcel
 * (what the system writes when the process dies), and a view model built from
 * that Parcel reads everything back. The kill itself is the scripted
 * `adb shell am kill` pass in docs/app/TESTING.md.
 */
@RunWith(AndroidJUnit4::class)
class WizardDraftRestoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var frames: File

    @Before
    fun setUp() {
        WizardDraft(context).clear()
        // Not temp_deformed: the app's own startup sweep may still be running.
        frames = File(context.cacheDir, "wizard_draft_restore_test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        WizardDraft(context).clear()
        frames.deleteRecursively()
    }

    private fun parcelled(state: Bundle): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(state)
            parcel.setDataPosition(0)
            return checkNotNull(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    private fun awaitDraft(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "the view model never mirrored its $what" }
            Thread.sleep(POLL_MS)
        }
    }

    @Test
    fun aRebuiltViewModelHasTheWizardBack() {
        val draft = WizardDraft(context)
        val paths = (1..FRAMES).map { File(frames, "frame_$it.png").apply { writeBytes(byteArrayOf(it.toByte())) }.path }
        val before = AnalysisViewModel(SavedStateHandle()).apply {
            attachDraft(draft)
            refBytes = REF
            roiMaskBytes = MASK
            realRefWidth = 640
            realRefHeight = 480
            refName = "speckle.png"
            hasCustomRoi = true
            roiX = 16
            roiY = 16
            roiW = 600
            roiH = 440
            deformedFrames = paths.map { DeformedFrame(it, File(it).name, size = ImageSize(640, 480)) }
            wizardStep = 2
            workingLocalId = "draft_device"
        }
        awaitDraft("reference") { draft.readReference()?.contentEquals(REF) == true }
        awaitDraft("mask") { draft.readMask()?.contentEquals(MASK) == true }

        val saved = parcelled(before.saveWizardState())
        val after = AnalysisViewModel(SavedStateHandle(mapOf(WizardState.KEY to saved)))
        after.attachDraft(WizardDraft(context))

        assertEquals(DraftRestore.RESTORED, runBlocking { after.restoreDraft() })
        assertArrayEquals(REF, after.refBytes)
        assertArrayEquals(MASK, after.roiMaskBytes)
        assertEquals(paths, after.defFilePaths)
        assertEquals(before.defFrameSizes, after.defFrameSizes)
        assertEquals(listOf(16, 16, 600, 440), listOf(after.roiX, after.roiY, after.roiW, after.roiH))
        assertEquals(2, after.wizardStep)
        assertEquals("draft_device", after.workingLocalId)
        assertTrue(WizardDraft.isLive(context.filesDir))
    }

    @Test
    fun leavingTheWizardDropsTheDraft() {
        val draft = WizardDraft(context)
        val vm = AnalysisViewModel(SavedStateHandle()).apply {
            attachDraft(draft)
            refBytes = REF
        }
        awaitDraft("reference") { draft.readReference() != null }
        vm.discardDraft()
        // Deleted on the draft's own lane, so onDestroy never waits for it.
        awaitDraft("deletion") { !WizardDraft.dirIn(context.filesDir).exists() }
    }

    private companion object {
        val REF = ByteArray(4096) { (it % 251).toByte() }
        val MASK = ByteArray(1024) { 1 }
        const val FRAMES = 3
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 20L
    }
}
