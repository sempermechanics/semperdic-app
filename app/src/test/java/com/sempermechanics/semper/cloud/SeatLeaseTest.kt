package com.sempermechanics.semper.cloud

import android.util.Log
import com.sempermechanics.semper.data.account.SeatLease
import com.sempermechanics.semper.data.net.AppConfigDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

/**
 * Floating-seat release and renew gates ([SeatLease.seatCall], which both
 * share). The network is faked so the order of decisions — whether to call,
 * and whether a failure is swallowed — is what these tests pin.
 */
class SeatLeaseTest {

    private val api = FakeCloudApi()
    private val tokens = FakeTokens()
    private val steps = mutableListOf<String>()

    /** The priority of every line logged, in order. */
    private val logged = mutableListOf<Int>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logged += priority
        }
    }.also { Timber.plant(it) }

    @After
    fun uproot() = Timber.uproot(tree)

    private fun config(): AppConfigDto = AppConfigDto(mode = "demo")

    /** The release as `releaseBestEffort` makes it, with the config apply recorded in [steps]. */
    private suspend fun release(holdsSeat: Boolean = true): Boolean =
        SeatLease.seatCall(holdsSeat, api, tokens, "release") {
            releaseLease(it)
            steps.add("apply")
        }

    @Test
    fun `sign-out release runs only while a floating seat is held`() = runBlocking {
        api.onReleaseLease = {
            steps.add("release")
            config()
        }

        assertTrue(release())
        assertEquals(listOf("release", "apply"), steps)
        assertEquals(listOf("releaseLease"), api.calls)
    }

    @Test
    fun `sign-out without a held seat never hits the API`() = runBlocking {
        assertFalse(release(holdsSeat = false))
        assertTrue(steps.isEmpty())
        assertTrue(api.calls.isEmpty())
        assertEquals("no token is asked for", 0, tokens.asked)
    }

    @Test
    fun `a failed release still finishes sign-out rather than throwing`() = runBlocking {
        api.onReleaseLease = { error("network") }

        assertFalse(release())
        assertFalse(steps.contains("apply"))
        assertEquals("a bug is an error (a Crashlytics non-fatal)", listOf(Log.ERROR), logged)
    }

    @Test
    fun `an Error from the release is swallowed too, so sign-out still clears the session`() = runBlocking {
        // suspendRunCatching caught Throwable; authed alone catches Exception.
        api.onReleaseLease = { throw LinkageError("bad class") }

        assertFalse(release())
        assertFalse(steps.contains("apply"))
    }

    @Test
    fun `a Firebase-style cancelled task during the release is a failure, not a cancelled sign-out`() = runBlocking {
        // suspendRunCatching rethrew this, which aborted signOut before the
        // session was cleared; the caller here is still active.
        api.onReleaseLease = { throw CancellationException("Task was cancelled") }

        assertFalse(release())
        assertFalse(steps.contains("apply"))
        assertEquals("a cancelled task is not a bug report", listOf(Log.WARN), logged)
    }

    @Test
    fun `no backend or no token means no call`() = runBlocking {
        api.enabled = false
        assertFalse(release())
        api.enabled = true
        tokens.token = null
        assertFalse(release())

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `heartbeat renews with the checkout route, not a separate path`() = runBlocking {
        api.onCheckoutLease = {
            steps.add("checkout")
            config()
        }

        val ok = SeatLease.seatCall(holdsSeat = true, api, tokens, "heartbeat") {
            checkoutLease(it)
            steps.add("apply")
        }

        assertTrue(ok)
        assertEquals(listOf("checkout", "apply"), steps)
        assertEquals(listOf("checkoutLease"), api.calls)
    }

    @Test
    fun `heartbeat is skipped when the seat is already gone`() = runBlocking {
        val ok = SeatLease.seatCall(holdsSeat = false, api, tokens, "heartbeat") {
            checkoutLease(it)
            steps.add("apply")
        }

        assertFalse(ok)
        assertTrue(steps.isEmpty())
        assertTrue(api.calls.isEmpty())
    }
}
