package com.sempermechanics.semper.data.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The allow-list `AuthActivity` applies to arriving continue links. The
 * activity is exported, so this — not the manifest filter — is what keeps a
 * foreign host's `oobCode` away from Firebase.
 */
class AuthHostsTest {

    @Test
    fun `both continue hosts are trusted`() {
        assertTrue(isTrustedAuthLink("https", AUTH_HOST))
        assertTrue(isTrustedAuthLink("https", LEGACY_AUTH_HOST))
    }

    @Test
    fun `host and scheme match case-insensitively`() {
        assertTrue(isTrustedAuthLink("HTTPS", "App.SemperMechanics.com"))
    }

    @Test
    fun `any other host is refused`() {
        assertFalse(isTrustedAuthLink("https", "sempermechanics.com"))
        assertFalse(isTrustedAuthLink("https", "evil.app.sempermechanics.com"))
        assertFalse(isTrustedAuthLink("https", null))
    }

    @Test
    fun `plain http is refused even on our host`() {
        assertFalse(isTrustedAuthLink("http", AUTH_HOST))
        assertFalse(isTrustedAuthLink(null, AUTH_HOST))
    }
}
