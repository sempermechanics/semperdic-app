package com.indicvision.semper.viewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import com.indicvision.semper.fixtures.idleUntil
import com.indicvision.semper.ui.viewer.SaveExportActivity
import com.indicvision.semper.ui.viewer.SaveExportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.Executor

/**
 * The "Save to Files" proxy over a rotation: one picker, never two, and the
 * picked document still gets the file whichever instance the answer reaches.
 */
@RunWith(RobolectricTestRunner::class)
class SaveExportActivityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private companion object {
        const val CSV = "image,x,y\nFrame_1,0,0\n"
    }

    private fun staged(): File = temp.newFile("export.csv").apply { writeText(CSV) }

    private fun intent(file: File): Intent =
        SaveExportActivity.intent(ApplicationProvider.getApplicationContext(), file, "text/csv")

    @Test
    fun `a rotation while the picker is open opens no second picker`() {
        val controller = Robolectric.buildActivity(SaveExportActivity::class.java, intent(staged())).setup()
        val picker = shadowOf(controller.get()).nextStartedActivityForResult
        assertNotNull(picker)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent.action)

        val rebuilt = controller.recreate().get()

        assertNull("a second picker", shadowOf(rebuilt).nextStartedActivityForResult)
        assertTrue("still waiting on the first picker", !rebuilt.isFinishing)
    }

    @Test
    fun `the first picker's answer reaches the recreated proxy and saves`() {
        val controller = Robolectric.buildActivity(SaveExportActivity::class.java, intent(staged())).setup()
        val picker = shadowOf(controller.get()).nextStartedActivityForResult
        val rebuilt = controller.recreate().get()
        val dest = File(temp.root, "picked.csv")

        rebuilt.activityResultRegistry.dispatchResult(
            picker.requestCode,
            Activity.RESULT_OK,
            Intent().setData(Uri.fromFile(dest)),
        )
        idleUntil("the save") { rebuilt.isFinishing }

        assertEquals(CSV, dest.readText())
        assertEquals(rebuilt.getString(R.string.save_success), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a rotation mid-copy finishes the copy instead of leaving the proxy up`() {
        val controller = Robolectric.buildActivity(SaveExportActivity::class.java, intent(staged())).setup()
        val first = controller.get()
        val picker = shadowOf(first).nextStartedActivityForResult
        val dest = File(temp.root, "picked.csv")
        first.activityResultRegistry.dispatchResult(
            picker.requestCode,
            Activity.RESULT_OK,
            Intent().setData(Uri.fromFile(dest)),
        )

        // Before the copy's result is back on the main thread.
        val rebuilt = controller.recreate().get()
        idleUntil("the save") { rebuilt.isFinishing }

        assertNull("no new picker", shadowOf(rebuilt).nextStartedActivityForResult)
        assertEquals(CSV, dest.readText())
    }

    @Test
    fun `a rotation mid-copy waits on the running copy instead of starting another`() {
        val copies = mutableListOf<Runnable>()
        SaveExportViewModel.ioDispatcher = Executor { copies += it }.asCoroutineDispatcher()
        try {
            val controller = Robolectric.buildActivity(SaveExportActivity::class.java, intent(staged())).setup()
            val first = controller.get()
            val picker = shadowOf(first).nextStartedActivityForResult
            val dest = File(temp.root, "picked.csv")
            first.activityResultRegistry.dispatchResult(
                picker.requestCode,
                Activity.RESULT_OK,
                Intent().setData(Uri.fromFile(dest)),
            )
            assertEquals("the copy is under way", 1, copies.size)

            // Rotated while that copy is still writing the document.
            val rebuilt = controller.recreate().get()
            assertEquals("no second copy into the same document", 1, copies.size)

            copies.toList().forEach { it.run() }
            idleUntil("the save") { rebuilt.isFinishing }
            assertEquals(CSV, dest.readText())
            assertEquals(rebuilt.getString(R.string.save_success), ShadowToast.getTextOfLatestToast())
        } finally {
            SaveExportViewModel.ioDispatcher = Dispatchers.IO
        }
    }
}
