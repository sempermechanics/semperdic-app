package com.indicvision.semper.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.account.DeviceKeyManager
import com.indicvision.semper.data.cloud.CloudBackupListing
import com.indicvision.semper.data.cloud.restore.RestoreFailureLedger
import com.indicvision.semper.data.net.AppConfigDto
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.CloudSessionDto
import com.indicvision.semper.data.net.TokenStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * [PrefKey] and the [PrefFiles] catalogue against the code that owns each
 * file today. Each key is written through [PrefKey] and checked against the
 * raw value under the existing key string, and where the owner has a public
 * reader or writer, against that too: a key whose string drifted by a byte
 * would orphan every value already on a phone.
 */
@RunWith(RobolectricTestRunner::class)
class PrefKeyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun prefs(name: String): SharedPreferences = privatePrefs(context, name)

    @Before
    @After
    fun wipe() {
        PrefFiles.ALL_NAMES.forEach { prefs(it).edit(commit = true) { clear() } }
    }

    // ── PrefKey itself ─────────────────────────────────────────────────────

    @Test
    fun `privatePrefs is the same file the owners open`() {
        privatePrefs(context, "dic_settings").edit(commit = true) { putBoolean("save_to_cloud", false) }
        val opened = context.getSharedPreferences("dic_settings", Context.MODE_PRIVATE)
        assertFalse(opened.getBoolean("save_to_cloud", true))
    }

    @Test
    fun `each type reads its default when unset and its value once stored`() {
        val p = prefs("prefkey_scratch")
        val flag = PrefKey.boolean("flag", default = true)
        val count = PrefKey.int("count", 7)
        val stamp = PrefKey.long("stamp", 9L)
        val maybe = PrefKey.string("maybe")
        val text = PrefKey.string("text", "fallback")
        val ids = PrefKey.stringSet("ids")

        assertEquals(true, p[flag])
        assertEquals(7, p[count])
        assertEquals(9L, p[stamp])
        assertNull(p[maybe])
        assertEquals("fallback", p[text])
        assertEquals(emptySet<String>(), p[ids])
        assertFalse(flag in p)

        p.edit(commit = true) {
            put(flag, false)
            put(count, 3)
            put(stamp, 1L shl 40)
            put(maybe, "x")
            put(text, "y")
            put(ids, setOf("a", "b"))
        }

        assertFalse(p.getBoolean("flag", true))
        assertEquals(3, p.getInt("count", 0))
        assertEquals(1L shl 40, p.getLong("stamp", 0L))
        assertEquals("x", p.getString("maybe", null))
        assertEquals("y", p.getString("text", null))
        assertEquals(setOf("a", "b"), p.getStringSet("ids", null))
        assertEquals(setOf("a", "b"), p[ids])
        assertTrue(flag in p)
        p.edit(commit = true) { clear() }
    }

    @Test
    fun `writing null to a nullable string removes it, as putString does`() {
        val p = prefs("prefkey_scratch")
        val role = PrefKey.string("role")
        p.edit(commit = true) { put(role, "admin") }
        p.edit(commit = true) { put(role, null) }
        assertFalse(p.contains("role"))
        p.edit(commit = true) { put(role, "user") }
        p.edit(commit = true) { remove(role) }
        assertFalse(role in p)
    }

    @Test
    fun `a string set read is a copy`() {
        val p = prefs("prefkey_scratch")
        val ids = PrefKey.stringSet("ids")
        p.edit(commit = true) { put(ids, setOf("a", "b")) }
        val stored = p.getStringSet("ids", null)
        val read = p[ids]
        assertEquals(stored, read)
        assertNotSame(stored, read)
        p.edit(commit = true) { clear() }
    }

    // ── The catalogue, file by file ────────────────────────────────────────

    @Test
    fun `the catalogue names twelve distinct files`() {
        assertEquals(12, PrefFiles.ALL_NAMES.toSet().size)
    }

    @Test
    fun `dic_settings keys are DicSettings' own`() {
        val s = PrefFiles.Settings
        val p = prefs(s.NAME)
        assertEquals("dic_settings", s.NAME)
        // Defaults agree with the owner's on an empty file.
        assertEquals(DicSettings.saveToCloud(context), p[s.SAVE_TO_CLOUD])
        assertEquals(DicSettings.uploadWifiOnly(context), p[s.UPLOAD_WIFI_ONLY])
        assertEquals(DicSettings.maxFrames(context, 0), p[s.MAX_FRAMES])
        assertEquals(DicSettings.autoFreeBudgetGb(context), p[s.AUTO_FREE_GB])
        assertEquals(DicSettings.diagnosticsEnabled(context), p[s.DIAGNOSTICS_ENABLED])
        assertEquals(DicSettings.diagnosticsAsked(context), p[s.DIAGNOSTICS_ASKED])

        // Written through the key, read by the owner.
        p.edit(commit = true) {
            put(s.SAVE_TO_CLOUD, false)
            put(s.UPLOAD_WIFI_ONLY, true)
            put(s.MAX_FRAMES, 77)
            put(s.AUTO_FREE_GB, 5)
        }
        assertFalse(DicSettings.saveToCloud(context))
        assertTrue(DicSettings.uploadWifiOnly(context))
        assertEquals(77, DicSettings.maxFrames(context, 0))
        assertEquals(5, DicSettings.autoFreeBudgetGb(context))
        assertFalse(p.getBoolean("save_to_cloud", true))
        assertTrue(p.getBoolean("upload_wifi_only", false))
        assertEquals(77, p.getInt("max_frames", 0))
        assertEquals(5, p.getInt("auto_free_gb", -1))

        // Written by the owner, read through the key.
        DicSettings.setDiagnosticsEnabled(context, true)
        assertTrue(p[s.DIAGNOSTICS_ENABLED])
        assertTrue(p[s.DIAGNOSTICS_ASKED])
        assertTrue(p.getBoolean("diagnostics_enabled", false))
        assertTrue(p.getBoolean("diagnostics_asked", false))

        p.edit(commit = true) { put(s.KEEP_EVERY_RERUN, true) }
        DicSettings.migrate(context)
        assertFalse(s.KEEP_EVERY_RERUN in p)
        assertFalse(p.contains("keep_every_rerun"))
        assertEquals(1, p[s.SCHEMA])
        assertEquals(1, p.getInt("schema", 0))
    }

    @Test
    fun `param_clipboard keys are ParamClipboard's own`() {
        val c = PrefFiles.Clipboard
        val p = prefs(c.NAME)
        assertEquals("param_clipboard", c.NAME)
        ParamClipboard.copy(context, subset = 41, step = 5, vsg = 21)
        assertTrue(p[c.HAS_PARAMS])
        assertEquals(41, p[c.SUBSET])
        assertEquals(5, p[c.STEP])
        assertEquals(21, p[c.STRAIN_WINDOW])
        assertEquals(41, p.getInt("SUBSET_SIZE", 0))
        assertEquals(5, p.getInt("STEP", 0))
        assertEquals(21, p.getInt("STRAIN_WINDOW", 0))
        assertTrue(p.getBoolean("has_params", false))

        p.edit(commit = true) { put(c.SUBSET, 31) }
        assertEquals(ParamClipboard.Params(31, 5, 21), ParamClipboard.peek(context))
        p.edit(commit = true) { put(c.HAS_PARAMS, false) }
        assertNull(ParamClipboard.peek(context))
    }

    @Test
    fun `indic_coach has one key per coached screen`() {
        val c = PrefFiles.Coach
        val p = prefs(c.NAME)
        assertEquals("indic_coach", c.NAME)
        val raw = mapOf(
            CoachPrefs.Screen.HOME to "coach_home_seen",
            CoachPrefs.Screen.ANALYSIS_IMAGES to "coach_analysis_images_seen",
            CoachPrefs.Screen.ANALYSIS_SETTINGS to "coach_analysis_settings_seen",
            CoachPrefs.Screen.ANALYSIS_SWEEP to "coach_analysis_sweep_seen",
            CoachPrefs.Screen.SWEEP_LATTICE to "coach_sweep_lattice_seen",
            CoachPrefs.Screen.MEDIA_PICKER_REF to "coach_media_picker_ref_seen",
            CoachPrefs.Screen.MEDIA_PICKER_DEF to "coach_media_picker_def_seen",
        )
        assertEquals(CoachPrefs.Screen.entries.toSet(), raw.keys)
        for ((screen, key) in raw) {
            assertEquals(key, c.seen(screen).name)
            assertFalse(p[c.seen(screen)])
            CoachPrefs.markSeen(context, screen)
            assertTrue(p[c.seen(screen)])
            assertTrue(p.getBoolean(key, false))
        }
        p.edit(commit = true) { clear() }
        p.edit(commit = true) { put(c.SWEEP_LATTICE, true) }
        assertTrue(CoachPrefs.hasSeen(context, CoachPrefs.Screen.SWEEP_LATTICE))
        assertFalse(CoachPrefs.hasSeen(context, CoachPrefs.Screen.HOME))
    }

    @Test
    fun `indic_session keys are TokenStore's own`() {
        val s = PrefFiles.Session
        val p = prefs(s.NAME)
        assertEquals("indic_session", s.NAME)

        TokenStore.saveIdentity(context, "u1", "a@b.c")
        TokenStore.setStatus(context, "APPROVED")
        TokenStore.setRole(context, "admin")
        TokenStore.setDeviceRegistered(context, true)
        TokenStore.setQuota(context, used = 3, localCount = 4)
        TokenStore.setTermsRequiredVersion(context, "2026-09")
        TokenStore.setTermsAccepted(context, "2026-08", synced = true)
        TokenStore.setImprovementConsent(context, false)
        assertEquals("u1", p[s.UID])
        assertEquals("a@b.c", p[s.EMAIL])
        assertEquals("APPROVED", p[s.STATUS])
        assertEquals("admin", p[s.ROLE])
        assertTrue(p[s.DEVICE_REGISTERED])
        assertEquals(3, p[s.QUOTA_USED])
        assertEquals(4, p[s.LOCAL_COUNT])
        assertFalse(p[s.LIMIT_FORCED])
        assertEquals("2026-09", p[s.TERMS_REQUIRED])
        assertEquals("2026-08", p[s.TERMS_ACCEPTED])
        assertTrue(p[s.TERMS_SYNCED])
        assertEquals("false", p[s.IMPROVEMENT_CONSENT])
        val raw = mapOf(
            "uid" to "u1",
            "email" to "a@b.c",
            "last_status" to "APPROVED",
            "role" to "admin",
            "terms_required_version" to "2026-09",
            "terms_accepted_version" to "2026-08",
            "improvement_consent" to "false",
        )
        raw.forEach { (k, v) -> assertEquals(k, v, p.getString(k, null)) }
        assertTrue(p.getBoolean("device_registered", false))
        assertEquals(3, p.getInt("quota_used", 0))
        assertEquals(4, p.getInt("quota_local_count", 0))
        assertTrue(p.getBoolean("terms_accepted_synced", false))

        p.edit(commit = true) {
            put(s.ROLE, null)
            put(s.LIMIT_FORCED, true)
            put(s.IMPROVEMENT_CONSENT, "true")
        }
        assertNull(TokenStore.cachedRole(context))
        assertEquals(true, TokenStore.improvementConsent(context))
        assertTrue(p.getBoolean("session_limit_forced", false))
        TokenStore.clear(context)
    }

    @Test
    fun `indic_onboarding beta acks follow TokenStore's key rule`() {
        val o = PrefFiles.Onboarding
        val p = prefs(o.NAME)
        assertEquals("indic_onboarding", o.NAME)

        TokenStore.clear(context)
        TokenStore.setBetaNoticeAcked(context)
        assertTrue(p[o.betaAcked(null)])
        assertTrue(p.getBoolean("signed_out_beta_notice_acked", false))

        TokenStore.saveIdentity(context, "u9", null)
        assertFalse(TokenStore.hasAckedBetaNotice(context))
        p.edit(commit = true) { put(o.betaAcked("u9"), true) }
        assertTrue(TokenStore.hasAckedBetaNotice(context))
        assertTrue(p.getBoolean("beta_notice_acked_u9", false))
        TokenStore.clear(context)
    }

    @Test
    fun `indic_remote_config keys are AppRemoteConfig's own`() {
        val r = PrefFiles.RemoteConfig
        val p = prefs(r.NAME)
        assertEquals("indic_remote_config", r.NAME)
        // Defaults agree with the owner's on an empty file (spelled out in PrefFiles).
        assertEquals(AppRemoteConfig.DURATION_PERPETUAL, p[r.LICENSE_DURATION])
        assertEquals(AppRemoteConfig.NO_INSTANT, p[r.LICENSE_EXPIRES_AT])
        assertEquals(AppRemoteConfig.SEATING_ASSIGNED, p[r.SEATING])
        assertEquals(AppRemoteConfig.licenseDuration(context), p[r.LICENSE_DURATION])
        assertEquals(AppRemoteConfig.licenseSeating(context), p[r.SEATING])
        assertEquals(AppRemoteConfig.licensePrefix(context), p[r.LICENSE_PREFIX])

        AppRemoteConfig.apply(
            context,
            AppConfigDto(
                maxSessions = 25,
                maxFilesPerSession = 600,
                maxFrames = 120,
                datCodecEncodingEnabled = true,
                mode = "licensed",
                cloudBackupEnabled = true,
                shareEnabled = true,
                licensePrefix = "UNI",
                licenseKind = "institution",
                licenseSeating = "floating",
                leaseHeartbeatMinutes = 15,
                licenseDuration = "timed",
                licenseExpiresAt = "2027-01-01T00:00:00Z",
            ),
            now = 1234L,
        )
        assertEquals(25, p[r.MAX_SESSIONS])
        assertEquals(600, p[r.MAX_FILES_PER_SESSION])
        assertEquals(120, p[r.MAX_FRAMES])
        assertTrue(p[r.DAT_CODEC_ENCODING])
        assertEquals(AppRemoteConfig.mode(context), p[r.MODE])
        assertTrue(p[r.CLOUD_BACKUP])
        assertTrue(p[r.SHARE])
        assertEquals("UNI", p[r.LICENSE_PREFIX])
        assertEquals(AppRemoteConfig.licenseKind(context), p[r.LICENSE_KIND])
        assertEquals(AppRemoteConfig.licenseDuration(context), p[r.LICENSE_DURATION])
        assertEquals(AppRemoteConfig.licenseExpiresAtMillis(context), p[r.LICENSE_EXPIRES_AT])
        assertTrue(p[r.LICENSE_EXPIRES_AT] > 0L)
        assertEquals(AppRemoteConfig.inGrace(context), p[r.IN_GRACE])
        assertEquals("floating", p[r.SEATING])
        assertEquals(15, p[r.HEARTBEAT_MINUTES])
        assertEquals(1234L, p[r.FETCHED_AT])
        assertEquals(0, p[r.FAIL_STREAK])
        assertEquals(1234L, p.getLong("fetched_at", 0L))
        assertEquals(25, p.getInt("max_sessions", 0))
        assertEquals(600, p.getInt("max_files_per_session", 0))
        assertEquals(120, p.getInt("max_frames", 0))
        assertTrue(p.getBoolean("dat_codec_encoding_enabled", false))
        assertTrue(p.getBoolean("cloud_backup_enabled", false))
        assertTrue(p.getBoolean("share_enabled", false))
        assertEquals("floating", p.getString("license_seating", null))
        assertEquals(15, p.getInt("lease_heartbeat_minutes", 0))
        AppRemoteConfig.clear(context)
    }

    @Test
    fun `indic_remote_config keys written here are read by AppRemoteConfig`() {
        val r = PrefFiles.RemoteConfig
        val p = prefs(r.NAME)
        AppRemoteConfig.recordFetchFailure(context)
        assertEquals(1, p[r.FAIL_STREAK])
        assertEquals(1, p.getInt("config_fail_streak", 0))

        // The pre-rename plan key is what mode() falls back to.
        p.edit(commit = true) {
            clear()
            put(r.LEGACY_PLAN, "professional")
        }
        assertEquals("licensed", AppRemoteConfig.mode(context))
        p.edit(commit = true) { put(r.MODE, "demo") }
        assertEquals("demo", AppRemoteConfig.mode(context))
        p.edit(commit = true) { put(r.IN_GRACE, true) }
        assertTrue(AppRemoteConfig.inGrace(context))
        AppRemoteConfig.clear(context)
    }

    @Test
    fun `indic_device holds the device id DeviceKeyManager reads`() {
        val d = PrefFiles.Device
        val p = prefs(d.NAME)
        assertEquals("indic_device", d.NAME)
        p.edit(commit = true) { put(d.DEVICE_ID, "dev-123") }
        assertEquals("dev-123", DeviceKeyManager.deviceId(context))
        assertEquals("dev-123", p.getString("device_id", null))
    }

    @Test
    fun `indic_cloud_listing keys are CloudBackupListing's own`() {
        val c = PrefFiles.CloudListing
        val p = prefs(c.NAME)
        assertEquals("indic_cloud_listing", c.NAME)
        CloudBackupListing.record(
            context,
            listOf(CloudSessionDto(sessionId = "c1", localSessionId = "l1", status = "COMPLETED")),
        )
        assertEquals(p.getString("backups", null), p[c.BACKUPS])
        assertTrue(p[c.BACKUPS].orEmpty().contains("c1"))
        CloudBackupListing.hide(context, listOf("c1"))
        assertEquals(setOf("c1"), p[c.HIDDEN])
        assertEquals(setOf("c1"), p.getStringSet("hidden", null))
        assertTrue(CloudBackupListing.offered(context).isEmpty())

        p.edit(commit = true) { put(c.HIDDEN, emptySet()) }
        assertEquals(listOf("c1"), CloudBackupListing.offered(context).map { it.cloudId })
    }

    @Test
    fun `indic_restore_outcomes holds RestoreFailureLedger's claims`() {
        val r = PrefFiles.RestoreOutcomes
        val p = prefs(r.NAME)
        assertEquals("indic_restore_outcomes", r.NAME)
        assertEquals("", p[r.ANNOUNCED])
        val id = UUID.randomUUID()
        assertTrue(RestoreFailureLedger.claim(context, id))
        assertEquals(id.toString(), p[r.ANNOUNCED])
        assertEquals(id.toString(), p.getString("announced", null))

        val other = UUID.randomUUID()
        p.edit(commit = true) { put(r.ANNOUNCED, other.toString()) }
        assertFalse(RestoreFailureLedger.claim(context, other))
    }

    @Test
    fun `the remaining files keep their names and keys`() {
        // Owners with no public reader for these: the raw value is the check.
        val sync = PrefFiles.CloudSync
        assertEquals("indic_cloudsync", sync.NAME)
        prefs(sync.NAME).edit(commit = true) { put(sync.LAST_RECONCILE_AT, 99L) }
        assertEquals(99L, prefs("indic_cloudsync").getLong("last_reconcile_at", 0L))

        val link = PrefFiles.EmailLink
        assertEquals("indic_emaillink", link.NAME)
        assertEquals("pending_email", link.PENDING_EMAIL.name)
        prefs(link.NAME).edit(commit = true) { put(link.PENDING_EMAIL, "a@b.c") }
        assertEquals("a@b.c", prefs("indic_emaillink").getString("pending_email", null))

        val deletes = PrefFiles.SessionDeletes
        assertEquals("session_deletes", deletes.NAME)
        prefs(deletes.NAME).edit(commit = true) { put(deletes.SHOWN_OUTCOMES, setOf("w1")) }
        assertEquals(setOf("w1"), prefs("session_deletes").getStringSet("shown_outcomes", null))
    }
}
