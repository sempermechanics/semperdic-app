package com.sempermechanics.semper.auth

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.auth.AuthActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The password-reset half of the sign-in screen ([com.sempermechanics.semper.ui.auth.AuthPasswordReset]),
 * on the parts that never reach Firebase: the address check before a reset
 * mail, and a reset link from a host that is not ours.
 */
@RunWith(RobolectricTestRunner::class)
class AuthPasswordResetTest {

    private fun pillText(activity: AuthActivity): String? =
        activity.findViewById<ViewGroup>(android.R.id.content)
            .findViewWithTag<View>("semper_crisp_toast")
            ?.findViewById<TextView>(R.id.tvToast)?.text?.toString()

    @Test
    fun `forgot password with no valid address says so and sends nothing`() {
        val activity = Robolectric.buildActivity(AuthActivity::class.java).setup().get()
        activity.findViewById<EditText>(R.id.etEmail).setText("not-an-address")

        activity.findViewById<View>(R.id.tvForgotPassword).performClick()

        assertEquals(activity.getString(R.string.error_email_invalid), pillText(activity))
        assertEquals("not busy", View.GONE, activity.findViewById<View>(R.id.progressBar).visibility)
    }

    @Test
    fun `a reset link from a host that is not ours leaves the sign-in form`() {
        val link = Uri.parse("https://evil.example.com/finishReset?mode=resetPassword&oobCode=abc")
        val intent = Intent(Intent.ACTION_VIEW, link)
            .setClass(org.robolectric.RuntimeEnvironment.getApplication(), AuthActivity::class.java)
        val activity = Robolectric.buildActivity(AuthActivity::class.java, intent).setup().get()
        shadowOf(activity.mainLooper).idle()

        assertEquals(
            activity.getString(R.string.auth_sign_in),
            activity.findViewById<Button>(R.id.btnMainAction).text.toString(),
        )
        assertEquals(View.GONE, activity.findViewById<View>(R.id.layoutConfirmPassword).visibility)
    }
}
