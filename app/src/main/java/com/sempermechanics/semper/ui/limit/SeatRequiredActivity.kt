package com.sempermechanics.semper.ui.limit

import android.os.Bundle
import android.view.View
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.LicenseConfigWorker
import com.sempermechanics.semper.data.net.ApiErrors
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.databinding.ActivitySeatRequiredBinding
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.setBusy
import com.sempermechanics.semper.util.suspendRunCatching
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Gate shown when every seat on a floating institution license is in use.
 *
 * Deliberately not shaped like [SessionLimitActivity], which it otherwise
 * resembles. Being at the analysis limit needs a human — an email to support to
 * raise the cap. Having no seat needs nobody: seats free themselves as
 * colleagues finish, so the whole screen is one button that tries again.
 *
 * The user is **not blocked from the app**. They are eligible, in demo mode,
 * and everything already on the device is still theirs to open — only starting
 * new work waits.
 */
@MainThread
class SeatRequiredActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySeatRequiredBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeatRequiredBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.padVertical(binding.seatRoot)

        binding.tvSeatBody.setText(R.string.seat_body)
        binding.btnTakeSeat.setOnClickListener { takeSeat() }
        binding.tvSeatBack.setOnClickListener { finish() }
    }

    /**
     * Ask for a seat. On success the backend's response already carries the
     * new entitlement, so it is applied straight to the cache rather than
     * waiting for the next config fetch — otherwise the user would return to
     * Home still looking blocked.
     */
    private fun takeSeat() {
        setLoading(true)
        lifecycleScope.launch {
            val api = SemperApi.get(this@SeatRequiredActivity)
            val token = TokenProvider.usableIdToken()
            if (!api.enabled || token == null) {
                setLoading(false)
                Feedback.toast(this@SeatRequiredActivity, R.string.seat_offline, long = true)
                return@launch
            }
            val outcome = suspendRunCatching { api.checkoutLease(token) }
            setLoading(false)
            outcome
                .onSuccess { config ->
                    AppRemoteConfig.apply(this@SeatRequiredActivity, config)
                    LicenseConfigWorker.enqueue(this@SeatRequiredActivity)
                    Feedback.toast(this@SeatRequiredActivity, R.string.seat_taken)
                    finish()
                }
                .onFailure { error ->
                    // A full pool is the expected answer, not a fault: say so
                    // plainly and leave the screen up to try again.
                    val message = when {
                        error is SemperApi.NoSeatAvailableException -> R.string.seat_still_full
                        // Past RetryOnTransient's three attempts, so this is a
                        // sustained throttle, not a blip — blaming the
                        // connection would send the user to their wifi settings
                        // for a limit that clears on its own.
                        error.hasApiCode(ApiErrors.RATE_LIMITED) -> R.string.error_rate_limited
                        error.hasApiCode(ApiErrors.APP_CHECK_REQUIRED) ->
                            R.string.error_app_check_required
                        else -> {
                            Timber.w(error, "Could not take a seat")
                            R.string.seat_error
                        }
                    }
                    Feedback.toast(this@SeatRequiredActivity, message, long = true)
                }
        }
    }

    /** True when this failure is the backend answering with [code]. */
    private fun Throwable.hasApiCode(code: String): Boolean =
        this is SemperApi.ApiException && ApiErrors.isCode(parsedDetail, code)

    /** INVISIBLE, not GONE, so the layout does not jump while it spins. */
    private fun setLoading(loading: Boolean) {
        binding.progressSeat.setBusy(loading, binding.btnTakeSeat, idleVisibility = View.INVISIBLE)
    }
}
