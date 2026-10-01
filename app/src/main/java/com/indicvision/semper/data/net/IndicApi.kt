package com.indicvision.semper.data.net

import android.content.Context
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.data.DevAuth
import com.indicvision.semper.data.DeviceKeyManager
import com.indicvision.semper.util.AtomicFiles
import com.indicvision.semper.util.Digests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

// OkHttp client timeouts, in seconds.
private const val CONNECT_TIMEOUT_S = 30L
private const val WRITE_TIMEOUT_S = 300L
private const val READ_TIMEOUT_S = 60L
private const val DOWNLOAD_READ_TIMEOUT_S = 300L

/** Enough of a 401 body to read its `detail` code. */
private const val REFUSAL_PEEK_BYTES = 4096L

/** Seat routes take no body; the backend reads the caller from the token. */
private const val EMPTY_JSON = "{}"

/**
 * Whether [resp] is the server refusing a client nonce. Finding out reads the
 * body, which can fail (a connection reset mid-body); [resp] is closed then,
 * since the caller never gets it back to close.
 */
internal fun isClientNonceRefusal(resp: Response): Boolean {
    if (resp.code != HttpStatus.UNAUTHORIZED) return false
    var read = false
    try {
        val refused = ClientNonce.isRefusal(resp.code, resp.peekBody(REFUSAL_PEEK_BYTES).string())
        read = true
        return refused
    } finally {
        if (!read) resp.close()
    }
}

/**
 * Client for the Semper GCP backend (Cloud Run / FastAPI).
 *
 * Every mutating call carries a Google **ID token** (user proof) plus a
 * challenge-response **device signature** (device proof). File bytes go
 * **directly to Google Drive** via the resumable session URI returned by the
 * broker — they never pass through this client's backend host.
 */
@Suppress("TooManyFunctions") // one method per backend endpoint plus signing helpers
class IndicApi private constructor(context: Context) : CloudApi {

    private val appContext = context.applicationContext

    // Lazy: DeviceKeyManager touches the AndroidKeyStore in its constructor,
    // which only exists on a device. Deferring it keeps every non-signed path
    // (uploads, downloads, probes) constructible in JVM unit tests.
    private val device by lazy { DeviceKeyManager(appContext) }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val base = BuildConfig.INDIC_API_BASE_URL.trimEnd('/').also { url ->
        require(url.isEmpty() || url.startsWith("https://")) {
            "INDIC_API_BASE_URL must be https (or empty to disable cloud): $url"
        }
    }

    /**
     * Cloud calls are possible: a base URL is configured and we are not running
     * under the debug emulator sign-in bypass (which has no Firebase user, so
     * every authenticated call would fail — see [DevAuth]).
     */
    override val enabled: Boolean get() = base.isNotBlank() && !DevAuth.active

    /** The backend URL for [path]; see [IndicApiHttp.endpoint]. */
    private fun url(path: String): String = IndicApiHttp.endpoint(base, path)

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val octet = "application/octet-stream".toMediaType()
    private val drive = DriveTransfer(client, downloadClient, octet)

    /**
     * A non-2xx from the Semper backend.
     *
     * [requestId] is the response's `X-Request-Id` when the backend answered at
     * all (absent for a gateway kill with no headers). It is carried into the
     * message so every place that already shows or logs an exception message —
     * the restore worker's failure output, Timber, Crashlytics — becomes
     * joinable with the backend access log without touching those call sites.
     */
    class ApiException(
        val code: Int,
        val body: String,
        val requestId: String? = null,
    ) : IOException(IndicApiHttp.withRef("HTTP $code: $body", requestId)) {

        val parsedDetail: String get() = ApiErrors.detailOf(body)
    }

    class NotApprovedException : IOException(ApiErrors.NOT_APPROVED)

    /**
     * No backend is configured (`INDIC_API_BASE_URL` is empty). An [IOException],
     * so every caller treats it like offline instead of crashing (TD-90).
     */
    class CloudNotConfiguredException : IOException("Cloud backend is not configured (INDIC_API_BASE_URL).")

