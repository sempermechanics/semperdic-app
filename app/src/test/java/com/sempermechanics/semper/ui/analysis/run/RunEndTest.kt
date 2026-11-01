package com.sempermechanics.semper.ui.analysis.run

import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.analysis.wizard.BatchAnalysisOutcome
import com.sempermechanics.semper.ui.viewer.ViewerArgs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a run ends, read once from its outcome ([RunEnd.of]): the one ladder a
 * batch run and a sweep share (ADR-018), table by table. Only a saved row
 * opens; a first frame that kept no points saves nothing, however many frames
 * solved after it.
 */
class RunEndTest {

    private val spec = RunSpec.of(DicParams(21, 5, 15), Roi(0, 0, 64, 64), null, false, null)
    private val row = sessionRecord(id = "run", frameCount = 3).copy(plannedFrameCount = 4)
    private val sweepRow = sessionRecord(id = "sweep", frameCount = 2)
        .copy(sweepSubsets = listOf(21, 31), sweepSteps = listOf(5, 5), sweepStrainWindows = listOf(15, 15))
    private val lattice = ViewerArgs.ofFrames("/sessions/sweep", 64, 64, 5)

    private class Case(val name: String, val outcome: BatchAnalysisOutcome, val end: RunEnd)

    private fun outcome(code: Int = 0, validPoints: Int = 500, record: SessionRecord? = row) = BatchAnalysisOutcome(
        engineErrorCode = code,
        firstFrameValidPoints = validPoints,
        totalFrames = record?.frameCount ?: 0,
        executionTimeMs = 6_000,
        batchDirPath = "/sessions/run",
        record = record,
        spec = spec,
    )

    @Test
    fun `each outcome reads as the one ending the wizard shows`() {
        val cases = listOf(
            Case(
                "the index refused the row",
                outcome(record = null).copy(indexUnavailable = true),
                RunEnd.NotSaved,
            ),
            Case("a cancel", outcome(RunStop.Cancelled.wireCode, validPoints = 0, record = null), RunEnd.Cancelled),
            Case(
                "a grid too large for this phone's memory",
                outcome(RunStop.TooLarge.wireCode, validPoints = 0, record = null),
                RunEnd.TooLarge,
            ),
            Case(
                "a full quota at save time",
                outcome(RunStop.SessionLimit.wireCode, record = null),
                RunEnd.SessionLimit,
            ),
            Case("every frame solved and saved", outcome(), RunEnd.Solved(row, 6_000)),
            Case(
                "a stop with its frames saved",
                outcome(RunStop.LowConvergence.wireCode).copy(failedFrameIndex = 2, failedFrameName = "c.png"),
                RunEnd.Partial(row, RunStop.LowConvergence.wireCode, 3, 2, "c.png"),
            ),
            Case(
                "an engine failure on the first frame",
                outcome(EngineFailure.ENGINE_ERROR_FEATURES, validPoints = 0, record = null)
                    .copy(failedFrameIndex = 0, failedFrameName = "a.png"),
                RunEnd.Failed(EngineFailure.ENGINE_ERROR_FEATURES, 0, "a.png"),
            ),
            Case(
                "a first frame that kept no points",
                outcome(code = 0, validPoints = 0, record = null).copy(firstFrameCorrelatedPoints = 441),
                RunEnd.Failed(0, -1, null, RunEnd.EmptyFirstFrame(441, spec)),
            ),
            Case(
                "frames that solved after a first frame that kept nothing",
                outcome(EngineFailure.ENGINE_ERROR_FEATURES, validPoints = 0, record = null)
                    .copy(failedFrameIndex = 3, firstFrameCorrelatedPoints = 0),
                RunEnd.Failed(EngineFailure.ENGINE_ERROR_FEATURES, 3, null, RunEnd.EmptyFirstFrame(0, spec)),
            ),
            Case("a saved sweep, whatever its code", outcome(record = sweepRow), RunEnd.SweepSaved(sweepRow)),
            Case(
                "a sweep that solved nothing",
                outcome(EngineFailure.ENGINE_ERROR_INIT, validPoints = 0, record = null).copy(failedSweep = lattice),
                RunEnd.SweepFailed(lattice),
            ),
            Case(
                "a sweep the index refused",
                outcome(record = null).copy(indexUnavailable = true, failedSweep = lattice),
                RunEnd.NotSaved,
            ),
        )

        cases.forEach { assertEquals(it.name, it.end, RunEnd.of(it.outcome)) }
    }

    @Test
    fun `a run that threw keeps only what it was, never what it said`() {
        assertEquals(RunEnd.Unexpected("IllegalStateException"), RunEnd.unexpected(IllegalStateException("boom")))
        assertEquals(RunEnd.Unexpected("IllegalStateException"), RunEnd.unexpected(IllegalStateException()))
    }
}
