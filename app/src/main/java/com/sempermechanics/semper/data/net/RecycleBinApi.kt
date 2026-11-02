package com.sempermechanics.semper.data.net

/**
 * The account's recycle bin (ADR-035): list it, restore one backup from it,
 * or erase one for good. [SemperApi] implements it; tests pass a fake (as
 * [CloudApi], ADR-002), so the many [CloudApi] fakes need not learn these
 * routes. `DELETE /v1/sessions/{sid}` ([CloudApi.deleteSession]) is what puts
 * a backup here.
 */
interface RecycleBinApi {
    /** A backend is configured and the debug sign-in bypass is off ([CloudApi.enabled]). */
    val enabled: Boolean

    /**
     * `GET /v1/recycle-bin`, every page: this app's binned backups and the
     * erased markers of those purged lately.
     */
    suspend fun listRecycleBin(idToken: String): RecycleBinResponse

    /**
     * `POST /v1/recycle-bin/{sid}/restore`, signed: put [sessionId] back in the
     * backups with the status it had. 404 `session_not_found` once it is gone
     * from the bin; 409 `session_quota_exceeded` / `device_quota_exceeded` with
     * no room for it.
     */
    suspend fun restoreFromRecycleBin(idToken: String, sessionId: String): RecycleBinRestoreResponse

    /** `DELETE /v1/recycle-bin/{sid}`, signed: erase [sessionId] for good, now. */
    suspend fun purgeFromRecycleBin(idToken: String, sessionId: String): RecycleBinPurgeResponse
}
