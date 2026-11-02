package com.sempermechanics.semper.auth

import com.sempermechanics.semper.data.account.AccessStatus
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.ui.auth.StatusRecheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.security.KeyStoreException

/**
 * [StatusRecheck.decide]: the background check after a launch from cache.
 *
 * The user is already on Home when this answers, so only a definitive answer
 * may move them. A timeout or a 5xx must leave them where they are.
 */
class StatusRecheckTest {

    @Test
    fun `still approved stays on Home`() {
        assertNull(StatusRecheck.decide(Result.success(AccessStatus.APPROVED)))
        assertNull(StatusRecheck.decide(Result.success(AccessStatus.OFFLINE_CACHE_APPROVED)))
    }

    @Test
    fun `revoked or suspended goes to pending`() {
        assertEquals(
            StatusRecheck.Reroute.Pending,
            StatusRecheck.decide(Result.success(AccessStatus.PENDING)),
        )
    }

    @Test
    fun `refused sign-in goes back to sign-in with the reason`() {
        val result = Result.failure<String>(AuthRepository.AccessLostException("Session expired."))
        assertEquals(StatusRecheck.Reroute.SignIn("Session expired."), StatusRecheck.decide(result))
    }

    @Test
    fun `no network or a server error stays on Home`() {
        assertNull(StatusRecheck.decide(Result.failure(IOException("timeout"))))
        assertNull(StatusRecheck.decide(Result.failure(IllegalStateException("HTTP 503"))))
    }

    @Test
    fun `a check that throws is no answer, not a crash at launch`() = runBlocking {
        // Before: only IOException became a result; these escaped the bare
        // scope and took the app down while the user was already on Home.
        assertNull(StatusRecheck.check { throw KeyStoreException("Keystore unavailable") })
        assertNull(StatusRecheck.check { throw SerializationException("bad /v1/me body") })
        assertNull(StatusRecheck.check { throw IllegalStateException("unexpected") })
    }

    @Test
    fun `a check that answers is decided as before`() = runBlocking {
        assertEquals(StatusRecheck.Reroute.Pending, StatusRecheck.check { Result.success(AccessStatus.PENDING) })
        assertNull(StatusRecheck.check { Result.success(AccessStatus.APPROVED) })
    }

    @Test
    fun `cancellation of the check is rethrown`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { StatusRecheck.check { throw CancellationException("splash gone") } }
        }
    }
}
