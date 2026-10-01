package com.indicvision.semper.data.net

import android.content.Context
import androidx.core.content.edit
import com.indicvision.semper.data.account.LicenseEntitlements
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Cached product limits from [GET /v1/config](IndicApi.getConfig).
 *
 * Source of truth is the backend (per-user Firestore fields → fleet defaults).
 * The app never invents a session quota locally — until a successful fetch,
 * [isKnown] is false and cloud-backed gates fail closed.
 *
 * This is the **sole owner of the limit ceilings**. [TokenStore] reads the
 * session ceiling from here ([maxSessions]) rather than caching its own copy, so
 * the dependency runs one way (TokenStore → AppRemoteConfig) with no cycle. This
 * object never calls back into TokenStore.
 */
@Suppress("TooManyFunctions")
object AppRemoteConfig {

    private const val PREFS = "indic_remote_config"
    private const val K_MAX_SESSIONS = "max_sessions"
    private const val K_MAX_FILES = "max_files_per_session"
    private const val K_MAX_FRAMES = "max_frames"
    private const val K_DAT_CODEC_ENCODING = "dat_codec_encoding_enabled"
    private const val K_MODE = "mode"
    private const val K_CLOUD_BACKUP = "cloud_backup_enabled"
    private const val K_SHARE = "share_enabled"
    private const val K_LICENSE_PREFIX = "license_prefix"
    private const val K_LICENSE_KIND = "license_kind"
    private const val K_FAIL_STREAK = "config_fail_streak"
    private const val FAIL_STREAK_HINT = 3
    private const val K_LICENSE_DURATION = "license_duration"
    private const val K_LICENSE_EXPIRES_AT = "license_expires_at"
    private const val K_IN_GRACE = "license_in_grace"
    private const val K_SEATING = "license_seating"
    private const val K_HEARTBEAT_MINUTES = "lease_heartbeat_minutes"

    /**
     * When [apply] last stored a response. Without it the cache has no age:
     * "the license expired" and "we have not asked in three weeks" look
     * identical, and only the first of those should ever produce a warning.
     */
    private const val K_FETCHED_AT = "fetched_at"

    /** Pre-rename value of [LicenseEntitlements.MODE_LICENSED], still sent as the `plan` mirror. */
    private const val LEGACY_PLAN_PROFESSIONAL = "professional"

    /** Pref key a build predating the rename wrote; read once on upgrade. */
    private const val K_LEGACY_PLAN = "plan"

    /** Pre-rename value of `"institution"`, still sent by an older backend. */
    private const val LEGACY_KIND_CAMPUS = "campus"

    const val DURATION_PERPETUAL = "perpetual"
    const val DURATION_TIMED = "timed"

    /** Every member of the roster is entitled outright. */
    const val SEATING_ASSIGNED = "assigned"

    /** Only so many members are entitled at once; a seat must be taken. */
    const val SEATING_FLOATING = "floating"

    /**
     * Renew a seat at least this often when the backend has not said. Only a
     * fallback: a backend that knows about seats always sends its own value.
     */
    const val DEFAULT_HEARTBEAT_MINUTES = 30

    /** No expiry on file — a perpetual license, or nothing fetched yet. */
    const val NO_INSTANT = 0L

    /** ISO-8601 instant with an explicit offset, sub-second digits removed. */
    private const val ISO_INSTANT = "yyyy-MM-dd'T'HH:mm:ssXXX"

    /** The fractional-seconds group; nothing else in an instant matches. */
    private val SUBSECOND = Regex("""\.\d+""")

    private fun normalizeKind(raw: String): String =
        if (raw == LEGACY_KIND_CAMPUS) "institution" else raw

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Persist a successful config response. The session-limit hard stop is
     * recomputed live by [TokenStore.isSessionLimitReached] from the used count
     * against [maxSessions], so storing the ceiling here is all that is needed —
     * no write back into TokenStore, no [localSessionCount] to fold in.
     */
    fun apply(context: Context, config: AppConfigDto, now: Long = System.currentTimeMillis()) {
        prefs(context).edit {
            putInt(K_MAX_SESSIONS, config.maxSessions.coerceAtLeast(0))
            putInt(K_MAX_FILES, config.maxFilesPerSession.coerceAtLeast(0))
            putInt(K_MAX_FRAMES, config.maxFrames.coerceAtLeast(0))
            putBoolean(K_DAT_CODEC_ENCODING, config.datCodecEncodingEnabled)
            putString(K_MODE, resolveMode(config))
            putBoolean(K_CLOUD_BACKUP, config.cloudBackupEnabled)
            putBoolean(K_SHARE, config.shareEnabled)
            putString(K_LICENSE_PREFIX, config.licensePrefix)
            putString(K_LICENSE_KIND, normalizeKind(config.licenseKind))
            putString(K_LICENSE_DURATION, resolveDuration(config))
            putLong(K_LICENSE_EXPIRES_AT, parseInstant(config.licenseExpiresAt))
            putBoolean(K_IN_GRACE, config.inGrace)
            putString(K_SEATING, resolveSeating(config))
            putInt(K_HEARTBEAT_MINUTES, config.leaseHeartbeatMinutes.coerceAtLeast(0))
            putLong(K_FETCHED_AT, now)
            putInt(K_FAIL_STREAK, 0)
        }
    }

