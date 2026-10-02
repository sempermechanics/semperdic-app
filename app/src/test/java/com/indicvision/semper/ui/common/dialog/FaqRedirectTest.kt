package com.indicvision.semper.ui.common.dialog

import com.google.android.material.snackbar.Snackbar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A snackbar that explains a failure and its remedy must stay up long enough
 * to be read, without holding a one-liner on screen any longer than before.
 */
class FaqRedirectTest {

    @Test
    fun `a short message keeps the long duration`() {
        assertEquals(Snackbar.LENGTH_LONG, FaqRedirect.durationFor("Could not read this video."))
    }

    @Test
    fun `a cause-and-remedy message stays up long enough to read, but not forever`() {
        val codec = "This AVI holds HFYU video, which this device has no decoder for. Re-save it as " +
            "MP4 (H.264), or export the frames as PNG and import them as images."
        val ms = FaqRedirect.durationFor(codec)

        assertTrue("$ms ms", ms in 8_000..10_000)
        assertEquals(10_000, FaqRedirect.durationFor("x".repeat(1_000)))
    }
}
