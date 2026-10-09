package com.sempermechanics.semper.data.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

/**
 * The shared backend clients: they follow no redirect (TD-161), and the
 * certificate pins are registered for the backend's bare host name (TD-160).
 */
class SemperApiClientsTest {

    @get:Rule
    val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    /**
     * Sends a backend-shaped request through [client] to [server], which
     * answers with a redirect to [elsewhere]; returns the status the caller saw.
     */
    private fun callRedirectedTo(client: OkHttpClient, elsewhere: MockWebServer): Int {
        server.enqueue(MockResponse.Builder().code(FOUND).addHeader("Location", elsewhere.url("/stolen")).build())
        elsewhere.enqueue(MockResponse(code = 200))
        val request = Request.Builder().url(server.url("/v1/me"))
            .header(SemperApiHttp.DEVICE_ID, "device-1")
            .header("X-Nonce", "nonce-1")
            .header("X-Signature", "signature-1")
            .build()
        return client.newCall(request).execute().use { it.code }
    }

    @Test
    fun `a redirect to another host is not followed`() {
        MockWebServer().use { elsewhere ->
            elsewhere.start()
            assertEquals(FOUND, callRedirectedTo(SemperApiClients.api, elsewhere))
            assertEquals(FOUND, callRedirectedTo(SemperApiClients.download, elsewhere))
            assertEquals(0, elsewhere.requestCount)
        }
    }

    @Test
    fun `a client that follows redirects would carry the backend headers along`() {
        // The hazard the clients avoid: OkHttp drops only Authorization on a cross-host hop.
        MockWebServer().use { elsewhere ->
            elsewhere.start()
            assertEquals(OK, callRedirectedTo(OkHttpClient(), elsewhere))
            val leaked = elsewhere.takeRequest().headers
            assertEquals("device-1", leaked[SemperApiHttp.DEVICE_ID])
            assertEquals("signature-1", leaked["X-Signature"])
        }
    }

    @Test
    fun `the pins are registered for the bare host of a base url with a port`() {
        assertEquals(setOf("api.example.test"), patternsFor(" $PIN , ", "https://api.example.test:8443/base/"))
        assertEquals(setOf("api.example.test"), patternsFor(PIN, "https://api.example.test"))
    }

    private fun patternsFor(pins: String, baseUrl: String): Set<String>? =
        SemperApiClients.certificatePinner(pins, baseUrl)?.pins?.map { it.pattern }?.toSet()

    @Test
    fun `no pins or no backend means no pinner`() {
        assertNull(SemperApiClients.certificatePinner("", "https://api.example.test"))
        assertNull(SemperApiClients.certificatePinner(" , ", "https://api.example.test"))
        assertNull(SemperApiClients.certificatePinner(PIN, ""))
    }

    @Test
    fun `the pinner refuses a host and port pattern`() {
        // Why the pins take the bare host name: a `host:port` pattern does not
        // just miss, it throws, and the clients would fail to build.
        assertThrows(IllegalArgumentException::class.java) {
            CertificatePinner.Builder().add("api.example.test:8443", PIN)
        }
    }

    private companion object {
        const val OK = 200
        const val FOUND = 302

        /** A well-formed SHA-256 pin (32 zero bytes). */
        val PIN = "sha256/" + "A".repeat(43) + "="
    }
}
