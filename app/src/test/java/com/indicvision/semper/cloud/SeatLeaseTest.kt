package com.indicvision.semper.cloud

import com.indicvision.semper.data.account.SeatLease
import com.indicvision.semper.data.net.AppConfigDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Floating-seat release and renew gates. The network is faked so the order of
 * decisions — whether to call, and whether a failure is swallowed — is what
 * these tests pin.
 */
class SeatLeaseTest {

    private val steps = mutableListOf<String>()

    private fun config(): AppConfigDto = AppConfigDto(mode = "demo")

    @Test
    fun `sign-out release runs only while a floating seat is held`() = runBlocking {
        val released = SeatLease.release(
            shouldRelease = { true },
            token = { "tok" },
            apiEnabled = { true },
            release = {
                steps.add("release")
                config()
            },
            applyConfig = { steps.add("apply") },
        )
        assertTrue(released)
        assertEquals(listOf("release", "apply"), steps)
    }

    @Test
    fun `sign-out without a held seat never hits the API`() = runBlocking {
        val released = SeatLease.release(
            shouldRelease = { false },
            token = { "tok" },
            apiEnabled = { true },
            release = {
                steps.add("release")
                config()
            },
            applyConfig = { steps.add("apply") },
        )
        assertFalse(released)
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `a failed release still finishes sign-out rather than throwing`() = runBlocking {
        val released = SeatLease.release(
            shouldRelease = { true },
            token = { "tok" },
            apiEnabled = { true },
            release = { error("network") },
            applyConfig = { steps.add("apply") },
        )
        assertFalse(released)
        assertFalse(steps.contains("apply"))
    }

    @Test
    fun `heartbeat renews with the checkout route, not a separate path`() = runBlocking {
        val ok = SeatLease.heartbeat(
            shouldHeartbeat = { true },
            token = { "tok" },
            apiEnabled = { true },
            checkout = {
                steps.add("checkout")
                config()
            },
            applyConfig = { steps.add("apply") },
        )
        assertTrue(ok)
        assertEquals(listOf("checkout", "apply"), steps)
    }

    @Test
    fun `heartbeat is skipped when the seat is already gone`() = runBlocking {
        val ok = SeatLease.heartbeat(
            shouldHeartbeat = { false },
            token = { "tok" },
            apiEnabled = { true },
            checkout = {
                steps.add("checkout")
                config()
            },
            applyConfig = { steps.add("apply") },
        )
        assertFalse(ok)
        assertTrue(steps.isEmpty())
    }
}
