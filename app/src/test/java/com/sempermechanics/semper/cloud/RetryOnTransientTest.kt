package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.net.HttpStatus
import com.sempermechanics.semper.data.net.RetryOnTransient
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * [RetryOnTransient] against a fake backend.
 *
 * The distinction under test is not "retry on failure" but *which* failure:
 * 429 means the throttle rejected the call before the handler ran, so nothing
 * happened and a repeat is free. 503 carries no such promise, so it is retried
 * only where a duplicate is harmless. Getting that wrong duplicates a
 * server-side effect nobody would see.
 *
 * Robolectric because the kill-switch case reads the body's code through
 * [com.sempermechanics.semper.data.net.ApiErrors], which parses it with
 * `org.json`, a stub on the plain JVM classpath.
 */
@RunWith(RobolectricTestRunner::class)
class RetryOnTransientTest {

    @get:Rule
    val serverRule = MockWebServerRule()
    private val server get() = serverRule.server
    private lateinit var client: OkHttpClient

    private val json = "application/json; charset=utf-8".toMediaType()

    @Before
    fun setUp() {
        client = OkHttpClient.Builder().addInterceptor(RetryOnTransient()).build()
    }

    private fun get(path: String = "/v1/config") =
        client.newCall(Request.Builder().url(server.url(path)).get().build()).execute()

    private fun post(path: String) = client.newCall(
        Request.Builder().url(server.url(path)).post("{}".toRequestBody(json)).build(),
    ).execute()

    private fun throttled(retryAfter: String? = null) = MockResponse(
        code = HttpStatus.TOO_MANY_REQUESTS,
        headers = retryAfter?.let { Headers.headersOf("Retry-After", it) } ?: Headers.headersOf(),
        body = """{"detail":"rate_limited"}""",
    )

    private fun ok() = MockResponse(code = HttpStatus.OK, body = """{"ok":true}""")

    private fun unavailable() = MockResponse(code = HttpStatus.SERVICE_UNAVAILABLE, body = "")

    // ------------------------------------------------------------------- 429

    @Test
    fun `a throttled call is retried and the caller sees the success`() {
        server.enqueue(throttled())
        server.enqueue(ok())

        get().use { assertEquals(HttpStatus.OK, it.code) }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `retries are capped so a throttled backend is not hammered`() {
        repeat(5) { server.enqueue(throttled()) }

        get().use { assertEquals(HttpStatus.TOO_MANY_REQUESTS, it.code) }

        // Three attempts in total, not five: the cap is the point.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a throttled POST is retried too — nothing ran on the first attempt`() {
        server.enqueue(throttled())
        server.enqueue(ok())

        post("/v1/sessions").use { assertEquals(HttpStatus.OK, it.code) }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `Retry-After is honoured over the default backoff`() {
        server.enqueue(throttled(retryAfter = "1"))
        server.enqueue(ok())

        val started = System.nanoTime()
        get().use { assertEquals(HttpStatus.OK, it.code) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        // The default first step is 500ms, so anything at or past a second can
        // only have come from the header.
        assertTrue("waited ${elapsedMs}ms", elapsedMs >= 950)
    }

    @Test
    fun `cancelling the call ends a long backoff wait at once`() {
        server.enqueue(throttled(retryAfter = "8"))
        server.enqueue(ok())
        val call = client.newCall(Request.Builder().url(server.url("/v1/config")).get().build())
        Thread {
            Thread.sleep(300)
            call.cancel()
        }.start()

        val started = System.nanoTime()
        val thrown = runCatching { call.execute().close() }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("expected an IOException, got $thrown", thrown is IOException)
        // The header asked for eight seconds; a cancel must not sit them out.
        assertTrue("waited ${elapsedMs}ms", elapsedMs < 2_000)
        assertEquals(1, server.requestCount)
    }

    // ------------------------------------------------------------------- 503

    @Test
    fun `an unavailable GET is retried`() {
        server.enqueue(unavailable())
        server.enqueue(ok())

        get().use { assertEquals(HttpStatus.OK, it.code) }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a device-signed call's 503 is left to the signing layer, which signs a new nonce`() {
        // The same X-Nonce again would be refused as replayed if the first
        // attempt reached verified_device (SemperApiSigning retries instead).
        server.enqueue(unavailable())
        server.enqueue(ok())

        signedGet().use { assertEquals(HttpStatus.SERVICE_UNAVAILABLE, it.code) }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a device-signed call's 429 is retried as it is - the nonce was never claimed`() {
        server.enqueue(throttled())
        server.enqueue(ok())

        signedGet().use { assertEquals(HttpStatus.OK, it.code) }

        assertEquals(2, server.requestCount)
    }

    private fun signedGet() =
        client.newCall(Request.Builder().url(server.url("/v1/config")).header("X-Nonce", "t1.1.abc").get().build())
            .execute()

    @Test
    fun `an unavailable POST that is not idempotent is left alone`() {
        server.enqueue(unavailable())
        server.enqueue(ok())

        // Creating a session twice costs a Drive object, so a 503 that may have
        // been delivered is surfaced rather than repeated.
        post("/v1/sessions").use { assertEquals(HttpStatus.SERVICE_UNAVAILABLE, it.code) }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an unavailable seat checkout is retried — re-calling it is the heartbeat`() {
        server.enqueue(unavailable())
        server.enqueue(ok())

        post("/v1/licenses/checkout").use { assertEquals(HttpStatus.OK, it.code) }

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a kill switch's pause is answered at once, body intact, not waited out`() {
        val paused = """{"detail":"feature_paused"}"""
        server.enqueue(
            MockResponse(
                code = HttpStatus.SERVICE_UNAVAILABLE,
                headers = Headers.headersOf("Retry-After", "300"),
                body = paused,
            ),
        )
        server.enqueue(ok())

        // A restore's GET: retried, it would sit out two capped backoffs for
        // an answer that lasts minutes, then say paused anyway.
        get("/v1/files/f1/content").use {
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, it.code)
            assertEquals("the worker still reads the code", paused, it.body.string())
        }

        assertEquals(1, server.requestCount)
    }

    // ------------------------------------------------------ everything else

    @Test
    fun `a server error is not a transient answer`() {
        server.enqueue(MockResponse(code = HttpStatus.INTERNAL_ERROR, body = ""))
        server.enqueue(ok())

        get().use { assertEquals(HttpStatus.INTERNAL_ERROR, it.code) }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a refusal is not retried`() {
        server.enqueue(MockResponse(code = HttpStatus.FORBIDDEN, body = """{"detail":"not_approved"}"""))
        server.enqueue(ok())

        get().use { assertEquals(HttpStatus.FORBIDDEN, it.code) }

        assertEquals(1, server.requestCount)
    }
}
