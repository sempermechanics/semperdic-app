package com.sempermechanics.semper.data.net

import com.sempermechanics.semper.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * The Semper backend's address: what the backend-only interceptors and the
 * certificate pins are scoped to. Drive shares the same OkHttp client and must
 * not get the backend's headers.
 *
 * The backend is a host *and* a port, written `host:port` with the port always
 * explicit, so `https://api.x` and `https://api.x:443` are the same backend and
 * `https://api.x:8443` is another one (TD-160).
 */
internal object ApiHost {

    /** `host:port` of [BuildConfig.SEMPER_API_BASE_URL], or "" when no backend is configured. */
    val configured: String = of(BuildConfig.SEMPER_API_BASE_URL)

    /** `host:port` of [baseUrl] (`https://host[:port]/…`); "" for "" or a URL OkHttp cannot parse. */
    fun of(baseUrl: String): String = baseUrl.toHttpUrlOrNull()?.let(::authorityOf).orEmpty()

    /**
     * The host name of [baseUrl] without its port, or "": what the certificate
     * pins are registered for, since OkHttp's `CertificatePinner` matches host
     * names only and refuses a `host:port` pattern.
     */
    fun hostNameOf(baseUrl: String): String = baseUrl.toHttpUrlOrNull()?.host.orEmpty()

    /**
     * `host:port` of [url], the port spelled out even where the URL leaves it to
     * its scheme's default. The port follows the last colon, so an IPv6 host
     * cannot make two different pairs read the same.
     */
    fun authorityOf(url: HttpUrl): String = "${url.host}:${url.port}"
}

/**
 * An interceptor for calls to the backend at [apiHost] (`host:port`, see
 * [ApiHost]) only. A call to any other host or port (Drive's uploads and
 * downloads share the client), or any call at all when no backend is
 * configured ([apiHost] empty), passes through as it is.
 */
abstract class ApiHostInterceptor(private val apiHost: String) : Interceptor {

    final override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return if (apiHost.isNotEmpty() && ApiHost.authorityOf(request.url) == apiHost) {
            interceptApiCall(chain)
        } else {
            chain.proceed(request)
        }
    }

    /** [Interceptor.intercept] for a call to the backend. */
    protected abstract fun interceptApiCall(chain: Interceptor.Chain): Response
}
