package com.indicvision.semper.analysis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.account.LicenseEntitlements
import com.indicvision.semper.data.net.AppConfigDto
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.field.DicParams
import com.indicvision.semper.field.Roi
import com.indicvision.semper.field.RunStop
import com.indicvision.semper.ui.analysis.frames.DeformedFrame
import com.indicvision.semper.ui.analysis.run.BatchRun
import com.indicvision.semper.ui.analysis.run.RunSpec
import com.indicvision.semper.ui.analysis.run.runBatchAnalysisBody
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.analysis.wizard.BatchAnalysisOutcome
import com.indicvision.semper.ui.analysis.wizard.BatchProgressUpdate
import com.indicvision.semper.ui.analysis.wizard.sessionLimitOutcome
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
class DicBatchRunnerLimitTest {

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
        TokenStore.clear(ctx)
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 1, maxFilesPerSession = 600, maxFrames = 150))
        vm.deformedFrames = listOf("/frames/a.png", "/frames/b.png", "/frames/c.png").map { DeformedFrame(it, "") }
    }

    @After
    fun tearDown() {
        TokenStore.clear(ctx)
    }

    private fun run(): BatchAnalysisOutcome {
        val progress = mutableListOf<BatchProgressUpdate>()
        val outcome = vm.runBatchAnalysisBody(ctx, BatchRun(spec, ctx.cacheDir, 0L, Job())) { progress += it }
        assertTrue("a blocked run reports no progress", progress.isEmpty())
        return outcome
    }

    @Test
    fun `a new session at a full quota stops before the engine`() {
        TokenStore.setQuota(ctx, used = 1)
        val outcome = run()
        assertEquals(RunStop.SessionLimit, outcome.stop)
        assertEquals(3, outcome.totalFrames)
        assertEquals("", outcome.batchDirPath)
        assertNull("no session id is minted for a blocked run", vm.workingLocalId)
        assertTrue(SessionStore.list(ctx).isEmpty())
    }

    @Test
    fun `the stop is for new sessions only`() {
        TokenStore.setQuota(ctx, used = 1)
        assertNotNull(vm.sessionLimitOutcome(ctx, 3))
        vm.workingLocalId = "existing_row"
        assertNull("a re-run reuses its Home row and costs nothing", vm.sessionLimitOutcome(ctx, 3))
    }

    @Test
    fun `before the config arrives a demo account is held to the demo cap`() {
        TokenStore.clear(ctx)
        TokenStore.setQuota(ctx, used = LicenseEntitlements.DEMO_MAX_ANALYSES - 1)
        assertNull(vm.sessionLimitOutcome(ctx, 3))
        TokenStore.setQuota(ctx, used = LicenseEntitlements.DEMO_MAX_ANALYSES)
        assertNotNull(vm.sessionLimitOutcome(ctx, 3))
    }
}
