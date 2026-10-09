package com.sempermechanics.semper.data.net

import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

/** The backend-only scoping that AppIdHeader, AppCheckHeader and ServerDateObserver share. */
class ApiHostInterceptorTest {

    @get:Rule
    val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private class Counting(apiHost: String) : ApiHostInterceptor(apiHost) {
        var calls = 0

        override fun interceptApiCall(chain: Interceptor.Chain): Response {
            calls++
            return chain.proceed(chain.request())
        }
    }

    private fun call(interceptor: Interceptor) {
        server.enqueue(MockResponse(code = 200))
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        client.newCall(Request.Builder().url(server.url("/v1/me")).build()).execute().close()
    }

    @Test
    fun `a call to a base url with a port is intercepted`() {
        // The fake backend listens on a random port, so its base URL always has one (TD-160).
        val interceptor = Counting(ApiHost.of(server.url("/").toString()))
        call(interceptor)
        assertEquals(1, interceptor.calls)
    }

    @Test
    fun `a call to another host or with no host configured passes through`() {
        val drive = Counting("api.example.test:443")
        val offline = Counting("")
        call(drive)
        call(offline)
        assertEquals(0, drive.calls)
        assertEquals(0, offline.calls)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a call to the api host on another port passes through`() {
        val otherPort = if (server.port == MAX_PORT) server.port - 1 else server.port + 1
        val interceptor = Counting("${server.hostName}:$otherPort")
        val bareHost = Counting(server.hostName)
        call(interceptor)
        call(bareHost)
        assertEquals(0, interceptor.calls)
        assertEquals(0, bareHost.calls)
    }

    @Test
    fun `the api host is the base url's host and port`() {
        assertEquals("api.example.test:443", ApiHost.of("https://api.example.test"))
        assertEquals("api.example.test:443", ApiHost.of("https://api.example.test/"))
        assertEquals("api.example.test:443", ApiHost.of("https://api.example.test/base/"))
        assertEquals("api.example.test:443", ApiHost.of("https://API.example.test:443/base/"))
        assertEquals("api.example.test:8443", ApiHost.of("https://api.example.test:8443/base/"))
        assertNotEquals(ApiHost.of("https://api.example.test"), ApiHost.of("https://api.example.test:8443"))
        assertEquals("", ApiHost.of(""))
        assertEquals("", ApiHost.of("api.example.test"))
    }

    @Test
    fun `the pinned host name drops the port`() {
        assertEquals("api.example.test", ApiHost.hostNameOf("https://api.example.test:8443/base/"))
        assertEquals("api.example.test", ApiHost.hostNameOf("https://api.example.test"))
        assertEquals("", ApiHost.hostNameOf(""))
    }

    private companion object {
        const val MAX_PORT = 65_535
    }
}
