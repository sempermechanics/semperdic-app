package com.sempermechanics.semper.data.net

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.data.account.DevAuth
import com.sempermechanics.semper.data.account.DeviceKeyManager
import com.sempermechanics.semper.data.net.drive.DriveTransfer
import com.sempermechanics.semper.data.net.drive.DriveUpload
import com.sempermechanics.semper.data.net.drive.DriveUploader
import com.sempermechanics.semper.util.AtomicFiles
import com.sempermechanics.semper.util.writeVia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File

/** Seat routes take no body; the backend reads the caller from the token. */
private const val EMPTY_JSON = "{}"

/** `GET /v1/sessions` page size. */
private const val SESSIONS_PAGE_SIZE = 100

/**
 * Client for the Semper GCP backend (Cloud Run / FastAPI).
 *
 * Every mutating call carries a Google **ID token** (user proof) plus a
 * challenge-response **device signature** (device proof, [SemperApiSigning]),
 * both sent through [SemperApiCalls].
 * File bytes go **directly to Google Drive** via the resumable session URI
 * returned by the broker — they never pass through this client's backend host.
 */
@Suppress("TooManyFunctions") // one method per backend endpoint
class SemperApi @VisibleForTesting internal constructor(
    context: Context,
    baseUrl: String,
    /** How calls are sent, from [endpoint]; null for the shared clients and this device's key. */
    newCalls: ((endpoint: (path: String) -> String) -> SemperApiCalls)?,
) : CloudApi {

    private constructor(context: Context) : this(context, BuildConfig.SEMPER_API_BASE_URL, null)

    private val appContext = context.applicationContext

    // Lazy: DeviceKeyManager touches the AndroidKeyStore in its constructor,
    // which only exists on a device. Deferring it keeps every non-signed path
    // (uploads, downloads, probes) constructible in JVM unit tests.
    private val device by lazy { DeviceKeyManager(appContext) }
    private val json = SemperApiHttp.json

    private val base = baseUrl.trimEnd('/').also { url ->
        require(url.isEmpty() || url.startsWith("https://")) {
            "SEMPER_API_BASE_URL must be https (or empty to disable cloud): $url"
        }
    }

    /**
     * Cloud calls are possible: a base URL is configured and we are not running
     * under the debug emulator sign-in bypass (which has no Firebase user, so
     * every authenticated call would fail — see [DevAuth]).
     */
    override val enabled: Boolean get() = base.isNotBlank() && !DevAuth.active

    /** The backend URL for [path]; see [SemperApiHttp.endpoint]. */
    private fun endpoint(path: String): String = SemperApiHttp.endpoint(base, path)

    private val drive = DriveTransfer(SemperApiClients.api, SemperApiClients.download, SemperApiHttp.OCTET_MEDIA)
    private val calls = newCalls?.invoke(::endpoint) ?: SemperApiCalls(
        SemperApiClients.api,
        ::endpoint,
        deviceId = { device.getDeviceId() },
        sign = { device.signMessage(it) },
    )

    private inline fun <reified T> decode(resp: Response): T = json.decodeFromString(resp.body.string())

    private inline fun <reified T> jsonBody(value: T): RequestBody =
        json.encodeToString(value).toRequestBody(SemperApiHttp.JSON_MEDIA)

    private inline fun <reified T> jsonBytes(value: T): ByteArray = json.encodeToString(value).toByteArray()

    // ---------------------------------------------------------------- identity

    /** GET /v1/me. Throws [NotApprovedException] for a PENDING/SUSPENDED user. */
    override suspend fun getMe(idToken: String): MeResponse =
        calls.bearer(idToken, { url(endpoint("/v1/me")) }, ApiAnswer::failMe) { decode(it) }

    /**
     * GET /v1/config — resolved product limits for this account.
     *
     * At launch the status check and the cloud reconcile both ask for it within
     * ~100 ms, so a call that arrives while one is running shares its answer
     * (docs/perf/request-volume.md, Pass 2). Both callers hold the same signed-in
     * user's token.
     */
    override suspend fun getConfig(idToken: String): AppConfigDto = configFlight.run {
        calls.bearer(idToken, { url(endpoint("/v1/config")) }, ApiAnswer::failApprovedOnly) { decode(it) }
    }

    private val configFlight = SingleFlight<AppConfigDto>()

    /**
     * GET /v1/me/export — everything the cloud holds about this account, as JSON
     * (GDPR Art. 20 portability): profile, registered devices, and every analysis
     * with its file manifest.
     *
     * Streams straight to [dest] rather than into memory: the response is
     * unbounded in principle, and the backend emits it incrementally.
     *
     * Device-signed like account erasure — a full-account dump is high enough
     * consequence that a stolen ID token alone must not trigger it.
     *
     * Distinct from the local "Export my data" zip, which only bundles what is on
     * this device.
     */
    override suspend fun exportAccount(idToken: String, dest: File): Unit =
        calls.signed(idToken, SignedCall("GET", "/v1/me/export")) { resp ->
            // Promoted only after the whole body landed: a truncated transfer
            // must not look like a complete export.
            AtomicFiles.writeVia(dest) { part ->
                resp.body.byteStream().use { input ->
                    part.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
        }

    /**
     * POST /v1/devices/register. Registers this device's public key.
     * 201 → registered, 409 → another device already bound (needs admin rebind).
     * Requires an APPROVED user. The one call that sends no `X-Device-Id`
     * header: the id is in the body.
     */
    override suspend fun registerDevice(idToken: String) = withContext(Dispatchers.IO) {
        val body = DeviceRegisterRequest(
            deviceId = device.getDeviceId(),
            publicKeyPem = device.getPublicKeyPem(),
            model = android.os.Build.MODEL ?: "",
            osVersion = "Android ${android.os.Build.VERSION.RELEASE}",
            appVersion = BuildConfig.VERSION_NAME,
        )
        val request = Request.Builder().url(endpoint("/v1/devices/register"))
            .header(SemperApiHttp.AUTHORIZATION, "Bearer $idToken")
            .post(jsonBody(body)).build()
        SemperApiClients.api.newCall(request).execute().use { resp ->
            when (resp.code) {
                HttpStatus.CREATED, HttpStatus.OK -> Unit
                HttpStatus.CONFLICT -> throw DeviceConflictException(SemperApiHttp.requestIdOf(resp))
                else -> throw ApiAnswer.of(resp).exception()
            }
        }
    }

    /**
     * POST /v1/licenses/activate — redeem a license key (individual or
     * institution; the backend tells them apart by the key itself).
     * Bearer + `X-Device-Id` like [registerDevice], **not** device-signed: the
     * backend route is `current_user` + a plain `X-Device-Id` header, no
     * challenge/nonce/signature. Every subsequent signed/bearer call still
     * re-validates the resulting lock (see [AppRemoteConfig] /
     * `revalidate_device_lock` in the backend) — this call only kicks it off.
     *
     * Throws [ApiException] with the backend's error code as `detail` for a
     * mismatch/revoked/exhausted key (`license_email_mismatch`,
     * `license_device_mismatch`, `license_revoked`, `license_seat_disabled`,
     * `license_seats_exhausted`, `license_already_redeemed`, `license_not_found`).
     */
    override suspend fun activateLicense(idToken: String, key: String): AppConfigDto = calls.bearer(
        idToken,
        route = { url(endpoint("/v1/licenses/activate")).post(jsonBody(LicenseActivateRequest(key = key))) },
    ) { decode<LicenseActivateResponse>(it).config }

    /**
     * POST /v1/licenses/checkout — take or renew a floating seat.
     *
     * Calling it again IS the heartbeat: renewing does not consume a second
     * seat, so there is no separate route to get that wrong. Call it every
     * [AppConfigDto.leaseHeartbeatMinutes] while work is in progress.
     *
     * Throws [NoSeatAvailableException] when the pool is full. That is not a
     * problem with the account — the member is still eligible and still in
     * demo — so it is a distinct type rather than a generic [ApiException],
     * to stop a caller rendering "something went wrong" for it.
     */
    override suspend fun checkoutLease(idToken: String): AppConfigDto = seatCall(idToken, "checkout")

    /** POST /v1/licenses/release — give a floating seat back. Idempotent. */
    override suspend fun releaseLease(idToken: String): AppConfigDto = seatCall(idToken, "release")

    private suspend fun seatCall(idToken: String, action: String): AppConfigDto = calls.bearer(
        idToken,
        route = { url(endpoint("/v1/licenses/$action")).post(EMPTY_JSON.toRequestBody(SemperApiHttp.JSON_MEDIA)) },
        orElse = { answer ->
            if (answer.code == HttpStatus.CONFLICT && answer.hasCode(ApiErrors.NO_FLOATING_SEAT)) {
                throw NoSeatAvailableException()
            }
            answer.fail()
        },
    ) { decode<LicenseActivateResponse>(it).config }

    // ---------------------------------------------------------- legal / consent

    /**
     * POST /v1/me/terms — record clickwrap acceptance of [version].
     *
     * Plain bearer auth (like [registerDevice]): the gate runs at registration,
     * before a device is registered and before an operator has approved the
     * account, so it can require neither attestation nor APPROVED status.
     * Throws [TermsVersionMismatchException] when the server no longer serves
     * [version] — the app is older than the published Terms.
     */
    override suspend fun acceptTerms(idToken: String, version: String): Unit = calls.bearer(
        idToken,
        route = { url(endpoint("/v1/me/terms")).post(jsonBody(TermsAcceptRequest(version))) },
        orElse = { answer ->
            if (answer.code == HttpStatus.CONFLICT) throw TermsVersionMismatchException(answer.requestId)
            answer.fail()
        },
    ) {}

    /** PUT /v1/me/consents — grant or withdraw the optional product-improvement consent. */
    override suspend fun setImprovementConsent(idToken: String, granted: Boolean): Unit =
        calls.bearer(idToken, { url(endpoint("/v1/me/consents")).put(jsonBody(ConsentUpdateRequest(granted))) }) {}

    // ----------------------------------------------------------- session/files

    /**
     * GET /v1/sessions — the caller's cloud analyses (for sync reconciliation).
     *
     * Follows `nextPageToken` until the account listing is complete so quota
     * reconciliation is not silently truncated by server page size. The quota
     * is the last page's.
     *
     * [verify] makes the backend also confirm each page's session blobs still
     * exist in Drive (catching artifacts deleted straight in Drive). It costs
     * Drive calls per page, so it's for explicit refreshes, not every resume.
     */
    override suspend fun listSessions(idToken: String, verify: Boolean): SessionsResponse {
        val pages = fetchAllPages(
            fetch = { token ->
                val query = buildString {
                    append("page_size=$SESSIONS_PAGE_SIZE")
                    if (verify) append("&verify=true")
                    if (token != null) append('&').append(pageTokenParam(token))
                }
                calls.bearer(idToken, { url(endpoint("/v1/sessions?$query")) }, ApiAnswer::failApprovedOnly) {
                    decode<SessionsResponse>(it)
                }
            },
            pageOf = { it.page },
        )
        return SessionsResponse(sessions = pages.flatMap { it.sessions }, quota = pages.last().quota)
    }

    /** POST /v1/sessions (device-signed). Initiates a session + one resumable target per file. */
    override suspend fun createSession(idToken: String, request: SessionCreateRequest): SessionCreateResponse =
        calls.signed(idToken, SignedCall("POST", "/v1/sessions", jsonBytes(request))) { decode(it) }

    /**
     * GET /v1/sessions/{sid}/uploads — what still needs uploading, every page
     * of it. A later page's query string is inside the signature.
     *
     * The resume path: an interrupted upload continues into the same session
     * instead of POSTing a new one (which would duplicate the Drive folder and
     * consume another slot of the analysis quota).
     *
     * Device-signed, not just token-authenticated: the response carries Drive
     * resumable upload URIs, which are bearer capabilities to write into the
     * user's Drive folder. The backend requires attestation here for the same
     * reason it does on createSession — a stolen ID token must not be able to
     * recover them.
     */
    override suspend fun listSessionUploads(idToken: String, sessionId: String): SessionUploadsResponse =
        fetchAllPages(
            fetch = { token ->
                val path = "/v1/sessions/$sessionId/uploads" + pageTokenQuery(token)
                calls.signed(idToken, SignedCall("GET", path)) { decode<SessionUploadsResponse>(it) }
            },
            pageOf = { it.page },
        ).merged()

    /** POST /v1/files/{id}/complete (device-signed). */
    override suspend fun completeFile(idToken: String, fileId: String, request: FileCompleteRequest) =
        calls.signed(idToken, SignedCall("POST", "/v1/files/$fileId/complete", jsonBytes(request))) {}

    /**
     * PUT /v1/sessions/{sid}/metadata — replace a backed-up analysis's
     * metadata.json with [metadataJson], for a change made after the backup
     * (ADR-013). Device-signed; any non-200 throws [ApiException].
     */
    override suspend fun replaceSessionMetadata(idToken: String, sessionId: String, metadataJson: String) =
        calls.signed(idToken, SignedCall("PUT", "/v1/sessions/$sessionId/metadata", metadataJson.toByteArray())) {}

    // ----------------------------------------------------------------- restore

    /**
     * GET /v1/sessions/{sid}/files — the manifest for one cloud analysis, every
     * page of it: a restore from a truncated manifest would quietly miss files.
     */
    override suspend fun listSessionFiles(idToken: String, sessionId: String): SessionFilesResponse =
        fetchAllPages(
            fetch = { token ->
                calls.bearer(idToken, { url(endpoint("/v1/sessions/$sessionId/files" + pageTokenQuery(token))) }) {
                    decode<SessionFilesResponse>(it)
                }
            },
            pageOf = { it.page },
        ).merged()

    /**
     * Fetch only `[rangeStart, rangeStart + length)` of an object.
     *
     * Restore uses this to read a legacy backup's central directory and then just the
     * prefix of entries it needs, instead of the whole archive.
     */
    override suspend fun downloadRange(idToken: String, fileId: String, dest: File, rangeStart: Long, length: Long) =
        drive.downloadFile(
            fileId,
            dest,
            endpoint(""), // the base; DriveTransfer appends its own paths
            expectedBytes = length,
            rangeStart = rangeStart,
            signedGetHeaders = calls.signedGet(idToken),
        )

    /** GET /v1/files/{id}/content into [dest], device-attested per window: [DriveTransfer.downloadFile]. */
    override suspend fun downloadFile(
        idToken: String,
        fileId: String,
        dest: File,
        expectedBytes: Long,
        onBytes: suspend (haveBytes: Long) -> Unit,
    ) = drive.downloadFile(
        fileId,
        dest,
        endpoint(""), // the base; DriveTransfer appends its own paths
        expectedBytes = expectedBytes,
        onBytes = onBytes,
        signedGetHeaders = calls.signedGet(idToken),
    )

    // ------------------------------------------------------------------- admin

    /** GET /v1/admin/users?status=… (admin ID token; no device signature). */
    override suspend fun listUsers(idToken: String, status: String): List<AdminUserDto> = calls.bearer(
        idToken,
        route = { url(endpoint(if (status.isBlank()) "/v1/admin/users" else "/v1/admin/users?status=$status")) },
        orElse = { answer ->
            if (answer.code == HttpStatus.FORBIDDEN) {
                throw ApiException(HttpStatus.FORBIDDEN, ApiErrors.NOT_ADMIN, answer.requestId)
            }
            answer.fail()
        },
    ) { decode<AdminUsersResponse>(it).users }

    /** POST /v1/admin/users/{uid}/{action} — device-attested (approve/revoke). */
    override suspend fun setUserStatus(idToken: String, uid: String, action: String) =
        calls.signed(idToken, SignedCall("POST", "/v1/admin/users/$uid/$action"), ApiAnswer::fail) {}

    // ----------------------------------------------------------------- erasure

    /**
     * DELETE /v1/me — erase the account and every analysis it owns from the
     * cloud. The caller must sign out immediately afterwards; any further
     * authenticated call would create a fresh, empty profile.
     *
     * No 404-is-fine shortcut here: this endpoint never legitimately 404s, so
     * a 404 means the route isn't reachable (e.g. not published on the API
     * Gateway). Treating that as success would wipe the local copy while
     * leaving every byte in the cloud.
     */
    override suspend fun deleteAccount(idToken: String) = calls.signed(idToken, SignedCall("DELETE", "/v1/me")) {}

    /**
     * DELETE /v1/sessions/{id} — erase an analysis from the cloud: the Drive
     * folder (raw images, .dat, csv, report) and all Firestore metadata.
     * Permanent; there is no undo.
     *
     * A 404 is only "already erased" when OUR backend says so
     * (`session_not_found`). A bare 404 means the route isn't reachable —
     * accepting that as success would delete the local copy and orphan the
     * cloud data forever.
     */
    override suspend fun deleteSession(idToken: String, sessionId: String) = calls.signed(
        idToken,
        SignedCall("DELETE", "/v1/sessions/$sessionId"),
        orElse = { answer ->
            if (answer.code != HttpStatus.NOT_FOUND || !answer.hasCode(ApiErrors.SESSION_NOT_FOUND)) {
                answer.failSigned()
            }
        },
    ) {}

    // ------------------------------------------------- direct-to-Drive uploads

    /**
     * Resumable upload of [file] straight to Drive ([DriveUploader.uploadResumable]), as the
     * `(driveFileId, localMd5Hex)` pair ([DriveUpload.toPair]) that `:complete` needs.
     */
    override suspend fun uploadResumable(
        uploadUrl: String,
        file: File,
        chunkSize: Int,
        onBytes: (Long) -> Unit,
    ): Pair<String, String> = drive.uploadResumable(uploadUrl, file, chunkSize, onBytes).toPair()

    companion object {
        @Volatile
        private var instance: SemperApi? = null

        /**
         * Process-wide client. Shares OkHttp pools and DeviceKeyManager; call sites
         * must not construct [SemperApi] directly.
         */
        fun get(context: Context): SemperApi {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: SemperApi(context.applicationContext).also { instance = it }
            }
        }
    }
}
