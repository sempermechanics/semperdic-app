package com.sempermechanics.semper.data.session

import android.content.Context
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenStore
import timber.log.Timber

/**
 * Analysis-quota gate for new local sessions. Every account stops at
 * [LicenseEntitlements.analysisCap]: the backend's ceiling once known, else
 * [LicenseEntitlements.DEMO_MAX_ANALYSES] for demo and none for licensed.
 * [SessionStore.upsert] stays CRUD-only and consults this gate when inserting
 * a new id (unless [allowOverLimit] on the caller).
 */
object SessionQuotaGate {

    /**
     * Whether a *new* session may be persisted.
     *
     * A quota that is *known and full* is a hard stop; an *unknown* quota
     * (max ≤ 0) is not — the analysis is already computed and must be saved
     * locally (upload is separately gated in CloudSync until config arrives).
     * Offline / cloud-disabled accounts always pass.
     *
     * @param existingCount current index size (used as a floor on "used").
     * @return false if the insert must be refused.
     */
    fun allowNewSession(context: Context, existingCount: Int): Boolean {
        if (!SemperApi.get(context).enabled || LicenseEntitlements.hasUnlimitedAnalysis(context)) return true
        val max = LicenseEntitlements.analysisCap(context)
        val used = maxOf(TokenStore.quotaUsed(context), existingCount)
        val full = used >= max
        if (full) {
            TokenStore.refreshSessionLimit(context, existingCount)
            Timber.w("Hard stop: refusing new session (at %d/%d)", used, max)
        }
        return !full
    }
}
