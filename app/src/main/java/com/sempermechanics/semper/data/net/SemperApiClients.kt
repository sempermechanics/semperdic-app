package com.sempermechanics.semper.data.net

import com.sempermechanics.semper.BuildConfig
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// OkHttp client timeouts, in seconds.
private const val CONNECT_TIMEOUT_SECONDS = 30L
private const val WRITE_TIMEOUT_SECONDS = 300L
private const val READ_TIMEOUT_SECONDS = 60L
private const val DOWNLOAD_READ_TIMEOUT_SECONDS = 300L

/**
 * The OkHttp clients every [SemperApi] shares.
 *
 * One connection pool + dispatcher for the process: SemperApi is reached from
 * every worker and repository, and a fresh OkHttpClient each time would throw
 * away TLS session reuse and keep-alive. [download] shares the pool through
 * newBuilder().
 */
internal object SemperApiClients {

    val api: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS) // large chunk PUTs to Drive
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // No redirects (TD-161): OkHttp would carry every header but
        // Authorization to the new host, X-App-Id, X-Firebase-AppCheck,
        // X-Device-Id, X-Nonce and X-Signature among them. Neither the
        // gateway nor Cloud Run sends one, and nothing on this client needs
        // one: Drive downloads come through the backend, and a Drive upload's
        // 308 is "resume incomplete", which DriveUploader reads itself. A 30x
        // reaches the caller like any other status it did not expect.
        .followRedirects(false)
        .followSslRedirects(false)
        // Application interceptors, so each sees the logical call once
        // rather than once per retry. Retry first, so a retried
        // request gets a freshly read App Check token rather than replaying
        // the one that may have expired while it waited. [download]
        // inherits them all through newBuilder() below.
        .addInterceptor(RetryOnTransient())
        .addInterceptor(AppCheckHeader())
        // Which app's device binding a call is for (ADR-010).
        .addInterceptor(AppIdHeader())
        .addInterceptor(ClientNonce.ServerDateObserver(ApiHost.configured))
        .apply { certificatePins()?.let(::certificatePinner) }
        .build()

    /** Longer read idle for large Session.zip / legacy restores through the proxy. */
    val download: OkHttpClient = api.newBuilder()
        .readTimeout(DOWNLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** `SEMPER_API_CERT_PINS` for the backend host, or null when there are none. */
    private fun certificatePins(): CertificatePinner? =
        certificatePinner(BuildConfig.SEMPER_API_CERT_PINS, BuildConfig.SEMPER_API_BASE_URL)

    /**
     * The comma-separated [pins] for [baseUrl]'s host, or null when there are
     * none or no backend. Registered for the bare host name: the pinner matches
     * host names only and refuses a `host:port` pattern (TD-160).
     */
    internal fun certificatePinner(pins: String, baseUrl: String): CertificatePinner? {
        val hashes = pins.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val host = ApiHost.hostNameOf(baseUrl)
        if (hashes.isEmpty() || host.isEmpty()) return null
        return CertificatePinner.Builder().apply { hashes.forEach { add(host, it) } }.build()
    }
}
