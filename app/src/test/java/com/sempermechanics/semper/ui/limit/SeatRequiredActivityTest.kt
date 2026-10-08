package com.sempermechanics.semper.ui.limit

import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.ensureTestFirebaseApp
import com.sempermechanics.semper.fixtures.idleUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast

/**
 * The floating-seat wait screen: one button that asks for a seat again, and a
 * way back to the app (which stays usable). A request that cannot reach the
 * server must leave the screen up and say why, not close it as if a seat came.
 */
@RunWith(RobolectricTestRunner::class)
class SeatRequiredActivityTest {

    @get:Rule
    val clean = CleanAppState()

    private lateinit var controller: ActivityController<SeatRequiredActivity>
    private val screen: SeatRequiredActivity get() = controller.get()

    @Before
    fun setUp() {
        ensureTestFirebaseApp(ApplicationProvider.getApplicationContext())
        controller = Robolectric.buildActivity(SeatRequiredActivity::class.java).setup()
    }

    @After
    fun tearDown() {
        runCatching { controller.pause().stop().destroy() }
    }

    @Test
    fun `the screen explains the wait`() {
        val body = screen.findViewById<TextView>(R.id.tvSeatBody).text.toString()
        assertEquals(screen.getString(R.string.seat_body), body)
        assertTrue(screen.findViewById<View>(R.id.btnTakeSeat).isEnabled)
    }

    @Test
    fun `asking for a seat signed out stays on the screen and says it is offline`() {
        screen.findViewById<View>(R.id.btnTakeSeat).performClick()
        idleUntil("the answer") { ShadowToast.getTextOfLatestToast() != null }

        assertEquals(screen.getString(R.string.seat_offline), ShadowToast.getTextOfLatestToast())
        assertFalse("no seat, so the screen stays", screen.isFinishing)
        assertTrue("the button is usable again", screen.findViewById<View>(R.id.btnTakeSeat).isEnabled)
    }

    @Test
    fun `Back to the app closes the screen`() {
        screen.findViewById<View>(R.id.tvSeatBack).performClick()
        shadowOf(screen.mainLooper).idle()
        assertTrue(screen.isFinishing)
    }
}
