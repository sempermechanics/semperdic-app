package com.sempermechanics.semper.analysis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.fixtures.QuotaBackendOn
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.run.BatchRun
import com.sempermechanics.semper.ui.analysis.run.RunSpec
import com.sempermechanics.semper.ui.analysis.run.runBatchAnalysisBody
import com.sempermechanics.semper.ui.analysis.run.saveRunRecord
import com.sempermechanics.semper.ui.analysis.run.snapshotRunInputs
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.BatchAnalysisOutcome
import com.sempermechanics.semper.ui.analysis.wizard.BatchProgressUpdate
import com.sempermechanics.semper.ui.analysis.wizard.RunAdmission
import com.sempermechanics.semper.ui.analysis.wizard.RunKind
import com.sempermechanics.semper.ui.analysis.wizard.WorkingSession
import com.sempermechanics.semper.ui.analysis.wizard.admitRun
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The quota hard stop at the top of the batch loop: a new session at a full,
 * known quota returns before any engine or disk work. The JVM has no engine
 * library, so a stop that let the run reach the JNI calls would fail this test
 * with an UnsatisfiedLinkError rather than pass quietly.
 */
@RunWith(RobolectricTestRunner::class)
class BatchAnalysisLimitTest {

    @get:Rule
    val backend = QuotaBackendOn()

