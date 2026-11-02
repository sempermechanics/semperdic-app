package com.sempermechanics.semper.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A run the process died in left `files/sessions/<id>` with its `run_staging/`
 * frames and no index row, and nothing ever reclaimed it: 12 MB per 800 x 600
 * frame (readiness TE1-101). The startup sweep now removes such a folder, and
 * only such a folder: an indexed analysis, a folder holding a record manifest,
 * one without `run_staging/`, one written since the process started, and
 * anything at all while the index does not parse, are kept.
 */
@RunWith(RobolectricTestRunner::class)
class DeadRunReclaimTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.filesDir, SessionPaths.SESSIONS_ROOT)

    /** A folder the way a killed run leaves it, every file dated [at]. */
    private fun deadRun(id: String, at: Long = OLD): File {
        val dir = File(root, id)
        File(SessionPaths.runStaging(dir), "frame_0000.dat").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(64))
        File(dir, SessionPaths.RAW_DEFORMED_SUBDIR + "/specimen_01.png")
            .apply { parentFile!!.mkdirs() }
            .writeBytes(ByteArray(32))
        dir.walkBottomUp().forEach { it.setLastModified(at) }
        return dir
    }

    private fun saved(id: String) {
        val dir = deadRun(id)
        SessionStore.save(context, sessionRecord(id, sessionDir = dir.path), allowOverLimit = true)
    }

    @Test
    fun `an unindexed folder a killed run left is reclaimed at startup`() {
        saved("kept")
        val dead = deadRun("dead")

        val freed = SessionStore.reclaimDeadRuns(context, startedAtMs = NOW)

        assertFalse("dead run folder still there", dead.exists())
        assertTrue("reclaimed $freed bytes", freed >= 96)
        assertTrue("indexed analysis kept", File(root, "kept").isDirectory)
    }

    @Test
    fun `the app's startup sweep reclaims it with no index at all`() {
        val dead = deadRun("dead")

        CacheJanitor.sweepOnStartup(context)

        assertFalse(dead.exists())
    }

    @Test
    fun `only a dead run's folder goes`() {
        val manifest = deadRun("manifest")
        File(manifest, SessionLayout.MANIFEST).apply {
            writeText("{}")
            setLastModified(OLD)
        }
        val noStaging = deadRun("nostaging").also { SessionPaths.runStaging(it).deleteRecursively() }
        val fresh = deadRun("fresh", at = NOW + 1_000)

        SessionStore.reclaimDeadRuns(context, startedAtMs = NOW)

        assertTrue("a folder with a record manifest is someone's analysis", manifest.isDirectory)
        assertTrue("a folder without run_staging is not a dead run", noStaging.isDirectory)
        assertTrue("a run started in this process is live", fresh.isDirectory)
    }

    @Test
    fun `nothing is deleted while the index does not parse`() {
        saved("kept")
        val dead = deadRun("dead")
        File(root, SessionPaths.INDEX_JSON).writeText("{ not json")
        File(root, SessionPaths.INDEX_JSON + ".bak").writeText("{ not json either")

        assertEquals(0L, SessionStore.reclaimDeadRuns(context, startedAtMs = NOW))

        assertTrue(dead.isDirectory)
        assertTrue(File(root, "kept").isDirectory)
    }

    /** A folder the way a run that committed but saved nothing leaves it, every file dated [at]. */
    private fun unsavedRun(id: String, at: Long = DAYS_AGO): File {
        val dir = File(root, id)
        File(dir, "frame_0000.dat").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(64))
        File(dir, SessionLayout.MANIFEST).writeText("{}")
        File(dir, SessionPaths.RAW_DEFORMED_SUBDIR + "/specimen_01.png")
            .apply { parentFile!!.mkdirs() }
            .writeBytes(ByteArray(32))
        dir.walkBottomUp().forEach { it.setLastModified(at) }
        return dir
    }

    @Test
    fun `a committed run that saved nothing is reclaimed once no wizard can need it`() {
        saved("kept")
        val unsaved = unsavedRun("unsaved")

        val freed = SessionStore.reclaimUnsavedRuns(context, startedAtMs = NOW, draftLive = false)

        assertFalse("unsaved run folder still there", unsaved.exists())
        assertTrue("reclaimed $freed bytes", freed >= 96)
        assertTrue("indexed analysis kept", File(root, "kept").isDirectory)
    }

    @Test
    fun `the app's startup sweep reclaims an unsaved run with no live draft`() {
        val unsaved = unsavedRun("unsaved")

        CacheJanitor.sweepOnStartup(context)

        assertFalse(unsaved.exists())
    }

    @Test
    fun `an unsaved run is kept while a draft lives, while recent, and when a saving run reached it`() {
        val whileDraft = unsavedRun("draft")
        assertEquals(0L, SessionStore.reclaimUnsavedRuns(context, startedAtMs = NOW, draftLive = true))
        assertTrue("a restored wizard may read its frames", whileDraft.isDirectory)

        val recent = unsavedRun("recent", at = OLD)
        // What only a run that saves writes: an analysis whose row an index restored from .bak lost.
        val thumbnail = unsavedRun("thumbnail").also {
            File(it, SessionLayout.REFERENCE_PNG).apply { writeBytes(ByteArray(8)) }.setLastModified(DAYS_AGO)
        }
        val rerun = unsavedRun("rerun").also {
            SessionLayout(it).rerunInputs.apply { parentFile!!.mkdirs() }.writeText("{}")
            it.walkBottomUp().forEach { f -> f.setLastModified(DAYS_AGO) }
        }
        val backedUp = unsavedRun("backedup").also {
            File(it, SessionLayout.METADATA_JSON).apply { writeText("{}") }.setLastModified(DAYS_AGO)
        }

        SessionStore.reclaimUnsavedRuns(context, startedAtMs = NOW, draftLive = false)

        assertFalse("no draft: it goes", whileDraft.exists())
        assertTrue("written within the day", recent.isDirectory)
        assertTrue("it has a Home thumbnail", thumbnail.isDirectory)
        assertTrue("it has Re-run inputs", rerun.isDirectory)
        assertTrue("it has a backup's metadata", backedUp.isDirectory)
    }

    @Test
    fun `no unsaved run is deleted while the index does not parse`() {
        saved("kept")
        val unsaved = unsavedRun("unsaved")
        File(root, SessionPaths.INDEX_JSON).writeText("{ not json")
        File(root, SessionPaths.INDEX_JSON + ".bak").writeText("{ not json either")

        assertEquals(0L, SessionStore.reclaimUnsavedRuns(context, startedAtMs = NOW, draftLive = false))

        assertTrue(unsaved.isDirectory)
    }

    private companion object {
        /** This process's start, as the startup sweep sees it. */
        val NOW = System.currentTimeMillis()
        val OLD = NOW - 3_600_000L

        /** Older than [UNSAVED_RUN_MIN_AGE_MS]. */
        val DAYS_AGO = NOW - 2 * UNSAVED_RUN_MIN_AGE_MS
    }
}
