package com.indicvision.semper.auth

import android.content.Context
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import com.indicvision.semper.data.account.LegalTerms
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.ui.auth.AccessRouter
import com.indicvision.semper.ui.auth.AuthActivity
import com.indicvision.semper.ui.auth.PendingApprovalActivity
import com.indicvision.semper.ui.auth.TermsActivity
import com.indicvision.semper.ui.common.SignOutRun
import com.indicvision.semper.ui.home.HomeActivity
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The clickwrap gate. What matters legally: nobody reaches Home or Pending
 * under terms they have not accepted, acceptance is an affirmative act (the
 * box starts unticked and the button dead), the improvement consent is a
 * separate choice that never unlocks the button, and declining ends the session.
 */
@RunWith(RobolectricTestRunner::class)
class TermsGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Every gate a test opens; destroyed after it, so none reads the next test's sign-out. */
    private val built = mutableListOf<ActivityController<TermsActivity>>()

    @Before
    fun freshDevice() {
        TokenStore.clear(context)
        SignOutRun.resetForTest()
    }

    @After
    fun tearDown() {
        built.forEach { runCatching { it.pause().stop().destroy() } }
        SignOutRun.resetForTest()
    }

    @Test
    fun `a fresh device needs acceptance and is routed through the gate`() {
        assertTrue(LegalTerms.needsAcceptance(context))
        assertEquals(LegalTerms.TERMS_VERSION, LegalTerms.requiredVersion(context))

        val intent = AccessRouter.intentFor(context, HomeActivity::class.java)
        assertEquals(TermsActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `pending approval is gated too, sign-in is not`() {
        assertTrue(AccessRouter.isGated(PendingApprovalActivity::class.java))
        assertTrue(AccessRouter.isGated(HomeActivity::class.java))
        assertFalse(AccessRouter.isGated(AuthActivity::class.java))

        val intent = AccessRouter.intentFor(context, AuthActivity::class.java)
        assertEquals(AuthActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `an accepted current version goes straight through`() {
        TokenStore.setTermsAccepted(context, LegalTerms.TERMS_VERSION, synced = true)

        assertFalse(LegalTerms.needsAcceptance(context))
        val intent = AccessRouter.intentFor(context, HomeActivity::class.java)
        assertEquals(HomeActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `a server-side version bump re-gates an already accepted user`() {
        TokenStore.setTermsAccepted(context, LegalTerms.TERMS_VERSION, synced = true)
        TokenStore.setTermsRequiredVersion(context, "2099-01-01")

        assertEquals("2099-01-01", LegalTerms.requiredVersion(context))
        assertTrue(LegalTerms.needsAcceptance(context))
        val intent = AccessRouter.intentFor(context, HomeActivity::class.java)
        assertEquals(TermsActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `the required box starts unticked and the button is dead until it is ticked`() {
        val activity = launchGate()
        val agree = activity.findViewById<CheckBox>(R.id.cbAgreeTerms)
        val improve = activity.findViewById<CheckBox>(R.id.cbImprovementConsent)
        val button = activity.findViewById<Button>(R.id.btnAgree)

        assertFalse(agree.isChecked)
        assertTrue("improvement consent is pre-ticked by product decision", improve.isChecked)
        assertFalse(button.isEnabled)

        agree.isChecked = true
        assertTrue(button.isEnabled)
        agree.isChecked = false
        assertFalse(button.isEnabled)
    }

    @Test
    fun `the improvement box never unlocks the button either way`() {
        val activity = launchGate()
        val improve = activity.findViewById<CheckBox>(R.id.cbImprovementConsent)
        improve.isChecked = false
        assertFalse(activity.findViewById<Button>(R.id.btnAgree).isEnabled)
        improve.isChecked = true
        assertFalse(activity.findViewById<Button>(R.id.btnAgree).isEnabled)
    }

    @Test
    fun `declining signs out and returns to sign-in`() {
        val activity = launchGate()
        var signedOut = false
        activity.signOut = { signedOut = true }

        activity.findViewById<Button>(R.id.btnDecline).performClick()

        assertTrue(signedOut)
        assertTrue(activity.isFinishing)
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(AuthActivity::class.java.name, next.component?.className)
        assertNull("no acceptance may be recorded on decline", TokenStore.termsAcceptedVersion(context))
    }

    @Test
    fun `back is a decline, not a way around the gate`() {
        val activity = launchGate()
        var signedOut = false
        activity.signOut = { signedOut = true }

        activity.onBackPressedDispatcher.onBackPressed()

        assertTrue(signedOut)
        assertTrue(activity.isFinishing)
        assertEquals(
            AuthActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className,
        )
    }

    @Test
    fun `a rotation mid-decline still finishes the sign-out, and the new gate routes`() {
        // The seat release is a network call; a rotation during it used to
        // cancel the sign-out (session kept) while the old gate routed anyway.
        val controller = gateController()
        val release = CompletableDeferred<Unit>()
        var signedOut = false
        controller.get().signOut = {
            release.await()
            signedOut = true
        }

        controller.get().findViewById<Button>(R.id.btnDecline).performClick()
        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("nothing routes before the sign-out ends", shadowOf(controller.get()).nextStartedActivity)

        release.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the sign-out ran to the end", signedOut)
        assertEquals(
            AuthActivity::class.java.name,
            shadowOf(controller.get()).nextStartedActivity.component?.className,
        )
        assertTrue(controller.get().isFinishing)
    }

    private fun gateController(): ActivityController<TermsActivity> {
        val intent = TermsActivity.intent(context, HomeActivity::class.java)
        return Robolectric.buildActivity(TermsActivity::class.java, intent).setup().also { built += it }
    }

    private fun launchGate(): TermsActivity = gateController().get()
}
