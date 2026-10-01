package com.indicvision.semper.data.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferLogTest {

    @Test
    fun `phase payload uses allowed keys only`() {
        val json = TransferLog.formatPhaseJson(
            TransferLog.PhaseFields(
                phase = "upload",
                outcome = "complete",
                requestId = "req-abc",
                attempt = 2,
                bytes = 4096L,
                stage = "upload",
                httpStatus = 200,
                count = 5,
                opClass = "backup",
            ),
        )
        assertTrue(json.contains("\"event\":\"transfer_phase\""))
        assertTrue(json.contains("\"phase\":\"upload\""))
        assertTrue(json.contains("\"outcome\":\"complete\""))
        assertTrue(json.contains("\"requestId\":\"req-abc\""))
        assertFalse(json.contains("errorCode"))
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
