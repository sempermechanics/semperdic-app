package com.sempermechanics.semper.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A truncated [SessionStore] index must never be treated as empty and overwritten
 * with a one-record file — that was silent total loss of session metadata.
 */
@RunWith(RobolectricTestRunner::class)
class SessionStoreAtomicTest {

    @get:Rule
    val clean = CleanAppState()

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    private fun record(id: String, createdAt: Long = 1L) = sessionRecord(
        id = id,
        createdAt = createdAt,
        refPath = "ref.png",
        sessionDir = "/dir/$id",
    )

    private fun indexFile(): File =
        File(File(ctx.filesDir, "sessions"), "index.json")

    private fun bakFile(): File =
        File(File(ctx.filesDir, "sessions"), "index.json.bak")

    @Test
    fun `truncated index is not overwritten and prior sessions survive via bak`() {
        assertTrue(SessionStore.upsert(ctx, record("a", 1)))
        assertTrue(SessionStore.upsert(ctx, record("b", 2)))
        // Third write promotes [a,b] into .bak (bak always holds the prior good file).
        assertTrue(SessionStore.upsert(ctx, record("c", 3)))
        assertEquals(3, SessionStore.list(ctx).size)

        // Crash mid-write: leave a truncated primary; bak still has a+b.
        indexFile().writeText("[{\"id\":\"a\"")
        val listed = SessionStore.list(ctx)
        assertEquals(setOf("a", "b"), listed.map { it.id }.toSet())
        assertFalse(SessionStore.isIndexCorrupt())

        // Both primary and bak unreadable → refuse mutations (no one-record clobber).
        indexFile().writeText("{truncated")
        bakFile().writeText("{also-bad")

        assertEquals(emptyList<SessionRecord>(), SessionStore.list(ctx))
        assertTrue(SessionStore.isIndexCorrupt())
        assertFalse(
            "corrupt index must not be replaced with a single new record",
            SessionStore.upsert(ctx, record("d", 4)),
        )
        assertTrue(indexFile().readText().startsWith("{truncated"))
    }

    @Test
    fun `list restores from bak when primary is truncated`() {
        assertTrue(SessionStore.upsert(ctx, record("a", 1)))
        assertTrue(SessionStore.upsert(ctx, record("b", 2)))
        assertTrue(SessionStore.upsert(ctx, record("c", 3)))
        indexFile().writeText("[")

        val listed = SessionStore.list(ctx)
        assertEquals(setOf("a", "b"), listed.map { it.id }.toSet())
        assertFalse(SessionStore.isIndexCorrupt())
        assertEquals(2, SessionStore.list(ctx).size)
    }

    @Test
    fun `atomic write leaves a bak of the prior good index`() {
        assertTrue(SessionStore.upsert(ctx, record("a", 1)))
        val afterFirst = indexFile().readText()
        assertTrue(SessionStore.upsert(ctx, record("b", 2)))
        assertTrue(bakFile().exists())
        assertEquals(afterFirst, bakFile().readText())
    }

    @Test
    fun `older index JSON missing newer fields still loads`() {
        // Pre-sweep / pre-sync fields: only the original required keys.
        indexFile().parentFile?.mkdirs()
        indexFile().writeText(
            """
            [{
              "id":"legacy-1",
              "name":"Legacy",
              "createdAt":10,
              "updatedAt":10,
              "frameCount":2,
              "subset":41,
              "step":5,
              "strainWindow":15,
              "imgW":100,
              "imgH":100,
              "roiX":0,
              "roiY":0,
              "roiW":100,
              "roiH":100,
              "refPath":"ref.png",
              "refName":"ref.png",
              "sessionDir":"/dir/legacy-1"
            }]
            """.trimIndent(),
        )

        val listed = SessionStore.list(ctx)
        assertEquals(1, listed.size)
        assertEquals("legacy-1", listed[0].id)
        assertEquals(emptyList<Int>(), listed[0].sweepSubsets)
        assertEquals("", listed[0].cloudSessionId)
        assertEquals(SessionRecord.SyncState.LOCAL_ONLY, listed[0].syncState)

        // Rewrite must preserve required identity after an upgrade touch.
        assertTrue(SessionStore.upsert(ctx, listed[0].copy(name = "Legacy renamed")))
        assertEquals("Legacy renamed", SessionStore.get(ctx, "legacy-1")?.name)
    }

    @Test
    fun `save says why a row was not written`() {
        assertEquals(SessionStore.UpsertResult.SAVED, SessionStore.save(ctx, record("a", 1)))

        indexFile().writeText("{truncated")
        bakFile().writeText("{also-bad")

        assertEquals(SessionStore.UpsertResult.INDEX_UNAVAILABLE, SessionStore.save(ctx, record("b", 2)))
    }

    @Test
    fun `save refuses a new row at a full quota but still updates an existing one`() {
        try {
            AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 1, maxFilesPerSession = 600, maxFrames = 150))
            assertEquals(SessionStore.UpsertResult.SAVED, SessionStore.save(ctx, record("a", 1)))
            TokenStore.setQuota(ctx, used = 1)

            assertEquals(SessionStore.UpsertResult.QUOTA_FULL, SessionStore.save(ctx, record("b", 2)))
            assertEquals(null, SessionStore.get(ctx, "b"))
            assertEquals(SessionStore.UpsertResult.SAVED, SessionStore.save(ctx, record("a", 3)))
            assertEquals(
                SessionStore.UpsertResult.SAVED,
                SessionStore.save(ctx, record("c", 4), allowOverLimit = true),
            )
        } finally {
            TokenStore.clear(ctx)
        }
    }

    @Test
    fun `update changes only its own row and refuses a corrupt index`() {
        assertTrue(SessionStore.upsert(ctx, record("a", 1)))
        assertTrue(SessionStore.upsert(ctx, record("b", 2)))

        assertTrue(SessionStore.update(ctx, "a") { it.copy(cloudSessionId = "c-a") })

        assertEquals("c-a", SessionStore.get(ctx, "a")?.cloudSessionId)
        assertEquals("", SessionStore.get(ctx, "b")?.cloudSessionId)

        indexFile().writeText("{truncated")
        bakFile().writeText("{also-bad")
        assertFalse(SessionStore.update(ctx, "a") { it.copy(name = "x") })
        assertTrue(indexFile().readText().startsWith("{truncated"))
    }
}
