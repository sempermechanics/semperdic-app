package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.data.cloud.WorkTags
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.QuotaBackendOn
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.BatchAnalysisOutcome
import com.sempermechanics.semper.ui.analysis.wizard.RunAdmission
import com.sempermechanics.semper.ui.analysis.wizard.RunKind
import com.sempermechanics.semper.ui.analysis.wizard.RunSession
import com.sempermechanics.semper.ui.analysis.wizard.SweepSession
import com.sempermechanics.semper.ui.analysis.wizard.admitRun
import com.sempermechanics.semper.ui.analysis.wizard.applyRunOutcome
import com.sempermechanics.semper.ui.analysis.wizard.endSweep
import com.sempermechanics.semper.ui.analysis.wizard.openRunSession
import com.sempermechanics.semper.ui.analysis.wizard.repointDeformedPaths
import com.sempermechanics.semper.util.Digests
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException

/**
 * Cancel keeps nothing of a run, and a saved analysis is never written over
 * (owner decisions, 2026-10-15). A new run that is cancelled leaves no row,
 * no folder and no upload, and costs no quota; a run after a saved one is a
 * new analysis, which leaves the saved one byte for byte as it was whether it
 * is cancelled or saves; a cancelled sweep saves nothing. A stop that is not
 * a cancel still keeps its frames. The JVM has no engine, so the batch
 * loop's own steps are driven here as it takes them: [openRunSession] at its
 * start, [keepDeformedFrame] and a staged `.dat` per frame it reaches, then
 * [discardCancelledBatch] or [RunSession.commit] at its end, or
 * [undoFailedBatch] when it throws before that and [undoCommittedBatch] when
 * it throws after, before the wizard follows its frames (the instrumented
 * `EnginePipelineSmokeTest` runs the real loop). A sweep is handed its
 * runner's result ([endSweep]). The wizard takes what a run learned from its
 * outcome on Main ([applyRunOutcome]); the run itself
 * changes none of its fields.
 */
@RunWith(RobolectricTestRunner::class)
class CancelDiscardsRunTest {

    @get:Rule
    val clean = CleanAppState()

