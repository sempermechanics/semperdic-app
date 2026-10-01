package com.indicvision.semper.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.cloud.FakeCloudApi
import com.indicvision.semper.cloud.FakeTokens
import com.indicvision.semper.data.account.AuthRepository
import com.indicvision.semper.data.account.SignInMethod
import com.indicvision.semper.data.prefs.DicSettings
import com.indicvision.semper.diagnostics.SemperAnalytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A sign-in whose screen goes away is cancelled, not failed: it must not be
 * counted as a `sign_in_failed`, and the cancellation must reach the caller. A
 * Firebase Task cancelled on its own, while the caller still waits, is an
 * ordinary failure the caller must hear about.
 */
@RunWith(RobolectricTestRunner::class)
class AuthCancellationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = AuthRepository(context, FakeCloudApi(), FakeTokens(), signedIn = { true })
    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        DicSettings.setDiagnosticsEnabled(context, true)
        SemperAnalytics.sink = SemperAnalytics.Sink { _, name, _ -> synchronized(events) { events += name } }
    }

    @After
    fun tearDown() {
        SemperAnalytics.sink = SemperAnalytics.Sink { _, _, _ -> }
        DicSettings.setDiagnosticsEnabled(context, false)
    }

    @Test
    fun `a sign-in cancelled mid-flight is not reported as a failed sign-in`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            repo.firebaseThen(SignInMethod.PASSWORD) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a Firebase Task cancelled while the caller still waits is a failed sign-in`() = runBlocking {
        // Task.await() throws CancellationException for a cancelled Task even though
        // this coroutine is active; rethrowing it would end the caller silently.
        val result = repo.firebaseThen(SignInMethod.GOOGLE) { throw CancellationException("Task was cancelled") }

        assertTrue(result.isFailure)
        assertEquals("Task was cancelled", result.exceptionOrNull()?.message)
        assertEquals(listOf(SemperAnalytics.SIGN_IN_FAILED), events)
    }

    @Test
    fun `an ordinary sign-in failure keeps its cause`() = runBlocking {
        val boom = IllegalStateException("network down")

        val result = repo.firebaseThen(SignInMethod.GOOGLE) { throw boom }

        assertEquals("network down", result.exceptionOrNull()?.message)
        assertEquals(boom, result.exceptionOrNull()?.cause)
        assertEquals(listOf(SemperAnalytics.SIGN_IN_FAILED), events)
    }
}
