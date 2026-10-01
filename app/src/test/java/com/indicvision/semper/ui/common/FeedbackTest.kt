package com.indicvision.semper.ui.common

import android.app.Activity
import android.app.Application
import android.widget.Toast
import com.indicvision.semper.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The one toast entry point: same text and the same two durations as `Toast.makeText`. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FeedbackTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `a string resource shows short by default`() {
        Feedback.toast(activity, R.string.save_failed)

        assertEquals(activity.getString(R.string.save_failed), ShadowToast.getTextOfLatestToast())
        assertEquals(Toast.LENGTH_SHORT, ShadowToast.getLatestToast().duration)
    }

    @Test
    fun `text shows long when asked`() {
        Feedback.toast(activity, "https://example.com/faq", long = true)

        assertEquals("https://example.com/faq", ShadowToast.getTextOfLatestToast())
        assertEquals(Toast.LENGTH_LONG, ShadowToast.getLatestToast().duration)
    }

    /** The context the toast was built on; Toast keeps it in a private field. */
    private fun Toast.builtOn(): Any? =
        Toast::class.java.getDeclaredField("mContext").apply { isAccessible = true }.get(this)

    @Test
    fun `the toast is built on the application context, not the Activity`() {
        Feedback.toast(activity, R.string.save_failed)
        val fromRes = ShadowToast.getLatestToast().builtOn()
        Feedback.toast(activity, "text", long = true)
        val fromText = ShadowToast.getLatestToast().builtOn()

        assertSame(activity.applicationContext, fromRes)
        assertSame(activity.applicationContext, fromText)
        assertNotSame(activity, fromRes)
    }

    @Test
    fun `it shows from a finished Activity too`() {
        activity.finish()

        Feedback.toast(activity, R.string.seat_offline, long = true)

        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals(activity.getString(R.string.seat_offline), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `lengths map to the platform's`() {
        assertEquals(Toast.LENGTH_SHORT, Feedback.lengthOf(false))
        assertEquals(Toast.LENGTH_LONG, Feedback.lengthOf(true))
    }
}