    /** The Terms this build carries are older than the ones the server publishes (409). */
    class TermsVersionMismatchException(val requestId: String? = null) : IOException(ApiErrors.TERMS_VERSION_MISMATCH)

    /**
     * This account is bound to a *different* device (registration refused).
     * [requestId] is the backend's `X-Request-Id` when it answered — a device
     * rebind is a support conversation, so the log line has to be findable.
     */
    class DeviceConflictException(val requestId: String? = null) : IOException(ApiErrors.DEVICE_CONFLICT)

    /**
     * This device is already bound to a different account (409 from `GET /v1/me`).
     */
    class DeviceInUseException(val requestId: String? = null) : IOException(ApiErrors.DEVICE_IN_USE)

    /**
     * Every floating seat on the institution's license is in use right now.
     *
     * Not an account problem: the caller is still on the roster and still
     * entitled to a seat as soon as one frees. Distinct from [ApiException] so
     * callers cannot render it as a generic failure.
     */
    class NoSeatAvailableException : IOException(ApiErrors.NO_FLOATING_SEAT)

    /**
     * The backend has no ACTIVE device record for us — the record was revoked or
     * deleted server-side while we still believed we were registered. Callers
     * should re-register and retry rather than give up.
     */
    class DeviceNotActiveException(val requestId: String? = null) : IOException(ApiErrors.DEVICE_NOT_ACTIVE)

    /** Maps a failed signed-request response to the most specific exception. */
    private fun failSigned(resp: Response): Nothing =
        failSigned(resp.code, IndicApiHttp.bodyText(resp), IndicApiHttp.requestIdOf(resp))

    /**
     * As above when the body has already been read. Matches on the parsed
     * `detail` code ([ApiErrors]) rather than a substring of the whole body.
     */
    @Suppress("ThrowsCount") // one throw per distinct 409 sub-reason, then the fallback
    private fun failSigned(code: Int, body: String, requestId: String?): Nothing {
        if (code == HttpStatus.CONFLICT) {
            val detail = ApiErrors.detailOf(body)
            if (ApiErrors.isCode(detail, ApiErrors.DEVICE_NOT_ACTIVE)) throw DeviceNotActiveException(requestId)
            if (ApiErrors.isCode(detail, ApiErrors.DEVICE_IN_USE)) throw DeviceInUseException(requestId)
            if (ApiErrors.isCode(detail, ApiErrors.DEVICE_CONFLICT)) throw DeviceConflictException(requestId)
        }
        throw ApiException(code, body, requestId)
    }

    /**
     * The 403/other mapping shared by the token-authenticated endpoints that are
     * only reachable by an approved account.
     */
    private val approvedOnly: (Int, String, String?) -> Nothing = { code, body, ref ->
        if (code == HttpStatus.FORBIDDEN) throw NotApprovedException() else throw ApiException(code, body, ref)
    }

    /**
     * A bearer-authenticated GET. Returns the 200 body; on any other status
     * defers to [onError], which throws the endpoint's most specific exception
     * (the default is a plain [ApiException]).
     */
    private fun authedGet(
        idToken: String,
        url: String,
        onError: (Int, String, String?) -> Nothing = { code, body, ref -> throw ApiException(code, body, ref) },
    ): String {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $idToken")
            .header("X-Device-Id", device.getDeviceId())
            .get().build()
        client.newCall(req).execute().use { resp ->
            return if (resp.code == HttpStatus.OK) {
                resp.body.string()
            } else {
                onError(resp.code, IndicApiHttp.bodyText(resp), IndicApiHttp.requestIdOf(resp))
            }
        }
    }

    // ---------------------------------------------------------------- identity

