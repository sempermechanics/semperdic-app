package com.sempermechanics.semper.auth

import android.os.Parcel
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthMultiFactorException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.MultiFactorAssertion
import com.google.firebase.auth.MultiFactorInfo
import com.google.firebase.auth.MultiFactorResolver
import com.google.firebase.auth.MultiFactorSession
import com.google.firebase.auth.TotpMultiFactorGenerator
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.account.ReauthCredentials
import com.sempermechanics.semper.data.account.reauthFailure
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * What a re-authentication presents to Firebase, and what its failures mean.
 * Firebase itself is not reachable here, so the credentials are checked by
 * the sign-in method Firebase will read from them.
 */
@RunWith(RobolectricTestRunner::class)
class ReauthenticationTest {

    @Test
    fun `an emailed link is presented as an email-link credential, not as a password`() {
        val link = "https://app.sempermechanics.com/auth/finishSignIn?apiKey=k&oobCode=c&mode=signIn&lang=en"

        val credential = ReauthCredentials.emailLink("a@b.c", link)

        assertEquals(EmailAuthProvider.EMAIL_LINK_SIGN_IN_METHOD, credential.signInMethod)
    }

    @Test
    fun `a password is presented as a password credential`() {
        val credential = ReauthCredentials.password("a@b.c", "hunter2")

        assertEquals(EmailAuthProvider.EMAIL_PASSWORD_SIGN_IN_METHOD, credential.signInMethod)
    }

    @Test
    fun `a Google token is presented as a Google credential`() {
        assertEquals(GoogleAuthProvider.PROVIDER_ID, ReauthCredentials.google("id-token").provider)
    }

    @Test
    fun `a second factor demanded during re-auth becomes the authenticator challenge`() {
        val demand = FirebaseAuthMultiFactorException("mfa", "second factor", Resolver(listOf(Totp("enroll-1"))))

        val error = reauthFailure(demand)?.exceptionOrNull()

        assertTrue("got $error", error is AuthRepository.MfaTotpRequired)
        assertEquals("enroll-1", (error as AuthRepository.MfaTotpRequired).enrollmentId)
    }

    @Test
    fun `a second factor the phone cannot answer says where to enrol one`() {
        val demand = FirebaseAuthMultiFactorException("mfa", "second factor", Resolver(emptyList()))

        val error = reauthFailure(demand)?.exceptionOrNull()

        assertTrue(error?.message.orEmpty(), error?.message.orEmpty().contains("authenticator app"))
    }

    @Test
    fun `a wrong password is named only where a password was asked for`() {
        val refused = FirebaseAuthInvalidCredentialsException("bad", "refused")

        assertEquals("Incorrect password.", reauthFailure(refused, "Incorrect password.")?.exceptionOrNull()?.message)
        assertNull("no password, nothing to call wrong", reauthFailure(refused))
        assertNull("anything else is the generic failure", reauthFailure(IOException("offline")))
    }

    private class Totp(private val id: String) : MultiFactorInfo() {
        override fun getEnrollmentTimestamp(): Long = 0L
        override fun getDisplayName(): String? = null
        override fun getFactorId(): String = TotpMultiFactorGenerator.FACTOR_ID
        override fun getUid(): String = id
        override fun toJson(): JSONObject = JSONObject()
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private class Resolver(private val hints: List<MultiFactorInfo>) : MultiFactorResolver() {
        override fun resolveSignIn(assertion: MultiFactorAssertion): Task<AuthResult> =
            throw UnsupportedOperationException()
        override fun getFirebaseAuth(): FirebaseAuth = throw UnsupportedOperationException()
        override fun getSession(): MultiFactorSession = throw UnsupportedOperationException()
        override fun getHints(): List<MultiFactorInfo> = hints
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }
}
