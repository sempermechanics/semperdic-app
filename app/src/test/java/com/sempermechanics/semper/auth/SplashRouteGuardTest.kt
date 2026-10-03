package com.sempermechanics.semper.auth

import com.sempermechanics.semper.ui.auth.SplashActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The splash's fallback to sign-in. A startup that fails goes to sign-in with
 * an error; a splash that is merely cancelled (destroyed, or recreated by a
 * rotation whose new instance routes by itself) must not navigate at all.
 */
class SplashRouteGuardTest {

    @Test
    fun `a failed startup falls back to sign-in`() = runTest {
        val fallBacks = mutableListOf<Exception>()

        SplashActivity.routeOrFallBack(route = { throw IOException("boom") }, fallBack = { fallBacks += it })

        assertEquals(1, fallBacks.size)
        assertTrue(fallBacks.single() is IOException)
    }

    @Test
    fun `a cancelled splash does not fall back, so it cannot navigate twice`() = runTest {
        val fallBacks = mutableListOf<Exception>()
        val never = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            SplashActivity.routeOrFallBack(route = { never.await() }, fallBack = { fallBacks += it })
        }

        job.cancel(CancellationException("splash destroyed"))
        job.join()

        assertTrue("cancellation is not a startup failure", fallBacks.isEmpty())
        assertTrue(job.isCancelled)
    }

    @Test
    fun `a route that completes neither falls back nor throws`() = runTest {
        var routed = false
        SplashActivity.routeOrFallBack(route = { routed = true }, fallBack = { error("unexpected $it") })
        assertTrue(routed)
    }
}
