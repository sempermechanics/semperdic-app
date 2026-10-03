package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.prefs.DicSettings
import com.sempermechanics.semper.diagnostics.SemperAnalytics
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.ui.analysis.wizard.BatchAnalysisOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A batch run ends with one analytics event: completed with its frame count,
 * or failed with why. A cancel says nothing.
 */
@RunWith(RobolectricTestRunner::class)
class BatchEndEventTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val recorded = mutableListOf<Pair<String, Map<String, String>>>()

    @Before
    fun setUp() {
        SemperAnalytics.sink = SemperAnalytics.Sink { _, name, params -> recorded += name to params }
        DicSettings.setDiagnosticsEnabled(context, true)
    }

    @After
    fun tearDown() {
        SemperAnalytics.sink = SemperAnalytics.Sink { _, _, _ -> }
        DicSettings.setDiagnosticsEnabled(context, false)
    }

    private fun outcome(points: Int, frames: Int = 3, indexUnavailable: Boolean = false) = BatchAnalysisOutcome(
        engineErrorCode = 0,
        firstFrameValidPoints = points,
        totalFrames = frames,
        executionTimeMs = 3_000,
        batchDirPath = "",
        indexUnavailable = indexUnavailable,
    )

    @Test
    fun `a run that kept points completes`() {
        batchEndEvent(context, outcome(points = 10), RunStop.Finished)
        assertEquals(
            listOf(
                SemperAnalytics.ANALYSIS_COMPLETED to mapOf("mode" to "batch", "frames" to "2_5", "duration" to "1_5s"),
            ),
            recorded,
        )
    }

    @Test
    fun `a failed run says why`() {
        batchEndEvent(context, outcome(points = 0), RunStop.Finished)
        batchEndEvent(context, outcome(points = 10), RunStop.SessionLimit)
        batchEndEvent(context, outcome(points = 10, indexUnavailable = true), RunStop.Finished)
        batchEndEvent(context, outcome(points = 0), RunStop.fromWireCode(ENGINE_FAILURE))

        assertEquals(
            listOf("no_points", "session_limit", "index_unavailable", "engine"),
            recorded.map { it.second["reason"] },
        )
        assertTrue(recorded.all { it.first == SemperAnalytics.ANALYSIS_FAILED && it.second["mode"] == "batch" })
    }

    @Test
    fun `a cancel says nothing`() {
        batchEndEvent(context, outcome(points = 10), RunStop.Cancelled)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun `a cancelled re-run whose save missed the index reports it`() {
        batchEndEvent(context, outcome(points = 10, indexUnavailable = true), RunStop.Cancelled)
        assertEquals(listOf(SemperAnalytics.ANALYSIS_FAILED), recorded.map { it.first })
        assertEquals("index_unavailable", recorded.single().second["reason"])
    }

    private companion object {
        const val ENGINE_FAILURE = -3
    }
}
