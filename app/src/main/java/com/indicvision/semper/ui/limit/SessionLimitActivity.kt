package com.indicvision.semper.ui.limit

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import com.indicvision.semper.data.account.DeviceKeyManager
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.common.SupportMail
import kotlinx.coroutines.launch

/**
 * Persistent gate shown when the account's cloud analysis quota is full. Unlike
 * a transient warning, this screen stays until the limit is resolved: it directs
 * the user to email support@sempermechanics.com to raise their limit, and lets them
 * re-check or go back to manage (delete) existing analyses.
 */
@MainThread
class SessionLimitActivity : AppCompatActivity() {

    private lateinit var tvBody: TextView
    private lateinit var tvQuota: TextView
    private lateinit var btnEmail: Button
    private lateinit var btnRecheck: Button
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_limit)
        Insets.padVertical(findViewById(R.id.limitRoot))

        tvBody = findViewById(R.id.tvLimitBody)
        tvQuota = findViewById(R.id.tvLimitQuota)
        btnEmail = findViewById(R.id.btnEmailSupport)
        btnRecheck = findViewById(R.id.btnRecheckLimit)
        progress = findViewById(R.id.progressLimit)

        tvBody.text = getString(R.string.limit_body, getString(R.string.support_email))
        renderQuota()

        btnEmail.setOnClickListener { emailSupport() }
        btnRecheck.setOnClickListener { recheck() }
        findViewById<TextView>(R.id.tvLimitBack).setOnClickListener { finish() }
    }

    private fun renderQuota() {
        val used = TokenStore.quotaUsed(this)
        val max = TokenStore.quotaMax(this)
        // Only show the counter when the backend actually reported numbers.
        tvQuota.visibility = if (max > 0) View.VISIBLE else View.GONE
        if (max > 0) tvQuota.text = resources.getQuantityString(R.plurals.limit_quota_fmt, used, used, max)
    }

    /** Opens the mail app pre-filled to support with account + device context. */
    private fun emailSupport() {
        val email = TokenStore.cachedEmail(this) ?: "(unknown account)"
        val deviceId = DeviceKeyManager.deviceId(this)
        val used = TokenStore.quotaUsed(this)
        val max = TokenStore.quotaMax(this)
        val body = buildString {
            append("I've reached my Semper analysis limit and would like it raised.\n\n")
            append("Account: ").append(email).append('\n')
            append("Quota: ").append(used).append('/').append(max).append('\n')
            append("Device ID: ").append(deviceId).append('\n')
            append(SupportMail.deviceLines())
        }
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
            when (val outcome = CloudSync.reconcile(this@SessionLimitActivity, deep = true)) {
                is CloudSync.Outcome.Ok -> {
                    val localCount = SessionStore.listAsync(this@SessionLimitActivity).size
                    // Ceiling is owned by AppRemoteConfig (refreshed by the same
                    // reconcile's config fetch); only the used count is stored here.
                    TokenStore.setQuota(
                        this@SessionLimitActivity,
                        outcome.quotaUsed,
                        localCount,
                    )
                }
                else -> Unit
            }
            setLoading(false)
            if (!TokenStore.isSessionLimitReached(this@SessionLimitActivity)) {
                Toast.makeText(this@SessionLimitActivity, R.string.limit_cleared, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                renderQuota()
                Toast.makeText(this@SessionLimitActivity, R.string.limit_still_full, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        btnRecheck.isEnabled = !loading
        btnEmail.isEnabled = !loading
    }
}
