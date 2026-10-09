package com.sempermechanics.semper.ui.limit

import android.os.Bundle
import android.view.View
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.DeviceKeys
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.session.SessionQuota
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.databinding.ActivitySessionLimitBinding
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.auth.SupportMail
import com.sempermechanics.semper.ui.common.auth.contextLines
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.setBusy
import kotlinx.coroutines.launch

/**
 * Persistent gate shown when the account's cloud analysis quota is full. Unlike
 * a transient warning, this screen stays until the limit is resolved: it directs
 * the user to email support@sempermechanics.com to raise their limit, and lets them
 * re-check or go back to manage (delete) existing analyses.
 */
@MainThread
class SessionLimitActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionLimitBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionLimitBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.padVertical(binding.limitRoot)

        binding.tvLimitBody.text = getString(R.string.limit_body_fmt, getString(R.string.support_email))
        renderQuota()

        binding.btnEmailSupport.setOnClickListener { emailSupport() }
        binding.btnRecheckLimit.setOnClickListener { recheck() }
        binding.tvLimitBack.setOnClickListener { finish() }
    }

    private fun renderQuota() {
        val used = AccountCache.quotaUsed(this)
        val max = AccountCache.quotaMax(this)
        // Only show the counter when the backend actually reported numbers.
        binding.tvLimitQuota.isVisible = max > 0
        if (max > 0) {
            binding.tvLimitQuota.text = resources.getQuantityString(R.plurals.limit_quota_fmt, used, used, max)
        }
    }

    /** Opens the mail app pre-filled to support with account + device context. */
    private fun emailSupport() {
        val email = AccountCache.cachedEmail(this) ?: getString(R.string.pending_unknown_account)
        val quota = "Quota: ${AccountCache.quotaUsed(this)}/${AccountCache.quotaMax(this)}"
        val context = SupportMail.contextLines(email, DeviceKeys.deviceId(this), extra = listOf(quota))
        val body = "I've reached my Semper analysis limit and would like it raised.\n\n$context"
        SupportMail.open(
            this,
            subject = getString(R.string.limit_subject) + " — " + email,
            body = body,
            purpose = "the limit request",
        )
    }

    /** Re-query the backend; if the account is under the limit again, dismiss. */
    private fun recheck() {
        setLoading(true)
        lifecycleScope.launch {
            // deep=true: the user explicitly tapped Recheck, so bypass the
            // reconcile throttle — a silently skipped check would report
            // "still full" from stale data.
            val outcome = CloudSync.reconcile(this@SessionLimitActivity, deep = true)
            // The rows after the reconcile, which may have repaired some.
            val localCount = SessionStore.listOnIo(this@SessionLimitActivity).size
            when (outcome) {
                is CloudSync.Outcome.Ok -> {
                    // Ceiling is owned by AppRemoteConfig (refreshed by the same
                    // reconcile's config fetch); only the used count is stored here.
                    AccountCache.setQuota(
                        this@SessionLimitActivity,
                        outcome.quotaUsed,
                        localCount,
                    )
                }
                else -> Unit
            }
            setLoading(false)
            if (!SessionQuota.blocked(this@SessionLimitActivity, localCount)) {
                Feedback.toast(this@SessionLimitActivity, R.string.limit_cleared)
                finish()
            } else {
                renderQuota()
                Feedback.toast(this@SessionLimitActivity, R.string.limit_still_full, long = true)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressLimit.setBusy(
            loading,
            binding.btnRecheckLimit,
            binding.btnEmailSupport,
            idleVisibility = View.INVISIBLE,
        )
    }
}
