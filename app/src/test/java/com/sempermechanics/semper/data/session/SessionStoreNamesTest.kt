package com.sempermechanics.semper.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.sessionRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * No two analyses on the phone share a name: [SessionStore.save] and
 * [SessionStore.rename] number a name another row has, under the index lock,
 * and an index an earlier build left with clashes is repaired when it is read.
 */
@RunWith(RobolectricTestRunner::class)
class SessionStoreNamesTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ── Saving ──────────────────────────────────────────────────────────────

    @Test
    fun `a new row under a name another row has is numbered`() {
        save(sessionRecord("a", name = "steel_00"))
        save(sessionRecord("b", name = "steel_00"))
        save(sessionRecord("c", name = "steel_00"))

        assertEquals(listOf("steel_00", "steel_00 (2)", "steel_00 (3)"), namesById("a", "b", "c"))
    }

    @Test
    fun `a row saved again under its own name keeps it`() {
        save(sessionRecord("a", name = "steel_00"))
        save(sessionRecord("a", name = "steel_00", createdAt = 9L))

        assertEquals(listOf("steel_00"), namesById("a"))
    }

    @Test
    fun `runs and restores saving at once never share a name`() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val go = CountDownLatch(1)
        repeat(threads) { i ->
            pool.execute {
                go.await()
                save(sessionRecord("r$i", name = "pmma_00"))
            }
        }
        go.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        val names = SessionStore.list(context).map { it.name }
        assertEquals(threads, names.size)
        assertEquals(names.toSet().size, names.size)
        assertTrue("pmma_00" in names)
    }

    @Test
    fun `a restore that lands takes the backup's name, numbered against the rows beside it`() {
        save(sessionRecord("local", name = "pmma_00"))
        // Two placeholders, labelled as Home's restore list labels them.
        save(RestoreStart.newRow(context, "c1", "r1", "pmma_00 (2)", now = 5L))
        save(RestoreStart.newRow(context, "c2", "r2", "pmma_00 (3)", now = 5L))

        // Each restore's metadata.json says "pmma_00"; the second lands first.
        save(sessionRecord("r2", name = "pmma_00", refName = "pmma_00.png", createdAt = 3L))
        save(sessionRecord("r1", name = "pmma_00", refName = "pmma_00.png", createdAt = 2L))

        val names = namesById("local", "r1", "r2")
        assertEquals("pmma_00", names[0])
        assertEquals(3, names.toSet().size)
    }

    // ── Renaming ────────────────────────────────────────────────────────────

    @Test
    fun `a rename to a name another row has is numbered and says so`() {
        save(sessionRecord("a", name = "Beam"))
        save(sessionRecord("b", name = "Plate"))
        save(sessionRecord("c", name = "Beam (2)"))

        assertEquals("Beam (3)", SessionStore.rename(context, "b", "Beam"))
        assertEquals("Beam (3)", SessionStore.get(context, "b")!!.name)
        assertTrue(SessionStore.get(context, "b")!!.renamedByUser)
        val numbered = SessionStore.rename(context, "a", "Beam (2)")
        assertEquals("a numbered name that is taken counts on", "Beam (4)", numbered)
    }

    @Test
    fun `a rename to a free name or its own is kept as typed`() {
        save(sessionRecord("a", name = "Beam"))

        assertEquals("Beam", SessionStore.rename(context, "a", "Beam"))
        assertEquals("Girder", SessionStore.rename(context, "a", "Girder"))
        assertEquals("no row", null, SessionStore.rename(context, "missing", "Girder"))
    }

    // ── An index an earlier build wrote ─────────────────────────────────────

    @Test
    fun `an index with clashes and file-named restores is repaired when read`() {
        val restoredDir = SessionStore.dirFor(context, "restored")
        File(restoredDir, SessionLayout.METADATA_JSON)
            .writeText("""{"name":"steel_24 sweep","specimen":"steel_24.png"}""")
        writeIndex(
            sessionRecord("old", name = "pmma_00", createdAt = 1L),
            sessionRecord("twin", name = "pmma_00", createdAt = 2L),
            sessionRecord("plain", name = "pmma_00.png", refName = "pmma_00.png", createdAt = 3L),
            sessionRecord("restored", name = "steel_24.png", refName = "steel_24.png", sessionDir = restoredDir.path),
            sessionRecord("typed", name = "Beam", createdAt = 4L),
        )

        val names = namesById("old", "twin", "plain", "restored", "typed")

        assertEquals(listOf("pmma_00", "pmma_00 (2)", "pmma_00 (3)", "steel_24 sweep", "Beam"), names)
        val onDisk = File(context.filesDir, "${SessionPaths.SESSIONS_ROOT}/${SessionPaths.INDEX_JSON}").readText()
        val written = Json.decodeFromString<List<SessionRecord>>(onDisk).associate { it.id to it.name }
        assertEquals("the repair is written back", "steel_24 sweep", written["restored"])
        assertEquals("pmma_00 (2)", written["twin"])
        assertEquals("the rows' ids stay", setOf("old", "twin", "plain", "restored", "typed"), written.keys)
    }

    private fun save(record: SessionRecord) {
        assertEquals(SessionStore.UpsertOutcome.SAVED, SessionStore.save(context, record, allowOverLimit = true))
    }

    private fun namesById(vararg ids: String): List<String> {
        val byId = SessionStore.list(context).associateBy { it.id }
        return ids.map { byId.getValue(it).name }
    }

    private fun writeIndex(vararg rows: SessionRecord) {
        val root = File(context.filesDir, SessionPaths.SESSIONS_ROOT).apply { mkdirs() }
        File(root, SessionPaths.INDEX_JSON).writeText(Json.encodeToString(rows.toList()))
    }
}
