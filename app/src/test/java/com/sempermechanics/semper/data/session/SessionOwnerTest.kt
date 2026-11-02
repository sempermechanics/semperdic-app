package com.sempermechanics.semper.data.session

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.data.account.AccountSwitch
import com.sempermechanics.semper.data.cloud.CloudReconcile
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.SessionMetadataSync
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.session.SessionRecord.SyncState
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A phone two accounts share (L4-2, owner decision 2026-11-01): account A's
 * analyses are hidden while B is signed in, never counted, reconciled or
 * queued for B, and back when A signs in again. Rows from before owners are
 * adopted once, by the first account that is the owner.
 */
@RunWith(RobolectricTestRunner::class)
class SessionOwnerTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val queued = mutableListOf<String>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        CloudSync.queueUpload = { _, id -> queued += id }
        CloudSync.queueMetadata = { _, id -> queued += "meta:$id" }
    }

    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        CloudSync.queueUpload = CloudSync::enqueueUpload
        CloudSync.queueMetadata = SessionMetadataSync::enqueue
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun signIn(uid: String) = AccountSwitch.signedIn(context, uid, "$uid@example.com")

    private fun save(id: String, state: SyncState = SyncState.SYNCED) =
        assertEquals(
            SessionStore.UpsertOutcome.SAVED,
            SessionStore.save(context, sessionRecord(id = id, syncState = state, createdAt = id.hashCode().toLong())),
        )

    private fun ids() = SessionStore.list(context).map { it.id }.sorted()

    private fun indexText() =
        File(context.filesDir, "${SessionPaths.SESSIONS_ROOT}/${SessionPaths.INDEX_JSON}").readText()

    @Test
    fun `account A's analyses are hidden from B and back for A`() {
        signIn(UID_A)
        save("a1")
        save("a2")

        signIn(UID_B)
        assertEquals(emptyList<String>(), ids())
        assertNull(SessionStore.get(context, "a1"))
        save("b1")
        assertEquals(listOf("b1"), ids())
        assertEquals(1, AccountCache.localCount(context))

        signIn(UID_A)
        assertEquals(listOf("a1", "a2"), ids())
        assertEquals(2, AccountCache.localCount(context))
        // Nothing was lost while hidden: the index keeps every account's rows.
        assertTrue(indexText().contains("\"ownerUid\":\"$UID_B\""))
    }

    @Test
    fun `B's quota counts only B's analyses`() {
        signIn(UID_A)
        repeat(3) { save("a$it") }

        signIn(UID_B)
        save("b1")
        SessionStore.delete(context, "b1")

        assertEquals(0, AccountCache.localCount(context))
        assertEquals(0, AccountCache.quotaUsed(context))
    }

    @Test
    fun `reconcile never repairs or queues another account's rows`() {
        signIn(UID_A)
        save("a-synced", SyncState.SYNCED)
        save("a-pending", SyncState.PENDING)

        signIn(UID_B)
        save("b-synced", SyncState.SYNCED)
        // B's cloud holds none of these: only B's own row may be repaired.
        val repaired = CloudReconcile.repairRows(
            context,
            backedUp = emptySet(),
            keep = emptySet(),
            reupload = true,
            requeue = true,
        )

        assertEquals(1, repaired)
        assertEquals(listOf("b-synced"), queued)
        signIn(UID_A)
        assertEquals(
            mapOf("a-pending" to SyncState.PENDING, "a-synced" to SyncState.SYNCED),
            SessionStore.list(context).associate { it.id to it.syncState },
        )
    }

    @Test
    fun `rows from before owners are adopted once, by the first account`() {
        // An older build's rows: no owner, so none in the file.
        SessionStore.save(context, sessionRecord(id = "old1"))
        SessionStore.save(context, sessionRecord(id = "old2"))
        assertFalse(indexText().contains("ownerUid"))

        signIn(UID_A)
        assertEquals(listOf("old1", "old2"), ids())
        assertTrue(indexText().contains("\"ownerUid\":\"$UID_A\""))

        signIn(UID_B)
        assertEquals(emptyList<String>(), ids())
    }

    @Test
    fun `signed out, the phone keeps the last account's analyses as its own`() {
        signIn(UID_A)
        save("a1")

        AccountSwitch.clearLocalState(context)
        // A run that finishes after the sign-out still lands with A.
        save("a2")

        assertEquals(listOf("a1", "a2"), ids())
        signIn(UID_B)
        assertEquals(emptyList<String>(), ids())
    }

    @Test
    fun `an update keeps the row's owner`() {
        signIn(UID_A)
        save("a1")

        SessionStore.update(context, "a1") { it.copy(name = "renamed") }
        SessionStore.save(context, SessionStore.get(context, "a1")!!.copy(ownerUid = ""))

        assertEquals(UID_A, SessionStore.get(context, "a1")?.ownerUid)
    }

    @Test
    fun `deleting B's local data keeps A's`() {
        signIn(UID_A)
        save("a1")
        SessionStore.dirFor(context, "a1")
        signIn(UID_B)
        save("b1")
        SessionStore.dirFor(context, "b1")

        SessionStore.deleteAll(context)

        assertEquals(emptyList<String>(), ids())
        assertFalse(File(context.filesDir, "${SessionPaths.SESSIONS_ROOT}/b1").exists())
        assertTrue(File(context.filesDir, "${SessionPaths.SESSIONS_ROOT}/a1").exists())
        signIn(UID_A)
        assertEquals(listOf("a1"), ids())
    }

    private companion object {
        const val UID_A = "uid-a"
        const val UID_B = "uid-b"
    }
}
