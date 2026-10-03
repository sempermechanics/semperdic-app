package com.sempermechanics.semper.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.net.TokenStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Home's one-time beta / data-use notice. Each account acks once; with no
 * account signed in the phone acks once, so the notice does not come back on
 * every launch (it did: the ack was keyed by a uid that was null).
 */
@RunWith(RobolectricTestRunner::class)
class BetaNoticeAckTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun signedOut() = TokenStore.clear(context)

    @Test
    fun `a signed-out ack sticks across launches and sign-out`() {
        assertFalse(TokenStore.hasAckedBetaNotice(context))

        TokenStore.setBetaNoticeAcked(context)
        assertTrue(TokenStore.hasAckedBetaNotice(context))

        // Sign-out wipes the session prefs; the ack lives outside them.
        TokenStore.clear(context)
        assertTrue(TokenStore.hasAckedBetaNotice(context))
    }

    @Test
    fun `a signed-out ack does not stand in for an account's`() {
        TokenStore.setBetaNoticeAcked(context)

        TokenStore.saveIdentity(context, "uid-a", "a@example.com")
        assertFalse(TokenStore.hasAckedBetaNotice(context))
    }

    @Test
    fun `each account acks once and keeps it across sign-out`() {
        TokenStore.saveIdentity(context, "uid-a", "a@example.com")
        TokenStore.setBetaNoticeAcked(context)
        assertTrue(TokenStore.hasAckedBetaNotice(context))

        TokenStore.clear(context)
        assertFalse("an account's ack is not the phone's", TokenStore.hasAckedBetaNotice(context))

        TokenStore.saveIdentity(context, "uid-b", "b@example.com")
        assertFalse(TokenStore.hasAckedBetaNotice(context))

        TokenStore.saveIdentity(context, "uid-a", "a@example.com")
        assertTrue(TokenStore.hasAckedBetaNotice(context))
    }
}
