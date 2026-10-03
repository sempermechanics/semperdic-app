package com.sempermechanics.semper.ui.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.account.LegalTerms
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.databinding.ActivityTermsBinding
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.auth.AuthRoute
import com.sempermechanics.semper.ui.common.auth.ExternalLinks
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.setBusy
import com.sempermechanics.semper.ui.home.HomeActivity
import kotlinx.coroutines.launch

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

    private lateinit var binding: ActivityTermsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTermsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.padVertical(binding.termsRoot)

        binding.tvTermsVersion.text = getString(R.string.terms_version_fmt, LegalTerms.requiredVersion(this))
        binding.tvOpenTerms.setOnClickListener {
            ExternalLinks.open(this, getString(R.string.legal_terms_url))
        }
        binding.tvOpenPrivacy.setOnClickListener {
            ExternalLinks.open(this, getString(R.string.legal_privacy_url))
        }

        // The affirmative act: the button only becomes usable once the required
        // box is ticked by the user — never pre-ticked, never implied by "Continue".
        binding.btnAgree.isEnabled = false
        binding.cbAgreeTerms.setOnCheckedChangeListener { _, checked -> binding.btnAgree.isEnabled = checked }
        binding.btnAgree.setOnClickListener { onAgree() }
        binding.btnDecline.setOnClickListener { onDecline() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = onDecline()
            },
        )
        SignOutRun.observe(this, onRunning = { setLoading(true) }) { AuthRoute.toSignIn(this) }
    }

    private fun onAgree() {
        if (!binding.cbAgreeTerms.isChecked) return
        setLoading(true)
        lifecycleScope.launch {
            val result = authRepo.acceptTerms(
                version = LegalTerms.requiredVersion(this@TermsActivity),
                improvementConsent = binding.cbImprovementConsent.isChecked,
            )
            setLoading(false)
            result.fold(
                onSuccess = { continueToDestination() },
                onFailure = { error ->
                    val message = if (error is SemperApi.TermsVersionMismatchException) {
                        getString(R.string.terms_error_update_app)
                    } else {
                        error.message ?: getString(R.string.terms_error_generic)
                    }
                    Feedback.toast(this@TermsActivity, message, long = true)
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

    /** Agree follows the required box once the spinner stops. */
    private fun setLoading(loading: Boolean) {
        binding.progressTerms.setBusy(
            loading,
            binding.btnDecline,
            binding.cbAgreeTerms,
            binding.cbImprovementConsent,
            idleVisibility = View.INVISIBLE,
        )
        binding.btnAgree.isEnabled = !loading && binding.cbAgreeTerms.isChecked
    }

    companion object {
        private const val EXTRA_NEXT = "com.sempermechanics.semper.terms.NEXT"

        /** Start the gate, continuing to [next] once the Terms are accepted. */
        fun intent(context: Context, next: Class<out Activity>): Intent =
            Intent(context, TermsActivity::class.java).putExtra(EXTRA_NEXT, next.name)
    }
}