    private lateinit var ctx: Context
    private val vm = AnalysisViewModel()
    private val spec = RunSpec.of(
        params = DicParams(subset = 21, step = 5, strainWindow = 15),
        roi = Roi(0, 0, 64, 64),
        mask = byteArrayOf(1),
        use6x6 = false,
        debugDir = null,
    )

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        AccountCache.clear(ctx)
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 1, maxFilesPerSession = 600, maxFrames = 150))
        val picked = listOf("/frames/a.png", "/frames/b.png", "/frames/c.png").map { DeformedFrame(it, "") }
        vm.edit { it.copy(deformedFrames = picked) }
    }

    @After
    fun tearDown() {
        AccountCache.clear(ctx)
        SessionStore.deleteAll(ctx)
    }

    private fun run(): BatchAnalysisOutcome {
        val progress = mutableListOf<BatchProgressUpdate>()
        val run = BatchRun(spec, ctx.cacheDir, 0L, Job(), vm.snapshotRunInputs())
        val outcome = vm.runBatchAnalysisBody(ctx, run) { progress += it }
        assertTrue("a blocked run reports no progress", progress.isEmpty())
        return outcome
    }

    @Test
    fun `a new session at a full quota stops before the engine`() {
        AccountCache.setQuota(ctx, used = 1)
        val outcome = run()
        assertEquals(RunStop.SessionLimit, outcome.stop)
        assertEquals(3, outcome.totalFrames)
        assertEquals("", outcome.batchDirPath)
        assertNull("no session id is minted for a blocked run", vm.inputs.value.working?.id)
        assertNull("nor one for the wizard to take", outcome.session)
        assertTrue(SessionStore.list(ctx).isEmpty())
    }

    @Test
    fun `a grid too large for any heap stops before the engine, as a run too large`() {
        AccountCache.setQuota(ctx, used = 0)
        // 60,000 x 60,000 at step 1: 3.6 billion points, past one buffer's capacity.
        val huge = RunSpec.of(
            params = DicParams(subset = 21, step = 1, strainWindow = 15),
            roi = Roi(0, 0, 60_000, 60_000),
            mask = byteArrayOf(1),
            use6x6 = false,
            debugDir = null,
        )
        val run = BatchRun(huge, ctx.cacheDir, 0L, Job(), vm.snapshotRunInputs())

        val outcome = vm.runBatchAnalysisBody(ctx, run) { }

        assertEquals(RunStop.TooLarge, outcome.stop)
        assertEquals("", outcome.batchDirPath)
        assertNull("no session for the wizard to take", outcome.session)
        assertTrue(SessionStore.list(ctx).isEmpty())
    }

    @Test
    fun `the start refuses a grid this heap cannot hold, before the quota`() {
        AccountCache.setQuota(ctx, used = 0)
        val heap = 512L * 1024 * 1024
        // A 4000 x 3000 ROI at step 1 is 12 million points; 512 MB holds about 4.2 million.
        val blocked = vm.admitRun(ctx, 3, gridPoints = 12_000_000L, maxHeapBytes = heap)
        assertEquals(RunStop.TooLarge, (blocked as RunAdmission.Blocked).outcome.stop)
        assertEquals(RunAdmission.Admitted, vm.admitRun(ctx, 3, gridPoints = 3_000_000L, maxHeapBytes = heap))
    }

    @Test
    fun `a blocked run counts the frames it was launched with, not the wizard's later ones`() {
        AccountCache.setQuota(ctx, used = 1)
        val run = BatchRun(spec, ctx.cacheDir, 0L, Job(), vm.snapshotRunInputs())
        // The wizard picks five frames on Main while the run waits for the native thread.
        vm.edit { cur -> cur.copy(deformedFrames = (0 until 5).map { DeformedFrame("/frames/late_$it.png", "") }) }

        val outcome = vm.runBatchAnalysisBody(ctx, run) { }

        assertEquals(RunStop.SessionLimit, outcome.stop)
        assertEquals(3, outcome.totalFrames)
    }

    @Test
    fun `every run is a new analysis, so every run is checked`() {
        AccountCache.setQuota(ctx, used = 1)
        assertTrue(vm.admitRun(ctx, 3, gridPoints = 0) is RunAdmission.Blocked)
        vm.edit { it.copy(working = WorkingSession("earlier_run", RunKind.BATCH)) }
        assertTrue(
            "no run is a free re-run of a saved one",
            vm.admitRun(ctx, 3, gridPoints = 0) is RunAdmission.Blocked,
        )
        assertEquals("a blocked run keeps the working folder", "earlier_run", vm.inputs.value.working?.id)
    }

    @Test
    fun `before the config arrives a demo account is held to the demo cap`() {
        AccountCache.clear(ctx)
        AccountCache.setQuota(ctx, used = LicenseEntitlements.DEMO_MAX_ANALYSES - 1)
        assertEquals(RunAdmission.Admitted, vm.admitRun(ctx, 3, gridPoints = 0))
        AccountCache.setQuota(ctx, used = LicenseEntitlements.DEMO_MAX_ANALYSES)
        assertTrue(vm.admitRun(ctx, 3, gridPoints = 0) is RunAdmission.Blocked)
    }

    @Test
    fun `a run admitted under the cap saves after crossing it, and the next start is blocked`() {
        AccountCache.setQuota(ctx, used = 0)
        val admission = vm.admitRun(ctx, 3, gridPoints = 0)
        assertEquals(RunAdmission.Admitted, admission)

        // While it solves, the account fills: an upload's 409 forces the stop.
        AccountCache.setSessionLimitReached(ctx, true)
        val saved = saveRunRecord(ctx, row("admitted"), cloudEnabled = false, admitted = admission.admitted)

        assertEquals("only starting is blocked", SessionStore.UpsertOutcome.SAVED, saved)
        assertTrue(
            "the next new run is not admitted",
            AnalysisViewModel().admitRun(ctx, 3, gridPoints = 0) is RunAdmission.Blocked,
        )
    }

    @Test
    fun `the start counts the index's rows, not a stale stored count`() {
        AccountCache.setQuota(ctx, used = 0)
        // A row on the phone the stored count has not seen (cleared since).
        assertTrue(SessionStore.upsert(ctx, row("on_phone"), allowOverLimit = true))
        AccountCache.setQuota(ctx, used = 0, localCount = 0)

        assertTrue("1 row at a ceiling of 1", vm.admitRun(ctx, 3, gridPoints = 0) is RunAdmission.Blocked)
    }

    private fun row(id: String) = sessionRecord(id = id, createdAt = 1L, refPath = "ref.png", sessionDir = "/dir/$id")
}
