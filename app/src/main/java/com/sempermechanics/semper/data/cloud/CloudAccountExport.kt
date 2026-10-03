package com.sempermechanics.semper.data.cloud

import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.data.session.CacheJanitor
import com.sempermechanics.semper.util.suspendRunCatching
import timber.log.Timber
import java.io.File

/**
 * "Download my cloud account data": the backend's JSON export of the account,
 * saved into [cacheDir] for the share sheet.
 */
internal object CloudAccountExport {

    /**
     * The export file, or null when it could not be fetched (signed out,
     * offline, a server error). A cancel is not a failure: it propagates, so
     * the caller ends quietly instead of reporting "export failed".
     */
    suspend fun download(cacheDir: File, api: CloudApi, tokens: TokenSource = TokenProvider): File? {
        val dest = File(cacheDir, CacheJanitor.ACCOUNT_EXPORT)
        return suspendRunCatching {
            val idToken = tokens.usableIdToken() ?: error("not signed in")
            api.exportAccount(idToken, dest)
        }.onFailure { Timber.w(it, "Cloud account export failed") }
            .getOrNull()
            ?.let { dest }
    }
}
