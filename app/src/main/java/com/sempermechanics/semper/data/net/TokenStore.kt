// Token/claims store: one small accessor per stored field, so TooManyFunctions
// is suppressed for this whole file.
@file:Suppress("TooManyFunctions")

package com.sempermechanics.semper.data.net

import android.content.Context
import androidx.core.content.edit
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.prefs.PrefFiles.Onboarding
import com.sempermechanics.semper.data.prefs.PrefFiles.Session
import com.sempermechanics.semper.data.prefs.PrefKey
import com.sempermechanics.semper.data.prefs.get
import com.sempermechanics.semper.data.prefs.privatePrefs
import com.sempermechanics.semper.data.prefs.put

/**
 * Local session cache alongside Firebase Auth: the signed-in identity plus the
 * backend-confirmed role/status/quota.
 *
 * Firebase owns the actual credential (the ID token, auto-refreshed) — we no
 * longer store any token here. This holds only the cached identity and the
 * app-layer state the backend tells us (approval status, admin role, quota).
 */
object TokenStore {

    private fun prefs(context: Context) = privatePrefs(context, Session.NAME)

    /** Survives sign-out so a per-account beta ack is not re-prompted on every login. */
    private fun onboardingPrefs(context: Context) = privatePrefs(context, Onboarding.NAME)

    /** Cache the signed-in identity (from the Firebase user) for offline UI. */
    fun saveIdentity(context: Context, uid: String?, email: String?) {
        prefs(context).edit {
            put(Session.UID, uid)
            put(Session.EMAIL, email)
        }
    }

    fun cachedUid(context: Context): String? = prefs(context)[Session.UID]
    fun cachedEmail(context: Context): String? = prefs(context)[Session.EMAIL]

    fun cachedStatus(context: Context): String? = prefs(context)[Session.STATUS]
    fun setStatus(context: Context, status: String) = prefs(context).edit { put(Session.STATUS, status) }

    fun cachedRole(context: Context): String? = prefs(context)[Session.ROLE]
    fun setRole(context: Context, role: String?) = prefs(context).edit { put(Session.ROLE, role) }

    fun isAdmin(context: Context): Boolean = cachedRole(context) == "admin"

    fun isDeviceRegistered(context: Context): Boolean = prefs(context)[Session.DEVICE_REGISTERED]
    fun setDeviceRegistered(context: Context, v: Boolean) {
        prefs(context).edit { put(Session.DEVICE_REGISTERED, v) }
    }

    // ── Cloud analysis quota (max sessions per account) ──────────────────
    // The ceiling is owned by [AppRemoteConfig] (from /v1/config); TokenStore
    // holds only the runtime USED count and a forced-stop flag and reads the
    // ceiling from there. One dependency direction (TokenStore → AppRemoteConfig),
    // no cycle. The hard stop is computed live, so a changed ceiling takes effect
    // without any write-back from AppRemoteConfig.

    /**
     * Analyses counted against the cap: the server's count or the phone's,
     * whichever is higher. Kept as two numbers so a delete on the phone can
     * lower its half; one stored max never came down until a reconcile, so
     * offline or with cloud off "25 / 25" and the hard stop outlived the delete.
     */
    fun quotaUsed(context: Context): Int = prefs(context).let {
        maxOf(it[Session.QUOTA_USED], it[Session.LOCAL_COUNT])
    }

    /** Session ceiling, owned by [AppRemoteConfig]; 0 until the backend reports it. */
    fun quotaMax(context: Context): Int = AppRemoteConfig.maxSessions(context)

    /** True when the backend has reported a positive quota ceiling. */
    fun isQuotaKnown(context: Context): Boolean = quotaMax(context) > 0

    /**
     * Refresh the cached USED count. [localCount] is folded in so the client
     * blocks new analyses even before the next cloud reconcile. Fresh numbers
     * clear any forced stop; the hard stop itself is recomputed live in
     * [isSessionLimitReached] from used vs the [AppRemoteConfig] ceiling.
     */
    fun setQuota(context: Context, used: Int, localCount: Int = 0) {
        prefs(context).edit {
            put(Session.QUOTA_USED, used)
            put(Session.LOCAL_COUNT, localCount)
            put(Session.LIMIT_FORCED, false)
        }
    }

