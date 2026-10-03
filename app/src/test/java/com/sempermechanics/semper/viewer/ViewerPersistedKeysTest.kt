package com.sempermechanics.semper.viewer

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.fixtures.viewerArgs
import com.sempermechanics.semper.fixtures.viewerController
import com.sempermechanics.semper.fixtures.writeGridBatch
import com.sempermechanics.semper.ui.viewer.ResultViewerViewModel
import com.sempermechanics.semper.ui.viewer.share.ShareKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The viewer's persisted names, written out by hand. A recreated viewer, or one
 * restored after process death, reads back what the last one saved under these
 * exact strings, so none of them may be renamed.
 */
@RunWith(RobolectricTestRunner::class)
class ViewerPersistedKeysTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the share kinds keep their wire strings`() {
        assertEquals(listOf("photo", "photos", "gifs", "pdf", "csv", "zip"), ShareKind.entries.map { it.wire })
    }

    @Test
    fun `the viewer saves its state under the base build's keys`() {
        val batchDir = temp.newFolder("batch")
        writeGridBatch(batchDir, frames = 2, grid = 4, step = 4)
        val controller = viewerController(viewerArgs(batchDir, grid = 4, step = 4))
        controller.get().pickShareDocument(ShareKind.CSV, "picked.csv")

        val saved = Bundle()
        controller.saveInstanceState(saved)

        assertTrue(saved.containsKey("LAST_CLOSEST_IDX"))
        assertTrue(saved.containsKey("CURRENT_FRAME"))
        assertTrue(saved.containsKey("SHOWING_SUMMARY"))
        assertEquals("csv", saved.getString("PENDING_SHARE_KIND"))
    }

    @Test
    fun `a pending save-as is kept under the base build's keys`() {
        val state = SavedStateHandle()
        val vm = ResultViewerViewModel(ApplicationProvider.getApplicationContext<Application>(), state)
        val uri = Uri.parse("content://picked/doc")

        vm.setPendingSave("pdf", uri)

        assertEquals("pdf", state.get<String>("pending_save_kind"))
        assertEquals(uri, state.get<Uri>("pending_save_uri"))
    }
}
