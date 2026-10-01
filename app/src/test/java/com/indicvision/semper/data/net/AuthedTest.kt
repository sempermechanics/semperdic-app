package com.indicvision.semper.data.net

import com.indicvision.semper.cloud.FakeCloudApi
import com.indicvision.semper.cloud.FakeTokens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class AuthedTest {

    private val api = FakeCloudApi()
    private val tokens = FakeTokens()
    private val listing = ListSessionsResponse(sessions = emptyList(), quota = QuotaDto(used = 2, max = 25))

    @Test
    fun `a disabled backend asks for no token and makes no call`() = runBlocking {
        val off = FakeCloudApi(enabled = false)

        val result = off.authed(tokens) { listSessions(it) }

        assertEquals(Authed.Disabled, result)
        assertEquals(0, tokens.asked)
        assertTrue(off.calls.isEmpty())
    }

    @Test
    fun `no token means no call`() = runBlocking {
        val result = api.authed(FakeTokens(token = null)) { listSessions(it) }

        assertEquals(Authed.NoToken, result)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `the call gets the token and its value comes back`() = runBlocking {
        var seen: String? = null
        api.onListSessions = { token, _ ->
            seen = token
            listing
        }

        val result = api.authed(tokens) { listSessions(it) }

        assertEquals(Authed.Ok(listing), result)
        assertEquals(listing, result.getOrNull())
        assertEquals("tok", seen)
        assertEquals(1, tokens.asked)
    }

    @Test
    fun `a thrown failure is classified, not propagated`() = runBlocking {
        val cases = mapOf(
            NotApprovedException() to HttpFailure.Kind.NOT_APPROVED,
            ApiException(429, "") to HttpFailure.Kind.RATE_LIMITED,
            ApiException(401, "") to HttpFailure.Kind.UNAUTHORIZED,
            IOException("offline") to HttpFailure.Kind.OFFLINE,
            IllegalStateException("bug") to HttpFailure.Kind.UNEXPECTED,
        )
        for ((thrown, kind) in cases) {
            api.onListSessions = { _, _ -> throw thrown }

            val result = api.authed(tokens) { listSessions(it) }

            assertEquals(Authed.Failed(HttpFailure(kind, thrown)), result)
            assertEquals(null, result.getOrNull())
        }
    }

    @Test
    fun `a cancellation that is not the caller's is an ordinary failure`() = runBlocking {
        val taskCancelled = CancellationException("Firebase task cancelled")
        api.onListSessions = { _, _ -> throw taskCancelled }

        val result = api.authed(tokens) { listSessions(it) }

        assertEquals(Authed.Failed(HttpFailure(HttpFailure.Kind.UNEXPECTED, taskCancelled)), result)
    }

    @Test
    fun `a cancelled caller is cancelled, not failed`() = runBlocking {
        val inFlight = CompletableDeferred<Unit>()
        api.onListSessions = { _, _ ->
            inFlight.complete(Unit)
            awaitCancellation()
        }
        val call = async(Dispatchers.Default) { api.authed(tokens) { listSessions(it) } }
        inFlight.await()
        call.cancel()

        val outcome = runCatching { call.await() }

        assertTrue("the caller ends cancelled, got $outcome", outcome.exceptionOrNull() is CancellationException)
    }
}
