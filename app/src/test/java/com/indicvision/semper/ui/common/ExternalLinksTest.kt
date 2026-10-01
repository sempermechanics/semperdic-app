package com.indicvision.semper.ui.common

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.widget.Toast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** A browser link: opened when something can, else the URL in a long toast. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExternalLinksTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val url = "https://sempermechanics.com/faq#jpeg"

    @Test
    fun `a browser gets a VIEW intent for the url`() {
        assertTrue(ExternalLinks.open(activity, url))

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(url, started.dataString)
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun `with no browser the url is toasted, long`() {
        shadowOf(activity.application).checkActivities(true)

        assertFalse(ExternalLinks.open(activity, url))

        assertEquals(url, ShadowToast.getTextOfLatestToast())
        assertEquals(Toast.LENGTH_LONG, ShadowToast.getLatestToast().duration)
    }
}
