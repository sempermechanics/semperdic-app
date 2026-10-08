package com.sempermechanics.semper.ui.admin

import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.ensureTestFirebaseApp
import com.sempermechanics.semper.fixtures.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/**
 * The admin approvals screen lists pending users with the signed-in admin's
 * token. Without a session it must not sit on an empty list: it says the load
 * failed and closes.
 */
@RunWith(RobolectricTestRunner::class)
class AdminActivityTest {

    @get:Rule
    val clean = CleanAppState()

    @Test
    fun `signed out, the screen reports the failure and closes`() {
        ensureTestFirebaseApp(ApplicationProvider.getApplicationContext())
        val controller = Robolectric.buildActivity(AdminActivity::class.java).setup()
        val admin = controller.get()
        try {
            idleUntil("the screen to close") { admin.isFinishing }
            assertEquals(admin.getString(R.string.error_generic), ShadowToast.getTextOfLatestToast())
        } finally {
            runCatching { controller.pause().stop().destroy() }
        }
    }
}
