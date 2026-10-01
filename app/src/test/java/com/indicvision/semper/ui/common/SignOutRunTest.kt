package com.indicvision.semper.ui.common

import android.app.Application
import android.os.Looper
import com.indicvision.semper.ui.auth.TermsActivity
import com.indicvision.semper.ui.settings.SettingsActivity
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

    @After
    fun tearDown() {
        gate.complete(Unit)
        idle()
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

    @Test
    fun `an outcome nobody read does not block the next sign-out`() {
        SignOutRun.start(SettingsActivity::class.java) {}
        idle()

        assertTrue(SignOutRun.start(SettingsActivity::class.java) {})
    }
}
