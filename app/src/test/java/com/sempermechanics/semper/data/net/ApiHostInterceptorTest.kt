package com.sempermechanics.semper.data.net

import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
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
    fun `a call to the api host is intercepted`() {
        val interceptor = Counting(server.hostName)
        call(interceptor)
        assertEquals(1, interceptor.calls)
    }

    @Test
    fun `a call to another host or with no host configured passes through`() {
        val drive = Counting("api.example.test")
        val offline = Counting("")
        call(drive)
        call(offline)
        assertEquals(0, drive.calls)
        assertEquals(0, offline.calls)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the api host is the base url's host`() {
        assertEquals("api.example.test", ApiHost.of("https://api.example.test"))
        assertEquals("api.example.test", ApiHost.of("https://api.example.test/"))
        assertEquals("api.example.test", ApiHost.of("https://api.example.test/base/"))
        assertEquals("", ApiHost.of(""))
    }
}
