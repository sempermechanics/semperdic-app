package com.indicvision.semper.ui.common.auth

import android.app.Activity
import android.app.Application
import android.content.Intent
import com.indicvision.semper.data.account.DevAuth
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.ui.auth.AuthActivity
import com.indicvision.semper.ui.auth.SplashActivity
import com.indicvision.semper.ui.auth.StatusRecheck
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

/**
 * Every way out to sign-in goes through [AuthRoute]: sign-out, account
 * deletion, a declined Terms gate, a lost approval and the background status
 * check. Pending, Terms and the status check used to build their own intent,
 * which skipped the emulator bypass and sent a debug build to a sign-in
 * screen it could never finish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AuthRouteTest {

    private val context by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    private val clearTask = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

    @Test
    fun `a real build goes to sign-in with the reason, clearing the task`() {
        val intent = AuthRoute.signInIntent(context, "Session expired.", devBypass = false)

        assertEquals(AuthActivity::class.java.name, intent.component?.className)
        assertEquals("Session expired.", intent.getStringExtra(DicKeys.ROUTING_ERROR))
        assertEquals(clearTask, intent.flags and clearTask)
    }

    @Test
    fun `no reason, no routing error`() {
        val intent = AuthRoute.signInIntent(context, devBypass = false)
        assertFalse(intent.hasExtra(DicKeys.ROUTING_ERROR))
    }

    @Test
    fun `the emulator bypass goes back through the splash, which re-seeds the dev session`() {
        val intent = AuthRoute.signInIntent(context, "Logged out.", devBypass = true)

        assertEquals(SplashActivity::class.java.name, intent.component?.className)
        assertNull("the splash has nowhere to show it", intent.getStringExtra(DicKeys.ROUTING_ERROR))
        assertEquals(clearTask, intent.flags and clearTask)
    }

    @Test
    fun `the background status check routes the same way`() {
        val recheck = StatusRecheck.Reroute.SignIn("Device not authorised.").intent(context)
        val route = AuthRoute.signInIntent(context, "Device not authorised.")

        assertEquals(route.component, recheck.component)
        assertEquals(route.getStringExtra(DicKeys.ROUTING_ERROR), recheck.getStringExtra(DicKeys.ROUTING_ERROR))
        assertEquals(route.flags, recheck.flags)
        assertEquals(
            "unit tests do not run on an emulator, so this is the real sign-in screen",
            if (DevAuth.active) SplashActivity::class.java.name else AuthActivity::class.java.name,
            recheck.component?.className,
        )
    }

    @Test
    fun `toSignIn starts the route and finishes the screen it left`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        AuthRoute.toSignIn(activity, "Logged out.")

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(AuthRoute.signInIntent(activity, "Logged out.").component, started.component)
        assertTrue(activity.isFinishing)
    }
}
