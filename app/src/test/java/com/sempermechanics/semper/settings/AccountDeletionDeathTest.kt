package com.sempermechanics.semper.settings

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.cloud.FakeCloudApi
import com.sempermechanics.semper.cloud.FakeTokens
import com.sempermechanics.semper.data.cloud.CloudErase
import com.sempermechanics.semper.data.cloud.CloudErase.accountGone
import com.sempermechanics.semper.data.cloud.CloudErase.toProbe
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.CloudSync.AccountProbe
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.Authed
import com.sempermechanics.semper.data.net.DeviceNotActiveException
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.NotApprovedException
import com.sempermechanics.semper.data.prefs.AccountDeletionMarker
import com.sempermechanics.semper.data.prefs.AccountDeletionMarker.Stage
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.ui.settings.AccountDeletionRun
import com.sempermechanics.semper.ui.settings.AccountDeletionRun.Outcome
import com.sempermechanics.semper.ui.settings.AccountDeletionRun.State
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException

/**
 * TD-165: an account deletion the process did not live to finish. The run's
 * scope dies with the process, so the stage is kept on disk before the erase
 * is sent, and the next start finishes the wipe only when the account is
 * known gone.
 *
 * A "death" here is a run left hanging mid-step, then [AccountDeletionRun.resetForTest]:
 * memory goes back to a fresh process's, the marker on disk stays.
 */
