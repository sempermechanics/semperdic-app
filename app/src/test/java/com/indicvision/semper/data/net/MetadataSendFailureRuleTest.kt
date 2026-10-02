@file:Suppress("MagicNumber")

package com.indicvision.semper.data.net

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.cloud.FakeCloudApi
import com.indicvision.semper.cloud.FakeTokens
import com.indicvision.semper.data.cloud.SessionMetadataSync
import com.indicvision.semper.data.cloud.SessionMetadataSync.Outcome
import com.indicvision.semper.data.net.HttpFailure.Kind
import com.indicvision.semper.data.session.SessionRecord.SyncState
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.fixtures.sessionRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * The live metadata send's failure rule, per exception, next to the
 * [HttpFailure.Kind] each one classifies as. Pinned so a port of
 * `SessionMetadataSync.put` onto [HttpFailure] maps every kind explicitly:
 * it is not [HttpFailure.isRetryable]. Every [IOException] the send does not
 * name (the device, approval, seat and Terms failures among them) is RETRY,
 * a [DeviceConflictException] is LATER, and an [ApiException] goes by status,
 * with one 409 detail that waits. A failure that is not I/O at all used to
 * propagate and fail the worker; since the send moved onto `authed` it is
 * logged as an error and left for the next reconcile (LATER).
 */
@RunWith(RobolectricTestRunner::class)
class MetadataSendFailureRuleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()

    @Before
    fun setUp() {
        File(context.filesDir, "sessions").deleteRecursively()
        assertTrue(
            SessionStore.upsert(
                context,
                sessionRecord(
                    id = "s1",
                    frameCount = 1,
                    refPath = "/x/ref.png",
                    sessionDir = "/x",
                    defNames = listOf("d1.png"),
                    cloudSessionId = "c1",
                    syncState = SyncState.SYNCED,
                ).copy(metadataStale = true),
            ),
        )
    }

    private fun sendFailing(error: Throwable): Outcome {
        api.onReplaceSessionMetadata = { _, _, _ -> throw error }
        return runBlocking { SessionMetadataSync.send(context, "s1", api, tokens) }
    }

    private class Case(val error: Throwable, val kind: Kind, val outcome: Outcome)

    private val cases = listOf(
        Case(IOException("reset"), Kind.OFFLINE, Outcome.RETRY),
        Case(SocketTimeoutException(), Kind.OFFLINE, Outcome.RETRY),
        Case(CloudNotConfiguredException(), Kind.OFFLINE, Outcome.RETRY),
        // failSigned throws these two on a 409 from PUT /metadata; the send retries them.
        Case(DeviceNotActiveException("r1"), Kind.DEVICE_NOT_ACTIVE, Outcome.RETRY),
        Case(DeviceInUseException("r2"), Kind.DEVICE_IN_USE, Outcome.RETRY),
        Case(NotApprovedException(), Kind.NOT_APPROVED, Outcome.RETRY),
        Case(NoSeatAvailableException(), Kind.NO_SEAT, Outcome.RETRY),
        Case(TermsVersionMismatchException(), Kind.TERMS_MISMATCH, Outcome.RETRY),
        Case(DeviceConflictException("r3"), Kind.DEVICE_CONFLICT, Outcome.LATER),
        Case(ApiException(429, ""), Kind.RATE_LIMITED, Outcome.RETRY),
        Case(ApiException(500, ""), Kind.SERVER, Outcome.RETRY),
        Case(ApiException(503, "{}"), Kind.SERVER, Outcome.RETRY),
        Case(ApiException(409, """{"detail":"session_not_complete"}"""), Kind.CONFLICT, Outcome.WAIT),
        Case(ApiException(409, """{"detail":"other"}"""), Kind.CONFLICT, Outcome.LATER),
        Case(ApiException(401, ""), Kind.UNAUTHORIZED, Outcome.LATER),
        Case(ApiException(403, ""), Kind.FORBIDDEN, Outcome.LATER),
        Case(ApiException(404, ""), Kind.NOT_FOUND, Outcome.LATER),
        Case(ApiException(400, ""), Kind.REJECTED, Outcome.LATER),
    )

    @Test
    fun `each failure's outcome in the live send, beside its kind`() {
        for (case in cases) {
            val name = case.error.javaClass.simpleName + " " + case.error.message
            assertEquals(name, case.kind, HttpFailure.classify(case.error).kind)
            assertEquals(name, case.outcome, sendFailing(case.error))
            assertTrue(name, SessionStore.get(context, "s1")!!.metadataStale)
        }
    }

    @Test
    fun `isRetryable is not the send's rule`() {
        val differ = cases.filter { HttpFailure.classify(it.error).isRetryable != (it.outcome == Outcome.RETRY) }
        assertEquals(
            setOf(Kind.DEVICE_NOT_ACTIVE, Kind.DEVICE_IN_USE, Kind.NOT_APPROVED, Kind.NO_SEAT, Kind.TERMS_MISMATCH),
            differ.map { it.kind }.toSet(),
        )
    }

    @Test
    fun `a failure that is not an IOException is logged as an error and left for the next reconcile`() {
        val boom = IllegalStateException("Not signed in")
        assertEquals(Kind.UNEXPECTED, HttpFailure.classify(boom).kind)

        val errors = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == Log.ERROR) errors += message
            }
        }
        Timber.plant(tree)
        try {
            assertEquals(Outcome.LATER, sendFailing(boom))
        } finally {
            Timber.uproot(tree)
        }
        assertTrue(errors.toString(), errors.any { it.startsWith("Metadata for s1 not sent") })
        assertTrue(SessionStore.get(context, "s1")!!.metadataStale)
    }
}
