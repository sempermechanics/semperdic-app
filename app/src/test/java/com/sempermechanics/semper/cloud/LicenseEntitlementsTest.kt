package com.sempermechanics.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.prefs.DicSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/**
 * [LicenseEntitlements] is the single place the app asks "am I Demo or
 * Professional" — everything else (Settings gates, upload/backup, share)
 * should read through it rather than [AppRemoteConfig] directly.
 *
 * Key invariant under test: an individual key and an institution seat are the same
 * plan shape to every gate here. `licenseKind` is carried through purely for
 * display/support, never as a gating input — see the doc comment on
 * [LicenseEntitlements.licenseKind].
 */
@RunWith(RobolectricTestRunner::class)
class LicenseEntitlementsTest {

    private lateinit var ctx: Context
    private val backend = FakeCloudApi()

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        AppRemoteConfig.clear(ctx)
    }

    @After
    fun tearDown() {
        AppRemoteConfig.clear(ctx)
        DicSettings.setSaveToCloud(ctx, true)
    }

    @Test
    fun `fails closed to Demo before any config has ever landed`() {
        assertEquals(LicenseEntitlements.MODE_DEMO, LicenseEntitlements.mode(ctx))
        assertFalse(LicenseEntitlements.isLicensed(ctx))
        assertFalse(LicenseEntitlements.cloudBackupEnabled(ctx))
        assertFalse(LicenseEntitlements.shareEnabled(ctx))
        assertEquals(LicenseEntitlements.DEMO_MAX_ANALYSES, LicenseEntitlements.analysisCap(ctx))
        assertEquals("", LicenseEntitlements.licenseKind(ctx))
    }

    @Test
    fun `an individual key and an institution seat grant identical entitlements`() {
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(
                mode = "licensed",
                cloudBackupEnabled = true,
                shareEnabled = true,
                licensePrefix = "SEMP-AB12",
                licenseKind = "individual",
            ),
        )
        val individualBackup = LicenseEntitlements.cloudBackupEnabled(ctx)
        val individualShare = LicenseEntitlements.shareEnabled(ctx)
        val individualUnlimited = LicenseEntitlements.unlimitedAnalysis(ctx)

        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(
                mode = "licensed",
                cloudBackupEnabled = true,
                shareEnabled = true,
                licensePrefix = "SEMP-CD34",
                licenseKind = "institution",
            ),
        )
        assertEquals(individualBackup, LicenseEntitlements.cloudBackupEnabled(ctx))
        assertEquals(individualShare, LicenseEntitlements.shareEnabled(ctx))
        assertEquals(individualUnlimited, LicenseEntitlements.unlimitedAnalysis(ctx))
        assertTrue(LicenseEntitlements.isLicensed(ctx))
        // licenseKind itself DOES differ — it's carried through for display only.
        assertEquals("institution", LicenseEntitlements.licenseKind(ctx))
    }

    @Test
    fun `demo mode stays gated even if cloudBackupEnabled somehow arrives true`() {
        // Defense in depth: mode is the real gate, not the individual booleans —
        // a demo user must never get cloud backup/share regardless of what
        // else is in the config payload.
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "demo", cloudBackupEnabled = true, shareEnabled = true),
        )
        assertFalse(LicenseEntitlements.isLicensed(ctx))
        assertFalse(LicenseEntitlements.cloudBackupEnabled(ctx))
        assertFalse(LicenseEntitlements.shareEnabled(ctx))
        assertEquals(LicenseEntitlements.DEMO_MAX_ANALYSES, LicenseEntitlements.analysisCap(ctx))
    }

    @Test
    fun `a licensed account is held to the backend ceiling once it is known`() {
        // The server refuses the upload past maxSessions; the local gate has to
        // agree, or the run starts, bounces at 409, and "Re-check" says clear.
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed", maxSessions = 40))
        assertFalse(LicenseEntitlements.unlimitedAnalysis(ctx))
        assertEquals(40, LicenseEntitlements.analysisCap(ctx))
    }

    @Test
    fun `a licensed account has no local cap before the ceiling is known`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed", maxSessions = 0))
        assertTrue(LicenseEntitlements.unlimitedAnalysis(ctx))
        assertEquals(Int.MAX_VALUE, LicenseEntitlements.analysisCap(ctx))
    }

    @Test
    fun `demo analysis cap falls back to the constant before config is known`() {
        assertEquals(LicenseEntitlements.DEMO_MAX_ANALYSES, LicenseEntitlements.analysisCap(ctx))
    }

    @Test
    fun `downgrading from licensed to demo drops entitlements without needing a clear`() {
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "licensed", cloudBackupEnabled = true, shareEnabled = true),
        )
        assertTrue(LicenseEntitlements.isLicensed(ctx))

        // revalidate_device_lock / a revoked seat resolves the NEXT config fetch
        // to mode=demo — the app applies whatever GET /v1/config last returned.
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "demo"))
        assertFalse(LicenseEntitlements.isLicensed(ctx))
        assertFalse(LicenseEntitlements.cloudBackupEnabled(ctx))
        assertFalse(LicenseEntitlements.shareEnabled(ctx))
    }

    // ---- compatibility with a backend that predates the plan->mode rename ----

    @Test
    fun `falls back to the plan mirror when the backend sends no mode`() {
        // A deploy that has not shipped the rename sends only `plan`. Reading
        // that as demo would strip a paying account's entitlements.
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(plan = "professional", cloudBackupEnabled = true, shareEnabled = true),
        )
        assertTrue(LicenseEntitlements.isLicensed(ctx))
        assertTrue(LicenseEntitlements.cloudBackupEnabled(ctx))
    }

    @Test
    fun `mode wins over a disagreeing plan mirror`() {
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "demo", plan = "professional", cloudBackupEnabled = true),
        )
        assertFalse(LicenseEntitlements.isLicensed(ctx))
        assertFalse(LicenseEntitlements.cloudBackupEnabled(ctx))
    }

    @Test
    fun `an unrecognised mode is demo, never licensed`() {
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "enterprise", plan = "enterprise", cloudBackupEnabled = true),
        )
        assertFalse(LicenseEntitlements.isLicensed(ctx))
    }

    @Test
    fun `the pre-rename campus licenseKind is normalised to institution`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed", licenseKind = "campus"))
        assertEquals("institution", LicenseEntitlements.licenseKind(ctx))
    }

    @Test
    fun `a licensed account upgrading the app is not demoted before the next fetch`() {
        // Simulate the prefs a build predating the rename left behind: it wrote
        // the plan under its own key and knows nothing about `mode`. Reading
        // demo here would strip entitlements from launch until /v1/config
        // lands, which offline may be a long time.
        ctx.getSharedPreferences("semper_remote_config", Context.MODE_PRIVATE)
            .edit()
            .putString("plan", "professional")
            .commit()

        assertEquals(LicenseEntitlements.MODE_LICENSED, LicenseEntitlements.mode(ctx))
        assertTrue(LicenseEntitlements.isLicensed(ctx))
    }

    // ---- license duration, grace, and cache staleness ----

    private val day = 24L * 60 * 60 * 1000

    private fun applyLicensed(expiresAt: String?, inGrace: Boolean = false, now: Long) {
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(
                mode = "licensed",
                licenseDuration = if (expiresAt == null) "perpetual" else "timed",
                licenseExpiresAt = expiresAt,
                inGrace = inGrace,
            ),
            now = now,
        )
    }

    @Test
    fun `a perpetual license never produces an expiry notice`() {
        val now = 1_000_000_000_000L
        applyLicensed(expiresAt = null, now = now)
        assertNull(LicenseEntitlements.daysUntilExpiry(ctx, now))
        assertNull(LicenseEntitlements.expiryNoticeDays(ctx, now))
    }

    @Test
    fun `no notice until the license is inside the warning window`() {
        val now = 1_000_000_000_000L
        applyLicensed(Instant.ofEpochMilli(now + 30 * day).toString(), now = now)
        assertEquals(30L, LicenseEntitlements.daysUntilExpiry(ctx, now))
        assertNull(
            "30 days out is not yet worth interrupting for",
            LicenseEntitlements.expiryNoticeDays(ctx, now),
        )
    }

    @Test
    fun `the countdown counts the licence's UTC days rather than rounding hours up`() {
        // A licence ends at 23:59:59Z on its chosen day.
        val expiresAt = Instant.parse("2026-10-01T23:59:59Z")
        val threeHoursLeft = expiresAt.minusSeconds(3 * 3600).toEpochMilli()
        applyLicensed(expiresAt.toString(), now = threeHoursLeft)
        assertEquals(0L, LicenseEntitlements.daysUntilExpiry(ctx, threeHoursLeft))

        val twentyFiveHoursLeft = expiresAt.minusSeconds(25 * 3600).toEpochMilli()
        applyLicensed(expiresAt.toString(), now = twentyFiveHoursLeft)
        assertEquals(1L, LicenseEntitlements.daysUntilExpiry(ctx, twentyFiveHoursLeft))
    }

    @Test
    fun `a notice appears inside the warning window`() {
        val now = 1_000_000_000_000L
        applyLicensed(Instant.ofEpochMilli(now + 9 * day).toString(), now = now)
        assertEquals(9L, LicenseEntitlements.expiryNoticeDays(ctx, now))
    }

    @Test
    fun `grace notices regardless of how far past expiry it is`() {
        val now = 1_000_000_000_000L
        applyLicensed(Instant.ofEpochMilli(now - 3 * day).toString(), inGrace = true, now = now)
        assertTrue(LicenseEntitlements.inGrace(ctx))
        // Still fully entitled — grace withdraws nothing.
        assertTrue(LicenseEntitlements.isLicensed(ctx))
        assertEquals(-3L, LicenseEntitlements.expiryNoticeDays(ctx, now))
    }

    @Test
    fun `a stale cache suppresses the notice entirely`() {
        // A renewal may have landed while the device was offline. Warning from
        // a weeks-old cache would be a false alarm the user cannot act on.
        val fetchedAt = 1_000_000_000_000L
        applyLicensed(Instant.ofEpochMilli(fetchedAt + 2 * day).toString(), now = fetchedAt)
        val muchLater = fetchedAt + 30 * day
        assertTrue(AppRemoteConfig.isStale(ctx, LicenseEntitlements.STALE_CACHE_MS, muchLater))
        assertNull(LicenseEntitlements.expiryNoticeDays(ctx, muchLater))
    }

    @Test
    fun `a demo account never gets an expiry notice`() {
        val now = 1_000_000_000_000L
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "demo", licenseExpiresAt = Instant.ofEpochMilli(now).toString()),
            now = now,
        )
        assertNull(LicenseEntitlements.expiryNoticeDays(ctx, now))
    }

    @Test
    fun `an unparseable expiry is treated as absent, not as expired at the epoch`() {
        val now = 1_000_000_000_000L
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "licensed", licenseDuration = "timed", licenseExpiresAt = "not-a-date"),
            now = now,
        )
        assertNull(LicenseEntitlements.expiryNoticeDays(ctx, now))
    }

    @Test
    fun `the expiry parses in the shape the backend actually sends`() {
        // Every other case here builds its timestamp with Instant.toString(),
        // which writes `Z`. The backend serialises an aware datetime, so what
        // arrives on the wire is `+00:00` with microseconds — a spelling no
        // test covered while the parser only had to satisfy Instant.parse.
        val now = 1_000_000_000_000L // 2001-09-09T01:46:40Z
        for (wire in listOf(
            "2001-09-18T01:46:40+00:00",
            "2001-09-18T01:46:40.123456+00:00",
            "2001-09-18T01:46:40Z",
        )) {
            AppRemoteConfig.clear(ctx)
            applyLicensed(wire, now = now)
            assertEquals(wire, 9L, LicenseEntitlements.expiryNoticeDays(ctx, now))
        }
    }

    @Test
    fun `an expiry offset from UTC lands on the instant it names`() {
        // 22:00 five hours behind UTC is 03:00Z the next day, so one UTC day
        // out; read without its offset it would be later today, 0 days out.
        val now = 1_000_000_000_000L // 2001-09-09T01:46:40Z
        applyLicensed("2001-09-09T22:00:00-05:00", now = now)
        assertEquals(1L, LicenseEntitlements.expiryNoticeDays(ctx, now))
    }

    @Test
    fun `the dev-auth bypass licenses without an expiry, so it never warns`() {
        // DevAuth.install seeds mode=licensed with no expiry. A banner firing
        // on every emulator launch would be noise nobody can act on.
        val now = 1_000_000_000_000L
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "licensed", cloudBackupEnabled = true, shareEnabled = true),
            now = now,
        )
        assertNull(LicenseEntitlements.expiryNoticeDays(ctx, now))
    }

    @Test
    fun `a never-fetched cache is stale`() {
        assertTrue(AppRemoteConfig.isStale(ctx, LicenseEntitlements.STALE_CACHE_MS, 1L))
    }

    @Test
    fun `a fetched-at in the future reads as stale, not fresh forever`() {
        // NTP correction or the user changing the date. CloudSync guards its
        // reconcile throttle the same way.
        val fetchedAt = 1_000_000_000_000L
        applyLicensed(expiresAt = null, now = fetchedAt)
        assertTrue(AppRemoteConfig.isStale(ctx, LicenseEntitlements.STALE_CACHE_MS, fetchedAt - day))
    }

    // ---- floating seats ----

    @Test
    fun `an assigned license never needs a seat`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed", licenseSeating = "assigned"))
        assertFalse(LicenseEntitlements.needsSeat(ctx))
        assertFalse(LicenseEntitlements.seatRequiredToStart(ctx))
    }

    @Test
    fun `a backend predating floating seats reads as assigned`() {
        // Failing the other way would lock every institution user out of
        // starting work against an older deploy.
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed"))
        assertFalse(LicenseEntitlements.needsSeat(ctx))
        assertFalse(LicenseEntitlements.seatRequiredToStart(ctx))
    }

    @Test
    fun `a floating member holding a seat may start work`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed", licenseSeating = "floating"))
        assertTrue(LicenseEntitlements.needsSeat(ctx))
        assertFalse(LicenseEntitlements.seatRequiredToStart(ctx))
    }

    @Test
    fun `a floating member without a seat is gated`() {
        // demo + floating is the one combination meaning "eligible, but
        // somebody else has the seat".
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "demo", licenseSeating = "floating"))
        assertTrue(LicenseEntitlements.seatRequiredToStart(ctx))
    }

    @Test
    fun `a plain demo account is not sent to the seat screen`() {
        // It has no institution license at all — the quota gate is what limits
        // it, and offering a seat it can never take would be nonsense.
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "demo"))
        assertFalse(LicenseEntitlements.seatRequiredToStart(ctx))
    }

    @Test
    fun `the seat gate is independent of the analysis quota`() {
        // The reason this is a parallel predicate: a licensed institution
        // member is held only to the licensed ceiling, so every existing quota
        // check waves them through regardless of whether they hold a seat.
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "demo", licenseSeating = "floating"))
        assertTrue(LicenseEntitlements.seatRequiredToStart(ctx))

        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "licensed", licenseSeating = "floating", maxSessions = 999),
        )
        assertEquals(999, LicenseEntitlements.analysisCap(ctx))
        assertFalse(LicenseEntitlements.seatRequiredToStart(ctx))
    }

    @Test
    fun `the heartbeat interval falls back when the backend does not send one`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed", licenseSeating = "floating"))
        assertEquals(
            AppRemoteConfig.DEFAULT_HEARTBEAT_MINUTES,
            LicenseEntitlements.seatHeartbeatMinutes(ctx),
        )
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "licensed", licenseSeating = "floating", leaseHeartbeatMinutes = 10),
        )
        assertEquals(10, LicenseEntitlements.seatHeartbeatMinutes(ctx))
    }

    @Test
    fun `a demo account upgrading the app stays demo`() {
        ctx.getSharedPreferences("semper_remote_config", Context.MODE_PRIVATE)
            .edit()
            .putString("plan", "demo")
            .commit()

        assertFalse(LicenseEntitlements.isLicensed(ctx))
    }

    // ── Recording vs. backup ────────────────────────────────────────────
    //
    // Recording an analysis (upload) is open to every account; only restore
    // is licensed. Demo therefore always uploads, whatever the Settings
    // toggle says — the toggle is not even shown to a demo account.

    @Test
    fun `demo always records, even with the save-to-cloud toggle off`() {
        DicSettings.setSaveToCloud(ctx, false)
        assertFalse(LicenseEntitlements.cloudBackupEnabled(ctx))
        assertTrue(CloudSync.uploadsEnabled(ctx, backend))
    }

    @Test
    fun `a licensed account records only when the toggle is on`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed", cloudBackupEnabled = true))
        DicSettings.setSaveToCloud(ctx, true)
        assertTrue(CloudSync.uploadsEnabled(ctx, backend))
        DicSettings.setSaveToCloud(ctx, false)
        assertFalse(CloudSync.uploadsEnabled(ctx, backend))
    }

    @Test
    fun `a build with no backend records nothing, whatever the licence`() {
        val none = FakeCloudApi(enabled = false)
        assertFalse(CloudSync.uploadsEnabled(ctx, none))
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed", cloudBackupEnabled = true))
        DicSettings.setSaveToCloud(ctx, true)
        assertFalse(CloudSync.uploadsEnabled(ctx, none))
    }

    @Test
    fun `a downgrade to demo resumes recording regardless of the old toggle`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed", cloudBackupEnabled = true))
        DicSettings.setSaveToCloud(ctx, false)
        assertFalse(CloudSync.uploadsEnabled(ctx, backend))
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "demo", cloudBackupEnabled = false))
        assertTrue(CloudSync.uploadsEnabled(ctx, backend))
    }
}
