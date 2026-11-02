package com.sempermechanics.semper.data.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.util.Digests
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
class SemperApiRoutesTest {

    @get:Rule
    val serverRule = MockWebServerRule()
    private val server get() = serverRule.server

    private val api by lazy {
        val toServer = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val target = server.url(url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: ""))
            chain.proceed(chain.request().newBuilder().url(target).build())
        }.build()
        SemperApi(ApplicationProvider.getApplicationContext<Context>(), "https://api.test") { endpoint ->
            SemperApiCalls(
                toServer,
                endpoint,
                deviceId = { "device-1" },
                sign = { Digests.toHex(it) },
                recovery = DeviceKeyRecovery(reRegister = { reRegistered++ }),
            )
        }
    }

    /** How often the device key was registered again ([DeviceKeyRecovery]). */
    private var reRegistered = 0

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
        fails<NotApprovedException> { api.getMe("tok") }

        answer(409, "device_in_use")
        assertEquals("ref1", fails<DeviceInUseException> { api.getMe("tok") }.requestId)

        answer(409, "device_conflict")
        fails<DeviceConflictException> { api.getMe("tok") }
    }

    @Test
    fun `a full seat pool is its own exception, another seat 409 is not`() {
        answer(409, "no_floating_seat")
        fails<NoSeatAvailableException> { api.checkoutLease("tok") }

        answer(409, "license_revoked")
        assertEquals(409, fails<ApiException> { api.releaseLease("tok") }.code)
        assertEquals("/v1/licenses/checkout", server.takeRequest().target)
        assertEquals("/v1/licenses/release", server.takeRequest().target)
    }

    @Test
    fun `a terms 409 is a version mismatch with its request id`() {
        answer(409, "terms_version_mismatch")
        assertEquals("ref1", fails<TermsVersionMismatchException> { api.acceptTerms("tok", "v2") }.requestId)
    }

    @Test
    fun `listUsers turns a 403 into not_admin`() {
        answer(403, "anything")
        val e = fails<ApiException> { api.listUsers("tok", "PENDING") }
        assertEquals(403, e.code)
        assertEquals(ApiErrors.NOT_ADMIN, e.body)
        assertEquals("/v1/admin/users?status=PENDING", server.takeRequest().target)
    }

    @Test
    fun `deleteSession reads the bin's answer, and an older backend's empty one`() {
        answer(200, body = """{"deleted":"s1","filesRemoved":3,"binned":true,"purgeAt":"2026-12-03T09:14:05+00:00"}""")
        answer(200, body = """{"deleted":"s1","filesRemoved":3}""")
        answer(200, body = "")

        val binned = runBlocking { api.deleteSession("tok", "s1") }
        val erased = runBlocking { api.deleteSession("tok", "s1") }
        val empty = runBlocking { api.deleteSession("tok", "s1") }

        assertEquals(true, binned.binned)
        assertEquals(1_796_289_245_000L, binned.purgeAtMs)
        assertEquals(false, erased.binned)
        assertEquals(SessionDeleteResponse(), empty)
    }

    @Test
    fun `the recycle bin lists every page by token, and restores and erases signed`() {
        answer(
            200,
            body = """{"items":[{"sessionId":"a","purgeAt":"2026-12-03T09:14:05+00:00"}],"erased":[],""" +
                """"retentionDays":30,"page":{"nextPageToken":"t 1","hasMore":true}}""",
        )
        answer(200, body = """{"items":[],"erased":[{"sessionId":"b","localSessionId":"lb"}],"retentionDays":30}""")
        answer(200, body = """{"sessionId":"a","localSessionId":"la","status":"COMPLETED","restored":true}""")
        answer(200, body = """{"sessionId":"a","localSessionId":"la","filesRemoved":2,"erased":true}""")
        answer(404, "session_not_found")

        val listed = runBlocking { api.listRecycleBin("tok") }
        val restored = runBlocking { api.restoreFromRecycleBin("tok", "a") }
        val purged = runBlocking { api.purgeFromRecycleBin("tok", "a") }

        assertEquals(listOf("a"), listed.items.map { it.sessionId })
        assertEquals(listOf("b"), listed.erased.map { it.sessionId })
        assertEquals(30, listed.retentionDays)
        assertEquals(true, restored.restored)
        assertEquals(2, purged.filesRemoved)
        assertEquals("/v1/recycle-bin", server.takeRequest().target)
        assertEquals("/v1/recycle-bin?page_token=t+1", server.takeRequest().target)
        val restore = server.takeRequest()
        assertEquals("POST", restore.method)
        assertEquals("/v1/recycle-bin/a/restore", restore.target)
        assertEquals(true, restore.headers["X-Signature"]?.isNotEmpty())
        val purge = server.takeRequest()
        assertEquals("DELETE", purge.method)
        assertEquals("/v1/recycle-bin/a", purge.target)
        assertEquals(true, purge.headers["X-Signature"]?.isNotEmpty())
        assertEquals(404, fails<ApiException> { api.purgeFromRecycleBin("tok", "a") }.code)
    }

    @Test
    fun `deleteSession accepts only our own session_not_found 404`() {
        answer(404, "session_not_found")
        runBlocking { api.deleteSession("tok", "s1") }

        answer(404, body = "Not Found")
        assertEquals(404, fails<ApiException> { api.deleteSession("tok", "s1") }.code)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `a signed route maps device codes, but setUserStatus keeps a plain ApiException`() {
        answer(409, "device_not_active")
        fails<DeviceNotActiveException> { api.completeFile("tok", "f1", FileCompleteRequest("s1", "d1", 1L)) }

        answer(409, "device_not_active")
        val e = fails<ApiException> { api.setUserStatus("tok", "u1", "approve") }
        assertEquals(409, e.code)
    }

    @Test
    fun `a signed route recovers a bad signature whatever its own refusal mapping`() {
        answer(401, "bad_signature")
        answer(200)
        runBlocking { api.setUserStatus("tok", "u1", "approve") }

        assertEquals(1, reRegistered)
        assertEquals("/v1/admin/users/u1/approve", server.takeRequest().target)
        assertEquals("/v1/admin/users/u1/approve", server.takeRequest().target)
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
    fun `listSessions names the app only when asked, the account for all`() {
        answer(200, body = """{"sessions":[{"sessionId":"a","localSessionId":"l1"}],"quota":{"used":2,"max":25}}""")
        answer(200, body = """{"sessions":[],"page":{"nextPageToken":"t 1","hasMore":true}}""")
        answer(200, body = """{"sessions":[{"sessionId":"b"}],"quota":{"used":2,"max":25}}""")

        runBlocking { api.listSessions("tok") }
        val everyApp = runBlocking { api.listSessions("tok", app = CloudApi.ALL_APPS) }

        assertEquals("/v1/sessions?page_size=100", server.takeRequest().target)
        assertEquals("/v1/sessions?page_size=100&app=all", server.takeRequest().target)
        assertEquals("/v1/sessions?page_size=100&app=all&page_token=t+1", server.takeRequest().target)
        assertEquals(listOf("b"), everyApp.sessions.map { it.sessionId })
    }

    @Test
    fun `activate is signed, sends whether it is an upgrade, and reads the config it answers`() {
        answer(
            200,
            body = """{"activation":{"licenseId":"l1","deviceId":"device-1","already":false,""" +
                """"devicesActive":1,"maxDevices":2},"config":{"mode":"licensed","maxSessions":500,""" +
                """"activation":{"state":"active","devicesActive":1,"maxDevices":2}}}""",
        )
        answer(409, "device_limit_reached: 2/2")

        val done = runBlocking { api.activateDevice("tok", upgrade = true) }

        assertEquals(1, done.activation?.devicesActive)
        assertEquals("active", done.config.activation?.state)
        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/v1/me/devices/activate", sent.target)
        assertEquals("""{"upgrade":true}""", sent.body?.utf8())
        assertEquals("device-1", sent.headers["X-Device-Id"])
        val refused = fails<ApiException> { api.activateDevice("tok") }
        assertEquals("device_limit_reached: 2/2", refused.parsedDetail)
    }

    @Test
    fun `the phones list is a token call, and deactivate a signed delete`() {
        answer(
            200,
            body = """{"app":"semper","licenseId":"l1","devicesActive":1,"maxDevices":1,"pool":false,""" +
                """"model":"activation","devices":[{"deviceId":"device-1","model":"Pixel 6",""" +
                """"activated":true,"thisDevice":true,"activatedAt":"2026-10-29T10:00:00+00:00"}]}""",
        )
        answer(200, body = """{"licenseId":"l1","deviceId":"and-2","devicesActive":0,"maxDevices":1}""")
        answer(404, "activation_not_found")

        val phones = runBlocking { api.listDevices("tok") }
        val gone = runBlocking { api.deactivateDevice("tok", "and-2") }

        assertEquals(listOf("device-1"), phones.devices.map { it.deviceId })
        assertEquals(true, phones.devices.single().thisDevice)
        assertEquals(0, gone.devicesActive)
        assertEquals("/v1/me/devices", server.takeRequest().target)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/v1/me/devices/and-2", delete.target)
        assertEquals(true, delete.headers["X-Signature"]?.isNotEmpty())
        assertEquals("activation_not_found", fails<ApiException> { api.deactivateDevice("tok", "x") }.parsedDetail)
    }

    @Test
    fun `listSessionUploads walks every page and signs each page's query`() {
        answer(
            200,
            body = """{"sessionId":"s1","status":"UPLOADING",""" +
                """"uploads":[{"fileId":"a","uploadUrl":"u"}],"page":{"nextPageToken":"t 1","hasMore":true}}""",
        )
        answer(200, body = """{"sessionId":"s1","uploads":[{"fileId":"b","uploadUrl":"u"}]}""")

        val plan = runBlocking { api.listSessionUploads("tok", "s1") }

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

        fails<IOException> { api.listSessionUploads("tok", "s1") }
        assertEquals(2, server.requestCount)
    }
}
