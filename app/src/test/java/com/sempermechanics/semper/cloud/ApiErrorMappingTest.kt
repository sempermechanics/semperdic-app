package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.net.ApiAnswer
import com.sempermechanics.semper.data.net.ApiErrors
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.CloudNotConfiguredException
import com.sempermechanics.semper.data.net.DeviceConflictException
import com.sempermechanics.semper.data.net.DeviceInUseException
import com.sempermechanics.semper.data.net.HttpStatus
import com.sempermechanics.semper.data.net.SemperApiHttp
import com.sempermechanics.semper.data.net.failMe
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How a backend failure is read on the client: which code it *is* (not which
 * code it mentions), and the correlation id that joins it to the backend log.
 *
 * Robolectric because [ApiErrors] parses the body with `org.json`, which is a
 * stub on the plain JVM classpath.
 */
@RunWith(RobolectricTestRunner::class)
class ApiErrorMappingTest {

    @Test
    fun `detail is read from the FastAPI envelope`() {
        assertEquals(
            ApiErrors.DEVICE_NOT_ACTIVE,
            ApiErrors.detailOf("""{"detail":"device_not_active"}"""),
        )
        assertTrue(
            ApiErrors.hasCode("""{"detail":"device_not_active"}""", ApiErrors.DEVICE_NOT_ACTIVE),
        )
    }

    @Test
    fun `a code quoted inside a message is not that code`() {
        // A body that merely mentions a device conflict must not read as one:
        // that sends the user to "back up from your other device" for an
        // unrelated rejection.
        val body = """{"detail":"session_quota_exceeded: 5/5 stored. Not a device_conflict."}"""
        assertFalse(ApiErrors.hasCode(body, ApiErrors.DEVICE_CONFLICT))
        assertTrue(ApiErrors.hasCode(body, ApiErrors.SESSION_QUOTA_EXCEEDED))
    }

    @Test
    fun `a non-JSON body is not mistaken for a code`() {
        // API Gateway and Cloud Run deadline kills answer with plain text, or
        // with nothing at all.
        assertEquals("", ApiErrors.detailOf(""))
        assertEquals("upstream request timeout", ApiErrors.detailOf("upstream request timeout"))
        assertFalse(ApiErrors.hasCode("", ApiErrors.SESSION_NOT_FOUND))
        assertFalse(ApiErrors.hasCode("<html>502 Bad Gateway</html>", ApiErrors.RATE_LIMITED))
    }

    @Test
    fun `malformed JSON falls back to the raw body`() {
        assertEquals("""{"detail":""", ApiErrors.detailOf("""{"detail":"""))
    }

    @Test
    fun `request id is taken from the response header when present`() {
        assertEquals("a1b2c3d4e5f6", SemperApiHttp.requestIdOf(response("a1b2c3d4e5f6")))
        assertNull(SemperApiHttp.requestIdOf(response(null)))
        assertNull(SemperApiHttp.requestIdOf(response("   ")))
    }

    @Test
    fun `failure reasons carry the reference only when there is one`() {
        assertEquals(
            "Backup failed (ref: a1b2c3d4e5f6)",
            SemperApiHttp.withRef("Backup failed", "a1b2c3d4e5f6"),
        )
        assertEquals("Backup failed", SemperApiHttp.withRef("Backup failed", null))
        assertEquals("Backup failed", SemperApiHttp.withRef("Backup failed", ""))
    }

    @Test
    fun `me conflict maps device in use and device conflict distinctly`() {
        val inUseBody = """{"detail":"device_in_use"}"""
        val conflictBody = """{"detail":"device_conflict"}"""
        val quotaBody = """{"detail":"session_quota_exceeded: 5/5"}"""

        assertThrows(DeviceInUseException::class.java) {
            ApiAnswer(HttpStatus.CONFLICT, inUseBody, "req-1").failMe()
        }
        assertThrows(DeviceConflictException::class.java) {
            ApiAnswer(HttpStatus.CONFLICT, conflictBody, "req-2").failMe()
        }
        val quota = assertThrows(ApiException::class.java) {
            ApiAnswer(HttpStatus.CONFLICT, quotaBody, "req-3").failMe()
        }
        assertEquals(HttpStatus.CONFLICT, quota.code)
    }

    @Test
    fun `ApiException parsedDetail reads the envelope detail`() {
        val ex = ApiException(
            HttpStatus.CONFLICT,
            """{"detail":"session_quota_exceeded: 5/5"}""",
            "req-9",
        )
        assertEquals("session_quota_exceeded: 5/5", ex.parsedDetail)
        assertEquals("""{"detail":"session_quota_exceeded: 5/5"}""", ex.body)
    }

    @Test
    fun `no backend configured is an IOException, not a bare path`() {
        // OkHttp throws IllegalArgumentException on "/v1/me"; callers only
        // expect IOException, so a blank base must never reach it (TD-90).
        val ex = assertThrows(CloudNotConfiguredException::class.java) {
            SemperApiHttp.endpoint("", "/v1/me")
        }
        assertTrue("callers catch IOException", java.io.IOException::class.java.isInstance(ex))
        assertThrows(CloudNotConfiguredException::class.java) { SemperApiHttp.endpoint("  ", "") }
    }

    @Test
    fun `a configured backend joins base and path`() {
        val base = "https://api.example.invalid"
        assertEquals("$base/v1/me", SemperApiHttp.endpoint(base, "/v1/me"))
        assertEquals(base, SemperApiHttp.endpoint(base, ""))
    }

    private fun response(requestId: String?): Response =
        Response.Builder()
            .request(Request.Builder().url("https://example.invalid/v1/sessions").build())
            .protocol(Protocol.HTTP_1_1)
            .code(HttpStatus.CONFLICT)
            .message("Conflict")
            .apply { requestId?.let { header("X-Request-Id", it) } }
            .build()
}
