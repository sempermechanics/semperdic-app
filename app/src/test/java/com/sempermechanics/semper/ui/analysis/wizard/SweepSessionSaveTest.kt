package com.sempermechanics.semper.ui.analysis.wizard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.data.prefs.DicSettings
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.diagnostics.SemperAnalytics
import com.sempermechanics.semper.diagnostics.SemperAnalytics.ANALYSIS_COMPLETED
import com.sempermechanics.semper.diagnostics.SemperAnalytics.ANALYSIS_FAILED
import com.sempermechanics.semper.diagnostics.SemperAnalytics.CLOUD_UPLOAD_ENQUEUED
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.run.RunSpec
import com.sempermechanics.semper.ui.analysis.sweep.VsgStudy
import com.sempermechanics.semper.ui.analysis.sweep.VsgStudyRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A solved sweep's session goes through the real save ([finishSolvedSweep] →
 * `persistSweepSession` → `saveRunRecord` → [SessionStore]), and its outcome
 * says what that save did, in its outcome and its analytics event. Only the
 * engine's solve is skipped: the sweep is handed a finished
 * [VsgStudyRunner.SweepResult]. Uploads are on, so a refused save that still
 * queued one would show. The sweep used to ignore its save, so a full quota or
 * an unreadable index ended as a completed sweep with nothing on Home.
 */
@RunWith(RobolectricTestRunner::class)
class SweepSessionSaveTest {

    @get:Rule
    val clean = CleanAppState()

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val vm = AnalysisViewModel()

    private val events = mutableListOf<Pair<String, Map<String, String>>>()

    @Before
    fun setUp() {
        SemperAnalytics.sink = SemperAnalytics.Sink { _, name, params -> events += name to params }
        DicSettings.setDiagnosticsEnabled(ctx, true)
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 2, maxFilesPerSession = 600, maxFrames = 150))
        TokenStore.setQuota(ctx, used = 0)
    }

    @After
    fun tearDown() {
        SemperAnalytics.sink = SemperAnalytics.Sink { _, _, _ -> }
        DicSettings.setDiagnosticsEnabled(ctx, false)
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun failed(reason: String) =
        ANALYSIS_FAILED to mapOf("mode" to "sweep", "reason" to reason, "duration" to "1_5s")

    private fun uploadsQueuedFor(id: String) =
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("upload-$id").get().size

    /** Two combinations solved against frame 0, a 4 x 4 raw-RGBA reference. */
    private fun finish(id: String): BatchAnalysisOutcome {
        vm.realRefWidth = REF_SIDE
        vm.realRefHeight = REF_SIDE
        val frame = File(ctx.cacheDir, "0000_def.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        vm.deformedFrames = listOf(DeformedFrame(frame.absolutePath, "def.png"))
        val batchDir = SessionStore.dirFor(ctx, id)
        val plan = listOf(VsgStudy.Point(21, 5, 3), VsgStudy.Point(31, 5, 3))
        val spec = RunSpec.sweep(
            RunSpec.Sweep(plan, labels = listOf("a", "b"), lineCutHorizontal = true, frameIndex = 0),
            roi = Roi(0, 0, 100, 100),
            mask = null,
            use6x6 = false,
            debugDir = null,
        )
        val result = VsgStudyRunner.SweepResult(
            runs = plan.mapIndexed { i, p -> VsgStudyRunner.RunOutcome(p, File(batchDir, "frame_000$i.dat"), 400) },
            firstMetrics = null,
            engineErrorCode = 0,
        )
        val session = SweepSession(id, batchDir, ByteArray(REF_SIDE * REF_SIDE * 4), result, spec, 3_000)
        return vm.finishSolvedSweep(ctx, session, cloudEnabled = true)
    }

    @Test
    fun `a saved sweep keeps its row and queues its upload`() {
        val outcome = finish("s1")

        assertTrue(outcome.saved)
        assertEquals(RunStop.Finished, outcome.stop)
        assertNotNull(SessionStore.get(ctx, "s1"))
        assertEquals(1, uploadsQueuedFor("s1"))
        assertEquals(
            listOf(
                CLOUD_UPLOAD_ENQUEUED to emptyMap(),
                ANALYSIS_COMPLETED to mapOf("mode" to "sweep", "frames" to "2_5", "duration" to "1_5s"),
            ),
            events,
        )
    }

    @Test
    fun `a quota that filled before the save ends the sweep at the session limit, queueing nothing`() {
        TokenStore.setQuota(ctx, used = 2)

        val outcome = finish("s1")

        assertEquals(RunStop.SessionLimit, outcome.stop)
        assertFalse(outcome.saved)
        assertFalse(outcome.indexUnavailable)
        assertNull(SessionStore.get(ctx, "s1"))
        assertEquals(0, uploadsQueuedFor("s1"))
        assertEquals(listOf(failed("session_limit")), events)
    }

    @Test
    fun `an unreadable index says not saved, not the quota, and queues nothing`() {
        val sessions = File(ctx.filesDir, "sessions").apply { mkdirs() }
        File(sessions, "index.json").writeText("{truncated")
        File(sessions, "index.json.bak").writeText("{also-bad")

        val outcome = finish("s1")

        assertTrue(outcome.indexUnavailable)
        assertFalse(outcome.saved)
        assertEquals(RunStop.Finished, outcome.stop)
        assertEquals(0, uploadsQueuedFor("s1"))
        assertEquals(listOf(failed("index_unavailable")), events)
    }

    private companion object {
        const val REF_SIDE = 4
    }
}
