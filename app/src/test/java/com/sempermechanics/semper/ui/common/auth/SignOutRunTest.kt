package com.sempermechanics.semper.ui.common.auth

import android.app.Application
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.auth.TermsActivity
import com.sempermechanics.semper.ui.settings.SettingsActivity
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
import org.robolectric.annotation.Config

/**
 * The sign-out run behind Settings, the Terms gate and Pending: it always
 * finishes, one runs at a time, and its outcome goes to the screen that asked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SignOutRunTest {

    private val gate = CompletableDeferred<Unit>()

    @Before
    fun setUp() = SignOutRun.resetForTest()

    /** Screens built by a test; closed after it, so none keeps observing the shared run. */
    private val screens = mutableListOf<ActivityController<AppCompatActivity>>()

    @After
    fun tearDown() {
        gate.complete(Unit)
        idle()
        screens.forEach { runCatching { it.pause().stop().destroy() } }
        SignOutRun.resetForTest()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `a sign-out that throws still finishes, so the user still leaves`() {
        assertTrue(SignOutRun.start(TermsActivity::class.java) { error("seat release failed") })
        idle()

        assertEquals(SignOutRun.State.Done(TermsActivity::class.java), SignOutRun.state.value)
        assertTrue(SignOutRun.consume(TermsActivity::class.java))
        assertEquals(SignOutRun.State.Idle, SignOutRun.state.value)
    }

    @Test
    fun `only one runs at a time`() {
        assertTrue(SignOutRun.start(TermsActivity::class.java) { gate.await() })
        assertFalse(SignOutRun.start(SettingsActivity::class.java) {})
        assertEquals(SignOutRun.State.Running, SignOutRun.state.value)
    }

    @Test
    fun `the outcome goes to the screen that asked`() {
        SignOutRun.start(TermsActivity::class.java) {}
        idle()

        assertFalse("another screen leaves it", SignOutRun.consume(SettingsActivity::class.java))
        assertTrue(SignOutRun.consume(TermsActivity::class.java))
    }

    private fun screen(): ActivityController<AppCompatActivity> {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        return built.setup().also { screens += it }
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `a sign-out whose screen was closed first still reaches sign-in`() {
        val controller = screen()
        val activity = controller.get()
        var screenRouted = false
        SignOutRun.observe(activity) { screenRouted = true }
        SignOutRun.start(activity.javaClass) { gate.await() }

        // Backed out of while the seat release still waits on the network.
        controller.pause().stop().destroy()
        gate.complete(Unit)
        idle()

        assertFalse(screenRouted)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(AuthRoute.signInIntent(app).component, started?.component)
        assertEquals("held until a screen claims it", SignOutRun.State.Unclaimed, SignOutRun.state.value)
    }

    @Test
    fun `a sign-out that finishes while its screen is stopped routes when the screen closes`() {
        val controller = screen()
        val activity = controller.get()
        var screenRouted = false
        SignOutRun.observe(activity) { screenRouted = true }
        SignOutRun.start(activity.javaClass) { gate.await() }

        controller.pause().stop()
        gate.complete(Unit)
        idle()
        val unread = SignOutRun.State.Done(activity.javaClass)
        assertEquals("a stopped screen does not read it", unread, SignOutRun.state.value)
        assertNull(shadowOf(app).nextStartedActivity)

        controller.destroy()
        idle()

        assertFalse(screenRouted)
        assertEquals(AuthRoute.signInIntent(app).component, shadowOf(app).nextStartedActivity?.component)
        assertEquals(SignOutRun.State.Unclaimed, SignOutRun.state.value)
    }

    @Test
    fun `an outcome the app routed is claimed once, by the next screen that starts`() {
        val first = screen()
        SignOutRun.observe(first.get()) {}
        SignOutRun.start(first.get().javaClass) { gate.await() }
        first.pause().stop().destroy()
        gate.complete(Unit)
        idle()
        assertEquals(SignOutRun.State.Unclaimed, SignOutRun.state.value)

        // The background start may have been refused; the next observing screen routes.
        var routed = 0
        SignOutRun.observe(screen().get()) { routed++ }
        idle()

        assertEquals(1, routed)
        assertEquals(SignOutRun.State.Idle, SignOutRun.state.value)
        assertFalse("claimed once", SignOutRun.claimUnclaimed())
    }

    @Test
    fun `an open screen routes itself, and the app does not route again`() {
        val activity = screen().get()
        var screenRouted = false
        SignOutRun.observe(activity) { screenRouted = true }
        SignOutRun.start(activity.javaClass) {}
        idle()

        assertTrue(screenRouted)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `a rotation is not a close`() {
        val controller = screen()
        var routed = 0
        SignOutRun.observe(controller.get()) { routed++ }
        SignOutRun.start(controller.get().javaClass) { gate.await() }

        // What a recreate does: the old screen goes, the new one observes in the same step.
        controller.pause().stop().destroy()
        SignOutRun.observe(screen().get()) { routed++ }
        gate.complete(Unit)
        idle()

        assertEquals(1, routed)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `an outcome nobody read does not block the next sign-out`() {
        SignOutRun.start(SettingsActivity::class.java) {}
        idle()

        assertTrue(SignOutRun.start(SettingsActivity::class.java) {})
    }
}
