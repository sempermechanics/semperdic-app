package com.indicvision.semper.ui.analysis

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A pick nothing can be read from leaves the reference as it was, and a pick
 * with no display name is still named.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReferenceImportControllerTest {

    @Test
    fun `a pick with no display name is still named`() {
        val bed = WizardTestBed()
        assertEquals("Image_File", displayNameOf(bed.activity.contentResolver, Uri.fromFile(File("/x/ref.png"))))
    }

    @Test
    fun `an unreadable pick leaves the reference as it was`() {
        val bed = WizardTestBed()
        var loaded = 0
        val controller = ReferenceImportController(bed.activity, bed.viewModel, onLoaded = { loaded++ })
        val notThere = Uri.fromFile(File(bed.activity.cacheDir, "missing.png"))

        controller.load(notThere)
        repeat(IDLE_ROUNDS) {
            Thread.sleep(POLL_MS)
            bed.idle()
        }

        assertNull(bed.viewModel.refBytes)
        assertEquals(0, loaded)
    }

    private companion object {
        const val IDLE_ROUNDS = 10
        const val POLL_MS = 20L
    }
}
