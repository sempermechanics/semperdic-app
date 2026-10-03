package com.sempermechanics.semper.ui.common

import android.app.Application
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The spinner shows and the controls lock while busy; idle hides it as the screen chose. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BusyTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val progress = ProgressBar(context)
    private val agree = Button(context)
    private val decline = Button(context)

    @Test
    fun `busy shows the spinner and disables the controls`() {
        progress.setBusy(true, agree, decline, idleVisibility = View.INVISIBLE)

        assertEquals(View.VISIBLE, progress.visibility)
        assertFalse(agree.isEnabled)
        assertFalse(decline.isEnabled)
    }

    @Test
    fun `idle can keep the spinner's space`() {
        progress.setBusy(true, agree, idleVisibility = View.INVISIBLE)
        progress.setBusy(false, agree, idleVisibility = View.INVISIBLE)

        assertEquals(View.INVISIBLE, progress.visibility)
        assertTrue(agree.isEnabled)
    }

    @Test
    fun `idle can drop the spinner's space`() {
        progress.setBusy(false, agree, idleVisibility = View.GONE)

        assertEquals(View.GONE, progress.visibility)
        assertTrue(agree.isEnabled)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `idle cannot be visible`() {
        progress.setBusy(false, idleVisibility = View.VISIBLE)
    }
}