    @get:Rule
    val quota = QuotaBackendOn()

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val vm = AnalysisViewModel()
    private val importDir by lazy {
        File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        // One analysis on this account: a run that consumed it would block the next.
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 1, maxFilesPerSession = 600, maxFrames = 150))
        AccountCache.setQuota(ctx, used = 0)
        vm.edit {
            it.copy(
                refSize = it.refSize.copy(width = REF_SIDE, height = REF_SIDE),
                refName = "ref.png",
                refBytes = ByteArray(REF_SIDE * REF_SIDE * 4),
            )
        }
    }

    @After
    fun tearDown() {
        importDir.deleteRecursively()
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** [count] frames as an import commits them, in the wizard. */
    private fun importFrames(count: Int): List<File> {
        val files = (0 until count).map { i ->
            File(importDir, "000${i}_def_$i.png").apply { writeBytes(byteArrayOf(i.toByte(), 1, 2, 3)) }
        }
        val picked = files.map { DeformedFrame(it.absolutePath, it.name.substringAfter('_')) }
        vm.edit { it.copy(deformedFrames = picked) }
        return files
    }

    /**
     * What the batch loop does in [session] for frames 0 until [reached]:
     * keeps each frame's image ([keepDeformedFrame]) and writes its `.dat`
     * where the loop writes it. Returns where each image now is.
     */
    private fun solveUpTo(session: RunSession, launched: List<String>, reached: Int, tag: String): List<String> {
        val raw = SessionLayout(session.dir).rawDeformedDir.apply { mkdirs() }
        val resolved = launched.toMutableList()
        for (i in 0 until reached) {
            val source = File(launched[i])
            if (source.parentFile?.absolutePath != raw.absolutePath) {
                val name = source.name.substringAfter('_')
                resolved[i] = keepDeformedFrame(source, source.readBytes(), raw, name, i).absolutePath
            }
            SessionPaths.frameDat(session.staging, i).writeText("$tag frame $i")
        }
        return resolved
    }

    /** What a run in [session] that saved its row, or not, hands the wizard in its outcome. */
    private fun ended(session: RunSession, saved: Boolean) = BatchAnalysisOutcome(
        engineErrorCode = 0,
        firstFrameValidPoints = 1,
        totalFrames = 1,
        executionTimeMs = 0,
        batchDirPath = session.dir.absolutePath,
        record = if (saved) sessionRecord(id = session.id, sessionDir = session.dir.absolutePath) else null,
        session = session.working,
    )

    /** A saved single run that solved [solved] of the wizard's frames, as the loop and its save leave it. */
    private fun savedRun(solved: Int): RunSession {
        val session = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        val resolved = solveUpTo(session, vm.defFilePaths, solved, "saved")
        session.commit()
        vm.repointDeformedPaths(resolved)
        val layout = SessionLayout(session.dir)
        layout.fieldRanges.writeText("saved ranges")
        layout.referencePng.writeText("saved reference")
        SessionPaths.resultThumb(session.dir).writeText("saved thumbnail")
        val row = sessionRecord(
            id = session.id,
            frameCount = solved,
            sessionDir = session.dir.absolutePath,
            refPath = layout.referencePng.absolutePath,
            defNames = resolved.map { File(it).name },
            syncState = SessionRecord.SyncState.SYNCED,
            cloudSessionId = "cloud-1",
        )
        assertTrue(SessionStore.upsert(ctx, row, allowOverLimit = true))
        // As the wizard takes the run's outcome once its row is saved.
        vm.applyRunOutcome(ended(session, saved = true))
        return session
    }

    /** Every file under [dir], by its path there, with its SHA-256. */
    private fun hashes(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to Digests.sha256Hex(it) }

    private fun uploadsQueuedFor(id: String) =
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(WorkTags.uploadName(id)).get().size

    @Test
    fun `a cancelled new run leaves no row, no folder and no upload, and costs no quota`() {
        val frames = importFrames(3)
        val bytes = frames.map { it.readBytes() }
        val launched = vm.defFilePaths
        val session = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        assertTrue(session.fresh)
        val resolved = solveUpTo(session, launched, reached = 2, tag = "cancelled")
        assertFalse("the loop moved the frames it reached", frames[0].exists())

        vm.discardCancelledBatch(ctx, session, launched, resolved)
        vm.applyRunOutcome(cancelledOutcome(executionTimeMs = 0))
        idle()

        assertTrue("no Home row", SessionStore.list(ctx).isEmpty())
        assertFalse("no session folder", session.dir.exists())
        assertEquals("no upload", 0, uploadsQueuedFor(session.id))
        frames.forEachIndexed { i, file -> assertArrayEquals("frame $i is back", bytes[i], file.readBytes()) }
        assertEquals(launched, vm.defFilePaths)
        assertNull("the next run is a new session", vm.inputs.value.working?.id)
        assertEquals(
            "the one analysis allowed is still free",
            RunAdmission.Admitted,
            vm.admitRun(ctx, 3, gridPoints = 0),
        )
    }

    @Test
    fun `a run that throws before its commit is undone as a cancel is, and its frames are back`() {
        val frames = importFrames(3)
        val bytes = frames.map { it.readBytes() }
        val launched = vm.defFilePaths
        val session = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        // Frame 0 solved and was moved in; writing frame 1's .dat then fails (a full disk).
        val resolved = solveUpTo(session, launched, reached = 1, tag = "failed")
        assertFalse("the loop moved the frame it reached", frames[0].exists())
        val failure = IOException("ENOSPC")

        val thrown = assertThrows(IOException::class.java) {
            vm.undoFailedBatch(ctx, session, launched, resolved, failure)
        }
        idle()

        assertSame("the run's own failure goes on up", failure, thrown)
        frames.forEachIndexed { i, file -> assertArrayEquals("frame $i is back", bytes[i], file.readBytes()) }
        assertEquals(launched, vm.defFilePaths)
        assertFalse("no session folder", session.dir.exists())
        assertTrue("no Home row", SessionStore.list(ctx).isEmpty())
    }

    @Test
    fun `a run that throws after its commit is undone too, and the wizard names files that exist`() {
        val frames = importFrames(3)
        val bytes = frames.map { it.readBytes() }
        val launched = vm.defFilePaths
        val session = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        // Frames 0 and 1 solved and were moved in, and the run committed them.
        val resolved = solveUpTo(session, launched, reached = 2, tag = "committed").toMutableList()
        session.commit()
        SessionLayout(session.dir).fieldRanges.writeText("committed ranges")
        // Re-run's keep then moved frame 2, which the loop stopped before, before it threw.
        val kept = File(SessionLayout(session.dir).rerunFramesDir.apply { mkdirs() }, "def_2.png")
        frames[2].renameTo(kept)
        resolved[2] = kept.absolutePath
        assertTrue("the commit moved the results in", SessionPaths.frameDat(session.dir, 1).isFile)
        val failure = IllegalStateException("after the commit")

        val thrown = assertThrows(IllegalStateException::class.java) {
            vm.undoCommittedBatch(ctx, session, launched, resolved, failure)
        }
        idle()

        assertSame("the run's own failure goes on up", failure, thrown)
        assertEquals(launched, vm.defFilePaths)
        assertTrue("every frame the wizard names exists", vm.defFilePaths.all { File(it).isFile })
        frames.forEachIndexed { i, file -> assertArrayEquals("frame $i is back", bytes[i], file.readBytes()) }
        assertFalse("no session folder", session.dir.exists())
        assertTrue("no Home row", SessionStore.list(ctx).isEmpty())
        assertEquals(
            "the one analysis allowed is still free",
            RunAdmission.Admitted,
            vm.admitRun(ctx, 3, gridPoints = 0),
        )
    }

    @Test
    fun `a committed run undone in a folder that saved nothing takes its results out and keeps the folder`() {
        importFrames(2)
        val first = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        vm.applyRunOutcome(ended(first, saved = false))
        val launched = vm.defFilePaths
        val next = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        assertEquals("the unsaved folder is written again", first.id, next.id)
        val resolved = solveUpTo(next, launched, reached = 2, tag = "committed")
        next.commit()
        SessionLayout(next.dir).fieldRanges.writeText("committed ranges")

        assertThrows(IOException::class.java) {
            vm.undoCommittedBatch(ctx, next, launched, resolved, IOException("after the commit"))
        }
        idle()

        assertTrue("a folder that was there before stays", next.dir.isDirectory)
        assertFalse("its committed results go", SessionPaths.frameDat(next.dir, 0).exists())
        assertFalse(SessionLayout(next.dir).fieldRanges.exists())
        assertEquals(launched, vm.defFilePaths)
        assertTrue(vm.defFilePaths.all { File(it).isFile })
    }

    @Test
    fun `a cancelled run after a saved one leaves the saved analysis byte for byte as it was`() {
        val frames = importFrames(3)
        // Saved partial: frame 2 was never reached, so it is still in the import.
        val saved = savedRun(solved = 2)
        val files = hashes(saved.dir)
        val row = SessionStore.get(ctx, saved.id)
        val launched = vm.defFilePaths
        val thumbTime = SessionPaths.resultThumb(saved.dir).lastModified()

        val next = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        assertNotEquals("a new analysis, never the saved one", saved.id, next.id)
        assertTrue(next.fresh)
        val resolved = solveUpTo(next, launched, reached = 3, tag = "next")
        assertEquals("the saved frames are copied, not moved", files, hashes(saved.dir))

        vm.discardCancelledBatch(ctx, next, launched, resolved)
        vm.applyRunOutcome(cancelledOutcome(executionTimeMs = 0))
        idle()

        assertEquals("every file of the saved analysis", files, hashes(saved.dir))
        assertEquals("its row", row, SessionStore.get(ctx, saved.id))
        assertEquals(1, SessionStore.list(ctx).size)
        assertFalse("no folder for the cancelled one", next.dir.exists())
        assertEquals("no upload", 0, uploadsQueuedFor(saved.id))
        assertEquals("its thumbnail is not made stale", thumbTime, SessionPaths.resultThumb(saved.dir).lastModified())
        assertTrue("the frame the run moved in is back in the import", frames[2].isFile)
        assertEquals(launched, vm.defFilePaths)
        assertNull(vm.inputs.value.working?.id)
    }

    @Test
    fun `computing again after a saving partial stop saves a new row and leaves the first as it was`() {
        importFrames(3)
        val saved = savedRun(solved = 2)
        val files = hashes(saved.dir)
        val row = SessionStore.get(ctx, saved.id)

        val next = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        val resolved = solveUpTo(next, vm.defFilePaths, reached = 3, tag = "next")
        next.commit()
        val record = sessionRecord(
            id = next.id,
            name = "next",
            frameCount = 3,
            sessionDir = next.dir.absolutePath,
            defNames = resolved.map { File(it).name },
        )
        val after = afterSave(RunStop.Finished, saveRunRecord(ctx, record, cloudEnabled = false, admitted = true))
        vm.applyRunOutcome(ended(next, saved = after.recordSaved))

        assertTrue(after.recordSaved)
        assertNotEquals(saved.id, next.id)
        assertEquals("two analyses", setOf(saved.id, next.id), SessionStore.list(ctx).map { it.id }.toSet())
        assertEquals("the first, byte for byte", files, hashes(saved.dir))
        assertEquals("its row", row, SessionStore.get(ctx, saved.id))
        assertEquals("next frame 2", SessionPaths.frameDat(next.dir, 2).readText())
        assertFalse(next.staging.exists())
        assertNull("the next run is a new one again", vm.inputs.value.working?.id)
    }

    @Test
    fun `a run in a folder that saved nothing leaves its results alone until it commits`() {
        importFrames(3)
        val first = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        SessionPaths.frameDat(first.staging, 0).writeText("unsaved frame 0")
        first.commit()
        SessionLayout(first.dir).fieldRanges.writeText("unsaved ranges")
        vm.applyRunOutcome(ended(first, saved = false))

        val next = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)

        assertEquals("no row names it", first.id, next.id)
        assertEquals("unsaved frame 0", SessionPaths.frameDat(first.dir, 0).readText())
        assertTrue(SessionLayout(first.dir).fieldRanges.exists())
        assertTrue("an empty staging", next.staging.isDirectory && next.staging.list().isNullOrEmpty())
    }

    /** A two-combination sweep against frame 0 that the runner left in [session] with [code]. */
    private fun sweepRun(session: RunSession, code: Int, tag: String): SweepSession {
        val plan = listOf(SweepStudy.Point(21, 5, 3), SweepStudy.Point(31, 5, 3))
        val sweepSpec = RunSpec.sweep(
            RunSpec.Sweep(plan, labels = listOf("a", "b"), lineCutHorizontal = true, frameIndex = 0),
            roi = Roi(0, 0, REF_SIDE, REF_SIDE),
            mask = null,
            use6x6 = false,
            debugDir = null,
        )
        val dats = plan.indices.map { SessionPaths.frameDat(session.staging, it).apply { writeText("$tag $it") } }
        val result = SweepStudyRunner.SweepResult(
            runs = plan.mapIndexed { i, p -> SweepStudyRunner.RunOutcome(p, dats[i], 400) },
            firstMetrics = null,
            engineErrorCode = code,
        )
        return SweepSession(session.id, session.dir, vm.snapshotRunInputs(), result, sweepSpec, 3_000)
    }

    @Test
    fun `a cancelled sweep saves nothing, however many combinations it solved`() {
        val frames = importFrames(1)
        val session = openRunSession(ctx, vm.inputs.value.working, RunKind.SWEEP)

        val outcome = vm.endSweep(ctx, session, sweepRun(session, RunStop.Cancelled.wireCode, "cancelled"), true)
        vm.applyRunOutcome(outcome)
        idle()

        assertEquals(RunStop.Cancelled, outcome.stop)
        assertEquals(0, outcome.totalFrames)
        assertFalse(outcome.saved)
        assertTrue("no Home row", SessionStore.list(ctx).isEmpty())
        assertFalse("no session folder", session.dir.exists())
        assertEquals("no upload", 0, uploadsQueuedFor(session.id))
        assertTrue("the sweep's frame stays in the import", frames[0].isFile)
        assertNull("no session for the wizard to take", outcome.session)
        assertNull(vm.inputs.value.working?.id)
    }

    @Test
    fun `a cancelled sweep after a saved one leaves the saved sweep as it was`() {
        importFrames(1)
        val first = openRunSession(ctx, vm.inputs.value.working, RunKind.SWEEP)
        val saved = vm.endSweep(ctx, first, sweepRun(first, code = 0, tag = "saved"), true)
        vm.applyRunOutcome(saved)
        idle()
        assertTrue(saved.saved)
        assertNull("a saved sweep closes its session", vm.inputs.value.working?.id)
        assertTrue("committed into the session", SessionPaths.frameDat(first.dir, 1).isFile)
        val files = hashes(first.dir)
        val row = SessionStore.get(ctx, first.id)
        val uploads = uploadsQueuedFor(first.id)

        val next = openRunSession(ctx, vm.inputs.value.working, RunKind.SWEEP)
        assertNotEquals(first.id, next.id)
        val outcome = vm.endSweep(ctx, next, sweepRun(next, RunStop.Cancelled.wireCode, "next"), true)
        vm.applyRunOutcome(outcome)
        idle()

        assertEquals(RunStop.Cancelled, outcome.stop)
        assertEquals(files, hashes(first.dir))
        assertEquals(row, SessionStore.get(ctx, first.id))
        assertEquals(uploads, uploadsQueuedFor(first.id))
        assertNull(outcome.session)
        assertFalse(next.dir.exists())
        assertNull(vm.inputs.value.working?.id)
    }

    @Test
    fun `a copied frame is deleted, a moved one goes back, and one that cannot stays where it is`() {
        val raw = File(ctx.cacheDir, "raw-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val kept = File(importDir, "0000_kept.png").apply { writeText("other session's") }
        val copy = File(raw, "kept.png").apply { writeText("other session's") }
        val movedFrom = File(importDir, "0001_moved.png")
        val moved = File(raw, "moved.png").apply { writeText("moved") }
        val blocker = File(importDir, "blocker").apply { writeText("a file, not a folder") }
        val strandedFrom = File(blocker, "0002_stranded.png")
        val stranded = File(raw, "stranded.png").apply { writeText("stranded") }
        val untouched = File(importDir, "0003_never_reached.png").apply { writeText("never reached") }
        val launched = listOf(kept, movedFrom, strandedFrom, untouched).map { it.absolutePath }

        val now = returnMovedFrames(
            launched,
            listOf(copy.absolutePath, moved.absolutePath, stranded.absolutePath, untouched.absolutePath),
        )

        assertFalse("the copy", copy.exists())
        assertEquals("other session's", kept.readText())
        assertEquals("moved", movedFrom.readText())
        assertFalse(moved.exists())
        assertEquals("stranded", stranded.readText())
        assertEquals(listOf(launched[0], launched[1], stranded.absolutePath, launched[3]), now)
        raw.deleteRecursively()
    }

    @Test
    fun `a new run whose frame cannot go back keeps its folder and points the wizard there`() {
        val blocker = File(importDir, "blocker").apply { writeText("a file, not a folder") }
        val launched = listOf(File(blocker, "0000_a.png").absolutePath)
        vm.edit { it.copy(deformedFrames = listOf(DeformedFrame(launched[0], "a.png"))) }
        val session = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        val moved = File(SessionLayout(session.dir).rawDeformedDir.apply { mkdirs() }, "a.png").apply { writeText("a") }

        vm.discardCancelledBatch(ctx, session, launched, listOf(moved.absolutePath))
        idle()

        assertEquals("a", moved.readText())
        assertTrue(session.dir.isDirectory)
        assertEquals(listOf(moved.absolutePath), vm.defFilePaths)
        assertTrue(SessionStore.list(ctx).isEmpty())
    }

    @Test
    fun `only a folder no row names is deleted as unsaved`() {
        val savedDir = SessionStore.dirFor(ctx, "saved")
        assertTrue(SessionStore.upsert(ctx, sessionRecord(id = "saved", sessionDir = savedDir.absolutePath), true))
        val unsavedDir = SessionStore.dirFor(ctx, "unsaved")

        assertFalse(SessionStore.deleteUnsavedDir(ctx, "saved"))
        assertTrue(savedDir.isDirectory)
        assertTrue(SessionStore.deleteUnsavedDir(ctx, "unsaved"))
        assertFalse(unsavedDir.exists())
        assertFalse("never the sessions root", SessionStore.deleteUnsavedDir(ctx, ""))
        assertFalse(SessionStore.deleteUnsavedDir(ctx, ".."))
        assertTrue(savedDir.isDirectory)
    }

    private companion object {
        const val REF_SIDE = 4
    }
}
