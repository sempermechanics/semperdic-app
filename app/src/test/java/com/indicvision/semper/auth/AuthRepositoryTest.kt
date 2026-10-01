package com.indicvision.semper.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.cloud.FakeCloudApi
import com.indicvision.semper.cloud.FakeTokens
import com.indicvision.semper.data.account.AccessStatus
import com.indicvision.semper.data.account.AuthRepository
import com.indicvision.semper.data.net.AppConfigDto
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.MeLicenseDto
import com.indicvision.semper.data.net.MeResponse
import com.indicvision.semper.data.net.TermsDto
import com.indicvision.semper.data.net.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * What [AuthRepository] concludes from the backend's answers (ADR-002). The
 * distinction under test is the one that decides the first screen: a refusal
 * the server meant sends the user back to sign-in, while a server fault or no
 * network must not throw out someone who was already approved.
 */
@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()
    private var signedIn = true

    private val repo = AuthRepository(context, api, tokens, signedIn = { signedIn })

    private fun approved(terms: TermsDto? = null, consent: Boolean? = null) =
        MeResponse(uid = "u1", role = "admin", accessStatus = "APPROVED", terms = terms, improvementConsent = consent)

    private fun serverUp(me: MeResponse = approved()) {
        api.onMe = { me }
        api.onGetConfig = { throw IOException("config down") }
        api.onRegisterDevice = {}
    }

    // ------------------------------------------------------------ status

    @Test
    fun `no Firebase user means no backend call`() = runBlocking {
        signedIn = false

        assertTrue(repo.refreshStatus().isFailure)
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `an approved account registers this device once`() = runBlocking {
        serverUp()

        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())
        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())

        assertEquals("admin", TokenStore.cachedRole(context))
        assertTrue(TokenStore.isDeviceRegistered(context))
        assertEquals(1, api.calls.count { it == "registerDevice" })
    }

    @Test
    fun `a failed config read does not fail the sign-in`() = runBlocking {
        serverUp()

        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())
        assertTrue("config still fetched", "getConfig" in api.calls)
    }

    @Test
    fun `a config read before the invite claim landed is asked again`() = runBlocking {
        serverUp(approved().copy(license = MeLicenseDto(mode = "licensed")))
        val answers = ArrayDeque(
            listOf(
                AppConfigDto(maxSessions = 25, mode = "demo"),
                AppConfigDto(maxSessions = 999, mode = "licensed"),
            ),
        )
        api.onGetConfig = { answers.removeFirst() }

        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())
        assertEquals(2, api.calls.count { it == "getConfig" })
        assertEquals(999, AppRemoteConfig.maxSessions(context))
    }

    @Test
    fun `a config that agrees with me is fetched once`() = runBlocking {
        serverUp(approved().copy(license = MeLicenseDto(mode = "demo")))
        api.onGetConfig = { AppConfigDto(maxSessions = 25, mode = "demo") }

        assertEquals(AccessStatus.APPROVED, repo.refreshStatus().getOrThrow())
        assertEquals(1, api.calls.count { it == "getConfig" })
    }

    @Test
    fun `an unapproved account waits on the pending screen`() = runBlocking {
        api.onMe = { throw IndicApi.NotApprovedException() }
        api.onGetConfig = { throw IOException("config down") }

        assertEquals(AccessStatus.PENDING, repo.refreshStatus().getOrThrow())
        assertEquals(AccessStatus.PENDING, TokenStore.cachedStatus(context))
    }

    @Test
    fun `a device bound elsewhere loses access`() = runBlocking {
        api.onMe = { throw IndicApi.DeviceInUseException() }
        api.onGetConfig = { throw IOException("config down") }

        val error = repo.refreshStatus().exceptionOrNull()
        assertTrue("got $error", error is AuthRepository.AccessLostException)
    }

    @Test
    fun `a 401 loses access and carries the server's hint`() = runBlocking {
        api.onMe = { throw IndicApi.ApiException(401, """{"detail":"bad audience"}""") }
        api.onGetConfig = { throw IOException("config down") }

        val error = repo.refreshStatus().exceptionOrNull()
        assertTrue("got $error", error is AuthRepository.AccessLostException)
        assertTrue(error!!.message!!, "bad audience" in error.message!!)
    }

    @Test
    fun `a server fault is an error, not a lost session`() = runBlocking {
        api.onMe = { throw IndicApi.ApiException(500, "") }
        api.onGetConfig = { throw IOException("config down") }

        val error = repo.refreshStatus().exceptionOrNull()
        assertTrue("got $error", error != null && error !is AuthRepository.AccessLostException)
    }

    @Test
    fun `offline keeps an approved account in, and nobody else`() = runBlocking {
        api.onMe = { throw IOException("no route") }
        api.onGetConfig = { throw IOException("no route") }

        assertTrue(repo.refreshStatus().isFailure)

        TokenStore.setStatus(context, AccessStatus.APPROVED)
        assertEquals(AccessStatus.OFFLINE_CACHE_APPROVED, repo.refreshStatus().getOrThrow())
    }

    @Test
    fun `no token is treated like offline`() = runBlocking {
        tokens.token = null
        TokenStore.setStatus(context, AccessStatus.APPROVED)

        assertEquals(AccessStatus.OFFLINE_CACHE_APPROVED, repo.refreshStatus().getOrThrow())
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `no backend configured is treated like offline`() = runBlocking {
        // A build without INDIC_API_BASE_URL that still holds a signed-in,
        // approved session opens Home from cache; the background re-check
        // must not call /v1/me with an empty base URL (the process died).
        api.enabled = false

        assertTrue(repo.refreshStatus().isFailure)

        TokenStore.setStatus(context, AccessStatus.APPROVED)
        assertEquals(AccessStatus.OFFLINE_CACHE_APPROVED, repo.refreshStatus().getOrThrow())
        assertEquals(emptyList<String>(), api.calls)
    }

    // ------------------------------------------------------------- terms

    @Test
    fun `terms accepted offline open the gate and sync on the next status check`() = runBlocking {
        api.enabled = false
        assertTrue(repo.acceptTerms("2026-09", improvementConsent = false).isSuccess)
        assertEquals("2026-09", TokenStore.termsAcceptedVersion(context))
        assertFalse(TokenStore.isTermsAcceptanceSynced(context))
        assertEquals(emptyList<String>(), api.calls)

        api.enabled = true
        serverUp()
        val sent = mutableListOf<String>()
        api.onAcceptTerms = { _, version -> sent += version }
        repo.refreshStatus().getOrThrow()

        assertEquals(listOf("2026-09"), sent)
        assertTrue(TokenStore.isTermsAcceptanceSynced(context))
    }

    @Test
    fun `terms the server no longer serves do not open the gate`() = runBlocking {
        api.onAcceptTerms = { _, _ -> throw IndicApi.TermsVersionMismatchException() }

        val result = repo.acceptTerms("2025-01", improvementConsent = true)

        assertTrue(result.exceptionOrNull() is IndicApi.TermsVersionMismatchException)
        assertNull(TokenStore.termsAcceptedVersion(context))
        assertFalse("consent is not sent for refused terms", "setImprovementConsent" in api.calls)
    }

    @Test
    fun `an unreachable server keeps the acceptance for later`() = runBlocking {
        api.onAcceptTerms = { _, _ -> throw IOException("no route") }
        api.onSetImprovementConsent = { _, _ -> throw IOException("no route") }

        assertTrue(repo.acceptTerms("2026-09", improvementConsent = true).isSuccess)

        assertEquals("2026-09", TokenStore.termsAcceptedVersion(context))
        assertFalse(TokenStore.isTermsAcceptanceSynced(context))
        assertEquals(true, TokenStore.improvementConsent(context))
    }

    @Test
    fun `the server's record of the terms wins over this device's`() = runBlocking {
        serverUp(approved(terms = TermsDto(requiredVersion = "2026-09", acceptedVersion = "2026-09"), consent = true))

        repo.refreshStatus().getOrThrow()

        assertEquals("2026-09", TokenStore.termsAcceptedVersion(context))
        assertTrue(TokenStore.isTermsAcceptanceSynced(context))
        assertEquals(true, TokenStore.improvementConsent(context))
    }

    @Test
    fun `a consent change the server did not take is reported`() = runBlocking {
        api.onSetImprovementConsent = { _, _ -> throw IOException("no route") }

        assertTrue(repo.setImprovementConsent(true).isFailure)
        // The switch still shows what the user chose.
        assertEquals(true, TokenStore.improvementConsent(context))
    }
}
