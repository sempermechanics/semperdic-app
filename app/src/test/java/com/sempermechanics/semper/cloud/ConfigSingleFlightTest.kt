package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.net.SingleFlight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * On every app open the status check and the cloud reconcile each ask for
 * `/v1/config`, 20–100 ms apart, before either answer is back (production
 * access log, 2026-09-25). `SemperApi.getConfig` runs through a [SingleFlight] so
 * the second one waits for the first instead of sending its own
 * (docs/perf/request-volume.md, Pass 2).
 *
 * Every caller here starts [CoroutineStart.UNDISPATCHED]: it runs into
 * [SingleFlight.run], and so has joined (or started) the shared call, before the
 * next line of the test, and a shared fetch is held open until every caller
 * has started. The tests used to start callers on `Dispatchers.Default` and
 * assume each had joined within 20–50 ms; on a busy CI runner one could arrive
 * after the shared call had finished and start a second fetch.
 */
class ConfigSingleFlightTest {

    private val fetched = AtomicInteger()

    private suspend fun fetchSlowly(): Int {
        val n = fetched.incrementAndGet()
        delay(NETWORK_MS)
        return n
    }

    /** Held open until [callersAtOnce] has started every caller. */
    private var gate = CompletableDeferred<Unit>()

    private suspend fun fetchUntilAllStarted(): Int {
        val n = fetched.incrementAndGet()
        gate.await()
        return n
    }

    private fun callersAtOnce(k: Int, call: suspend () -> Int): List<Int> = runBlocking {
        gate = CompletableDeferred()
        val callers = (1..k).map { async(start = CoroutineStart.UNDISPATCHED) { call() } }
        gate.complete(Unit)
        callers.awaitAll()
    }

    @Test
    fun `callers that overlap share one fetch at every size`() {
        for (k in SIZES) {
            fetched.set(0)
            val flight = SingleFlight<Int>()
            val answers = callersAtOnce(k) { flight.run { fetchUntilAllStarted() } }
            println("K=$k fetches=${fetched.get()}")
            assertEquals("K=$k", 1, fetched.get())
            assertEquals("every caller gets the one answer", List(k) { 1 }, answers)
        }
    }

    @Test
    fun `without it every caller fetches`() {
        // The baseline this change removes: K overlapping callers, K fetches.
        for (k in SIZES) {
            fetched.set(0)
            callersAtOnce(k) { fetchUntilAllStarted() }
            assertEquals("K=$k", k, fetched.get())
        }
    }

    @Test
    fun `a call after the last one finished fetches again`() = runBlocking {
        val flight = SingleFlight<Int>()
        assertEquals(1, flight.run { fetchSlowly() })
        assertEquals(2, flight.run { fetchSlowly() })
    }

    @Test
    fun `a failure reaches every caller that joined, and the next call retries`() = runBlocking {
        val flight = SingleFlight<Int>()
        val release = CompletableDeferred<Unit>()
        val failing = suspend {
            fetched.incrementAndGet()
            release.await()
            throw IOException("offline")
        }
        val callers = (1..3).map {
            async(start = CoroutineStart.UNDISPATCHED) { runCatching { flight.run(block = failing) } }
        }
        release.complete(Unit)
        val results = callers.awaitAll()
        assertTrue(results.all { it.exceptionOrNull() is IOException })
        assertEquals(1, fetched.get())
        assertEquals(2, flight.run { fetchSlowly() })
    }

    @Test
    fun `a caller with another key does not join, and callers with its key join it`() = runBlocking {
        val flight = SingleFlight<String>()
        val release = CompletableDeferred<Unit>()
        val answer = { who: String ->
            suspend {
                fetched.incrementAndGet()
                release.await()
                who
            }
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { flight.run("token-a", answer("a")) }
        val other = async(start = CoroutineStart.UNDISPATCHED) { flight.run("token-b", answer("b")) }
        val again = async(start = CoroutineStart.UNDISPATCHED) { flight.run("token-b", answer("b2")) }
        release.complete(Unit)
        assertEquals(listOf("a", "b", "b"), listOf(first.await(), other.await(), again.await()))
        assertEquals(2, fetched.get())
    }

    @Test
    fun `a caller that gives up does not cancel the others`() = runBlocking {
        val flight = SingleFlight<Int>()
        val release = CompletableDeferred<Unit>()
        val gated = suspend { fetched.incrementAndGet().also { release.await() } }
        val first = async(start = CoroutineStart.UNDISPATCHED) { flight.run(block = gated) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { flight.run(block = gated) }
        first.cancel()
        release.complete(Unit)
        assertEquals(1, second.await())
        assertEquals(1, fetched.get())
    }

    private companion object {
        const val NETWORK_MS = 50L
        val SIZES = listOf(2, 8, 32)
    }
}
