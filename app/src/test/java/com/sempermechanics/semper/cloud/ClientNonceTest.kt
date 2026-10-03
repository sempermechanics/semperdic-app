package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.net.ClientNonce
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ClientNonce]: nonces the device mints so a signed call skips the challenge
 * round-trip.
 *
 * The shape must match `backend/app/deps.py` exactly, or every signed call pays
 * a refusal and a retry. The clock must be the server's, or a phone set to the
 * wrong time is refused on every call.
 *
 * Robolectric only for `org.json`, which [ClientNonce.isRefusal] parses with.
 */
@RunWith(RobolectricTestRunner::class)
class ClientNonceTest {

    /** `backend/app/deps.py` `_CLIENT_NONCE`. */
    private val serverPattern = Regex("""t1\.(\d{9,11})\.[A-Za-z0-9_-]{22,86}""")

    @Before
    fun setUp() = ClientNonce.reset()

    @After
    fun tearDown() = ClientNonce.reset()

    @Test
    fun `not usable until the server clock is known`() {
        assertFalse(ClientNonce.usable())
        ClientNonce.observeServerTime(serverMs = 1_800_000_000_000L, nowMs = 1_800_000_000_000L)
        assertTrue(ClientNonce.usable())
    }

    @Test
    fun `a refusal falls back to challenges for the rest of the process`() {
        ClientNonce.observeServerTime(serverMs = 1_800_000_000_000L, nowMs = 1_800_000_000_000L)
        ClientNonce.markRefused()
        assertFalse(ClientNonce.usable())
        ClientNonce.observeServerTime(serverMs = 1_800_000_000_000L, nowMs = 1_800_000_000_000L)
        assertFalse(ClientNonce.usable())
    }

    @Test
    fun `minted nonces match the server's pattern and are unique`() {
        ClientNonce.observeServerTime(serverMs = 1_800_000_000_000L, nowMs = 1_800_000_000_000L)
        val a = ClientNonce.mint(nowMs = 1_800_000_000_000L)
        val b = ClientNonce.mint(nowMs = 1_800_000_000_000L)
        assertTrue(a, serverPattern.matches(a))
        assertNotEquals(a, b)
    }

    @Test
    fun `seconds come from the server clock, not the phone's`() {
        // Phone is ten minutes slow; the server's window is two.
        val server = 1_800_000_000_000L
        val phone = server - 600_000L
        ClientNonce.observeServerTime(serverMs = server, nowMs = phone)
        val seconds = ClientNonce.mint(nowMs = phone).split('.')[1].toLong()
        assertEquals(server / 1000, seconds)
    }

    @Test
    fun `16 random bytes encode to 22 unpadded url-safe characters`() {
        val encoded = ClientNonce.base64Url(ByteArray(16) { 0xFF.toByte() })
        assertEquals(22, encoded.length)
        assertTrue(encoded.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA", ClientNonce.base64Url(ByteArray(16)))
        // RFC 4648 §10 vector, url-safe alphabet, no padding.
        assertEquals("Zm9vYmFy", ClientNonce.base64Url("foobar".toByteArray()))
        assertEquals("Zm9vYg", ClientNonce.base64Url("foob".toByteArray()))
    }

    @Test
    fun `only a 401 nonce refusal is a refusal`() {
        val body = """{"detail":"nonce_invalid_or_replayed"}"""
        assertTrue(ClientNonce.isRefusal(401, body))
        assertFalse(ClientNonce.isRefusal(403, body))
        assertFalse(ClientNonce.isRefusal(401, """{"detail":"device_signature_invalid"}"""))
    }

    @Test
    fun `learns the clock from the API host only`() {
        MockWebServer().use { server ->
            server.start()
            val date = Headers.Builder().set("Date", java.util.Date(1_800_000_000_000L)).build()
            server.enqueue(MockResponse(code = 200, headers = date))
            server.enqueue(MockResponse(code = 200, headers = date))

            val elsewhere = OkHttpClient.Builder()
                .addInterceptor(ClientNonce.ServerDateObserver("drive.example"))
                .build()
            elsewhere.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
            assertFalse(ClientNonce.usable())

            val api = OkHttpClient.Builder()
                .addInterceptor(ClientNonce.ServerDateObserver(server.hostName))
                .build()
            api.newCall(Request.Builder().url(server.url("/v1/me")).build()).execute().close()
            assertTrue(ClientNonce.usable())
        }
    }
}
