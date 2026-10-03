package com.sempermechanics.semper.ui.home

import android.content.Intent
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.ui.limit.SessionLimitActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Home's account lines and its analysis-limit gate: the quota line
 * ([quotaView]), the license-expiry notice ([licenseView]), and the
 * persistent limit screen every quota check on Home opens.
 *
 * @param openSettings where a tap on the quota line goes while under the limit.
 */
internal class HomeQuotaCard(
    private val activity: AppCompatActivity,
    private val quotaView: TextView,
    private val licenseView: TextView,
    private val openSettings: () -> Unit,
) {

    /**
     * At the account's analysis limit, opens the persistent limit screen
     * (email support) and says so; Home's every quota gate goes through here.
     */
    fun openLimitScreenIfReached(): Boolean {
        val reached = TokenStore.isSessionLimitReached(activity)
        if (reached) activity.startActivity(Intent(activity, SessionLimitActivity::class.java))
        return reached
    }

    /** Both lines, for a phone list of [localSessionCount] analyses. */
    fun render(localSessionCount: Int) {
        updateQuotaIndicator(localSessionCount)
        updateLicenseNotice()
    }

    /**
     * Records the used count a cloud check returned, with the phone's own
     * count, so the new-analysis gate and the limit screen reflect the
     * latest server truth; newly at the cap, opens the limit screen.
     */
    suspend fun recordReconciled(quotaUsed: Int) {
        val wasLimited = TokenStore.isSessionLimitReached(activity)
        val localCount = withContext(Dispatchers.IO) { SessionStore.list(activity).size }
        // Ceiling is owned by AppRemoteConfig (refreshed by the same
        // reconcile's config fetch); only the used count is stored here.
        TokenStore.setQuota(activity, quotaUsed, localCount)
        if (!wasLimited) openLimitScreenIfReached()
    }

    private fun updateQuotaIndicator(localSessionCount: Int) {
        val max = TokenStore.quotaMax(activity)
        val used = TokenStore.quotaUsed(activity).coerceAtLeast(localSessionCount)
        if (max <= 0) {
            if (AppRemoteConfig.shouldHintSyncBlocked(activity) && SemperApi.get(activity).enabled) {
                quotaView.isVisible = true
                quotaView.text = activity.getString(R.string.home_sync_config_unavailable)
            } else {
                quotaView.isVisible = false
            }
            return
        }
        quotaView.isVisible = true
        quotaView.text = activity.resources.getQuantityString(R.plurals.home_quota_fmt, used, used, max)
        quotaView.setTextColor(
            activity.getColor(
                if (used >= max) R.color.semantic_danger else R.color.text_secondary,
            ),
        )
        quotaView.setOnClickListener {
            if (!openLimitScreenIfReached()) openSettings()
        }
    }

    /**
     * Warn that a timed license is running out, or has run out and is inside
     * its grace window.
     *
     * Its own view rather than [quotaView]: a licensed account always has a
     * known quota, so it never reaches that view's unknown-quota hint branch.
     *
     * Advisory only. Entitlement is decided by the backend and arrives as
     * `mode`; this notice is suppressed entirely when the cached config is too
     * old to trust, so a renewal that landed while the device was offline
     * cannot show up here as a false alarm.
     */
    private fun updateLicenseNotice() {
        val days = LicenseEntitlements.expiryNoticeDays(activity)
        if (days == null) {
            licenseView.isVisible = false
            return
        }
        val support = activity.getString(R.string.support_email)
        licenseView.isVisible = true
        licenseView.text = when {
            LicenseEntitlements.isInGrace(activity) -> activity.getString(R.string.license_grace_fmt, support)
            // Past its day on a config fetched before it ended: the cache
            // cannot say whether grace applies, only that the day has gone.
            days < 0L -> activity.getString(R.string.license_expired_fmt, support)
            days == 0L -> activity.getString(R.string.license_expiring_today_fmt, support)
            else -> activity.resources.getQuantityString(
                R.plurals.license_expiring_fmt,
                days.toInt(),
                days.toInt(),
            )
        }
        licenseView.setTextColor(
            activity.getColor(
                if (LicenseEntitlements.isInGrace(activity)) {
                    R.color.semantic_danger
                } else {
                    R.color.text_secondary
                },
            ),
        )
    }
}