    /** GET /v1/me. Throws [NotApprovedException] for a PENDING/SUSPENDED user. */
    override suspend fun me(idToken: String): MeResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(
            authedGet(idToken, url("/v1/me")) { code, body, ref ->
                if (code == HttpStatus.FORBIDDEN) throw NotApprovedException()
                if (code == HttpStatus.CONFLICT) throwForMeConflict(body, ref)
                throw ApiException(code, body, ref)
            },
        )
    }

    /**
     * GET /v1/config — resolved product limits for this account.
     *
     * At launch the status check and the cloud reconcile both ask for it within
     * ~100 ms, so a call that arrives while one is running shares its answer
     * (docs/perf/request-volume.md, Pass 2). Both callers hold the same signed-in
     * user's token.
     */
    override suspend fun getConfig(idToken: String): AppConfigDto = configFlight.run {
        withContext(Dispatchers.IO) {
            json.decodeFromString(
                authedGet(idToken, url("/v1/config"), approvedOnly),
            )
        }
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
    override suspend fun exportAccount(idToken: String, dest: java.io.File): Unit = withContext(Dispatchers.IO) {
        val resp = signedRequest(idToken, "GET", "/v1/me/export", ByteArray(0))
        resp.use {
            if (it.code != HttpStatus.OK) failSigned(it)
            val part = AtomicFiles.partOf(dest)
            it.body.byteStream().use { input ->
                part.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            // Rename only after the whole body landed: a truncated transfer must
            // not look like a complete export.
            AtomicFiles.promote(part, dest)
        }
    }

    /**
     * POST /v1/devices/register. Registers this device's public key.
     * 201 → registered, 409 → another device already bound (needs admin rebind).
     * Requires an APPROVED user.
     */
    override suspend fun registerDevice(idToken: String) = withContext(Dispatchers.IO) {
        val body = DeviceRegisterRequest(
            deviceId = device.getDeviceId(),
            publicKeyPem = device.getPublicKeyPem(),
            model = android.os.Build.MODEL ?: "",
            osVersion = "Android ${android.os.Build.VERSION.RELEASE}",
            appVersion = BuildConfig.VERSION_NAME,
        )
        val req = Request.Builder().url(url("/v1/devices/register"))
            .header("Authorization", "Bearer $idToken")
            .post(json.encodeToString(body).toRequestBody(jsonMedia)).build()
        client.newCall(req).execute().use { resp ->
            when (resp.code) {
                HttpStatus.CREATED, HttpStatus.OK -> Unit
                HttpStatus.CONFLICT -> throw DeviceConflictException(IndicApiHttp.requestIdOf(resp))
                else -> throw IndicApiHttp.apiException(resp)
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
    override suspend fun activateLicense(idToken: String, key: String): AppConfigDto = withContext(Dispatchers.IO) {
        val body = LicenseActivateRequest(key = key)
        val req = Request.Builder().url(url("/v1/licenses/activate"))
            .header("Authorization", "Bearer $idToken")
            .header("X-Device-Id", device.getDeviceId())
            .post(json.encodeToString(body).toRequestBody(jsonMedia)).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code != HttpStatus.OK) {
                throw ApiException(
                    resp.code,
                    IndicApiHttp.bodyText(resp),
                    IndicApiHttp.requestIdOf(resp),
                )
            }
            val decoded: LicenseActivateResponse = json.decodeFromString(resp.body.string())
            decoded.config
        }
    }

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

    private suspend fun seatCall(idToken: String, action: String): AppConfigDto =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url("/v1/licenses/$action"))
                .header("Authorization", "Bearer $idToken")
                .header("X-Device-Id", device.getDeviceId())
                .post(EMPTY_JSON.toRequestBody(jsonMedia)).build()
            client.newCall(req).execute().use { resp ->
                if (resp.code != HttpStatus.OK) {
                    val body = IndicApiHttp.bodyText(resp)
                    if (resp.code == HttpStatus.CONFLICT &&
                        ApiErrors.hasCode(body, ApiErrors.NO_FLOATING_SEAT)
                    ) {
                        throw NoSeatAvailableException()
                    }
                    throw ApiException(resp.code, body, IndicApiHttp.requestIdOf(resp))
                }
                val decoded: LicenseActivateResponse = json.decodeFromString(resp.body.string())
                decoded.config
            }
        }

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
    override suspend fun acceptTerms(idToken: String, version: String): Unit = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/v1/me/terms"))
            .header("Authorization", "Bearer $idToken")
            .header("X-Device-Id", device.getDeviceId())
            .post(json.encodeToString(TermsAcceptanceBody(version)).toRequestBody(jsonMedia)).build()
        client.newCall(req).execute().use { resp ->
            when (resp.code) {
                HttpStatus.OK -> Unit
                HttpStatus.CONFLICT -> throw TermsVersionMismatchException(IndicApiHttp.requestIdOf(resp))
                else -> throw IndicApiHttp.apiException(resp)
            }
        }
    }

    /** PUT /v1/me/consents — grant or withdraw the optional product-improvement consent. */
    override suspend fun setImprovementConsent(idToken: String, granted: Boolean): Unit = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/v1/me/consents"))
            .header("Authorization", "Bearer $idToken")
            .header("X-Device-Id", device.getDeviceId())
            .put(json.encodeToString(ConsentUpdateBody(granted)).toRequestBody(jsonMedia)).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code != HttpStatus.OK) throw IndicApiHttp.apiException(resp)
        }
    }

    // ----------------------------------------------------------- session/files

    /**
     * GET /v1/sessions — the caller's cloud analyses (for sync reconciliation).
     *
     * Follows `nextPageToken` until the account listing is complete so quota
     * reconciliation is not silently truncated by server page size.
     *
     * [verify] makes the backend also confirm each page's session blobs still
     * exist in Drive (catching artifacts deleted straight in Drive). It costs
     * Drive calls per page, so it's for explicit refreshes, not every resume.
     */
    override suspend fun listSessions(
        idToken: String,
        verify: Boolean,
    ): ListSessionsResponse = withContext(Dispatchers.IO) {
        val all = mutableListOf<CloudSessionDto>()
        var pageToken: String? = null
        var lastQuota = QuotaDto()
        do {
            val qs = buildString {
                append("page_size=100")
                if (verify) append("&verify=true")
                if (!pageToken.isNullOrBlank()) append("&page_token=").append(pageToken)
            }
            val page: ListSessionsResponse = json.decodeFromString(
                authedGet(idToken, url("/v1/sessions?$qs"), approvedOnly),
            )
            all += page.sessions
            lastQuota = page.quota
            pageToken = page.page?.nextPageToken?.takeIf { page.page.hasMore }
        } while (pageToken != null)
        ListSessionsResponse(sessions = all, quota = lastQuota)
    }

    /** POST /v1/sessions (device-signed). Initiates a session + one resumable target per file. */
    override suspend fun createSession(
        idToken: String,
        request: SessionCreateRequest,
    ): SessionCreateResponse = withContext(Dispatchers.IO) {
        val bodyBytes = json.encodeToString(request).toByteArray()
        val resp = signedPost(idToken, "/v1/sessions", bodyBytes)
        resp.use {
            if (it.code == HttpStatus.OK) {
                json.decodeFromString(it.body.string())
            } else {
                failSigned(it)
            }
        }
    }

    /**
     * GET /v1/sessions/{sid}/uploads — what still needs uploading.
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
    override suspend fun sessionUploads(
        idToken: String,
        sessionId: String,
    ): SessionUploadsResponse = withContext(Dispatchers.IO) {
        val resp = signedRequest(idToken, "GET", "/v1/sessions/$sessionId/uploads", ByteArray(0))
        resp.use {
            if (it.code == HttpStatus.OK) {
                json.decodeFromString(it.body.string())
            } else {
                failSigned(it)
            }
        }
    }

    /** POST /v1/files/{id}/complete (device-signed). */
    override suspend fun completeFile(
        idToken: String,
        fileId: String,
        request: FileCompleteRequest,
    ) = withContext(Dispatchers.IO) {
        val bodyBytes = json.encodeToString(request).toByteArray()
        val resp = signedPost(idToken, "/v1/files/$fileId/complete", bodyBytes)
        resp.use { if (it.code != HttpStatus.OK) failSigned(it) }
    }

    /**
     * PUT /v1/sessions/{sid}/metadata — replace a backed-up analysis's
     * metadata.json with [metadataJson], for a change made after the backup
     * (ADR-013). Device-signed; any non-200 throws [ApiException].
     */
    override suspend fun replaceSessionMetadata(
        idToken: String,
        sessionId: String,
        metadataJson: String,
    ) = withContext(Dispatchers.IO) {
        val resp = signedRequest(idToken, "PUT", "/v1/sessions/$sessionId/metadata", metadataJson.toByteArray())
        resp.use { if (it.code != HttpStatus.OK) failSigned(it) }
    }

    // ----------------------------------------------------------------- restore

    /** GET /v1/sessions/{sid}/files — the manifest for one cloud analysis. */
    override suspend fun listSessionFiles(
        idToken: String,
        sessionId: String,
    ): SessionFilesResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(authedGet(idToken, url("/v1/sessions/$sessionId/files")))
    }

    /**
     * GET /v1/files/{id}/content — stream a file back from Drive into [dest].
     * These bytes are proxied by the backend (Drive has no anonymous download),
     * so this is the one path where the backend touches file content.
     *
     * Device-attested like writes: fresh nonce + ECDSA over method/path/empty
     * body per attempt. `Range` is an unsigned header (not part of the signed
     * message) so resume offsets can change without rehashing the body.
     *
     * Writes to a sibling `.part` file and renames on success. If the transfer
     * drops mid-stream, retries with `Range: bytes=N-` so already-received
     * bytes are kept (backend forwards Range to Drive and returns 206).
     */
    /**
     * Fetch only `[rangeStart, rangeStart + length)` of an object.
     *
     * Restore uses this to read a legacy backup's central directory and then just the
     * prefix of entries it needs, instead of the whole archive.
     */
    override suspend fun downloadRange(
        idToken: String,
        fileId: String,
        dest: java.io.File,
        rangeStart: Long,
        length: Long,
    ) = drive.downloadFile(
        fileId,
        dest,
        url(""), // the base; DriveTransfer appends its own paths
        expectedBytes = length,
        rangeStart = rangeStart,
    ) { path ->
        signedHeaders(idToken, "GET", path, ByteArray(0), nonceFor(idToken))
    }

    override suspend fun downloadFile(
        idToken: String,
        fileId: String,
        dest: java.io.File,
        expectedBytes: Long,
        onBytes: suspend (haveBytes: Long) -> Unit,
    ) = drive.downloadFile(
        fileId,
        dest,
        url(""), // the base; DriveTransfer appends its own paths
        expectedBytes = expectedBytes,
        onBytes = onBytes,
    ) { path ->
        signedHeaders(idToken, "GET", path, ByteArray(0), nonceFor(idToken))
    }

    // ------------------------------------------------------------------- admin

    /** GET /v1/admin/users?status=… (admin ID token; no device signature). */
    override suspend fun listUsers(idToken: String, status: String): List<AdminUserDto> = withContext(Dispatchers.IO) {
        val usersUrl = url(if (status.isBlank()) "/v1/admin/users" else "/v1/admin/users?status=$status")
        json.decodeFromString<AdminUsersResponse>(
            authedGet(idToken, usersUrl) { code, body, ref ->
                if (code == HttpStatus.FORBIDDEN) {
                    throw ApiException(HttpStatus.FORBIDDEN, ApiErrors.NOT_ADMIN, ref)
                } else {
                    throw ApiException(code, body, ref)
                }
            },
        ).users
    }

    /** POST /v1/admin/users/{uid}/{action} — device-attested (approve/revoke). */
    override suspend fun setUserStatus(idToken: String, uid: String, action: String) = withContext(Dispatchers.IO) {
        signedPost(idToken, "/v1/admin/users/$uid/$action", ByteArray(0)).use { resp ->
            if (resp.code != HttpStatus.OK) throw IndicApiHttp.apiException(resp)
        }
    }

    // ------------------------------------------------------- device-signed POST

    private fun signedPost(idToken: String, path: String, bodyBytes: ByteArray): Response =
        signedRequest(idToken, "POST", path, bodyBytes)

    /**
     * A device-signed request. The signature covers method + path + body hash.
     *
     * Signs with a [ClientNonce] when it can, saving the challenge round-trip.
     * If the server refuses it (clock outside its window, or a backend that
     * predates client nonces) the call is re-sent once with a server challenge;
     * the refusal happens before the route runs, so the re-send is safe.
     */
    private fun signedRequest(
        idToken: String,
        method: String,
        path: String,
        bodyBytes: ByteArray,
    ): Response {
        if (ClientNonce.usable()) {
            val resp = sendSigned(idToken, method, path, bodyBytes, ClientNonce.mint())
            if (!isClientNonceRefusal(resp)) return resp
            resp.close()
            ClientNonce.markRefused()
            Timber.i("Client nonce refused; using server challenges for this process")
        }
        return sendSigned(idToken, method, path, bodyBytes, fetchChallenge(idToken))
    }

    /** A client nonce when usable, else a fresh server challenge. */
    private fun nonceFor(idToken: String): String =
        if (ClientNonce.usable()) ClientNonce.mint() else fetchChallenge(idToken)

    private fun sendSigned(
        idToken: String,
        method: String,
        path: String,
        bodyBytes: ByteArray,
        nonce: String,
    ): Response {
        val headers = signedHeaders(idToken, method, path, bodyBytes, nonce)
        val builder = Request.Builder().url(url(path)).headers(headers)
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(bodyBytes.toRequestBody(jsonMedia))
            // No body: the backend hashes empty bytes, so we must send none.
            "DELETE" -> builder.delete()
            else -> builder.method(method, bodyBytes.toRequestBody(jsonMedia))
        }
        return client.newCall(builder.build()).execute()
    }

    /**
     * DELETE /v1/me — erase the account and every analysis it owns from the
     * cloud. The caller must sign out immediately afterwards; any further
     * authenticated call would create a fresh, empty profile.
     */
    override suspend fun deleteAccount(idToken: String) = withContext(Dispatchers.IO) {
        val resp = signedRequest(idToken, "DELETE", "/v1/me", ByteArray(0))
        // No 404-is-fine shortcut here: this endpoint never legitimately 404s,
        // so a 404 means the route isn't reachable (e.g. not published on the
        // API Gateway). Treating that as success would wipe the local copy while
        // leaving every byte in the cloud.
        resp.use { if (it.code != HttpStatus.OK) failSigned(it) }
    }

    /**
     * DELETE /v1/sessions/{id} — erase an analysis from the cloud: the Drive
     * folder (raw images, .dat, csv, report) and all Firestore metadata.
     * Permanent; there is no undo.
     */
    override suspend fun deleteSession(idToken: String, sessionId: String) = withContext(Dispatchers.IO) {
        val resp = signedRequest(idToken, "DELETE", "/v1/sessions/$sessionId", ByteArray(0))
        resp.use {
            if (it.code == HttpStatus.OK) return@use
            val body = IndicApiHttp.bodyText(it)
            // A 404 is only "already erased" when OUR backend says so
            // (`session_not_found`). A bare 404 means the route isn't reachable —
            // accepting that as success would delete the local copy and orphan
            // the cloud data forever.
            if (it.code == HttpStatus.NOT_FOUND && ApiErrors.hasCode(body, ApiErrors.SESSION_NOT_FOUND)) {
                return@use
            }
            failSigned(it.code, body, IndicApiHttp.requestIdOf(it))
        }
    }

    /** POST /v1/challenge → single-use nonce bound to (uid, deviceId). */
    private fun fetchChallenge(idToken: String): String {
        val req = Request.Builder().url(url("/v1/challenge"))
            .header("Authorization", "Bearer $idToken")
            .header("X-Device-Id", device.getDeviceId())
            .post(ByteArray(0).toRequestBody(jsonMedia)).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code != HttpStatus.OK) throw IndicApiHttp.apiException(resp)
            val nonce: ChallengeResponse = json.decodeFromString(resp.body.string())
            return nonce.nonce
        }
    }

    /**
     * Signature over (nonce || METHOD || path) ++ SHA-256(body) — matches
     * backend/app/deps.py.
     *
     * [path] is everything after the host, query string included: the backend
     * appends `?` + query when the request has one, so a call site that signs a
     * bare path and then fetches it with parameters would be rejected. No route
     * takes query parameters today; this is what to keep in step when one does.
     */
    private fun signedHeaders(
        idToken: String,
        method: String,
        path: String,
        bodyBytes: ByteArray,
        nonce: String,
    ): Headers {
        val msg = (nonce + method + path).toByteArray() + Digests.sha256(bodyBytes)
        val sig = device.signMessage(msg)
        return Headers.Builder()
            .add("Authorization", "Bearer $idToken")
            .add("X-Device-Id", device.getDeviceId())
            .add("X-Nonce", nonce)
            .add("X-Signature", sig)
            .build()
    }

    // ------------------------------------------------- direct-to-Drive uploads

    /**
     * Resumable upload of [file] to a Drive [uploadUrl], in [chunkSize] chunks
     * (multiple of 256 KiB). Resumes from the server offset on reconnect. Bytes
     * go straight to Drive — not through the backend.
     * Returns (driveFileId, localMd5Hex) — md5 is always set for `:complete`.
     */
    override suspend fun uploadResumable(
        uploadUrl: String,
        file: java.io.File,
        chunkSize: Int,
        onBytes: (Long) -> Unit,
    ): Pair<String, String> = drive.uploadResumable(uploadUrl, file, chunkSize, onBytes)

    companion object {
        // One connection pool + dispatcher shared by every IndicApi instance.
        // The class is constructed per worker/repo (many times), and a fresh
        // OkHttpClient each time would throw away TLS session reuse and
        // keep-alive. downloadClient shares this pool via newBuilder().
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS) // large chunk PUTs to Drive
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            // Application interceptors, so each sees the logical call once
            // rather than once per redirect hop. Retry first, so a retried
            // request gets a freshly read App Check token rather than replaying
            // the one that may have expired while it waited. downloadClient
            // inherits both through newBuilder() below.
            .addInterceptor(RetryOnTransient())
            .addInterceptor(AppCheckHeader())
            // Which app's device binding a call is for (ADR-010).
            .addInterceptor(AppIdHeader())
            .addInterceptor(ClientNonce.ServerDateObserver(apiHost()))
            .apply {
                val pins = BuildConfig.INDIC_API_CERT_PINS.trim()
                val host = apiHost()
                if (pins.isNotEmpty() && host.isNotEmpty()) {
                    val pinner = CertificatePinner.Builder().also { b ->
                        pins.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { b.add(host, it) }
                    }.build()
                    certificatePinner(pinner)
                }
            }
            .build()

        /** The backend's host name, or "" when no base URL is configured. */
        private fun apiHost(): String = runCatching {
            BuildConfig.INDIC_API_BASE_URL.trimEnd('/')
                .removePrefix("https://")
                .substringBefore('/')
        }.getOrNull().orEmpty()

        /** Longer read idle for large Session.zip / legacy restores through the proxy. */
        private val downloadClient = client.newBuilder()
            .readTimeout(DOWNLOAD_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

        @Volatile
        private var instance: IndicApi? = null

        /**
         * Process-wide client. Shares OkHttp pools and DeviceKeyManager; call sites
         * must not construct [IndicApi] directly.
         */
        /** Maps a 409 from `GET /v1/me` to the most specific device-binding exception. */
        internal fun throwForMeConflict(body: String, requestId: String?): Nothing {
            val err: Throwable = when {
                ApiErrors.hasCode(body, ApiErrors.DEVICE_IN_USE) -> DeviceInUseException(requestId)
                ApiErrors.hasCode(body, ApiErrors.DEVICE_CONFLICT) -> DeviceConflictException(requestId)
                else -> ApiException(HttpStatus.CONFLICT, body, requestId)
            }
            throw err
        }

        fun get(context: Context): IndicApi {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: IndicApi(context.applicationContext).also { instance = it }
            }
        }
    }
}
