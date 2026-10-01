package com.indicvision.semper.ui.common

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's cloud check and Settings' analysis list are refreshed from many
 * places at once. Each call used to launch its own coroutine, so loads
 * overlapped and an older one could land last. [ConflatedRefresh] runs them
 * one at a time and folds a burst into a single follow-up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConflatedRefreshTest {

    @Test
    fun `a burst while one runs costs exactly one more run, after it`() = runTest {
        val started = mutableListOf<Int>()
        val finished = mutableListOf<Int>()
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        var calls = 0
        val refresh = ConflatedRefresh<Int>(this, merge = { _, next -> next }) { arg ->
            val n = ++calls
            started += arg
            gates.getOrPut(n) { CompletableDeferred() }.await()
            finished += arg
        }

        refresh.request(1)
        advanceUntilIdle()
        refresh.request(2)
        refresh.request(3)
        refresh.request(4)
        assertTrue(refresh.hasPending)
        assertEquals("nothing overlaps the running one", listOf(1), started)

        gates.getOrPut(1) { CompletableDeferred() }.complete(Unit)
        advanceUntilIdle()
        assertEquals("the burst ran once, with the latest request", listOf(1, 4), started)
        assertFalse(refresh.hasPending)

        gates.getValue(2).complete(Unit)
        advanceUntilIdle()
        assertEquals("results land in request order", listOf(1, 4), finished)
    }

    @Test
    fun `pending requests merge, so a deep check asked for mid-run is not lost`() = runTest {
        val ran = mutableListOf<Boolean>()
        val gate = CompletableDeferred<Unit>()
        val refresh = ConflatedRefresh<Boolean>(this, merge = { a, b -> a || b }) { deep ->
            ran += deep
            if (ran.size == 1) gate.await()
        }

        refresh.request(false)
        advanceUntilIdle()
        refresh.request(true) // pull-to-refresh
        refresh.request(false) // a finished upload right after

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(false, true), ran)
    }

    @Test
    fun `a run that throws takes the queued request with it`() = runTest {
        val ran = mutableListOf<Int>()
        val gate = CompletableDeferred<Unit>()
        // Its own job, so the throw does not fail the test's scope.
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        val refresh = ConflatedRefresh<Int>(scope, merge = { _, next -> next }) { arg ->
            ran += arg
            if (arg == 1) {
                gate.await()
                error("reconcile blew up")
            }
        }

        refresh.request(1)
        advanceUntilIdle()
        refresh.request(2)
        assertTrue(refresh.hasPending)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse("the queue went with the failed run", refresh.hasPending)
        assertEquals(listOf(1), ran)

        refresh.request(3)
        advanceUntilIdle()
        assertEquals("a later request starts afresh", listOf(1, 3), ran)
    }

    @Test
    fun `a request after the last run ends starts a new one`() = runTest {
        var runs = 0
        val refresh = ConflatedRefresh<Unit>(this, merge = { _, _ -> }) { runs++ }

        refresh.request(Unit)
        advanceUntilIdle()
        refresh.request(Unit)
        advanceUntilIdle()

        assertEquals(2, runs)
    }
}
