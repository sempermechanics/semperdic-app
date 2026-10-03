package com.sempermechanics.semper.settings

import android.view.View
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog

/**
 * Deleting an account is gated on proving the identity, and that proof now
 * happens on the sign-in screen rather than in a password dialog of its own.
 */
@RunWith(RobolectricTestRunner::class)
class DeleteAccountReauthTest {

    /**
     * Every Settings a test opens, destroyed after it: a live one left behind
     * would still observe the account-deletion and sign-out runs of later tests.
     */
    private val built = mutableListOf<ActivityController<SettingsActivity>>()

    @After
    fun destroySettings() = built.forEach { runCatching { it.pause().stop().destroy() } }

    private fun settings(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().also { built += it }.get()

    @Test
    fun `delete asks for confirmation before anything else happens`() {
        val activity = settings()

        activity.findViewById<View>(R.id.btnDeleteAccount).performClick()

        assertNotNull("expected the are-you-sure dialog", ShadowDialog.getLatestDialog())
        assertNull("nothing should be launched yet", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `confirming hands off to the sign-in screen instead of a password box`() {
        val activity = settings()

        activity.findViewById<View>(R.id.btnDeleteAccount).performClick()
        val confirm = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        confirm.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(activity.mainLooper).idle()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("expected the re-auth screen to be launched", started)
        assertEquals(
            "com.sempermechanics.semper.ui.auth.AuthActivity",
            started.component?.className,
        )
        assertTrue(
            "the screen must be asked for re-auth, not a fresh sign-in",
            started.getBooleanExtra("reauth", false),
        )
    }
}
