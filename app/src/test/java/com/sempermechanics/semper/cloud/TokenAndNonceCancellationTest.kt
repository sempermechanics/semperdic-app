package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.data.net.isClientNonceRefusal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Two small paths that used to swallow what they should pass on: a token read
 * cancelled with its worker (read as "signed out"), and a nonce-refusal check
 * whose body read failed (leaking the response).
 */
@RunWith(RobolectricTestRunner::class) // ApiErrors reads the body with Android's org.json
class TokenAndNonceCancellationTest {

    @Test
    fun `a cancelled token read is rethrown, not reported as signed out`() = runBlocking {
        var outcome = "not run"
        launch(start = CoroutineStart.UNDISPATCHED) {
            outcome = try {
                val token = TokenProvider.tokenOrNull {
                    cancel() // the worker is stopped mid-read
                    throw CancellationException("worker stopped")
                }
                "token $token"
            } catch (_: CancellationException) {
                "rethrown"
            }
        }
        assertEquals("rethrown", outcome)
    }

    @Test
    fun `a failed token read is null`() = runBlocking {
        assertNull(TokenProvider.tokenOrNull { throw IllegalStateException("no network") })
        // A cancelled Firebase task while the caller is still active is a failure, too.
        assertNull(TokenProvider.tokenOrNull { throw CancellationException("task cancelled") })
        assertEquals("tok", TokenProvider.tokenOrNull { "tok" })
    }

    private fun response(code: Int, body: ResponseBody): Response = Response.Builder()
        .request(Request.Builder().url("https://api.test/v1/sessions").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .body(body)
        .build()

    /** A body whose read fails mid-stream, recording whether it was closed. */
    private class ResetBody : ResponseBody() {
        var closed = false
        private val source = object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("connection reset")

            override fun close() {
                closed = true
            }
        }.buffer()

        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = source
    }

    @Test
    fun `a nonce check whose body read fails closes the response`() {
        val body = ResetBody()
        try {
            isClientNonceRefusal(response(401, body))
            fail("the read failure must reach the caller")
        } catch (_: IOException) {
            // expected
        }
        assertTrue("the response the caller never got back must be closed", body.closed)
    }

    @Test
    fun `a nonce refusal is read without consuming the body`() {
        val refusal = """{"detail":"nonce_invalid_or_replayed"}"""
        val resp = response(401, refusal.toResponseBody())
        assertTrue(isClientNonceRefusal(resp))
        assertEquals(refusal, resp.body.string())

        assertFalse(isClientNonceRefusal(response(401, """{"detail":"bad_signature"}""".toResponseBody())))
        // Only a 401 is read at all.
        assertFalse(isClientNonceRefusal(response(200, ResetBody())))
    }
}
