package com.sempermechanics.semper.data.account

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.cloud.FakeCloudApi
import com.sempermechanics.semper.cloud.FakeTokens
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.MeResponse
import com.sempermechanics.semper.data.net.TermsDto
import com.sempermechanics.semper.fixtures.CleanAppState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * A sign-in as another account over a live session (L4-1): it never passed
 * through a sign-out, so [AccountSwitch.signedIn] must drop account A's cache
 * before account B uses the app. Before, B skipped the Terms clickwrap and A's
 * unsynced acceptance was sent to the server with B's token.
 */
@RunWith(RobolectricTestRunner::class)
class AccountSwitchTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()

    @Before
    fun startWorkManager() = WorkManagerTestInitHelper.initializeTestWorkManager(context)

    @SuppressLint("RestrictedApi")
    @After
    fun stopWorkManager() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    /** Account A, approved and bound, with a Terms acceptance the server has not had yet. */
    private fun accountASignedIn() {
        AccountCache.saveIdentity(context, UID_A, "a@example.com")
        AccountCache.setStatus(context, AccessStatus.APPROVED)
        AccountCache.setRole(context, "admin")
        AccountCache.setDeviceRegistered(context, true)
        AccountCache.setTermsRequiredVersion(context, TERMS)
        AccountCache.setTermsAccepted(context, TERMS, synced = false)
        AccountCache.setDeviceLimitReached(context, true)
        AppRemoteConfig.apply(context, AppConfigDto(maxSessions = 500, mode = "licensed"))
    }

    @Test
    fun `another account signing in drops the previous account's cache`() {
        accountASignedIn()

        AccountSwitch.signedIn(context, UID_B, "b@example.com")

        assertEquals(UID_B, AccountCache.cachedUid(context))
        assertEquals("b@example.com", AccountCache.cachedEmail(context))
        assertNull(AccountCache.termsAcceptedVersion(context))
        assertFalse(AccountCache.isTermsAcceptanceSynced(context))
        assertFalse(AccountCache.isDeviceRegistered(context))
        assertNull(AccountCache.cachedStatus(context))
        assertNull(AccountCache.cachedRole(context))
        assertFalse(AccountCache.isDeviceLimitForced(context))
        assertFalse(AppRemoteConfig.isKnown(context))
        assertTrue("B meets the clickwrap", LegalTerms.needsAcceptance(context))
    }

    @Test
    fun `account A's unsynced acceptance is never sent with account B's token`() = runBlocking {
        accountASignedIn()
        api.onMe = {
            MeResponse(uid = UID_B, role = "user", accessStatus = "APPROVED", terms = TermsDto(requiredVersion = TERMS))
        }
        api.onGetConfig = { throw IOException("config down") }
        api.onRegisterDevice = {}

        AccountSwitch.signedIn(context, UID_B, "b@example.com")
        val status = AuthRepository(context, api, FakeTokens("token-of-b"), signedIn = { true }).refreshStatus()

        assertEquals(AccessStatus.APPROVED, status.getOrThrow())
        assertFalse("acceptTerms" in api.calls)
        // B's own device binding is made, not taken from A's flag.
        assertTrue("registerDevice" in api.calls)
        assertTrue(LegalTerms.needsAcceptance(context))
    }

    @Test
    fun `the same account signing in again keeps its cache`() {
        accountASignedIn()

        AccountSwitch.signedIn(context, UID_A, "a@example.com")

        assertEquals(TERMS, AccountCache.termsAcceptedVersion(context))
        assertFalse(AccountCache.isTermsAcceptanceSynced(context))
        assertTrue(AccountCache.isDeviceRegistered(context))
        assertEquals(AccessStatus.APPROVED, AccountCache.cachedStatus(context))
        assertTrue(AppRemoteConfig.isKnown(context))
    }

    @Test
    fun `a first sign-in on a signed-out phone has nothing to drop`() {
        AccountCache.setTermsRequiredVersion(context, TERMS)

        AccountSwitch.signedIn(context, UID_B, "b@example.com")

        assertEquals(UID_B, AccountCache.cachedUid(context))
        assertEquals(TERMS, AccountCache.termsRequiredVersion(context))
    }

    private companion object {
        const val UID_A = "uid-a"
        const val UID_B = "uid-b"
        const val TERMS = "2026-09-15"
    }
}
