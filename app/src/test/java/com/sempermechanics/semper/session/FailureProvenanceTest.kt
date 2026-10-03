package com.sempermechanics.semper.session

import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SkippedNode
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.analysis.run.EngineFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Failure and sweep-skip fields on [SessionRecord] survive restart and cloud restore. */
class FailureProvenanceTest {

    private data class RecordArgs(
        val stopCode: Int = 0,
        val frameCount: Int = 10,
        val plannedFrameCount: Int = 0,
        val skipSubsets: List<Int> = emptyList(),
        val skipSteps: List<Int> = emptyList(),
        val skipWindows: List<Int> = emptyList(),
        val skipCodes: List<Int> = emptyList(),
        val skipNodes: List<SkippedNode> = emptyList(),
    )

    private fun record(args: RecordArgs = RecordArgs()) = sessionRecord(
        id = "s1",
        name = "run",
        createdAt = 0L,
        frameCount = args.frameCount,
        refPath = "ref.png",
        sessionDir = "/dir",
    ).copy(
        stopCode = args.stopCode,
        plannedFrameCount = args.plannedFrameCount,
        sweepSkipSubsets = args.skipSubsets,
        sweepSkipSteps = args.skipSteps,
        sweepSkipStrainWindows = args.skipWindows,
        sweepSkipCodes = args.skipCodes,
        sweepSkippedNodes = args.skipNodes,
    )

    @Test
    fun `a clean run is not marked as stopped early`() {
        assertFalse(record().stoppedEarly)
    }

    @Test
    fun `a run that stopped carries why, and what it had planned`() {
        val r = record(
            RecordArgs(
                stopCode = RunStop.LowConvergence.wireCode,
                frameCount = 39,
                plannedFrameCount = 50,
            ),
        )

        assertTrue(r.stoppedEarly)
        assertEquals(39, r.frameCount)
        assertEquals(50, r.plannedFrameCount)
    }

    @Test
    fun `the stored code resolves to a reason the user can read`() {
        val r = record(RecordArgs(stopCode = RunStop.LowConvergence.wireCode))

        assertTrue(EngineFailure.shortReasonRes(r.stopCode) != 0)
        assertNotEquals(
            EngineFailure.shortReasonRes(0),
            EngineFailure.shortReasonRes(r.stopCode),
        )
    }

    @Test
    fun `skip provenance stays aligned for typed nodes and legacy lists`() {
        val typed = listOf(
            SkippedNode(41, 5, 15, EngineFailure.ENGINE_ERROR_FEATURES),
            SkippedNode(33, 3, 11, EngineFailure.ENGINE_ERROR_ROI),
        )
        assertEquals(typed, record(RecordArgs(skipNodes = typed)).resolvedSkipNodes())

        val legacy = record(
            RecordArgs(
                skipSubsets = listOf(41, 33),
                skipSteps = listOf(5, 3),
                skipWindows = listOf(15, 11),
                skipCodes = listOf(
                    EngineFailure.ENGINE_ERROR_FEATURES,
                    EngineFailure.ENGINE_ERROR_ROI,
                ),
            ),
        )
        assertEquals(2, legacy.resolvedSkipNodes().size)
        assertNotEquals(
            EngineFailure.shortReasonRes(legacy.resolvedSkipNodes()[0].code),
            EngineFailure.shortReasonRes(legacy.resolvedSkipNodes()[1].code),
        )
    }

    @Test
    fun `records written before these fields existed still load`() {
        val old = record(RecordArgs(stopCode = 0, plannedFrameCount = 0))

        assertFalse(old.stoppedEarly)
        assertTrue(old.resolvedSkipNodes().isEmpty())
    }
}