@RunWith(RobolectricTestRunner::class)
class AccountDeletionDeathTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val steps = mutableListOf<String>()

    @Before
    fun setUp() {
        AccountDeletionRun.resetForTest()
        AccountDeletionMarker.clear(app)
        SessionStore.deleteAll(app)
        AccountCache.saveIdentity(app, UID, "user@example.com")
        seams()
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        AccountDeletionRun.resetForTest()
        AccountDeletionMarker.clear(app)
        SessionStore.deleteAll(app)
    }

    /** What every process starts with: the fake backend, and the real sequence minus Firebase. */
    private fun seams() {
        AccountDeletionRun.cloudApi = { api }
        AccountDeletionRun.signOut = { steps += "signOut" }
        AccountDeletionRun.delete = { _, cloud ->
            sequence { CloudErase.eraseAccountInCloud(cloud, FakeTokens()).accountGone }
        }
        AccountDeletionRun.probe = { _, cloud -> CloudErase.probeErasedAccount(cloud, FakeTokens()) }
        AccountDeletionRun.finish = { _, _ -> sequence { true } }
    }

    /** [CloudErase.deleteAccount] with the local half real and Firebase recorded. */
    private suspend fun sequence(eraseCloud: suspend () -> Boolean) = CloudErase.deleteAccount(
        eraseCloud = eraseCloud,
        deleteIdentity = {
            steps += "identity"
            true
        },
        wipeLocal = {
            steps += "wipe"
            SessionStore.deleteAll(app)
        },
        signOut = { steps += "signOut" },
    )

    /** The process dies: everything in memory is a new process's, the disk is kept. */
    private fun die() {
        AccountDeletionRun.resetForTest()
        steps.clear()
        seams()
    }

    private fun seedAnalysis() {
        val id = "s1"
        val record = SessionRecord(
            id = id, name = id, createdAt = 1L, updatedAt = 1L, frameCount = 1,
            subset = 41, step = 5, strainWindow = 15,
            imgW = 100, imgH = 100, roiX = 0, roiY = 0, roiW = 100, roiH = 100,
            refPath = "", refName = "ref.png", sessionDir = SessionStore.dirFor(app, id).absolutePath,
        )
        assertTrue(SessionStore.upsert(app, record))
        assertEquals(1, SessionStore.list(app).size)
    }

    private fun runDeletion(): Outcome? {
        assertTrue(AccountDeletionRun.start(app))
        idleUntil("the deletion to end") { AccountDeletionRun.state.value !is State.Running }
        return (AccountDeletionRun.state.value as? State.Done)?.outcome
    }

    /**
     * Starts a deletion that never gets past the erase's answer, as a process
     * killed there. Whether the server erased is up to the probe at the next start.
     */
    private fun startAndDieDuringErase() {
        val sent = CompletableDeferred<Unit>()
        api.onDeleteAccount = {
            sent.complete(Unit)
            awaitCancellation()
        }
        assertTrue(AccountDeletionRun.start(app))
        idleUntil("the erase to be sent") { sent.isCompleted }
        die()
    }

    /** Starts a deletion whose erase answered, then dies before the wipe. */
    private fun startAndDieAfterErase() {
        val erased = CompletableDeferred<Unit>()
        api.onDeleteAccount = {}
        AccountDeletionRun.delete = { _, cloud ->
            cloud.deleteAccount("tok")
            erased.complete(Unit)
            awaitCancellation()
        }
        assertTrue(AccountDeletionRun.start(app))
        idleUntil("the erase to answer") { erased.isCompleted }
        die()
    }

    private fun resume(): State {
        AccountDeletionRun.resumeInterrupted(app)
        idleUntil("the finish to settle") { !AccountDeletionRun.isResuming() }
        return AccountDeletionRun.state.value
    }

    // ── While the deletion runs ────────────────────────────────────────────

    @Test
    fun `the marker is on disk before the erase is sent`() {
        var atSend: AccountDeletionMarker.Owed? = null
        api.onDeleteAccount = { atSend = AccountDeletionMarker.read(app) }

        assertEquals(Outcome.DELETED, runDeletion())

        assertEquals(AccountDeletionMarker.Owed(Stage.REQUESTED, UID), atSend)
    }

    @Test
    fun `a finished deletion leaves no marker`() {
        api.onDeleteAccount = {}

        assertEquals(Outcome.DELETED, runDeletion())

        assertNull(AccountDeletionMarker.read(app))
        assertEquals(listOf("identity", "wipe", "signOut"), steps)
    }

    @Test
    fun `an erase the backend refused leaves no marker`() {
        api.onDeleteAccount = { throw ApiException(401, """{"detail":"invalid_token"}""") }

        assertEquals(Outcome.CLOUD_NOT_REACHED, runDeletion())

        assertNull(AccountDeletionMarker.read(app))
    }

    @Test
    fun `an erase with no answer keeps the marker for the next start to ask`() {
        api.onDeleteAccount = { throw IOException("timeout") }

        assertEquals(Outcome.CLOUD_NOT_REACHED, runDeletion())

        assertEquals(Stage.REQUESTED, AccountDeletionMarker.read(app)?.stage)
    }

    @Test
    fun `a wipe that throws after the erase keeps the marker at erased`() {
        api.onDeleteAccount = {}
        AccountDeletionRun.delete = { _, cloud ->
            cloud.deleteAccount("tok")
            throw IOException("could not delete the session index")
        }

        assertEquals(Outcome.PHONE_NOT_CLEARED, runDeletion())

        assertEquals(Stage.ERASED, AccountDeletionMarker.read(app)?.stage)
    }

    // ── The next start ─────────────────────────────────────────────────────

    @Test
    fun `nothing owed, nothing done`() {
        AccountDeletionRun.probe = { _, _ -> error("must not ask") }
        AccountDeletionRun.finish = { _, _ -> error("must not finish") }

        assertEquals(State.Idle, resume())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `death after the erase answered is finished at the next start without asking`() {
        seedAnalysis()
        startAndDieAfterErase()
        assertEquals(Stage.ERASED, AccountDeletionMarker.read(app)?.stage)
        AccountDeletionRun.probe = { _, _ -> error("an answered erase needs no probe") }

        assertEquals(State.Done(Outcome.DELETED), resume())

        assertTrue("the phone copy is wiped", SessionStore.list(app).isEmpty())
        assertEquals(listOf("identity", "wipe", "signOut"), steps)
        assertNull(AccountDeletionMarker.read(app))
    }

    @Test
    fun `death before the answer, account gone, is wiped and signed out`() {
        for (gone in listOf(DeviceNotActiveException(), ApiException(403, """{"detail":"not_approved"}"""))) {
            seedAnalysis()
            startAndDieDuringErase()
            assertEquals(Stage.REQUESTED, AccountDeletionMarker.read(app)?.stage)
            api.onListSessionUploads = { _, sid ->
                assertEquals(CloudErase.PROBE_SESSION_ID, sid)
                throw gone
            }

            assertEquals(State.Done(Outcome.DELETED), resume())

            assertTrue("the phone copy is wiped after $gone", SessionStore.list(app).isEmpty())
            assertEquals(listOf("identity", "wipe", "signOut"), steps)
            assertNull(AccountDeletionMarker.read(app))
            AccountDeletionRun.consume()
        }
    }

    @Test
    fun `death before the answer, account still there, touches nothing and forgets`() {
        seedAnalysis()
        startAndDieDuringErase()
        api.onListSessionUploads = { _, _ -> throw ApiException(404, """{"detail":"session_not_found"}""") }

        assertEquals(State.Idle, resume())

        assertEquals("the phone copy stays", 1, SessionStore.list(app).size)
        assertTrue("nothing signed out", steps.isEmpty())
        assertNull(AccountDeletionMarker.read(app))
    }

    @Test
    fun `no answer at the next start keeps everything for the start after`() {
        seedAnalysis()
        startAndDieDuringErase()
        api.onListSessionUploads = { _, _ -> throw IOException("offline") }

        assertEquals(State.Idle, resume())

        assertEquals(1, SessionStore.list(app).size)
        assertTrue(steps.isEmpty())
        assertEquals(AccountDeletionMarker.Owed(Stage.REQUESTED, UID), AccountDeletionMarker.read(app))

        // The start after has the network back.
        die()
        api.onListSessionUploads = { _, _ -> throw DeviceNotActiveException() }
        assertEquals(State.Done(Outcome.DELETED), resume())
        assertTrue(SessionStore.list(app).isEmpty())
    }

    @Test
    fun `another account signed in drops the marker without asking`() {
        seedAnalysis()
        startAndDieDuringErase()
        AccountCache.saveIdentity(app, "someone-else", "other@example.com")

        assertEquals(State.Idle, resume())

        assertFalse("the backend is not asked", "listSessionUploads" in api.calls)
        assertEquals(1, SessionStore.list(app).size)
        assertNull(AccountDeletionMarker.read(app))
    }

    @Test
    fun `another account signed in since an answered erase keeps its session and analyses`() {
        startAndDieAfterErase()
        AccountCache.saveIdentity(app, "someone-else", "other@example.com")
        seedAnalysis()

        assertEquals(State.Idle, resume())

        assertEquals(1, SessionStore.list(app).size)
        assertTrue("nothing signed out", steps.isEmpty())
        assertNull(AccountDeletionMarker.read(app))
    }

    @Test
    fun `signed out, an answered erase is still wiped`() {
        seedAnalysis()
        startAndDieAfterErase()
        AccountCache.saveIdentity(app, null, null)

        assertEquals(State.Done(Outcome.DELETED), resume())

        assertTrue(SessionStore.list(app).isEmpty())
        assertNull(AccountDeletionMarker.read(app))
    }

    @Test
    fun `signed out, an unanswered erase waits for a later start`() {
        startAndDieDuringErase()
        AccountCache.saveIdentity(app, null, null)

        assertEquals(State.Idle, resume())

        assertFalse("listSessionUploads" in api.calls)
        assertEquals(Stage.REQUESTED, AccountDeletionMarker.read(app)?.stage)
    }

    @Test
    fun `a wipe that fails at the next start signs out and keeps the marker`() {
        seedAnalysis()
        startAndDieAfterErase()
        AccountDeletionRun.finish = { _, _ -> throw IOException("disk") }

        assertEquals(State.Done(Outcome.PHONE_NOT_CLEARED), resume())

        assertEquals(listOf("signOut"), steps)
        assertEquals(Stage.ERASED, AccountDeletionMarker.read(app)?.stage)
    }

    @Test
    fun `a probe that says gone is recorded, so a death during the wipe needs no second probe`() {
        startAndDieDuringErase()
        val finishing = CompletableDeferred<Unit>()
        api.onListSessionUploads = { _, _ -> throw DeviceNotActiveException() }
        AccountDeletionRun.finish = { _, _ ->
            finishing.complete(Unit)
            awaitCancellation()
        }
        AccountDeletionRun.resumeInterrupted(app)
        idleUntil("the finish to start") { finishing.isCompleted }

        assertEquals(Stage.ERASED, AccountDeletionMarker.read(app)?.stage)
    }

    @Test
    fun `no second deletion starts while the finish runs`() {
        startAndDieAfterErase()
        val release = CompletableDeferred<Unit>()
        AccountDeletionRun.finish = { _, _ ->
            release.await()
            CloudSync.AccountDeletion.DELETED
        }
        AccountDeletionRun.resumeInterrupted(app)
        idleUntil("the finish to run") { AccountDeletionRun.state.value == State.Running }

        assertFalse("Settings cannot start another deletion", AccountDeletionRun.start(app))

        release.complete(Unit)
        idleUntil("the finish to end") { AccountDeletionRun.state.value is State.Done }
        assertEquals(Outcome.DELETED, AccountDeletionRun.consume())
    }

    // ── What the probe's answer means ──────────────────────────────────────

    @Test
    fun `the probe reads only a device or approval refusal as gone`() {
        fun failed(e: Exception): Authed<Unit> = Authed.Failed(HttpFailure.classify(e))
        val cases = mapOf(
            failed(DeviceNotActiveException()) to AccountProbe.GONE,
            failed(NotApprovedException()) to AccountProbe.GONE,
            failed(ApiException(403, """{"detail":"not_approved"}""")) to AccountProbe.GONE,
            failed(ApiException(404, """{"detail":"session_not_found"}""")) to AccountProbe.STILL_THERE,
            Authed.Ok(Unit) to AccountProbe.STILL_THERE,
            Authed.Disabled to AccountProbe.STILL_THERE,
            Authed.NoToken to AccountProbe.UNKNOWN,
            failed(IOException("offline")) to AccountProbe.UNKNOWN,
            failed(ApiException(503, "")) to AccountProbe.UNKNOWN,
            failed(ApiException(429, "")) to AccountProbe.UNKNOWN,
            failed(ApiException(401, """{"detail":"invalid_token"}""")) to AccountProbe.UNKNOWN,
            failed(ApiException(403, """{"detail":"app_check_required"}""")) to AccountProbe.UNKNOWN,
            failed(ApiException(404, "Not Found")) to AccountProbe.UNKNOWN,
        )
        for ((answer, probe) in cases) assertEquals("$answer", probe, answer.toProbe())
    }

    @Test
    fun `the probe sends one device-signed read for a session nobody has`() {
        val probed = mutableListOf<String>()
        api.onListSessionUploads = { _, sid ->
            probed += sid
            throw DeviceNotActiveException()
        }

        val probe = runBlocking { CloudErase.probeErasedAccount(api, FakeTokens()) }

        assertEquals(AccountProbe.GONE, probe)
        assertEquals(listOf(CloudErase.PROBE_SESSION_ID), probed)
    }

    private companion object {
        const val UID = "uid-1"
    }
}
