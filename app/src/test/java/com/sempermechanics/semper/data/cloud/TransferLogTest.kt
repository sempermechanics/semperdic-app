package com.sempermechanics.semper.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferLogTest {

    /**
     * Pins the exact key set of a fully populated line. The JSON goes to logs,
     * so a new key is a PII decision: adding one must fail here first.
     */
    @Test
    fun `phase payload carries exactly the eleven backend keys`() {
        val json = TransferLog.formatPhaseJson(
            TransferLog.PhaseFields(
                phase = "upload",
                outcome = "complete",
                errorCode = "quota_exceeded",
                requestId = "req-abc",
                attempt = 2,
                bytes = 4096L,
                stage = "upload",
                httpStatus = 200,
                count = 5,
                opClass = "backup",
            ),
        )
        val keys = Regex("\"(\\w+)\":").findAll(json).map { it.groupValues[1] }.toList()
        assertEquals(
            setOf(
                "event", "phase", "outcome", "errorCode", "requestId",
                "attempt", "bytes", "stage", "httpStatus", "count", "opClass",
            ),
            keys.toSet(),
        )
        assertEquals("no key repeats", keys.size, keys.toSet().size)
        assertTrue(json.contains("\"event\":\"transfer_phase\""))
        assertTrue(json.contains("\"requestId\":\"req-abc\""))
    }

    @Test
    fun `phase payload omits null fields`() {
        val json = TransferLog.formatPhaseJson(
            TransferLog.PhaseFields(phase = "upload", outcome = "complete"),
        )
        assertEquals("{\"event\":\"transfer_phase\",\"phase\":\"upload\",\"outcome\":\"complete\"}", json)
    }

    @Test
    fun `phase payload omits blank strings`() {
        val json = TransferLog.formatPhaseJson(
            TransferLog.PhaseFields(phase = "restore", outcome = "retry", requestId = "  "),
        )
        assertFalse(json.contains("requestId"))
    }

    @Test
    fun `phase payload never includes path or session patterns`() {
        val json = TransferLog.formatPhaseJson(
            TransferLog.PhaseFields(
                phase = "upload",
                outcome = "complete",
                requestId = "opaque-ref-123",
            ),
        )
        assertFalse(json.contains("/"))
        assertFalse(json.contains("session", ignoreCase = true))
        assertFalse(json.contains("path", ignoreCase = true))
    }
}
