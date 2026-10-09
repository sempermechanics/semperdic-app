@file:Suppress("MagicNumber")

package com.sempermechanics.semper.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Firebase Auth: email/password sign-in, an ID token and its refresh, against
 * the app's own project. CI's Tier 3 passes the CI test account from the
 * `FIREBASE_TEST_EMAIL` / `FIREBASE_TEST_PASSWORD` secrets (TD-200); without them
 * (a local run, a fork's PR) every case skips.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseAuthIntegrationTest {

    private lateinit var auth: FirebaseAuth
    private var testEmail: String = ""
    private var testPassword: String = ""

    @Before
    fun setUp() {
        auth = FirebaseAuth.getInstance()
        val args = InstrumentationRegistry.getArguments()
        testEmail = args.getString("FIREBASE_TEST_EMAIL", "")
        testPassword = args.getString("FIREBASE_TEST_PASSWORD", "")
        assumeTrue(
            "Skipped — no test credentials (FIREBASE_TEST_EMAIL / FIREBASE_TEST_PASSWORD)",
            testEmail.isNotBlank() && testPassword.isNotBlank(),
        )
    }

    /** The rest of the suite runs on this emulator after it: leave no one signed in. */
    @After
    fun tearDown() {
        auth.signOut()
    }

    @Test
    fun signInWithTestCredentialsSucceeds() = runBlocking {
        val result = auth.signInWithEmailAndPassword(testEmail, testPassword).await()
        assertNotNull(result.user)
    }

    @Test
    fun signInThenGetIdTokenSucceeds() = runBlocking {
        val result = auth.signInWithEmailAndPassword(testEmail, testPassword).await()
        val token = result.user!!.getIdToken(true).await()
        assertNotNull(token.token)
        assertTrue(token.token!!.isNotBlank())
    }

    @Test
    fun signInThenTokenRefreshSucceeds() = runBlocking {
        auth.signInWithEmailAndPassword(testEmail, testPassword).await()
        val user = auth.currentUser!!
        val firstToken = user.getIdToken(false).await().token
        val refreshedToken = user.getIdToken(true).await().token
        assertNotNull(firstToken)
        assertNotNull(refreshedToken)
    }
}
