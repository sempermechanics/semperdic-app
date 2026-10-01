package com.indicvision.semper.auth

import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.google.firebase.auth.TotpMultiFactorGenerator
import com.indicvision.semper.R
import com.indicvision.semper.data.account.TotpMfa
import com.indicvision.semper.ui.auth.AuthActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Authenticator challenge after first-factor sign-in. Enrolment stays on the
 * websites; the phone only has to accept a code when Firebase demands one.
 */
@RunWith(RobolectricTestRunner::class)
class TotpChallengeTest {

    @Test
    fun `TOTP enrolment id is preferred and phone factors are ignored`() {
        assertEquals(
            "enroll-totp",
            TotpMfa.enrollmentIdFromPairs(
                listOf(
                    "phone" to "enroll-sms",
                    TotpMultiFactorGenerator.FACTOR_ID to "enroll-totp",
                ),
            ),
        )
        assertNull(
            TotpMfa.enrollmentIdFromPairs(listOf("phone" to "enroll-sms")),
        )
    }

    @Test
    fun `a required second factor shows the authenticator prompt`() {
        val activity = Robolectric.buildActivity(AuthActivity::class.java).setup().get()
        activity.enterTotpChallengeUi()

        assertEquals(View.GONE, activity.findViewById<View>(R.id.cardCredentials).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.cardTotp).visibility)
        assertEquals(
            activity.getString(R.string.auth_totp_subtitle),
            activity.findViewById<TextView>(R.id.tvSubtitle).text.toString(),
        )
        assertEquals(
            activity.getString(R.string.auth_totp_verify),
            activity.findViewById<Button>(R.id.btnMainAction).text.toString(),
        )
        assertEquals(View.GONE, activity.findViewById<View>(R.id.btnGoogleSignIn).visibility)
    }

    @Test
    fun `an empty authenticator code does not leave the sign-in screen`() {
        val activity = Robolectric.buildActivity(AuthActivity::class.java).setup().get()
        activity.enterTotpChallengeUi()
        activity.findViewById<EditText>(R.id.etTotp).setText("")
        activity.findViewById<Button>(R.id.btnMainAction).performClick()

        assertTrue("wrong or empty code must not finish the activity", !activity.isFinishing)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.cardTotp).visibility)
    }
}
