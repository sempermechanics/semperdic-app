package com.indicvision.semper.ui.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** One job at a time: the latest launch wins, the earlier one is cancelled. */
@OptIn(ExperimentalCoroutinesApi::class)
class SerialJobTest {

    @Test
    fun `a new launch cancels the one before it`() = runTest {
        val serial = SerialJob()
        val landed = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()

        val first = serial.launch(this) {
            gate.await()
            landed += 1
        }
        val second = serial.launch(this) {
            gate.await()
            landed += 2
        }
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(first.isCancelled)
        assertFalse(second.isCancelled)
        assertEquals(listOf(2), landed)
        assertFalse(serial.isActive)
    }

    @Test
    fun `cancel stops the running job and holds nothing`() = runTest {
        val serial = SerialJob()
        val job = serial.launch(this) { awaitCancellation() }
        advanceUntilIdle()
        assertTrue(serial.isActive)

        serial.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertFalse(serial.isActive)
        serial.cancel() // nothing held: a no-op
    }

    @Test
    fun `the context is applied to the job`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val serial = SerialJob()
        var ran = false
        serial.launch(this, dispatcher) { ran = true }
        assertFalse(ran)
        advanceUntilIdle()
        assertTrue(ran)
    }

    @Test
    fun `a body that runs at once already sees itself as the held job`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val serial = SerialJob()
        var activeInside = false
        var self: Job? = null
        val job = serial.launch(scope) {
            activeInside = serial.isActive
            self = coroutineContext[Job]
        }
        assertTrue(activeInside)
        assertSame(job, self)
    }
}
