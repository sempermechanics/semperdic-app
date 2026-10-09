package com.sempermechanics.semper.data.session

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.SemperApi

/**
 * The one rule for whether the account may add another analysis (TD-167).
 * Every check asks [blocked]: Home's new-analysis gate and quota line, the
 * limit screen's re-check, the wizard's start check, the run's own start
 * check, and [SessionStore.save] for a new row that no admitted run or
 * restore vouches for.
 *
 * In order:
 * - no backend ([CloudApi.enabled] false: a build with no API URL, or the
 *   dev-auth bypass) → no cap;
 * - licensed with no config yet ([LicenseEntitlements.hasUnlimitedAnalysis]) → no cap;
 * - the server has said full (an upload refused with 409, the forced stop
 *   [AccountCache.setSessionLimitReached] sets) → blocked;
 * - otherwise blocked when the larger of the server's last count and the
 *   phone's rows reaches [LicenseEntitlements.analysisCap].
 *
 * The rule is for *starting* an analysis. A run it admitted saves its
 * session even if the cap fills while it runs: the run passes
 * `allowOverLimit` to [SessionStore.save], as a cloud restore does.
 */
object SessionQuota {

    /**
     * How the rule reaches the backend client. A JVM test build has no API URL,
     * so the real client reads as disabled; tests swap in `FakeCloudApi` and put
     * the original back.
     */
    @VisibleForTesting
    internal var api: (Context) -> CloudApi = { SemperApi.get(it) }

    /**
     * True when the account may not add another analysis, with [liveRows]
     * analyses on the phone (the session index's row count).
     */
    fun blocked(context: Context, liveRows: Int): Boolean = when {
        !api(context).enabled -> false
        LicenseEntitlements.hasUnlimitedAnalysis(context) -> false
        AccountCache.isLimitForced(context) -> true
        else -> maxOf(AccountCache.serverQuotaUsed(context), liveRows) >= LicenseEntitlements.analysisCap(context)
    }

    /**
     * [blocked] for a main-thread caller that cannot read the index: the
     * phone's rows are the count [SessionStore] stored with its last write.
     * Every write of the index stores the row count it wrote (save, update,
     * delete, heal, wipe), so it is the live count, except right after a
     * sign-out wiped the cache and before the next write or Home's start
     * stores it again.
     */
    fun blockedNow(context: Context): Boolean = blocked(context, AccountCache.localCount(context))
}
