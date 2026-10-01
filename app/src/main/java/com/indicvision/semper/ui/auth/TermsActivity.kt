package com.indicvision.semper.ui.auth

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.indicvision.semper.R
import com.indicvision.semper.data.account.AuthRepository
import com.indicvision.semper.data.account.LegalTerms
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.ui.common.AuthRoute
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.common.SignOutRun
import com.indicvision.semper.ui.home.HomeActivity
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The clickwrap gate. Shown once per Terms version, after sign-in and before
 * Pending/Home, for every sign-in method (see [AccessRouter.intentFor]).
 *
 * Two separate choices:
 *  - agreeing to the Terms and Privacy Policy — required, starts unticked;
 *  - allowing synced data to be used to improve Semper — optional, starts
 *    ticked (owner's decision), never bundled into the first box, and
 *    changeable later in Settings. Only the required box unlocks the button.
 *
 * Declining (button or back) signs the user out: the account cannot be used
 * under terms that were not accepted.
 */
@MainThread
class TermsActivity : AppCompatActivity() {

    private val authRepo by lazy { AuthRepository(applicationContext) }

    /** Seam for tests: declining must sign out, and the JVM has no Firebase to sign out of. */
    @VisibleForTesting
    internal var signOut: suspend () -> Unit = { authRepo.signOut() }

    private lateinit var cbAgree: CheckBox
    private lateinit var cbImprove: CheckBox
    private lateinit var btnAgree: MaterialButton
    private lateinit var btnDecline: MaterialButton
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terms)
        Insets.padVertical(findViewById(R.id.termsRoot))

        cbAgree = findViewById(R.id.cbAgreeTerms)
        cbImprove = findViewById(R.id.cbImprovementConsent)
        btnAgree = findViewById(R.id.btnAgree)
        btnDecline = findViewById(R.id.btnDecline)
        progress = findViewById(R.id.progressTerms)

        findViewById<TextView>(R.id.tvTermsVersion).text =
            getString(R.string.terms_version_fmt, LegalTerms.requiredVersion(this))
        findViewById<View>(R.id.tvOpenTerms).setOnClickListener {
            openExternalUrl(getString(R.string.legal_terms_url))
        }
        findViewById<View>(R.id.tvOpenPrivacy).setOnClickListener {
            openExternalUrl(getString(R.string.legal_privacy_url))
        }

        // The affirmative act: the button only becomes usable once the required
        // box is ticked by the user — never pre-ticked, never implied by "Continue".
        btnAgree.isEnabled = false
        cbAgree.setOnCheckedChangeListener { _, checked -> btnAgree.isEnabled = checked }
        btnAgree.setOnClickListener { onAgree() }
        btnDecline.setOnClickListener { onDecline() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = onDecline()
            },
        )
        SignOutRun.observe(this, onRunning = { setLoading(true) }) { AuthRoute.toSignIn(this) }
    }

    private fun onAgree() {
        if (!cbAgree.isChecked) return
        setLoading(true)
        lifecycleScope.launch {
            val result = authRepo.acceptTerms(
                version = LegalTerms.requiredVersion(this@TermsActivity),
                improvementConsent = cbImprove.isChecked,
            )
            setLoading(false)
            result.fold(
                onSuccess = { continueToDestination() },
                onFailure = { error ->
                    val message = if (error is IndicApi.TermsVersionMismatchException) {
                        getString(R.string.terms_error_update_app)
                    } else {
                        error.message ?: getString(R.string.terms_error_generic)
                    }
                    Toast.makeText(this@TermsActivity, message, Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    /**
     * Sign-out releases a floating seat over the network first, so it runs in
     * [SignOutRun], where a rotation cannot cut it short; this screen (or the
     * one recreated in its place) routes to sign-in when it is done, whether
     * or not the release succeeded.
     */
    private fun onDecline() {
        SignOutRun.start(TermsActivity::class.java, signOut)
    }

    private fun continueToDestination() {
        val next = intent.getStringExtra(EXTRA_NEXT)
            ?.let { runCatching { Class.forName(it) }.getOrNull() }
            ?: HomeActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        btnAgree.isEnabled = !loading && cbAgree.isChecked
        btnDecline.isEnabled = !loading
        cbAgree.isEnabled = !loading
        cbImprove.isEnabled = !loading
    }

    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No browser to open %s", url)
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val EXTRA_NEXT = "com.indicvision.semper.terms.NEXT"

        /** Start the gate, continuing to [next] once the Terms are accepted. */
        fun intent(context: Context, next: Class<out Activity>): Intent =
            Intent(context, TermsActivity::class.java).putExtra(EXTRA_NEXT, next.name)
    }
}
