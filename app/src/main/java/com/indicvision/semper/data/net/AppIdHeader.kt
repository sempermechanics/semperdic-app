package com.indicvision.semper.data.net

import com.indicvision.semper.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/** Header the backend reads (`deps.request_app`, backend `app/apps.py`). */
private const val APP_ID_HEADER = "X-App-Id"

/**
 * Names this app to the Semper backend, by its `applicationId`.
 *
 * Semper and Material Testing share the backend and its accounts, but Android
 * gives each app on a phone its own `ANDROID_ID`, so the backend sees two
 * devices. It binds one phone per app (ADR-010), and this header is how it
 * knows which app's binding a request is for. A request without it is read as
 * Semper, which is what every build before this header was.
 *
 * Scoped to the API host like [AppCheckHeader]: Drive shares this client and
 * has no use for it.
 */
class AppIdHeader(
    private val apiHost: String = apiHostOf(BuildConfig.INDIC_API_BASE_URL),
    private val appId: String = BuildConfig.APPLICATION_ID,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (apiHost.isEmpty() || appId.isEmpty() || request.url.host != apiHost) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header(APP_ID_HEADER, appId).build())
    }

    private companion object {
        fun apiHostOf(baseUrl: String): String =
            runCatching {
                baseUrl.trimEnd('/').removePrefix("https://").substringBefore('/')
            }.getOrNull().orEmpty()
    }
}