    /**
     * Epoch millis for an ISO-8601 instant, or [NO_INSTANT] when absent or
     * unparseable. Unparseable is treated as absent rather than as an expiry
     * at the epoch, which would read as expired forever.
     *
     * `SimpleDateFormat`, not `java.time`: minSdk is 24 and core library
     * desugaring is off, so `Instant.parse` is an API 26 call the lint gate
     * rejects. The rest of the app parses timestamps this way too.
     *
     * `XXX` accepts both spellings of a zero offset — the backend serialises
     * an aware datetime as `+00:00`, while `Instant.toString()` (what the
     * tests build) writes `Z`. Sub-second digits are dropped first: the
     * backend emits microseconds, and `SSS` would read all six as
     * milliseconds and land the expiry minutes late.
     */
    private fun parseInstant(raw: String?): Long {
        if (raw.isNullOrBlank()) return NO_INSTANT
        val text = SUBSECOND.replace(raw.trim(), "")
        val format = SimpleDateFormat(ISO_INSTANT, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        return runCatching { format.parse(text)?.time ?: NO_INSTANT }.getOrDefault(NO_INSTANT)
    }

    /**
     * Assigned unless the backend explicitly says floating.
     *
     * A deploy predating floating seats sends nothing here, and every license
     * that existed then entitled its members outright — so assigned is what
     * those responses mean, not a guess. Failing the other way would lock
     * every institution user out of starting work against an older backend.
     */
    private fun resolveSeating(config: AppConfigDto): String =
        if (config.licenseSeating == SEATING_FLOATING) SEATING_FLOATING else SEATING_ASSIGNED

    /** `timed` only when the backend said so, or when an expiry actually arrived. */
    private fun resolveDuration(config: AppConfigDto): String = when {
        config.licenseDuration == DURATION_TIMED -> DURATION_TIMED
        config.licenseDuration == DURATION_PERPETUAL -> DURATION_PERPETUAL
        !config.licenseExpiresAt.isNullOrBlank() -> DURATION_TIMED
        else -> DURATION_PERPETUAL
    }

    /** Record a failed /v1/config fetch (uploads stay gated until config lands). */
    fun recordFetchFailure(context: Context) {
        val prefs = prefs(context)
        prefs.edit { putInt(K_FAIL_STREAK, prefs.getInt(K_FAIL_STREAK, 0) + 1) }
    }

    /**
     * True when several consecutive config fetches failed while the quota is
     * still unknown — surface a "can't sync yet" hint so this is not silent.
     */
    fun shouldHintSyncBlocked(context: Context): Boolean =
        !isKnown(context) && prefs(context).getInt(K_FAIL_STREAK, 0) >= FAIL_STREAK_HINT

    /** True once the backend has reported a positive maxSessions. */
    fun isKnown(context: Context): Boolean = maxSessions(context) > 0

    fun maxSessions(context: Context): Int = prefs(context).getInt(K_MAX_SESSIONS, 0)

    fun maxFilesPerSession(context: Context): Int = prefs(context).getInt(K_MAX_FILES, 0)

    /** Deformed-frame ceiling from cloud; 0 until config is known. */
    fun maxFrames(context: Context): Int = prefs(context).getInt(K_MAX_FRAMES, 0)

    /**
     * Whether this account may upload `.dat` entries through [DatCodec][com.indicvision.semper.data.session.DatCodec].
     * Fails closed like everything else here — `false` (today's raw behaviour)
     * until a successful [apply] says otherwise, so a device that has never
     * synced config, or whose last fetch failed, never guesses "on".
     */
    fun datCodecEncodingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(K_DAT_CODEC_ENCODING, false)

    /**
     * Collapse the dual-keyed config response to one mode.
     *
     * The backend sends `mode` (`demo`/`licensed`) and, for builds that
     * predate the rename, a `plan` mirror (`demo`/`professional`). Prefer
     * `mode`; fall back to `plan` so this build still works against a deploy
     * that has not shipped the rename. Anything unrecognised is demo — an
     * entitlement is never inferred from a value we do not understand.
     */
    private fun resolveMode(config: AppConfigDto): String = when {
        config.mode == LicenseEntitlements.MODE_LICENSED -> LicenseEntitlements.MODE_LICENSED
        config.mode == LicenseEntitlements.MODE_DEMO -> LicenseEntitlements.MODE_DEMO
        config.plan == LEGACY_PLAN_PROFESSIONAL -> LicenseEntitlements.MODE_LICENSED
        else -> LicenseEntitlements.MODE_DEMO
    }

    /**
     * The cached mode, or demo when nothing has been cached yet.
     *
     * Falls back to [K_LEGACY_PLAN], the pref key a build predating the rename
     * wrote. Without that fallback, upgrading the app would read demo for a
     * licensed account from launch until the next successful /v1/config fetch
     * — which, offline, may not come for a long time. The cached value is not
     * rewritten here: the next [apply] does that, and a read path that writes
     * would touch prefs on every gate check.
     */
    fun mode(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(K_MODE, null)?.let { return it }
        val legacy = prefs.getString(K_LEGACY_PLAN, null)
        return if (legacy == LEGACY_PLAN_PROFESSIONAL) {
            LicenseEntitlements.MODE_LICENSED
        } else {
            LicenseEntitlements.MODE_DEMO
        }
    }

    fun cloudBackupEnabled(context: Context): Boolean =
        prefs(context).getBoolean(K_CLOUD_BACKUP, false)

    fun shareEnabled(context: Context): Boolean = prefs(context).getBoolean(K_SHARE, false)

    fun licensePrefix(context: Context): String =
        prefs(context).getString(K_LICENSE_PREFIX, "") ?: ""

    /** `""`, `"individual"`, or `"institution"` — display/support metadata only. */
    fun licenseKind(context: Context): String =
        prefs(context).getString(K_LICENSE_KIND, "") ?: ""

    /** Epoch millis of the last successful fetch, or 0 if there has never been one. */
    fun fetchedAtMillis(context: Context): Long = prefs(context).getLong(K_FETCHED_AT, 0L)

    /**
     * Whether the cache is older than [maxAgeMillis].
     *
     * `now` is a parameter with a production default, matching
     * `CacheJanitor.isReclaimable` and `SessionRepository.defaultSessionName`
     * — the app has no clock abstraction and this is not the place to invent
     * one. A cache that has never been written is stale.
     *
     * The `in 0 until` guard is `CloudSync`'s: a stored time in the future
     * (NTP correction, the user changing the date) reads as stale rather than
     * fresh forever.
     */
    fun isStale(
        context: Context,
        maxAgeMillis: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val fetchedAt = fetchedAtMillis(context)
        if (fetchedAt <= 0L) return true
        return (now - fetchedAt) !in 0 until maxAgeMillis
    }

    /** `perpetual` or `timed`; perpetual until a response says otherwise. */
    fun licenseDuration(context: Context): String =
        prefs(context).getString(K_LICENSE_DURATION, DURATION_PERPETUAL) ?: DURATION_PERPETUAL

    /** Epoch millis the license expires, or [NO_INSTANT] when perpetual. */
    fun licenseExpiresAtMillis(context: Context): Long =
        prefs(context).getLong(K_LICENSE_EXPIRES_AT, NO_INSTANT)

    /** Past expiry but still fully entitled — warn, do not gate. */
    fun inGrace(context: Context): Boolean = prefs(context).getBoolean(K_IN_GRACE, false)

    /** `assigned` or `floating`; assigned until a response says otherwise. */
    fun licenseSeating(context: Context): String =
        prefs(context).getString(K_SEATING, SEATING_ASSIGNED) ?: SEATING_ASSIGNED

    /** How often to renew a held seat, in minutes. */
    fun leaseHeartbeatMinutes(context: Context): Int {
        val stored = prefs(context).getInt(K_HEARTBEAT_MINUTES, 0)
        return if (stored > 0) stored else DEFAULT_HEARTBEAT_MINUTES
    }

    /** Drop cached limits (sign-out). */
    fun clear(context: Context) = prefs(context).edit { clear() }
}
