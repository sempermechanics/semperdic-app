package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.net.AppCheckHeader
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * [AppCheckHeader] against a fake backend.
 *
 * Two properties matter and neither is "the header is sent". The first is
 * *scope*: Drive shares this OkHttp client, and Google's endpoints have no use
 * for this project's attestation state. The second is *failing open*: a build
 * that cannot attest must still reach the backend on its other credentials,
 * because the decision to refuse belongs to the server, which is the side that
 * can tell `monitor` from `enforce`.
 */
class AppCheckHeaderTest {

    @get:Rule
    val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    /** The fake backend's host, which is what the interceptor is scoped to. */
    private val apiHost: String get() = server.hostName

    private fun clientWith(token: () -> String?): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(AppCheckHeader(apiHost, token)).build()

    private fun call(client: OkHttpClient) {
        server.enqueue(MockResponse(code = 200))
        val request = Request.Builder().url(server.url("/v1/me")).build()
        client.newCall(request).execute().use { assertEquals(200, it.code) }
    }

    @Test
    fun `a token is attached to a backend call`() {
        call(clientWith { "attested" })
        assertEquals("attested", server.takeRequest().headers["X-Firebase-AppCheck"])
    }

    @Test
    fun `a call goes out bare when no token is available`() {
        call(clientWith { null })
        assertNull(server.takeRequest().headers["X-Firebase-AppCheck"])
    }

    @Test
    fun `a call goes out bare when the token source throws`() {
        // Play Integrity unavailable, no provider installed, a fetch timeout:
        // every one of them lands here, and none may fail the request.
        call(clientWith { error("Play Integrity unavailable") })
        assertNull(server.takeRequest().headers["X-Firebase-AppCheck"])
    }

    @Test
    fun `an empty token is treated as no token rather than sent`() {
        call(clientWith { "" })
        assertNull(server.takeRequest().headers["X-Firebase-AppCheck"])
    }

    @Test
    fun `a call to another host is left alone`() {
        // The fake server stands in for Drive here: same client, different host
        // from the one the interceptor was told to attest to.
        val client = OkHttpClient.Builder()
            .addInterceptor(AppCheckHeader("api.example.test") { "attested" })
            .build()
        call(client)
        assertNull(server.takeRequest().headers["X-Firebase-AppCheck"])
    }

    @Test
    fun `no host configured means no header anywhere`() {
        // SEMPER_API_BASE_URL empty — an offline build. The token source must not
        // even be consulted, so a Play Integrity handshake is never paid for.
        var asked = false
        val token: () -> String = {
            asked = true
            "attested"
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AppCheckHeader("", token))
            .build()
        call(client)
        assertNull(server.takeRequest().headers["X-Firebase-AppCheck"])
        assertEquals(false, asked)
    }
}
