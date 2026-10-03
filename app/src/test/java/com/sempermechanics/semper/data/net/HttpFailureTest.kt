package com.sempermechanics.semper.data.net

import com.sempermechanics.semper.data.net.HttpFailure.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException

class HttpFailureTest {

    private fun kind(e: Throwable) = HttpFailure.classify(e).kind

    @Test
    fun `the aliases are the nested classes`() {
        val e: SemperApi.ApiException = ApiException(500, "boom", "req-1")
        assertTrue(e is ApiException)
        assertSame(SemperApi.NotApprovedException::class.java, NotApprovedException::class.java)
        assertSame(SemperApi.CloudNotConfiguredException::class.java, CloudNotConfiguredException::class.java)
        assertSame(SemperApi.TermsVersionMismatchException::class.java, TermsVersionMismatchException::class.java)
        assertSame(SemperApi.DeviceConflictException::class.java, DeviceConflictException::class.java)
        assertSame(SemperApi.DeviceInUseException::class.java, DeviceInUseException::class.java)
        assertSame(SemperApi.NoSeatAvailableException::class.java, NoSeatAvailableException::class.java)
        assertSame(SemperApi.DeviceNotActiveException::class.java, DeviceNotActiveException::class.java)
    }

    @Test
    fun `the specific failures win over their IOException supertype`() {
        assertEquals(Kind.NOT_APPROVED, kind(NotApprovedException()))
        assertEquals(Kind.DEVICE_CONFLICT, kind(DeviceConflictException("r")))
        assertEquals(Kind.DEVICE_IN_USE, kind(DeviceInUseException()))
        assertEquals(Kind.DEVICE_NOT_ACTIVE, kind(DeviceNotActiveException()))
        assertEquals(Kind.NO_SEAT, kind(NoSeatAvailableException()))
        assertEquals(Kind.TERMS_MISMATCH, kind(TermsVersionMismatchException()))
    }

    @Test
    fun `an ApiException is classified by its status`() {
        assertEquals(Kind.UNAUTHORIZED, kind(ApiException(401, "")))
        assertEquals(Kind.FORBIDDEN, kind(ApiException(403, "")))
        assertEquals(Kind.NOT_FOUND, kind(ApiException(404, "")))
        assertEquals(Kind.CONFLICT, kind(ApiException(409, """{"detail":"session_not_complete"}""")))
        assertEquals(Kind.RATE_LIMITED, kind(ApiException(429, "")))
        assertEquals(Kind.SERVER, kind(ApiException(500, "")))
        assertEquals(Kind.SERVER, kind(ApiException(503, "")))
        assertEquals(Kind.REJECTED, kind(ApiException(400, "")))
        assertEquals(Kind.REJECTED, kind(ApiException(413, "")))
    }

    @Test
    fun `any other IOException is offline, a missing backend included`() {
        assertEquals(Kind.OFFLINE, kind(IOException("reset")))
        assertEquals(Kind.OFFLINE, kind(SocketTimeoutException()))
        assertEquals(Kind.OFFLINE, kind(CloudNotConfiguredException()))
    }

    @Test
    fun `anything else is unexpected, a stray cancellation included`() {
        assertEquals(Kind.UNEXPECTED, kind(IllegalStateException("Not signed in")))
        assertEquals(Kind.UNEXPECTED, kind(CancellationException("task cancelled")))
    }

    @Test
    fun `status, body and request id come from the failure`() {
        val api = HttpFailure.classify(ApiException(409, """{"detail":"x"}""", "req-9"))
        assertEquals(409, api.code)
        assertEquals("""{"detail":"x"}""", api.body)
        assertEquals("req-9", api.requestId)

        val conflict = HttpFailure.classify(DeviceConflictException("req-2"))
        assertNull(conflict.code)
        assertEquals("", conflict.body)
        assertEquals("req-2", conflict.requestId)
        assertNull(HttpFailure.classify(IOException()).requestId)
    }

    @Test
    fun `retryable is the generic rule - 429, 5xx, no answer`() {
        assertTrue(HttpFailure.classify(ApiException(429, "")).isRetryable)
        assertTrue(HttpFailure.classify(ApiException(502, "")).isRetryable)
        assertTrue(HttpFailure.classify(IOException()).isRetryable)
        assertFalse(HttpFailure.classify(ApiException(404, "")).isRetryable)
        assertFalse(HttpFailure.classify(ApiException(409, "")).isRetryable)
        assertFalse(HttpFailure.classify(DeviceConflictException()).isRetryable)
        assertFalse(HttpFailure.classify(DeviceNotActiveException()).isRetryable)
    }

    @Test
    fun `gone or not ours is the restore workers' rule - 404 and 403`() {
        assertTrue(HttpFailure.classify(ApiException(404, "")).isGoneOrNotOurs)
        assertTrue(HttpFailure.classify(ApiException(403, "")).isGoneOrNotOurs)
        assertFalse(HttpFailure.classify(ApiException(500, "")).isGoneOrNotOurs)
        assertFalse(HttpFailure.classify(NotApprovedException()).isGoneOrNotOurs)
    }
}
