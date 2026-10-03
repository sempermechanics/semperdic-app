package com.sempermechanics.semper.data.net

import com.sempermechanics.semper.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * The Semper backend's host name: what the backend-only interceptors and the
 * certificate pins are scoped to. Drive shares the same OkHttp client and must
 * not get the backend's headers.
 */
internal object ApiHost {

    /** The host of [BuildConfig.SEMPER_API_BASE_URL], or "" when no backend is configured. */
    val configured: String = of(BuildConfig.SEMPER_API_BASE_URL)

    /** The host of an `https://host/…` [baseUrl]; "" for "". */
    fun of(baseUrl: String): String = baseUrl.trimEnd('/').removePrefix("https://").substringBefore('/')
}

/**
 * An interceptor for calls to the backend at [apiHost] only. A call to any
 * other host (Drive's uploads and downloads share the client), or any call at
 * all when no backend is configured ([apiHost] empty), passes through as it is.
 */
abstract class ApiHostInterceptor(private val apiHost: String) : Interceptor {

    final override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return if (apiHost.isNotEmpty() && request.url.host == apiHost) {
            interceptApiCall(chain)
        } else {
            chain.proceed(request)
        }
    }

    /** [Interceptor.intercept] for a call to the backend. */
    protected abstract fun interceptApiCall(chain: Interceptor.Chain): Response
}
