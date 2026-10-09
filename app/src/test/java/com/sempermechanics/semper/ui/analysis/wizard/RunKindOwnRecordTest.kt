package com.sempermechanics.semper.ui.analysis.wizard

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.run.RunSpec
import com.sempermechanics.semper.ui.analysis.run.keepDeformedFrame
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * A single run and a parameter sweep in one wizard (one [AnalysisViewModel])
 * are two records. The sweep used to take the single run's session id: it
 * deleted the run's `.dat` files, replaced its Home row, and moved its own
 * frame out of the run's `raw_deformed/` while deleting every other frame
 * there, which the wizard still pointed at. Everything a run does on disk is
 * driven here except the engine's solve, which the JVM has no library for:
 * [openRunSession] as both runs start, [finishSolvedSweep] as a sweep saves,
 * and [keepDeformedFrame] as the batch loop keeps each frame.
 */
@RunWith(RobolectricTestRunner::class)
class RunKindOwnRecordTest {

    @get:Rule
    val clean = CleanAppState()

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val vm = AnalysisViewModel()
    private val importDir by lazy { File(ctx.cacheDir, "import-test").apply { mkdirs() } }

    @Before
    fun setUp() {
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 10, maxFilesPerSession = 600, maxFrames = 150))
        AccountCache.setQuota(ctx, used = 0)
        vm.realRefWidth = REF_SIDE
        vm.realRefHeight = REF_SIDE
        vm.refName = "ref.png"
    }

    private fun names() = (0 until FRAMES).map { "def_$it.png" }

    /** A single run of [FRAMES] frames as the batch loop leaves it: saved, its images in its own folder. */
    private fun savedSingleRun(): RunSession {
        val session = vm.openRunSession(ctx, sweep = false)
        val raw = SessionLayout(session.dir).rawDeformedDir.apply { mkdirs() }
        val frames = names().map { name -> File(raw, name).apply { writeText("image $name") } }
        repeat(FRAMES) { SessionPaths.frameDat(session.dir, it).writeText("dat $it") }
        vm.deformedFrames = frames.map { DeformedFrame(it.absolutePath, it.name) }
        assertTrue(SessionStore.upsert(ctx, record(session, SINGLE_NAME, FRAMES)))
        return session
    }

    private fun record(session: RunSession, name: String, frames: Int, sweepSubsets: List<Int> = emptyList()) =
        SessionRecord(
            id = session.id,
            name = name,
            createdAt = 1L,
            updatedAt = 1L,
            frameCount = frames,
            subset = 21,
            step = 5,
            strainWindow = 3,
            imgW = REF_SIDE,
            imgH = REF_SIDE,
            roiX = 0,
            roiY = 0,
            roiW = REF_SIDE,
            roiH = REF_SIDE,
            refPath = File(session.dir, "reference.png").absolutePath,
            refName = "ref.png",
            sessionDir = session.dir.absolutePath,
            defNames = if (sweepSubsets.isEmpty()) names() else List(frames) { names()[SWEEP_FRAME] },
            sweepSubsets = sweepSubsets,
        )

    /** A sweep of two combinations against frame [SWEEP_FRAME], saved as [finishSolvedSweep] saves it. */
    private fun runSweep(): RunSession {
        val session = vm.openRunSession(ctx, sweep = true)
        val plan = listOf(SweepStudy.Point(21, 5, 3), SweepStudy.Point(31, 5, 3))
        val spec = RunSpec.sweep(
            RunSpec.Sweep(plan, labels = listOf("a", "b"), lineCutHorizontal = true, frameIndex = SWEEP_FRAME),
            roi = Roi(0, 0, REF_SIDE, REF_SIDE),
            mask = null,
            use6x6 = false,
            debugDir = null,
        )
        val dats = plan.indices.map { SessionPaths.frameDat(session.dir, it).apply { writeText("sweep $it") } }
        val result = SweepStudyRunner.SweepResult(
            runs = plan.mapIndexed { i, p -> SweepStudyRunner.RunOutcome(p, dats[i], 400) },
            firstMetrics = null,
            engineErrorCode = 0,
        )
        val reference = ByteArray(REF_SIDE * REF_SIDE * 4)
        vm.finishSolvedSweep(ctx, SweepSession(session.id, session.dir, reference, result, spec, 3_000), false)
        shadowOf(Looper.getMainLooper()).idle()
        return session
    }

    @Test
    fun `a sweep after a single run is its own record and leaves the run whole`() {
        val single = savedSingleRun()

        val sweep = runSweep()

        assertNotEquals("the sweep took the single run's session", single.id, sweep.id)
        assertEquals(2, SessionStore.list(ctx).size)
        val kept = checkNotNull(SessionStore.get(ctx, single.id))
        assertEquals(FRAMES, kept.frameCount)
        assertEquals(SINGLE_NAME, kept.name)
        assertTrue(kept.sweepSubsets.isEmpty())
        repeat(FRAMES) { assertTrue("frame $it's .dat", SessionPaths.frameDat(single.dir, it).isFile) }
        val singleRaw = SessionLayout(single.dir).rawDeformedDir
        names().forEach { assertTrue("$it in the single run's folder", File(singleRaw, it).isFile) }

        val saved = checkNotNull(SessionStore.get(ctx, sweep.id))
        assertEquals(listOf(21, 31), saved.sweepSubsets)
        assertTrue("a new sweep gets the sweep auto-name", saved.name.startsWith("Parameter sweep"))
        val copy = SessionLayout(sweep.dir).rawDeformed(names()[SWEEP_FRAME])
        assertEquals("image ${names()[SWEEP_FRAME]}", copy.readText())
        // A single re-run reads these paths: they must all still be there.
        vm.defFilePaths.forEach { assertTrue("$it is gone", File(it).isFile) }
    }

    @Test
    fun `a single run after a sweep is its own record and leaves the sweep whole`() {
        // The sweep moved its frame in from the import cache; the rest are still there.
        val staged = names().map { name -> File(importDir, name).apply { writeText("image $name") } }
        vm.deformedFrames = staged.map { DeformedFrame(it.absolutePath, it.name) }
        val sweep = runSweep()
        val sweepFrame = SessionLayout(sweep.dir).rawDeformed(names()[SWEEP_FRAME])
        assertEquals("the sweep followed its moved frame", sweepFrame.absolutePath, vm.defFilePaths[SWEEP_FRAME])

        val single = vm.openRunSession(ctx, sweep = false)
        val raw = SessionLayout(single.dir).rawDeformedDir.apply { mkdirs() }
        val kept = vm.defFilePaths.mapIndexed { i, path ->
            val source = File(path)
            keepDeformedFrame(source, source.readBytes(), raw, names()[i], i)
        }

        assertNotEquals("the single run took the sweep's session", sweep.id, single.id)
        assertTrue("the sweep's .dat files", (0..1).all { SessionPaths.frameDat(sweep.dir, it).isFile })
        assertEquals(listOf(21, 31), SessionStore.get(ctx, sweep.id)?.sweepSubsets)
        assertTrue("the sweep's own frame was moved out", sweepFrame.isFile)
        assertEquals(names(), kept.map { it.name })
        assertTrue(kept.all { it.isFile && it.parentFile == raw })
        assertFalse("frames from the import cache are moved", staged[0].exists())
    }

    @Test
    fun `a re-run of the same kind keeps its session`() {
        val single = vm.openRunSession(ctx, sweep = false)
        assertEquals(single.id, vm.openRunSession(ctx, sweep = false).id)

        val sweep = vm.openRunSession(ctx, sweep = true)
        assertNotEquals(single.id, sweep.id)
        assertEquals(sweep.id, vm.openRunSession(ctx, sweep = true).id)
        assertEquals("the view model names the last run's session", sweep.id, vm.workingLocalId)
    }

    @Test
    fun `a sweep re-run over a single run's frame copies it again and keeps the name`() {
        savedSingleRun()
        val first = runSweep()
        val second = runSweep()

        assertEquals(first.id, second.id)
        val raw = SessionLayout(second.dir).rawDeformedDir
        assertEquals(listOf(names()[SWEEP_FRAME]), raw.list()?.toList())
        assertEquals(listOf(names()[SWEEP_FRAME], names()[SWEEP_FRAME]), SessionStore.get(ctx, second.id)?.defNames)
    }

    @Test
    fun `a run clears the field-range sidecar its previous run left`() {
        val sweep = vm.openRunSession(ctx, sweep = true)
        val sidecar = SessionLayout(sweep.dir).fieldRanges.apply { writeText("stale ranges") }
        val dat = SessionPaths.frameDat(sweep.dir, 0).apply { writeText("old") }

        vm.openRunSession(ctx, sweep = true)

        assertFalse("a stale sidecar would give the re-run the old ranges", sidecar.exists())
        assertFalse(dat.exists())
    }

    @Test
    fun `only a file in another session's folder is another session's`() {
        val own = SessionStore.dirFor(ctx, "own")
        val other = SessionStore.dirFor(ctx, "other")
        val raw = SessionPaths.RAW_DEFORMED_SUBDIR

        assertTrue(SessionPaths.isInOtherSession(File(other, "$raw/a.png"), own))
        assertFalse(SessionPaths.isInOtherSession(File(own, "$raw/a.png"), own))
        assertFalse(SessionPaths.isInOtherSession(File(importDir, "a.png"), own))
        // A sibling whose name only starts with this session's id is still another session.
        assertTrue(SessionPaths.isInOtherSession(File(SessionStore.dirFor(ctx, "own2"), "a.png"), own))
    }

    private companion object {
        const val REF_SIDE = 4
        const val FRAMES = 5
        const val SWEEP_FRAME = 2
        const val SINGLE_NAME = "Single run · ref"
    }
}
