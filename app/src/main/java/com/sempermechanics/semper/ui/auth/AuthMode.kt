package com.sempermechanics.semper.ui.auth

import androidx.annotation.StringRes
import com.sempermechanics.semper.R

/**
 * What [AuthActivity]'s form is for. One at a time: the screen used to carry
 * three flags (register, re-auth, reset) that no path ever set together.
 */
enum class AuthMode(
    @StringRes val subtitle: Int,
    @StringRes val mainAction: Int,
) {
    /** Email + password, email link or Google, routing on to the app. */
    SIGN_IN(R.string.secure_access_portal, R.string.auth_sign_in),

    /** A new email + password account. */
    REGISTER(R.string.secure_access_portal, R.string.auth_create_account),

    /**
     * The caller already has a session and needs it proved again before
     * something irreversible: the screen answers with a result instead of
     * routing onward, and the address is fixed.
     */
    REAUTH(R.string.reauth_body, R.string.reauth_confirm),

    /**
     * In-app password reset from a Firebase email link (`mode=resetPassword`):
     * the email is fixed by the verified oobCode; the user enters the new
     * password twice.
     */
    RESET_PASSWORD(R.string.auth_reset_body, R.string.auth_reset_password),
    ;

    /** The email field; a reset takes its address from the link. */
    val showsEmail: Boolean get() = this != RESET_PASSWORD

    /** A new password, typed twice, under the password rules, with the generator. */
    val choosesPassword: Boolean get() = this == REGISTER || this == RESET_PASSWORD

    /** Password recovery and the sign-in link only make sense when signing in. */
    val showsRecoveryLinks: Boolean get() = this == SIGN_IN

    /** The sign-in / create-account switch. */
    val showsToggle: Boolean get() = this == SIGN_IN || this == REGISTER

    /** Google sign-in, where configured. */
    val offersGoogle: Boolean get() = this != RESET_PASSWORD
}
