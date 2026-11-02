package com.sempermechanics.semper.data.net

import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * Wire DTOs for the recycle bin (ADR-035): `backend/app/routers/recycle_bin.py`
 * and `DELETE /v1/sessions/{sid}`'s answer. The listing's shape is pinned by
 * `contracts/recycle_bin_response.json`. Every field has a default, so a
 * backend that predates one still decodes.
 */

/** One binned backup: what it was, its status when binned, and when it is erased for good. */
@Serializable
data class RecycleBinItemDto(
    val sessionId: String,
    val localSessionId: String = "",
    val name: String = "",
    val specimen: String? = null,
    val app: String = "",
    /** The status it had when binned, which a restore puts back. */
    val status: String = "",
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
    /** ISO-8601. */
    val deletedAt: String? = null,
    /** ISO-8601: when the bin erases it. A restore past this is refused. */
    val purgeAt: String? = null,
) {
    /** [purgeAt] in epoch ms, 0 when absent or unreadable. */
    val purgeAtMs: Long get() = isoToEpochMs(purgeAt)
}

/** An erased marker: a backup purged from the bin lately, by ids alone. */
@Serializable
data class ErasedBackupDto(
    val sessionId: String,
    val localSessionId: String = "",
    /** ISO-8601. */
    val erasedAt: String? = null,
)

/** `GET /v1/recycle-bin`: one page of the bin, or every page merged ([SemperApi.listRecycleBin]). */
@Serializable
data class RecycleBinResponse(
    val items: List<RecycleBinItemDto> = emptyList(),
    val erased: List<ErasedBackupDto> = emptyList(),
    val retentionDays: Int = 0,
    val page: PageDto? = null,
)

/** `POST /v1/recycle-bin/{sid}/restore`'s answer. */
@Serializable
data class RecycleBinRestoreResponse(
    val sessionId: String = "",
    val localSessionId: String = "",
    val status: String? = null,
    val restored: Boolean = false,
)

/** `DELETE /v1/recycle-bin/{sid}`'s answer: the erased marker's ids. */
@Serializable
data class RecycleBinPurgeResponse(
    val sessionId: String = "",
    val localSessionId: String = "",
    val filesRemoved: Int = 0,
    val erased: Boolean = false,
)

/**
 * `DELETE /v1/sessions/{sid}`'s answer. A backend with the bin bins the
 * session ([binned], erased for good at [purgeAt]); an older one erased it,
 * and an already-gone session (404 `session_not_found`) answers the defaults.
 */
@Serializable
data class SessionDeleteResponse(
    val deleted: String = "",
    val filesRemoved: Int = 0,
    val binned: Boolean = false,
    /** ISO-8601. */
    val purgeAt: String? = null,
) {
    /** [purgeAt] in epoch ms, 0 when absent or unreadable. */
    val purgeAtMs: Long get() = isoToEpochMs(purgeAt)
}

/**
 * Epoch ms for an ISO-8601 instant with an offset (`+00:00` as the backend
 * writes it, or `Z`), microseconds and all; 0 when absent or unreadable.
 */
internal fun isoToEpochMs(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0L
    return runCatching { OffsetDateTime.parse(raw.trim()).toInstant().toEpochMilli() }.getOrDefault(0L)
}
