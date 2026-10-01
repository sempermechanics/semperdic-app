package com.indicvision.semper.data.net

import java.io.File

/**
 * What the app asks of the Semper backend: the public surface of [IndicApi]
 * (ADR-002).
 *
 * It exists so the classes with decisions to test — [com.indicvision.semper.data.account.AuthRepository],
 * [com.indicvision.semper.data.account.SeatLease], [com.indicvision.semper.data.cloud.CloudSync]
 * and the cloud export — can take a fake in a JVM test. Each takes it as a
 * defaulted parameter, `api: CloudApi = IndicApi.get(context)`, so production
 * call sites do not change. The failures stay nested in [IndicApi]
 * ([IndicApi.ApiException], [IndicApi.NotApprovedException], …), so no `catch`
 * moves.
 *
 * Every call runs off the main thread on its own; the defaults below are the
 * only place they are declared, because an override may not restate them.
 */
@Suppress("TooManyFunctions") // one member per backend route, as in IndicApi
interface CloudApi {
    /** A backend is configured and the debug sign-in bypass is off. */
    val enabled: Boolean

    suspend fun me(idToken: String): MeResponse

    suspend fun getConfig(idToken: String): AppConfigDto

    suspend fun exportAccount(idToken: String, dest: File)

    suspend fun registerDevice(idToken: String)

    suspend fun activateLicense(idToken: String, key: String): AppConfigDto

    suspend fun checkoutLease(idToken: String): AppConfigDto

    suspend fun releaseLease(idToken: String): AppConfigDto

    suspend fun acceptTerms(idToken: String, version: String)

    suspend fun setImprovementConsent(idToken: String, granted: Boolean)

    suspend fun listSessions(idToken: String, verify: Boolean = false): ListSessionsResponse

    suspend fun createSession(idToken: String, request: SessionCreateRequest): SessionCreateResponse

    suspend fun sessionUploads(idToken: String, sessionId: String): SessionUploadsResponse

    suspend fun completeFile(idToken: String, fileId: String, request: FileCompleteRequest)

    suspend fun listSessionFiles(idToken: String, sessionId: String): SessionFilesResponse

    suspend fun replaceSessionMetadata(idToken: String, sessionId: String, metadataJson: String)

    suspend fun downloadRange(idToken: String, fileId: String, dest: File, rangeStart: Long, length: Long)

    suspend fun downloadFile(
        idToken: String,
        fileId: String,
        dest: File,
        expectedBytes: Long = -1L,
        onBytes: suspend (haveBytes: Long) -> Unit = {},
    )

    suspend fun listUsers(idToken: String, status: String = ""): List<AdminUserDto>

    suspend fun setUserStatus(idToken: String, uid: String, action: String)

    suspend fun deleteAccount(idToken: String)

    suspend fun deleteSession(idToken: String, sessionId: String)

    suspend fun uploadResumable(
        uploadUrl: String,
        file: File,
        chunkSize: Int,
        onBytes: (Long) -> Unit = {},
    ): Pair<String, String>
}

/** The signed-in user's Firebase ID token, refreshed when near expiry (ADR-002). */
fun interface TokenSource {
    /** A token the backend will accept, or null when signed out or offline. */
    suspend fun usableIdToken(): String?
}
