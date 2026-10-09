package com.sempermechanics.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.session.SessionQuota
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.QuotaBackendOn
import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The offline-first quota contract (P0-1) and the AppRemoteConfig ↔ AccountCache
 * ownership split (P1-5):
 *
 * - An UNKNOWN cloud quota is never a hard stop — analysis is on-device, so it
 *   must be allowed to run; only its upload is gated elsewhere.
 * - The session ceiling is owned by [AppRemoteConfig]; [SessionQuota] reads it and
 *   computes the hard stop live from used-vs-max.
 * - One rule (TD-167): every start check and the save of a row no run's start
 *   admitted ask [SessionQuota.blocked]. It used to be two, which disagreed on
 *   the forced stop, on a build with no backend, and on which local count.
 */
@RunWith(RobolectricTestRunner::class)
class QuotaGateTest {

    @get:Rule
    val backend = QuotaBackendOn()

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        AccountCache.clear(ctx) // also clears AppRemoteConfig
    }

    @After
    fun tearDown() {
        AccountCache.clear(ctx)
        SessionStore.deleteAll(ctx)
    }

    private val fiveMax = AppConfigDto(maxSessions = 5, maxFilesPerSession = 600, maxFrames = 150)

    private fun row(id: String) = sessionRecord(id = id, createdAt = 1L, refPath = "ref.png", sessionDir = "/dir/$id")

    @Test
    fun `a forced stop blocks the start and a save no start admitted, not an admitted one`() {
        // The save's gate used to ignore the stop an upload's 409 forces.
        AppRemoteConfig.apply(ctx, fiveMax)
        AccountCache.setQuota(ctx, used = 1, localCount = 0)
        AccountCache.setSessionLimitReached(ctx, true)

        assertTrue(SessionQuota.blocked(ctx, liveRows = 0))
        assertEquals(SessionStore.UpsertOutcome.QUOTA_FULL, SessionStore.save(ctx, row("new")))
        assertEquals(SessionStore.UpsertOutcome.SAVED, SessionStore.save(ctx, row("admitted"), allowOverLimit = true))
    }

    @Test
    fun `with no backend there is no cap, at the start as at the save`() {
        // The start check used to hold a build with no API URL to the demo cap.
        SessionQuota.api = { FakeCloudApi(enabled = false) }
        AccountCache.setQuota(ctx, used = LicenseEntitlements.DEMO_MAX_ANALYSES)
        AccountCache.setSessionLimitReached(ctx, true)

        assertFalse(SessionQuota.blockedNow(ctx))
        assertFalse(SessionQuota.blocked(ctx, liveRows = LicenseEntitlements.DEMO_MAX_ANALYSES))
        assertEquals(SessionStore.UpsertOutcome.SAVED, SessionStore.save(ctx, row("a")))
    }

    @Test
    fun `the rule counts the phone's rows, not a stale stored count`() {
        // The start check used to read the local count it last stored; the
        // save counted the rows. Both now take the rows.
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 2, maxFilesPerSession = 600, maxFrames = 150))
        assertEquals(SessionStore.UpsertOutcome.SAVED, SessionStore.save(ctx, row("a")))
        assertEquals(SessionStore.UpsertOutcome.SAVED, SessionStore.save(ctx, row("b")))
        AccountCache.setQuota(ctx, used = 0, localCount = 0) // stale: two rows on the phone

        assertTrue(SessionQuota.blocked(ctx, liveRows = SessionStore.list(ctx).size))
        assertEquals(SessionStore.UpsertOutcome.QUOTA_FULL, SessionStore.save(ctx, row("c")))
        // And the server's count still counts when the phone holds fewer.
        AccountCache.setQuota(ctx, used = 2, localCount = 0)
        assertTrue(SessionQuota.blocked(ctx, liveRows = 0))
    }

    @Test
    fun `every write of the index stores its row count for the main-thread check`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 2, maxFilesPerSession = 600, maxFrames = 150))
        assertEquals(SessionStore.UpsertOutcome.SAVED, SessionStore.save(ctx, row("a")))
        assertFalse(SessionQuota.blockedNow(ctx))
        assertEquals(SessionStore.UpsertOutcome.SAVED, SessionStore.save(ctx, row("b")))

        assertEquals(2, AccountCache.localCount(ctx))
        assertTrue(SessionQuota.blockedNow(ctx))

        SessionStore.forget(ctx, "b")
        assertEquals(1, AccountCache.localCount(ctx))
        assertFalse(SessionQuota.blockedNow(ctx))
    }

    @Test
    fun `unknown quota is not a hard stop`() {
        assertFalse("no config fetched yet", AppRemoteConfig.isKnown(ctx))
        assertFalse(AccountCache.isQuotaKnown(ctx))
        // The key offline-first invariant: analysis is NOT blocked when the
        // ceiling is unknown.
        assertFalse(SessionQuota.blockedNow(ctx))
    }

    @Test
    fun `ceiling comes from AppRemoteConfig, not a AccountCache copy`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 5, maxFilesPerSession = 600, maxFrames = 150))
        assertTrue(AccountCache.isQuotaKnown(ctx))
        assertEquals(5, AccountCache.quotaMax(ctx))

        // Under the ceiling → allowed.
        AccountCache.setQuota(ctx, used = 2, localCount = 2)
        assertFalse(SessionQuota.blockedNow(ctx))

        // At the ceiling → hard stop, computed live from used vs max.
        AccountCache.setQuota(ctx, used = 5, localCount = 5)
        assertTrue(SessionQuota.blockedNow(ctx))
    }

    @Test
    fun `a licensed account stops at a known ceiling, like demo`() {
        // Before this, licensed meant "no local cap": the run started, the
        // upload bounced at 409, and the limit screen's re-check said clear.
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed", maxSessions = 30))
        AccountCache.setQuota(ctx, used = 29, localCount = 29)
        assertFalse(SessionQuota.blockedNow(ctx))

        AccountCache.setQuota(ctx, used = 30, localCount = 30)
        assertTrue(SessionQuota.blockedNow(ctx))
    }

    @Test
    fun `a forced stop holds until fresh numbers arrive`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 5, maxFilesPerSession = 600, maxFrames = 150))
        // 409 from the upload path forces the stop without fresh counts.
        AccountCache.setSessionLimitReached(ctx, true)
        assertTrue(SessionQuota.blockedNow(ctx))

        // A reconcile with under-limit numbers clears the forced stop.
        AccountCache.setQuota(ctx, used = 1, localCount = 1)
        assertFalse(SessionQuota.blockedNow(ctx))
    }

    @Test
    fun `a forced stop survives a local count refresh`() {
        // Home's start and refresh, a run's pre-check and every save fold the
        // phone's count in; none of them brings fresh server numbers.
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 5, maxFilesPerSession = 600, maxFrames = 150))
        AccountCache.setSessionLimitReached(ctx, true)

        AccountCache.refreshSessionLimit(ctx, localCount = 1)

        assertTrue(SessionQuota.blockedNow(ctx))
    }

    @Test
    fun `a forced stop survives a restore saving its row`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 5, maxFilesPerSession = 600, maxFrames = 150))
        AccountCache.setSessionLimitReached(ctx, true)

        val row = RestoreStart.newRow(ctx, "cloud-1", "local-1", "Steel plate", now = 0L)
        assertTrue(SessionStore.upsert(ctx, row, allowOverLimit = true))

        assertTrue("saving a row is not fresh server numbers", SessionQuota.blockedNow(ctx))
    }

    @Test
    fun `deleting from the phone lifts a forced stop`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 5, maxFilesPerSession = 600, maxFrames = 150))
        val row = RestoreStart.newRow(ctx, "cloud-1", "local-1", "Steel plate", now = 0L)
        assertTrue(SessionStore.upsert(ctx, row, allowOverLimit = true))
        AccountCache.setSessionLimitReached(ctx, true)

        SessionStore.delete(ctx, "local-1")

        assertFalse(SessionQuota.blockedNow(ctx))
    }

    @Test
    fun `a delete on the phone lowers used, but never below the server's count`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 5, maxFilesPerSession = 600, maxFrames = 150))
        // Offline: the server last counted 2, the phone holds 5.
        AccountCache.setQuota(ctx, used = 2, localCount = 5)
        assertTrue(SessionQuota.blockedNow(ctx))

        // Deleting two on the phone takes the count, and the stop, with it.
        AccountCache.refreshSessionLimit(ctx, localCount = 3)
        assertEquals(3, AccountCache.quotaUsed(ctx))
        assertFalse(SessionQuota.blockedNow(ctx))

        // The server's copies still count until a reconcile says otherwise.
        AccountCache.refreshSessionLimit(ctx, localCount = 0)
        assertEquals(2, AccountCache.quotaUsed(ctx))
    }

    @Test
    fun `clearing config makes the quota unknown again`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 3, maxFilesPerSession = 600, maxFrames = 150))
        assertTrue(AccountCache.isQuotaKnown(ctx))

        AppRemoteConfig.clear(ctx)
        assertFalse(AccountCache.isQuotaKnown(ctx))
        assertEquals(0, AccountCache.quotaMax(ctx))
        assertFalse("unknown quota never hard-stops analysis", SessionQuota.blockedNow(ctx))
    }
}
