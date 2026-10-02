package com.indicvision.semper.data.net

import com.indicvision.semper.util.Digests
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What [IndicApiCalls] puts on the wire and how it reads the answer, plus the
 * shared refusal mappings. Robolectric for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class IndicApiCallsTest {

    @get:Rule
    val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private var deviceAsked = false

    private val calls by lazy {
        IndicApiCalls(
            OkHttpClient(),
            endpoint = { path -> endpoint(path) },
            deviceId = {
                deviceAsked = true
                "device-1"
            },
            sign = { Digests.toHex(it) },
        )
    }

    private fun endpoint(path: String) = server.url("/").toString().trimEnd('/') + path

    @Before
    fun setUp() = ClientNonce.observeServerTime(System.currentTimeMillis())

    @After
    fun tearDown() = ClientNonce.reset()

    /** The headers this client sets, in the order they went out. */
    private fun Headers.ours(): List<String> =
        (0 until size).map { name(it) }.filter { it == "Authorization" || it.startsWith("X-") }

    @Test
    fun `a bearer call sends the token then the device id, and reads a 200`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = "hello"))

        val read = calls.bearer("tok", { url(endpoint("/v1/me")) }) { it.body.string() }

        assertEquals("hello", read)
        val request = server.takeRequest()
        assertEquals(listOf("Authorization", "X-Device-Id"), request.headers.ours())
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertEquals("device-1", request.headers["X-Device-Id"])
    }

    @Test
    fun `a signed call sends token, device id, nonce and signature in that order`() = runBlocking {
        server.enqueue(MockResponse(code = 200, body = "{}"))

        calls.signed("tok", SignedCall("POST", "/v1/sessions", "{}".toByteArray())) {}

        assertEquals(
            listOf("Authorization", "X-Device-Id", "X-Nonce", "X-Signature"),
            server.takeRequest().headers.ours(),
        )
    }

    @Test
    fun `any other answer goes to orElse with its code, body and request id`() = runBlocking {
        server.enqueue(
            MockResponse(code = 503, headers = Headers.headersOf("X-Request-Id", "abc123"), body = "busy"),
        )

        val answer = calls.bearer("tok", { url(endpoint("/v1/config")) }, orElse = { it }) { error("not a 200") }

        assertEquals(503, answer.code)
        assertEquals("busy", answer.body)
        assertEquals("abc123", answer.requestId)
    }

    @Test
    fun `the default for a bearer call is a plain ApiException`() {
        server.enqueue(MockResponse(code = 409, body = """{"detail":"device_not_active"}"""))

        val e = assertThrows(IndicApi.ApiException::class.java) {
            runBlocking { calls.bearer("tok", { url(endpoint("/v1/config")) }) {} }
        }
        assertEquals(409, e.code)
    }

    @Test
    fun `with no backend the route fails before the device key is touched`() {
        assertThrows(IndicApi.CloudNotConfiguredException::class.java) {
            runBlocking { calls.bearer("tok", { url(IndicApiHttp.endpoint("", "/v1/me")) }) {} }
        }
        assertFalse(deviceAsked)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a signed call's 409 maps to the device exception its detail names`() {
        fun failed(code: Int, detail: String): Throwable =
            runCatching { ApiAnswer(code, """{"detail":"$detail"}""", "r1").failSigned() }.exceptionOrNull()!!

        val notActive = failed(409, "device_not_active")
        assertTrue(notActive is IndicApi.DeviceNotActiveException)
        assertEquals("r1", (notActive as IndicApi.DeviceNotActiveException).requestId)
        assertTrue(failed(409, "device_in_use") is IndicApi.DeviceInUseException)
        assertTrue(failed(409, "device_conflict") is IndicApi.DeviceConflictException)

        val otherConflict = failed(409, "size_or_state_mismatch")
        assertEquals(409, (otherConflict as IndicApi.ApiException).code)
        // Only a 409 is read for a device code.
        val notFound = failed(404, "device_not_active")
        assertEquals(404, (notFound as IndicApi.ApiException).code)
    }

    @Test
    fun `only a 403 is not-approved for the approved-only routes`() {
        assertThrows(IndicApi.NotApprovedException::class.java) { ApiAnswer(403, "", null).failApprovedOnly() }
        val e = assertThrows(IndicApi.ApiException::class.java) { ApiAnswer(500, "", null).failApprovedOnly() }
        assertEquals(500, e.code)
        assertNull(e.requestId)
    }
}
