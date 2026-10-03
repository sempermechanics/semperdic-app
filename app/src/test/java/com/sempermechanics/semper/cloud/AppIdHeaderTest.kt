package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.data.net.AppIdHeader
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * [AppIdHeader] against a fake backend. The backend binds one phone per app
 * (ADR-010) and reads a missing header as Semper, so the header must reach the
 * backend and must not reach anything else on the shared client.
 */
class AppIdHeaderTest {

    @get:Rule
    val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private fun call(interceptor: AppIdHeader) {
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        server.enqueue(MockResponse(code = 200))
        val request = Request.Builder().url(server.url("/v1/me")).build()
        client.newCall(request).execute().use { assertEquals(200, it.code) }
    }

    @Test
    fun `a backend call names the app`() {
        call(AppIdHeader(server.hostName, "com.indicvision.semper.materialtesting"))
        assertEquals(
            "com.indicvision.semper.materialtesting",
            server.takeRequest().headers["X-App-Id"],
        )
    }

    @Test
    fun `the default is this build's application id`() {
        call(AppIdHeader(server.hostName))
        assertEquals(BuildConfig.APPLICATION_ID, server.takeRequest().headers["X-App-Id"])
    }

    @Test
    fun `a call to another host is left alone`() {
        // The fake server stands in for Drive: same client, another host.
        call(AppIdHeader("api.example.test", "com.sempermechanics.semper"))
        assertNull(server.takeRequest().headers["X-App-Id"])
    }

    @Test
    fun `no host configured means no header`() {
        call(AppIdHeader("", "com.sempermechanics.semper"))
        assertNull(server.takeRequest().headers["X-App-Id"])
    }
}
