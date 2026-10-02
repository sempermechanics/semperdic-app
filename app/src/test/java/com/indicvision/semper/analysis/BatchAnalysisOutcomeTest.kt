package com.indicvision.semper.analysis

import com.indicvision.semper.ui.analysis.wizard.BatchAnalysisOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Stopped at frame N" must number the frame the way the error under it does. */
class BatchAnalysisOutcomeTest {

    private fun outcome(kept: Int, failedAt: Int) = BatchAnalysisOutcome(
        engineErrorCode = -2,
        firstFrameValidPoints = 10,
        totalFrames = kept,
        executionTimeMs = 0,
        batchDirPath = "",
        failedFrameIndex = failedAt,
    )

    @Test
    fun `an engine failure on 0-based frame 5 stops at frame 6, with 5 kept`() {
        assertEquals(6, outcome(kept = 5, failedAt = 5).stoppedAtFrame)
    }

    @Test
    fun `the low-convergence stop keeps its frame and is numbered the same way`() {
        assertEquals(6, outcome(kept = 6, failedAt = 5).stoppedAtFrame)
    }

    @Test
    fun `with no failing frame the kept count stands`() {
        assertEquals(4, outcome(kept = 4, failedAt = -1).stoppedAtFrame)
    }
}
