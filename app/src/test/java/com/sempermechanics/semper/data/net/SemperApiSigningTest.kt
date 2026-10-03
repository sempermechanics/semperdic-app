package com.sempermechanics.semper.data.net

import com.sempermechanics.semper.util.Digests
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The device-signed request shape the backend verifies (`backend/app/deps.py`):
 * the four headers, the signed message, and the one re-send with a server
 * challenge when a client nonce is refused. Robolectric for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class SemperApiSigningTest {

    @get:Rule
    val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    /** The "signature" is the message itself, hex, so a test can read what was signed. */
    private val signing by lazy {
        SemperApiSigning(
            OkHttpClient(),
            endpoint = { path -> server.url("/").toString().trimEnd('/') + path },
            deviceId = { "device-1" },
            sign = { Digests.toHex(it) },
        )
    }

    @Before
    fun setUp() = ClientNonce.reset()

    @After
    fun tearDown() = ClientNonce.reset()

    private fun expectedSignature(nonce: String, method: String, path: String, body: ByteArray): String =
        Digests.toHex((nonce + method + path).toByteArray() + Digests.sha256(body))

    @Test
    fun `a signed call fetches a challenge and signs nonce, method, path and body hash`() {
        server.enqueue(MockResponse(code = 200, body = """{"nonce":"n1"}"""))
        server.enqueue(MockResponse(code = 200, body = "{}"))
        val body = """{"a":1}""".toByteArray()

        signing.execute("tok", SignedCall("POST", "/v1/sessions?page_token=p1", body)).close()

        val challenge = server.takeRequest()
        assertEquals("POST", challenge.method)
        assertEquals("/v1/challenge", challenge.url.encodedPath)
        assertEquals("Bearer tok", challenge.headers["Authorization"])
        assertEquals("device-1", challenge.headers["X-Device-Id"])

        val call = server.takeRequest()
        assertEquals("POST", call.method)
        assertEquals("/v1/sessions?page_token=p1", call.target)
        assertEquals(
            listOf("Authorization", "X-Device-Id", "X-Nonce", "X-Signature"),
            call.headers.names().filter { it.startsWith("X-") || it == "Authorization" }.sorted(),
        )
        assertEquals("Bearer tok", call.headers["Authorization"])
        assertEquals("device-1", call.headers["X-Device-Id"])
        assertEquals("n1", call.headers["X-Nonce"])
        assertEquals(
            expectedSignature("n1", "POST", "/v1/sessions?page_token=p1", body),
            call.headers["X-Signature"],
        )
        assertEquals("""{"a":1}""", call.body?.utf8())
    }

    @Test
    fun `a delete sends no body and signs the empty one`() {
        server.enqueue(MockResponse(code = 200, body = """{"nonce":"n2"}"""))
        server.enqueue(MockResponse(code = 200))

        signing.execute("tok", SignedCall("DELETE", "/v1/me")).close()

        server.takeRequest()
        val call = server.takeRequest()
        assertEquals("DELETE", call.method)
        assertEquals(0L, call.body?.size?.toLong() ?: 0L)
        assertEquals(expectedSignature("n2", "DELETE", "/v1/me", ByteArray(0)), call.headers["X-Signature"])
    }

    @Test
    fun `a refused client nonce is re-sent once with a server challenge`() {
        ClientNonce.observeServerTime(System.currentTimeMillis())
        server.enqueue(MockResponse(code = 401, body = """{"detail":"nonce_invalid_or_replayed"}"""))
        server.enqueue(MockResponse(code = 200, body = """{"nonce":"n3"}"""))
        server.enqueue(MockResponse(code = 200))

        signing.execute("tok", SignedCall("PUT", "/v1/sessions/s1/metadata", "{}".toByteArray())).use {
            assertEquals(200, it.code)
        }

        val first = server.takeRequest()
        assertTrue(first.headers["X-Nonce"].orEmpty().startsWith("t1."))
        assertEquals("/v1/challenge", server.takeRequest().url.encodedPath)
        assertEquals("n3", server.takeRequest().headers["X-Nonce"])
        assertFalse("the process falls back to challenges", ClientNonce.usable())
    }

    @Test
    fun `download headers use a client nonce without a round trip when one is usable`() {
        ClientNonce.observeServerTime(System.currentTimeMillis())

        val headers = signing.headersFor("tok", SignedCall("GET", "/v1/files/f1/content"))

        val nonce = headers["X-Nonce"].orEmpty()
        assertTrue(nonce.startsWith("t1."))
        assertEquals(expectedSignature(nonce, "GET", "/v1/files/f1/content", ByteArray(0)), headers["X-Signature"])
        assertEquals(0, server.requestCount)
    }
}
