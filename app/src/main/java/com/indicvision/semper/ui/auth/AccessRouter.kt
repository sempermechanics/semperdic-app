package com.indicvision.semper.ui.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.indicvision.semper.data.account.AccessStatus
import com.indicvision.semper.data.account.LegalTerms
import com.indicvision.semper.ui.home.HomeActivity

/**
 * Maps [AuthRepository][com.indicvision.semper.data.account.AuthRepository] access-status
 * strings to the next Activity. Splash, Auth, and PendingApproval all call this
 * so the three screens cannot drift on which statuses mean "in".
 *
 * [intentFor] is the clickwrap gate: every route into Pending or Home passes
 * through it, so password registration, Google sign-in, the email link, and a
 * Terms version bump all land on [TermsActivity] first.
 */
object AccessRouter {

    /**
     * The Intent that actually starts [target]. When the user has not accepted
     * the Terms in force, it starts [TermsActivity] instead, carrying [target]
     * so the acceptance screen can continue there. Sign-in itself is never
     * gated — the user must be able to reach the screen that asks.
     */
    fun intentFor(context: Context, target: Class<out Activity>): Intent =
        if (isGated(target) && LegalTerms.needsAcceptance(context)) {
            TermsActivity.intent(context, target)
        } else {
            Intent(context, target)
        }

    /** Only the screens past sign-in are gated; Auth and Terms itself are not. */
    fun isGated(target: Class<out Activity>): Boolean =
        target == HomeActivity::class.java || target == PendingApprovalActivity::class.java

    /** True when the user may enter the main app (online or offline-approved). */
    fun isHomeStatus(status: String): Boolean =
        status == AccessStatus.APPROVED || status == AccessStatus.OFFLINE_CACHE_APPROVED

    /**
     * After a successful sign-in: pending accounts wait; everything else goes Home
     * (including offline-approved).
     */
    fun afterSignIn(status: String): Class<out Activity> =
        if (status == AccessStatus.PENDING) {
            PendingApprovalActivity::class.java
        } else {
            HomeActivity::class.java
        }

    /**
     * After a status refresh (Splash / PendingApproval). Unknown strings go to Auth
     * so the user can re-authenticate cleanly.
     *
     * @param stayOnPending when true, a still-PENDING result returns null (caller stays)
     * @return null when the caller should stay put
     */
    fun afterRefresh(status: String, stayOnPending: Boolean = false): Class<out Activity>? = when (status) {
        AccessStatus.APPROVED, AccessStatus.OFFLINE_CACHE_APPROVED -> HomeActivity::class.java
        AccessStatus.PENDING -> if (stayOnPending) null else PendingApprovalActivity::class.java
        else -> AuthActivity::class.java
    }
}
