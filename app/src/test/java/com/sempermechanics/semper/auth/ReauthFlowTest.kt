package com.sempermechanics.semper.auth

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.ui.auth.AuthActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The sign-in screen doubles as the identity check before an account is deleted.
 * What matters is that it cannot be used to sign in as someone else, and that it
 * answers the caller instead of routing onward.
 */
@RunWith(RobolectricTestRunner::class)
class ReauthFlowTest {

    private fun launchReauth(): AuthActivity {
        val intent = AuthActivity.reauthIntent(ApplicationProvider.getApplicationContext())
        return Robolectric.buildActivity(AuthActivity::class.java, intent).setup().get()
    }

    private fun launchSignIn(): AuthActivity =
        Robolectric.buildActivity(AuthActivity::class.java).setup().get()

    @Test
    fun `the account under test cannot be swapped for another`() {
        val activity = launchReauth()

        assertFalse(
            "the email field must be locked in re-auth",
            activity.findViewById<EditText>(R.id.etEmail).isEnabled,
        )
    }

    @Test
    fun `creating an account is not offered while re-authenticating`() {
        val activity = launchReauth()

        assertEquals(View.GONE, activity.findViewById<TextView>(R.id.tvToggleMode).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.layoutConfirmPassword).visibility)
    }

    @Test
    fun `the screen explains why it is asking, and what the button does`() {
        val activity = launchReauth()

        assertEquals(
            activity.getString(R.string.reauth_body),
            activity.findViewById<TextView>(R.id.tvSubtitle).text.toString(),
        )
        assertEquals(
            activity.getString(R.string.reauth_confirm),
            activity.findViewById<Button>(R.id.btnMainAction).text.toString(),
        )
    }

    @Test
    fun `backing out reports a cancel rather than a silent pass`() {
        val activity = launchReauth()

        activity.onBackPressedDispatcher.onBackPressed()

        val shadow = shadowOf(activity)
        assertTrue("activity should have finished", activity.isFinishing)
        // Anything other than RESULT_OK must leave the account alone; the default
        // RESULT_CANCELED is what the caller checks for.
        assertEquals(Activity.RESULT_CANCELED, shadow.resultCode)
    }

    @Test
    fun `an ordinary sign-in is untouched by the new mode`() {
        val activity = launchSignIn()

        assertTrue(activity.findViewById<EditText>(R.id.etEmail).isEnabled)
        assertEquals(View.VISIBLE, activity.findViewById<TextView>(R.id.tvToggleMode).visibility)
        assertEquals(
            activity.getString(R.string.auth_sign_in),
            activity.findViewById<Button>(R.id.btnMainAction).text.toString(),
        )
        assertEquals(
            activity.getString(R.string.secure_access_portal),
            activity.findViewById<TextView>(R.id.tvSubtitle).text.toString(),
        )
    }

    @Test
    fun `a fresh sign-up drops the screen back to sign in`() {
        val activity = launchSignIn()
        // Switch to Create account, as a new user would.
        activity.findViewById<TextView>(R.id.tvToggleMode).performClick()
        activity.findViewById<EditText>(R.id.etPassword).setText("hunter2")
        activity.findViewById<EditText>(R.id.etConfirmPassword).setText("hunter2")

        // The outcome Firebase produces once the verification mail is away. Driven
        // directly: the Activity builds its own AuthRepository, so there is no
        // seam to fake the network at.
        activity.onVerificationPending(AuthRepository.EmailVerificationRequired("new@example.com"))

        assertEquals(
            activity.getString(R.string.auth_sign_in),
            activity.findViewById<Button>(R.id.btnMainAction).text.toString(),
        )
        assertEquals(View.GONE, activity.findViewById<View>(R.id.layoutConfirmPassword).visibility)
        assertEquals("new@example.com", activity.findViewById<EditText>(R.id.etEmail).text.toString())
        assertTrue(
            "the password must not survive into the sign-in form",
            activity.findViewById<EditText>(R.id.etPassword).text.isEmpty(),
        )
        assertFalse("the screen should stay open", activity.isFinishing)
    }
}
