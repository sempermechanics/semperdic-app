package com.indicvision.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.cloud.restore.RestoreStart
import com.indicvision.semper.data.net.AppConfigDto
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.data.session.SessionStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The offline-first quota contract (P0-1) and the AppRemoteConfig ↔ TokenStore
 * ownership split (P1-5):
 *
 * - An UNKNOWN cloud quota is never a hard stop — analysis is on-device, so it
 *   must be allowed to run; only its upload is gated elsewhere.
 * - The session ceiling is owned by [AppRemoteConfig]; [TokenStore] reads it and
 *   computes the hard stop live from used-vs-max.
 */
@RunWith(RobolectricTestRunner::class)
class QuotaGateTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        TokenStore.clear(ctx) // also clears AppRemoteConfig
    }

    @After
    fun tearDown() {
        TokenStore.clear(ctx)
    }

    @Test
    fun `unknown quota is not a hard stop`() {
        assertFalse("no config fetched yet", AppRemoteConfig.isKnown(ctx))
        assertFalse(TokenStore.isQuotaKnown(ctx))
        // The key offline-first invariant: analysis is NOT blocked when the
        // ceiling is unknown.
        assertFalse(TokenStore.isSessionLimitReached(ctx))
    }

    @Test
    fun `ceiling comes from AppRemoteConfig, not a TokenStore copy`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 5, maxFilesPerSession = 600, maxFrames = 150))
        assertTrue(TokenStore.isQuotaKnown(ctx))
        assertEquals(5, TokenStore.quotaMax(ctx))

        // Under the ceiling → allowed.
        TokenStore.setQuota(ctx, used = 2, localCount = 2)
        assertFalse(TokenStore.isSessionLimitReached(ctx))

        // At the ceiling → hard stop, computed live from used vs max.
        TokenStore.setQuota(ctx, used = 5, localCount = 5)
        assertTrue(TokenStore.isSessionLimitReached(ctx))
    }

    @Test
    fun `a licensed account stops at a known ceiling, like demo`() {
        // Before this, licensed meant "no local cap": the run started, the
        // upload bounced at 409, and the limit screen's re-check said clear.
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed", maxSessions = 30))
        TokenStore.setQuota(ctx, used = 29, localCount = 29)
        assertFalse(TokenStore.isSessionLimitReached(ctx))

        TokenStore.setQuota(ctx, used = 30, localCount = 30)
        assertTrue(TokenStore.isSessionLimitReached(ctx))
    }

    @Test
    fun `a forced stop holds until fresh numbers arrive`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 5, maxFilesPerSession = 600, maxFrames = 150))
        // 409 from the upload path forces the stop without fresh counts.
        TokenStore.setSessionLimitReached(ctx, true)
        assertTrue(TokenStore.isSessionLimitReached(ctx))

        // A reconcile with under-limit numbers clears the forced stop.
        TokenStore.setQuota(ctx, used = 1, localCount = 1)
        assertFalse(TokenStore.isSessionLimitReached(ctx))
    }

    @Test
    fun `a forced stop survives a local count refresh`() {
        // Home's start and refresh, a run's pre-check and every save fold the
        // phone's count in; none of them brings fresh server numbers.
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 5, maxFilesPerSession = 600, maxFrames = 150))
        TokenStore.setSessionLimitReached(ctx, true)

        TokenStore.refreshSessionLimit(ctx, localCount = 1)

        assertTrue(TokenStore.isSessionLimitReached(ctx))
    }

    @Test
    fun `a forced stop survives a restore saving its row`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 5, maxFilesPerSession = 600, maxFrames = 150))
        TokenStore.setSessionLimitReached(ctx, true)

        val row = RestoreStart.newRow(ctx, "cloud-1", "local-1", "Steel plate", now = 0L)
        assertTrue(SessionStore.upsert(ctx, row, allowOverLimit = true))

        assertTrue("saving a row is not fresh server numbers", TokenStore.isSessionLimitReached(ctx))
    }

    @Test
    fun `deleting from the phone lifts a forced stop`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 5, maxFilesPerSession = 600, maxFrames = 150))
        val row = RestoreStart.newRow(ctx, "cloud-1", "local-1", "Steel plate", now = 0L)
        assertTrue(SessionStore.upsert(ctx, row, allowOverLimit = true))
        TokenStore.setSessionLimitReached(ctx, true)

        SessionStore.delete(ctx, "local-1")

        assertFalse(TokenStore.isSessionLimitReached(ctx))
    }

    @Test
    fun `a delete on the phone lowers used, but never below the server's count`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 5, maxFilesPerSession = 600, maxFrames = 150))
        // Offline: the server last counted 2, the phone holds 5.
        TokenStore.setQuota(ctx, used = 2, localCount = 5)
        assertTrue(TokenStore.isSessionLimitReached(ctx))

        // Deleting two on the phone takes the count, and the stop, with it.
        TokenStore.refreshSessionLimit(ctx, localCount = 3)
        assertEquals(3, TokenStore.quotaUsed(ctx))
        assertFalse(TokenStore.isSessionLimitReached(ctx))

        // The server's copies still count until a reconcile says otherwise.
        TokenStore.refreshSessionLimit(ctx, localCount = 0)
        assertEquals(2, TokenStore.quotaUsed(ctx))
    }

    @Test
    fun `clearing config makes the quota unknown again`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 3, maxFilesPerSession = 600, maxFrames = 150))
        assertTrue(TokenStore.isQuotaKnown(ctx))

        AppRemoteConfig.clear(ctx)
        assertFalse(TokenStore.isQuotaKnown(ctx))
        assertEquals(0, TokenStore.quotaMax(ctx))
        assertFalse("unknown quota never hard-stops analysis", TokenStore.isSessionLimitReached(ctx))
    }
}
