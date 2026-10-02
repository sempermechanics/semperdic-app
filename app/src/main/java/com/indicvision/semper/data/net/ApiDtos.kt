package com.indicvision.semper.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The backend's resumable chunk size when a pending upload does not name one. */
private const val DEFAULT_UPLOAD_CHUNK_BYTES = 8 * 1024 * 1024

/**
 * Wire DTOs for the Semper GCP backend (FastAPI on Cloud Run). Field names match
 * the JSON contract in backend/app/models.py exactly. See
 * docs/backend/CLOUD_ARCHITECTURE_GCP.md for the full API.
 */

@Serializable
data class MeResponse(
    val uid: String,
    val email: String? = null,
    val role: String? = null,
    @SerialName("access_status") val accessStatus: String,
    /** Absent on backends predating the clickwrap gate; the app then falls back to its own constant. */
    val terms: TermsDto? = null,
    /** null = never answered; the app treats that as "not asked", never as consent. */
    @SerialName("improvement_consent") val improvementConsent: Boolean? = null,
    /** Read only to cross-check the /v1/config fetched beside it. */
    val license: MeLicenseDto? = null,
)

/** The part of /v1/me's `license` block the app reads: the effective mode. */
@Serializable
data class MeLicenseDto(val mode: String = "") {
    /**
     * True when [config] was answered in a different mode than this. /v1/me
     * claims a pending invitation as it answers, and the /v1/config fetched
     * beside it can read the account a moment before that lands.
     */
    fun disagreesWith(config: AppConfigDto): Boolean =
        mode.isNotBlank() && config.mode.isNotBlank() && !mode.equals(config.mode, ignoreCase = true)
}

/** Which Terms version the server requires, and which (if any) this account accepted. */
@Serializable
data class TermsDto(
    @SerialName("required_version") val requiredVersion: String,
    @SerialName("accepted_version") val acceptedVersion: String? = null,
    @SerialName("terms_url") val termsUrl: String? = null,
    @SerialName("privacy_url") val privacyUrl: String? = null,
)

@Serializable
data class TermsAcceptanceBody(val version: String)

@Serializable
data class ConsentUpdateBody(val improvement: Boolean)

/** Resolved product limits from GET /v1/config (per-user override → fleet default). */
@Serializable
data class AppConfigDto(
    val maxSessions: Int = 0,
    val maxFilesPerSession: Int = 0,
    val maxFrames: Int = 0,
    /** Version gate for the `.dat` archive codec — see [AppRemoteConfig] and
     * [com.indicvision.semper.data.session.SessionZip]'s class doc. Missing on an older
     * backend deploy this app talks to → false (fail closed, matches the
     * default already used for every field here). */
    val datCodecEncodingEnabled: Boolean = false,
    /** `demo` or `licensed`. Empty on a backend deploy predating the
     * plan→mode rename; [AppRemoteConfig] falls back to [plan] in that case,
     * so a blank here is "not told", not "demo". */
    val mode: String = "",
    /** Pre-rename spelling of [mode]: `demo` or `professional`. Still sent by
     * the backend alongside `mode` for builds that predate the rename, and
     * still read here as the fallback when `mode` is absent. Missing from both
     * → demo (fail closed). */
    val plan: String = "demo",
    val cloudBackupEnabled: Boolean = false,
    val shareEnabled: Boolean = false,
    val licensePrefix: String = "",
    /** `""`, `"individual"`, or `"institution"` — display/support metadata,
     * so Settings can show e.g. "Activated via university.edu" and support
     * tickets can tell the two shapes apart. A backend predating the rename
     * sends `"campus"`; [AppRemoteConfig] normalises it.
     *
     * Not a gating input: entitlements are identical for an individual and an
     * institution seat once [mode] is licensed. What differs on an institution
     * license is [licenseSeating], and it is that field — not this one — that
     * decides whether a seat has to be taken. */
    val licenseKind: String = "",
    /** `assigned` or `floating`, for an institution license. Empty on a
     * backend predating floating seats, which the cache reads as assigned —
     * every license that existed then entitled its members outright.
     *
     * `floating` plus [mode] `demo` is the one combination that means "you may
     * work, but somebody else is holding the seat": eligible, not blocked. */
    val licenseSeating: String = "",
    /** ISO-8601 instant this account's floating seat lapses, or null when it
     * holds none (and always null on an assigned license).
     *
     * Parsed so the contract stays honest, and deliberately not cached: the
     * backend folds the lease into `mode`, so a local copy of the lapse time
     * would only be a second, staler opinion of what `isLicensed` already
     * answers. The account console shows it; the app has no use for it. */
    val leaseExpiresAt: String? = null,
    /** How often to renew the seat. Renewing IS the heartbeat — the backend
     * has no separate route — so this is the interval between checkout calls
     * while work is in progress. 0 until a backend that knows about seats
     * answers. */
    val leaseHeartbeatMinutes: Int = 0,
    /** `perpetual` or `timed`. Empty on a backend predating duration; the
     * cache infers it from whether an expiry arrived. */
    val licenseDuration: String = "",
    /** ISO-8601 instant the license stops, or null when perpetual. Advisory —
     * the app warns from it but never gates on it, because a cached date can
     * be arbitrarily stale and a renewal may have landed while offline. */
    val licenseExpiresAt: String? = null,
    /** ISO-8601 instant entitlement actually ends: expiry plus the grace
     * window. Null when perpetual. */
    val licenseGraceEndsAt: String? = null,
    /** Past expiry but still fully entitled. Nothing is withdrawn — this only
     * says a renewal is overdue. */
    val inGrace: Boolean = false,
)

