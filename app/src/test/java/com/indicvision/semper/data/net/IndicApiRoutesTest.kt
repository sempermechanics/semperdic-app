package com.indicvision.semper.data.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.util.Digests
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Each route's own mapping of a non-200 answer to an exception, and the paged
 * listings' walk, against a fake backend.
 *
 * The backend is `https://api.test`; an interceptor sends every call to the
 * MockWebServer instead, so the base URL keeps the https rule. Signed calls use
 * a client nonce (one request each) and a "signature" that is the signed
 * message in hex.
 */
@RunWith(RobolectricTestRunner::class)
class IndicApiRoutesTest {

    @get:Rule
    val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private val api by lazy {
        val toServer = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val target = server.url(url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: ""))
            chain.proceed(chain.request().newBuilder().url(target).build())
        }.build()
        IndicApi(ApplicationProvider.getApplicationContext<Context>(), "https://api.test") { endpoint ->
            IndicApiCalls(toServer, endpoint, deviceId = { "device-1" }, sign = { Digests.toHex(it) })
        }
    }

    @Before
    fun setUp() = ClientNonce.observeServerTime(System.currentTimeMillis())

    @After
    fun tearDown() = ClientNonce.reset()

    private fun answer(code: Int, detail: String? = null, body: String? = null) = server.enqueue(
        MockResponse(
            code = code,
            headers = Headers.headersOf("X-Request-Id", "ref1"),
            body = body ?: detail?.let { """{"detail":"$it"}""" }.orEmpty(),
        ),
    )

    private inline fun <reified E : Throwable> fails(noinline call: suspend () -> Unit): E =
        assertThrows(E::class.java) { runBlocking { call() } }

    @Test
    fun `me maps 403 to not approved and a 409 to the binding it names`() {
        answer(403, "not_approved")
        fails<IndicApi.NotApprovedException> { api.me("tok") }

        answer(409, "device_in_use")
        assertEquals("ref1", fails<IndicApi.DeviceInUseException> { api.me("tok") }.requestId)

        answer(409, "device_conflict")
        fails<IndicApi.DeviceConflictException> { api.me("tok") }
    }

    @Test
    fun `a full seat pool is its own exception, another seat 409 is not`() {
        answer(409, "no_floating_seat")
        fails<IndicApi.NoSeatAvailableException> { api.checkoutLease("tok") }

        answer(409, "license_revoked")
        assertEquals(409, fails<IndicApi.ApiException> { api.releaseLease("tok") }.code)
        assertEquals("/v1/licenses/checkout", server.takeRequest().target)
        assertEquals("/v1/licenses/release", server.takeRequest().target)
    }

    @Test
    fun `a terms 409 is a version mismatch with its request id`() {
        answer(409, "terms_version_mismatch")
        assertEquals("ref1", fails<IndicApi.TermsVersionMismatchException> { api.acceptTerms("tok", "v2") }.requestId)
    }

    @Test
    fun `listUsers turns a 403 into not_admin`() {
        answer(403, "anything")
        val e = fails<IndicApi.ApiException> { api.listUsers("tok", "PENDING") }
        assertEquals(403, e.code)
        assertEquals(ApiErrors.NOT_ADMIN, e.body)
        assertEquals("/v1/admin/users?status=PENDING", server.takeRequest().target)
    }

    @Test
    fun `deleteSession accepts only our own session_not_found 404`() {
        answer(404, "session_not_found")
        runBlocking { api.deleteSession("tok", "s1") }

        answer(404, body = "Not Found")
        assertEquals(404, fails<IndicApi.ApiException> { api.deleteSession("tok", "s1") }.code)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `a signed route maps device codes, but setUserStatus keeps a plain ApiException`() {
        answer(409, "device_not_active")
        fails<IndicApi.DeviceNotActiveException> { api.completeFile("tok", "f1", FileCompleteRequest("s1", "d1", 1L)) }

        answer(409, "device_not_active")
        val e = fails<IndicApi.ApiException> { api.setUserStatus("tok", "u1", "approve") }
        assertEquals(409, e.code)
    }

    @Test
    fun `listSessionFiles walks every page of the manifest`() {
        answer(
            200,
            body = """{"sessionId":"s1","status":"COMPLETED","files":[{"fileId":"a"}],""" +
                """"page":{"nextPageToken":"s1_raw_b.png","hasMore":true}}""",
        )
        answer(200, body = """{"sessionId":"s1","files":[{"fileId":"b"}],"page":{"hasMore":false}}""")

        val manifest = runBlocking { api.listSessionFiles("tok", "s1") }

        assertEquals(listOf("a", "b"), manifest.files.map { it.fileId })
        assertEquals("COMPLETED", manifest.status)
        assertEquals("/v1/sessions/s1/files", server.takeRequest().target)
        assertEquals("/v1/sessions/s1/files?page_token=s1_raw_b.png", server.takeRequest().target)
    }

    @Test
    fun `sessionUploads walks every page and signs each page's query`() {
        answer(
            200,
            body = """{"sessionId":"s1","status":"UPLOADING",""" +
                """"uploads":[{"fileId":"a","uploadUrl":"u"}],"page":{"nextPageToken":"t 1","hasMore":true}}""",
        )
        answer(200, body = """{"sessionId":"s1","uploads":[{"fileId":"b","uploadUrl":"u"}]}""")

        val plan = runBlocking { api.sessionUploads("tok", "s1") }

        assertEquals(listOf("a", "b"), plan.uploads.map { it.fileId })
        assertEquals("/v1/sessions/s1/uploads", server.takeRequest().target)
        val second = server.takeRequest()
        val path = "/v1/sessions/s1/uploads?page_token=t+1"
        assertEquals(path, second.target)
        val nonce = second.headers["X-Nonce"].orEmpty()
        val signed = Digests.toHex((nonce + "GET" + path).toByteArray() + Digests.sha256(ByteArray(0)))
        assertEquals(signed, second.headers["X-Signature"])
    }

    @Test
    fun `a listing that serves the same page token twice fails instead of repeating a page`() {
        val page = """{"sessionId":"s1","uploads":[{"fileId":"a","uploadUrl":"u"}],""" +
            """"page":{"nextPageToken":"t1","hasMore":true}}"""
        answer(200, body = page)
        answer(200, body = page)

        fails<IOException> { api.sessionUploads("tok", "s1") }
        assertEquals(2, server.requestCount)
    }
}
