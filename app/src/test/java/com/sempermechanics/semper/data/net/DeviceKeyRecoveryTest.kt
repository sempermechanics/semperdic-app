package com.sempermechanics.semper.data.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * When [DeviceKeyRecovery] registers this device's key again, and when it
 * says a refused call is worth sending once more. The clock is the test's.
 */
class DeviceKeyRecoveryTest {

    private var now = 10_000L
    private var registrations = 0
    private var failWith: Exception? = null

    private val recovery = DeviceKeyRecovery(
        reRegister = {
            registrations++
            failWith?.let { throw it }
        },
        nowMs = { now },
    )

    private fun recover(sentAt: Long = now) = runBlocking { recovery.recover("tok", sentAt) }

    @Test
    fun `the first refusal registers the key again`() {
        assertTrue(recover())
        assertEquals(1, registrations)
    }

    @Test
    fun `a call sent before a registration finished is re-sent without another`() {
        val sentAt = now
        now += 5
        assertTrue(recover())

        assertTrue(recover(sentAt))
        assertEquals(1, registrations)
    }

    @Test
    fun `parallel refusals share one registration`() = runBlocking {
        val sentAt = now
        val gate = CompletableDeferred<Unit>()
        var count = 0
        val shared = DeviceKeyRecovery(
            reRegister = {
                count++
                gate.await()
            },
            nowMs = { now },
        )
        val callers = (1..4).map { async { shared.recover("tok", sentAt) } }
        yield()
        now += 1
        gate.complete(Unit)

        assertEquals(listOf(true, true, true, true), callers.awaitAll())
        assertEquals(1, count)
    }

    @Test
    fun `a key lost again within the back-off is not taken back`() {
        assertTrue(recover())
        now += DeviceKeyRecovery.BACKOFF_MS - 1

        assertFalse("another app keeps registering; do not ping-pong", recover())
        assertEquals(1, registrations)

        now += 1
        assertTrue(recover())
        assertEquals(2, registrations)
    }

    @Test
    fun `a failed registration is not retried within the back-off`() {
        failWith = IOException("offline")
        val sentAt = now

        assertFalse(recover(sentAt))
        assertFalse("a parallel call learns it failed", recover(sentAt))
        now += 1
        assertFalse(recover())
        assertEquals(1, registrations)

        failWith = null
        now += DeviceKeyRecovery.BACKOFF_MS
        assertTrue(recover())
    }

    @Test
    fun `cancellation is rethrown and records no attempt`() {
        failWith = CancellationException("screen closed")

        assertThrows(CancellationException::class.java) { recover() }

        failWith = null
        assertTrue("the next refusal registers at once", recover())
        assertEquals(2, registrations)
    }

    @Test
    fun `the default clock moves forward`() {
        val real = DeviceKeyRecovery(reRegister = {})
        val first = real.now()
        assertTrue(real.now() >= first)
    }
}