    /**
     * Refresh the phone's half of the USED count; the server's stays as last
     * reported. A stop forced by [setSessionLimitReached] stays too: saving a
     * session, opening Home or starting a run brings no fresh server numbers,
     * and clearing it here let the stop vanish before the user next tried.
     * Only [setQuota] and [onLocalSessionsRemoved] lift it.
     */
    fun refreshSessionLimit(context: Context, localCount: Int) {
        prefs(context).edit { put(Session.LOCAL_COUNT, localCount) }
    }

    /**
     * Sessions were deleted from the phone, leaving [localCount]: refresh the
     * count and lift a forced stop, so the user is not held at a limit they
     * just made room under. The next upload re-forces it if the server still
     * says no.
     */
    fun onLocalSessionsRemoved(context: Context, localCount: Int) {
        prefs(context).edit {
            put(Session.LOCAL_COUNT, localCount)
            put(Session.LIMIT_FORCED, false)
        }
    }

    /** Force the hard stop (e.g. an upload rejected 409 without fresh numbers). */
    fun setSessionLimitReached(context: Context, v: Boolean) {
        prefs(context).edit { put(Session.LIMIT_FORCED, v) }
    }

    /**
     * True when the account may not create another analysis (hard stop).
     * Both modes stop at the backend's ceiling once [AppRemoteConfig] has it.
     * Before that, demo uses the 25-run ceiling and licensed has none.
     */
    fun isSessionLimitReached(context: Context): Boolean = when {
        LicenseEntitlements.unlimitedAnalysis(context) -> false
        prefs(context)[Session.LIMIT_FORCED] -> true
        else -> quotaUsed(context) >= LicenseEntitlements.analysisCap(context)
    }

    /**
     * Whether the current account has acknowledged the beta / data-use notice.
     * Stored outside the session prefs so it survives [clear].
     *
     * Each account acks once. With no account signed in (a debug build with no
     * API URL, or the dev-auth bypass) the ack is the phone's, under a fixed key,
     * and does not stand in for an account's: the first sign-in still asks.
     */
    fun hasAckedBetaNotice(context: Context): Boolean =
        onboardingPrefs(context)[betaAckKey(context)]

    fun setBetaNoticeAcked(context: Context) {
        onboardingPrefs(context).edit { put(betaAckKey(context), true) }
    }

    private fun betaAckKey(context: Context): PrefKey<Boolean> = Onboarding.betaAcked(cachedUid(context))

    // ------------------------------------------------------------ legal / consent

    /** The Terms version the backend last said it requires; null until /v1/me has answered. */
    fun termsRequiredVersion(context: Context): String? = prefs(context)[Session.TERMS_REQUIRED]
    fun setTermsRequiredVersion(context: Context, version: String) {
        prefs(context).edit { put(Session.TERMS_REQUIRED, version) }
    }

    /** The Terms version this user agreed to on this device; null until the gate was passed. */
    fun termsAcceptedVersion(context: Context): String? = prefs(context)[Session.TERMS_ACCEPTED]

    /**
     * Record acceptance locally. [synced] is false until the backend confirmed it,
     * so an acceptance made offline is re-sent on the next status refresh.
     */
    fun setTermsAccepted(context: Context, version: String, synced: Boolean) {
        prefs(context).edit {
            put(Session.TERMS_ACCEPTED, version)
            put(Session.TERMS_SYNCED, synced)
        }
    }

    fun isTermsAcceptanceSynced(context: Context): Boolean = prefs(context)[Session.TERMS_SYNCED]

    /** null = never answered. Never defaults to true: consent is only ever an explicit choice. */
    fun improvementConsent(context: Context): Boolean? =
        prefs(context)[Session.IMPROVEMENT_CONSENT]?.toBooleanStrictOrNull()

    fun setImprovementConsent(context: Context, granted: Boolean) {
        prefs(context).edit { put(Session.IMPROVEMENT_CONSENT, granted.toString()) }
    }

    /** Wipe the local session cache (sign-out). Keystore device key is left intact. */
    fun clear(context: Context) {
        prefs(context).edit { clear() }
        AppRemoteConfig.clear(context)
    }
}
