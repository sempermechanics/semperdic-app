package com.sempermechanics.semper.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.cloud.FakeCloudApi
import com.sempermechanics.semper.cloud.FakeTokens
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.cloud.CloudErase
import com.sempermechanics.semper.data.cloud.CloudSync.AccountDeletion
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.fixtures.ensureTestFirebaseApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The floating-seat release on sign-out, and why an account deletion skips it
 * (TD-206): the backend's erase gives the seat back itself, and a release sent
 * after it, with a token that still works, re-created the erased account's
 * profile and mailed support when it was pending.
 */
@RunWith(RobolectricTestRunner::class)
class SignOutSeatReleaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi().apply { onReleaseLease = { AppConfigDto(mode = "demo") } }
    private val tokens = FakeTokens()

    @Before
    fun holdFloatingSeat() {
        ensureTestFirebaseApp(context)
        AppRemoteConfig.apply(context, AppConfigDto(mode = "licensed", licenseSeating = "floating"))
    }

    @Test
    fun `a plain sign-out gives the floating seat back`() = runBlocking {
        AuthRepository(context, api, tokens).signOut()

        assertEquals(listOf("releaseLease"), api.calls)
    }

    @Test
    fun `a sign-out after an erase sends no release`() = runBlocking {
        AuthRepository(context, api, tokens).signOut(releaseSeat = false)

        assertFalse("releaseLease" in api.calls)
    }

    @Test
    fun `an account deletion never calls the seat release`() = runBlocking {
        val outcome = CloudErase.deleteAccount(context, api, tokens, eraseCloud = { true })

        // Nobody is signed in to Firebase here, so the identity delete has nothing to do.
        assertEquals(AccountDeletion.DELETED, outcome)
        assertFalse("releaseLease" in api.calls)
    }
}
