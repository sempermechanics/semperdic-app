package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.net.SingleFlight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 */
class ConfigSingleFlightTest {

    private val fetched = AtomicInteger()

    private suspend fun fetchSlowly(): Int {
        val n = fetched.incrementAndGet()
        delay(NETWORK_MS)
        return n
    }

    private fun callersAtOnce(k: Int, call: suspend () -> Int): List<Int> = runBlocking {
        (1..k).map { async(Dispatchers.Default) { call() } }.awaitAll()
    }

    @Test
    fun `callers that overlap share one fetch at every size`() {
        for (k in SIZES) {
            fetched.set(0)
            val flight = SingleFlight<Int>()
            val answers = callersAtOnce(k) { flight.run { fetchSlowly() } }
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
            callersAtOnce(k) { fetchSlowly() }
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
        val callers = (1..3).map { async(Dispatchers.Default) { runCatching { flight.run(failing) } } }
        withTimeout(TIMEOUT_MS) { while (fetched.get() == 0) delay(1) }
        delay(SETTLE_MS)
        release.complete(Unit)
        val results = callers.awaitAll()
        assertTrue(results.all { it.exceptionOrNull() is IOException })
        assertEquals(1, fetched.get())
        assertEquals(2, flight.run { fetchSlowly() })
    }

    @Test
    fun `a caller that gives up does not cancel the others`() = runBlocking {
        val flight = SingleFlight<Int>()
        val first = async(Dispatchers.Default) { flight.run { fetchSlowly() } }
        withTimeout(TIMEOUT_MS) { while (fetched.get() == 0) delay(1) }
        val second = async(Dispatchers.Default) { flight.run { fetchSlowly() } }
        first.cancel()
        assertEquals(1, second.await())
        assertEquals(1, fetched.get())
    }

    private companion object {
        const val NETWORK_MS = 50L
        const val SETTLE_MS = 20L
        const val TIMEOUT_MS = 2_000L
        val SIZES = listOf(2, 8, 32)
    }
}
