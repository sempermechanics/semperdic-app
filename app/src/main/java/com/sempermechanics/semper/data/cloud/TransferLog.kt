package com.sempermechanics.semper.data.cloud

import timber.log.Timber

/** PII-safe JSON log lines for upload/restore phases; field names match the backend. */
object TransferLog {

    /** Optional metadata for a transfer-phase log line. */
    data class PhaseFields(
        val phase: String,
        val outcome: String,
        val errorCode: String? = null,
        val requestId: String? = null,
        val attempt: Int? = null,
        val bytes: Long? = null,
        val stage: String? = null,
        val httpStatus: Int? = null,
        val count: Int? = null,
        val opClass: String? = null,
    )

    fun phase(fields: PhaseFields) {
        Timber.i(formatPhaseJson(fields))
    }

    internal fun formatPhaseJson(fields: PhaseFields): String {
        val packed = linkedMapOf<String, Any?>(
            "event" to "transfer_phase",
            "phase" to fields.phase,
            "outcome" to fields.outcome,
            "errorCode" to fields.errorCode?.takeIf { it.isNotBlank() },
            "requestId" to fields.requestId?.takeIf { it.isNotBlank() },
            "attempt" to fields.attempt,
            "bytes" to fields.bytes,
            "stage" to fields.stage?.takeIf { it.isNotBlank() },
            "httpStatus" to fields.httpStatus,
            "count" to fields.count,
            "opClass" to fields.opClass?.takeIf { it.isNotBlank() },
        )
        return buildString {
            append('{')
            var first = true
            for ((key, value) in packed) {
                if (value == null) continue
                if (!first) append(',')
                first = false
                append('"').append(key).append("\":")
                when (value) {
                    is String -> append('"').append(value).append('"')
                    else -> append(value)
                }
            }
            append('}')
        }
    }
}
