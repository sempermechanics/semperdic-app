package com.sempermechanics.semper.viewer

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.sempermechanics.semper.R
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.launchViewer
import com.sempermechanics.semper.fixtures.viewerArgs
import com.sempermechanics.semper.fixtures.writeGridBatch
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

/**
 * Typing a frame number is the only way to reach frame 118 of 150 without 117
 * taps, so what matters is that a good number lands there and a bad one moves
 * nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
class FrameNumberEntryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var batchDir: File

    private companion object {
        const val FRAMES = 6
        const val GRID = 4
        const val STEP = 4
    }

    @Before
    fun writeBatch() {
        batchDir = temp.newFolder("batch")
        writeGridBatch(batchDir, FRAMES, GRID, STEP) // u differs per frame
    }

    private fun viewer(): ResultViewerActivity {
        // viewerArgs opens on a frame rather than the summary, which is what
        // the frame field is about.
        // The batch is listed off the main thread, so wait for it rather than one idle.
        return launchViewer(viewerArgs(batchDir, GRID, STEP)).also {
            idleUntil("the viewer") { it.frameSetLoaded }
        }
    }

    private fun ResultViewerActivity.field(): EditText = findViewById(R.id.etFrameNumber)

    private fun ResultViewerActivity.jumpTo(text: String) {
        field().setText(text)
        field().onEditorAction(EditorInfo.IME_ACTION_GO)
        shadowOf(mainLooper).idle()
    }

    @Test
    fun `the field shows which frame is open`() {
        val activity = viewer()

        assertEquals("1", activity.field().text.toString())
        assertEquals(
            activity.getString(R.string.frame_total_fmt, FRAMES),
            activity.findViewById<android.widget.TextView>(R.id.tvFrameTotal).text.toString(),
        )
    }

    @Test
    fun `a valid number jumps straight to that frame`() {
        val activity = viewer()

        activity.jumpTo("5")

        assertEquals(4, activity.currentFrameIndex)
        assertEquals("5", activity.field().text.toString())
    }

    @Test
    fun `a number past the end moves nothing and restores itself`() {
        val activity = viewer()

        activity.jumpTo("999")

        assertEquals(0, activity.currentFrameIndex)
        assertEquals("1", activity.field().text.toString())
    }

    @Test
    fun `zero is not a frame`() {
        val activity = viewer()

        activity.jumpTo("0")

        assertEquals(0, activity.currentFrameIndex)
        assertEquals("1", activity.field().text.toString())
    }

    @Test
    fun `an empty field restores the current frame rather than jumping`() {
        val activity = viewer()
        activity.jumpTo("3")

        activity.jumpTo("")

        assertEquals(2, activity.currentFrameIndex)
        assertEquals("3", activity.field().text.toString())
    }

    @Test
    fun `the scrubber stays up while a number is typed, then hides after the jump`() {
        val activity = viewer()
        val scrubber = activity.findViewById<View>(R.id.layoutScrubber)
        activity.bumpChrome()
        activity.field().requestFocus()

        // Well past the 2.5 s auto-hide: hiding would take the field's focus
        // and close the keyboard mid-number.
        shadowOf(activity.mainLooper).idleFor(Duration.ofSeconds(10))
        assertEquals(View.VISIBLE, scrubber.visibility)
        assertTrue(activity.field().hasFocus())

        activity.jumpTo("4")
        shadowOf(activity.mainLooper).idleFor(Duration.ofSeconds(10))

        assertEquals(3, activity.currentFrameIndex)
        assertEquals(View.INVISIBLE, scrubber.visibility)
    }

    @Test
    fun `stepping with Next writes the new number back`() {
        val activity = viewer()

        activity.findViewById<View>(R.id.btnNextFrame).performClick()
        shadowOf(activity.mainLooper).idle()

        assertEquals(1, activity.currentFrameIndex)
        assertEquals("2", activity.field().text.toString())
    }
}
