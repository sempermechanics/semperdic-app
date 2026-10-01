package com.indicvision.semper.analysis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.account.LicenseEntitlements
import com.indicvision.semper.data.net.AppConfigDto
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.ui.analysis.run.RunSpec
import com.indicvision.semper.ui.analysis.run.runBatchAnalysisBody
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
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
        subset = 21,
        step = 5,
        strainWindow = 15,
        roi = intArrayOf(0, 0, 64, 64),
        mask = byteArrayOf(1),
        use6x6 = false,
        debugDir = null,
    )

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        TokenStore.clear(ctx)
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 1, maxFilesPerSession = 600, maxFrames = 150))
        vm.defFilePaths = listOf("/frames/a.png", "/frames/b.png", "/frames/c.png")
    }

    @After
    fun tearDown() {
        TokenStore.clear(ctx)
    }

    private fun run(): AnalysisViewModel.BatchAnalysisOutcome {
        val progress = mutableListOf<AnalysisViewModel.BatchProgressUpdate>()
        val outcome = vm.runBatchAnalysisBody(ctx, spec, spec.batchParams(ctx.cacheDir, 0L), { progress += it }, Job())
        assertTrue("a blocked run reports no progress", progress.isEmpty())
        return outcome
    }

    @Test
    fun `a new session at a full quota stops before the engine`() {
        TokenStore.setQuota(ctx, used = 1)
        val outcome = run()
        assertEquals(AnalysisViewModel.ERROR_SESSION_LIMIT, outcome.engineErrorCode)
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
