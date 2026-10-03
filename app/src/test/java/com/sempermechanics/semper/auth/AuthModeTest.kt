package com.sempermechanics.semper.auth

import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.auth.AuthMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AuthMode] decides what the sign-in form shows. Pinned against the rules
 * the three flags (register, re-auth, reset) encoded before it.
 */
class AuthModeTest {

    private data class Shown(
        val email: Boolean,
        val confirmAndRules: Boolean,
        val recovery: Boolean,
        val toggle: Boolean,
        val google: Boolean,
    )

    private fun AuthMode.shown() = Shown(showsEmail, choosesPassword, showsRecoveryLinks, showsToggle, offersGoogle)

    /** The pre-enum rules, verbatim, for each flag combination a path could reach. */
    private fun legacy(register: Boolean, reauth: Boolean, reset: Boolean) = Shown(
        email = !reset,
        confirmAndRules = register || reset,
        recovery = !(register || reset || reauth),
        toggle = !(reset || reauth),
        google = !reset,
    )

    @Test
    fun `each mode shows what its flags did`() {
        assertEquals(legacy(register = false, reauth = false, reset = false), AuthMode.SIGN_IN.shown())
        assertEquals(legacy(register = true, reauth = false, reset = false), AuthMode.REGISTER.shown())
        assertEquals(legacy(register = false, reauth = true, reset = false), AuthMode.REAUTH.shown())
        assertEquals(legacy(register = false, reauth = false, reset = true), AuthMode.RESET_PASSWORD.shown())
    }

    @Test
    fun `subtitle and main action follow the mode, reset first`() {
        assertEquals(R.string.secure_access_portal, AuthMode.SIGN_IN.subtitle)
        assertEquals(R.string.secure_access_portal, AuthMode.REGISTER.subtitle)
        assertEquals(R.string.reauth_body, AuthMode.REAUTH.subtitle)
        assertEquals(R.string.auth_reset_body, AuthMode.RESET_PASSWORD.subtitle)

        assertEquals(R.string.auth_sign_in, AuthMode.SIGN_IN.mainAction)
        assertEquals(R.string.auth_create_account, AuthMode.REGISTER.mainAction)
        assertEquals(R.string.reauth_confirm, AuthMode.REAUTH.mainAction)
        assertEquals(R.string.auth_reset_password, AuthMode.RESET_PASSWORD.mainAction)
    }
}