@Serializable
data class LicenseActivateRequest(val key: String)

@Serializable
data class LicenseActivateResponse(val config: AppConfigDto)

@Serializable
data class DeviceRegisterRequest(
    val deviceId: String,
    val publicKeyPem: String,
    val model: String = "",
    val osVersion: String = "",
    val appVersion: String = "",
)

@Serializable
data class ChallengeResponse(val nonce: String)

@Serializable
data class FileSpecDto(
    val name: String,
    val role: String, // one of ArtifactRoles.*
    val bytes: Long,
    val sha256: String,
)

@Serializable
data class SessionCreateRequest(
    val specimen: String,
    val files: List<FileSpecDto>,
    val metrics: Map<String, Float> = emptyMap(),
    /** The app's local analysis id, so cloud sessions can be matched back to it. */
    val localSessionId: String = "",
)

@Serializable
data class CloudSessionDto(
    val sessionId: String,
    val localSessionId: String = "",
    val specimen: String? = null,
    val status: String? = null,
    val fileCount: Int = 0,
    val completedCount: Int = 0,
    val totalBytes: Long = 0,
    val driveFolderId: String? = null,
)

@Serializable
data class QuotaDto(val used: Int = 0, val max: Int = 0)

@Serializable
data class ListSessionsResponse(
    val sessions: List<CloudSessionDto> = emptyList(),
    val quota: QuotaDto = QuotaDto(),
    val page: PageDto? = null,
)

@Serializable
data class PageDto(
    val size: Int = 0,
    val count: Int = 0,
    val nextPageToken: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class UploadTargetDto(
    val fileId: String,
    val uploadUrl: String,
    val chunkSize: Int,
)

@Serializable
data class SessionCreateResponse(
    val sessionId: String,
    /**
     * PROVISIONING while the backend opens the Drive resumable sessions in a
     * Cloud Task; UPLOADING once [uploads] is populated. Nullable so an older
     * backend that always provisions inline still parses.
     */
    val status: String? = null,
    val uploads: List<UploadTargetDto> = emptyList(),
)

@Serializable
data class FileCompleteRequest(
    val sessionId: String,
    val driveFileId: String,
    val bytes: Long,
    val md5: String? = null,
)

/** A file still awaiting bytes, with the resumable URI to continue into. */
@Serializable
data class PendingUploadDto(
    val fileId: String,
    val uploadUrl: String,
    val chunkSize: Int = DEFAULT_UPLOAD_CHUNK_BYTES,
    val name: String = "",
    val role: String = "",
    val sizeBytes: Long = 0,
)

@Serializable
data class SessionUploadsResponse(
    val sessionId: String,
    val status: String? = null,
    /** Set when status is PROVISION_FAILED — why the targets were never opened. */
    val provisionError: String? = null,
    val uploads: List<PendingUploadDto> = emptyList(),
    /** Set when more pending files follow; [IndicApi.sessionUploads] fetches them all. */
    val page: PageDto? = null,
)

@Serializable
data class CloudFileDto(
    val fileId: String,
    val name: String = "",
    val role: String = "",
    val sizeBytes: Long = 0,
    val sha256: String? = null,
    val status: String? = null,
)

@Serializable
data class SessionFilesResponse(
    val sessionId: String,
    val localSessionId: String = "",
    val specimen: String? = null,
    val status: String? = null,
    val files: List<CloudFileDto> = emptyList(),
    /** Set when more files follow; [IndicApi.listSessionFiles] fetches them all. */
    val page: PageDto? = null,
)

@Serializable
data class AdminUserDto(
    val uid: String,
    val email: String? = null,
    val displayName: String? = null,
    val role: String? = null,
    @SerialName("access_status") val accessStatus: String? = null,
    val activeDeviceId: String? = null,
)

@Serializable
data class AdminUsersResponse(val users: List<AdminUserDto>)
