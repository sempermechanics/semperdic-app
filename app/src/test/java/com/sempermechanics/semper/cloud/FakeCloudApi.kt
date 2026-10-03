package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.net.AdminUserDto
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.FileCompleteRequest
import com.sempermechanics.semper.data.net.ListSessionsResponse
import com.sempermechanics.semper.data.net.MeResponse
import com.sempermechanics.semper.data.net.SessionCreateRequest
import com.sempermechanics.semper.data.net.SessionCreateResponse
import com.sempermechanics.semper.data.net.SessionFilesResponse
import com.sempermechanics.semper.data.net.SessionUploadsResponse
import com.sempermechanics.semper.data.net.TokenSource
import java.io.File

/**
 * A hand-written [CloudApi] for JVM tests (ADR-002).
 *
 * Every call is recorded by name in [calls]. A test scripts only the calls it
 * expects, through the `on…` lambdas; any other call fails the test, so a
 * decision that reaches the backend when it should not is caught rather than
 * answered. A lambda can throw, including `CancellationException`, to script a
 * failure.
 */
class FakeCloudApi(override var enabled: Boolean = true) : CloudApi {

    val calls = mutableListOf<String>()

    var onMe: suspend (String) -> MeResponse = { unscripted("me") }
    var onGetConfig: suspend (String) -> AppConfigDto = { unscripted("getConfig") }
    var onExportAccount: suspend (String, File) -> Unit = { _, _ -> unscripted("exportAccount") }
    var onRegisterDevice: suspend (String) -> Unit = { unscripted("registerDevice") }
    var onActivateLicense: suspend (String, String) -> AppConfigDto = { _, _ -> unscripted("activateLicense") }
    var onCheckoutLease: suspend (String) -> AppConfigDto = { unscripted("checkoutLease") }
    var onReleaseLease: suspend (String) -> AppConfigDto = { unscripted("releaseLease") }
    var onAcceptTerms: suspend (String, String) -> Unit = { _, _ -> unscripted("acceptTerms") }
    var onSetImprovementConsent: suspend (String, Boolean) -> Unit = { _, _ -> unscripted("setImprovementConsent") }
    var onListSessions: suspend (String, Boolean) -> ListSessionsResponse = { _, _ -> unscripted("listSessions") }
    var onDeleteAccount: suspend (String) -> Unit = { unscripted("deleteAccount") }
    var onDeleteSession: suspend (String, String) -> Unit = { _, _ -> unscripted("deleteSession") }
    var onReplaceSessionMetadata: suspend (String, String, String) -> Unit =
        { _, _, _ -> unscripted("replaceSessionMetadata") }

    override suspend fun me(idToken: String) = record("me") { onMe(idToken) }

    override suspend fun getConfig(idToken: String) = record("getConfig") { onGetConfig(idToken) }

    override suspend fun exportAccount(idToken: String, dest: File) =
        record("exportAccount") { onExportAccount(idToken, dest) }

    override suspend fun registerDevice(idToken: String) = record("registerDevice") { onRegisterDevice(idToken) }

    override suspend fun activateLicense(idToken: String, key: String) =
        record("activateLicense") { onActivateLicense(idToken, key) }

    override suspend fun checkoutLease(idToken: String) = record("checkoutLease") { onCheckoutLease(idToken) }

    override suspend fun releaseLease(idToken: String) = record("releaseLease") { onReleaseLease(idToken) }

    override suspend fun acceptTerms(idToken: String, version: String) =
        record("acceptTerms") { onAcceptTerms(idToken, version) }

    override suspend fun setImprovementConsent(idToken: String, granted: Boolean) =
        record("setImprovementConsent") { onSetImprovementConsent(idToken, granted) }

    override suspend fun listSessions(idToken: String, verify: Boolean) =
        record("listSessions") { onListSessions(idToken, verify) }

    override suspend fun deleteAccount(idToken: String) = record("deleteAccount") { onDeleteAccount(idToken) }

    override suspend fun deleteSession(idToken: String, sessionId: String) =
        record("deleteSession") { onDeleteSession(idToken, sessionId) }

    override suspend fun replaceSessionMetadata(idToken: String, sessionId: String, metadataJson: String) =
        record("replaceSessionMetadata") { onReplaceSessionMetadata(idToken, sessionId, metadataJson) }

    // The upload and restore paths are not driven through this fake yet.
    override suspend fun createSession(idToken: String, request: SessionCreateRequest): SessionCreateResponse =
        unscripted("createSession")

    override suspend fun sessionUploads(idToken: String, sessionId: String): SessionUploadsResponse =
        unscripted("sessionUploads")

    override suspend fun completeFile(idToken: String, fileId: String, request: FileCompleteRequest): Unit =
        unscripted("completeFile")

    override suspend fun listSessionFiles(idToken: String, sessionId: String): SessionFilesResponse =
        unscripted("listSessionFiles")

    override suspend fun downloadRange(
        idToken: String,
        fileId: String,
        dest: File,
        rangeStart: Long,
        length: Long,
    ): Unit = unscripted("downloadRange")

    override suspend fun downloadFile(
        idToken: String,
        fileId: String,
        dest: File,
        expectedBytes: Long,
        onBytes: suspend (haveBytes: Long) -> Unit,
    ): Unit = unscripted("downloadFile")

    override suspend fun listUsers(idToken: String, status: String): List<AdminUserDto> = unscripted("listUsers")

    override suspend fun setUserStatus(idToken: String, uid: String, action: String): Unit =
        unscripted("setUserStatus")

    override suspend fun uploadResumable(
        uploadUrl: String,
        file: File,
        chunkSize: Int,
        onBytes: (Long) -> Unit,
    ): Pair<String, String> = unscripted("uploadResumable")

    private inline fun <T> record(name: String, block: () -> T): T {
        synchronized(calls) { calls += name } // concurrency tests call from several threads
        return block()
    }

    private fun unscripted(name: String): Nothing = throw AssertionError("unexpected CloudApi.$name call")
}

/** A [TokenSource] that answers [token] (null = signed out) and counts the asks. */
class FakeTokens(var token: String? = "tok") : TokenSource {
    var asked = 0
        private set

    override suspend fun usableIdToken(): String? {
        asked++
        return token
    }
}
