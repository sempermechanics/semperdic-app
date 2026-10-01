package com.indicvision.semper.cloud

import com.indicvision.semper.data.CloudSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account deletion is the one flow that destroys data it cannot get back, so
 * its ordering is pinned here: the cloud first, and no local wipe until the
 * cloud copy is confirmed gone.
 */
class AccountDeletionTest {

    /** Records what ran, in order, so the sequence can be asserted. */
    private val steps = mutableListOf<String>()

    private fun run(
        cloudErased: Boolean,
        identityErased: Boolean,
    ): CloudSync.AccountDeletion = runBlocking {
        CloudSync.deleteAccount(
            eraseCloud = {
                steps.add("cloud")
                cloudErased
            },
            deleteIdentity = {
                steps.add("identity")
                identityErased
            },
            wipeLocal = { steps.add("local") },
            signOut = { steps.add("signOut") },
        )
    }

    @Test
    fun `everything gone reports DELETED`() {
        val result = run(cloudErased = true, identityErased = true)
        assertEquals(CloudSync.AccountDeletion.DELETED, result)
    }

    @Test
    fun `an unreachable cloud leaves local data alone`() {
        val result = run(cloudErased = false, identityErased = true)
        assertEquals(CloudSync.AccountDeletion.CLOUD_UNREACHABLE, result)
        assertFalse("local data must survive a failed cloud erase", steps.contains("local"))
        assertFalse("the session must continue after a failed erase", steps.contains("signOut"))
    }

    @Test
    fun `a surviving identity still wipes the device and ends the session`() {
        val result = run(cloudErased = true, identityErased = false)
        assertEquals(CloudSync.AccountDeletion.IDENTITY_KEPT, result)
        assertTrue(steps.contains("local"))
        assertTrue("data is gone, so the session must not continue", steps.contains("signOut"))
    }

    @Test
    fun `the cloud is erased before anything local is touched`() {
        run(cloudErased = true, identityErased = true)
        assertEquals(listOf("cloud", "identity", "local", "signOut"), steps)
    }

    @Test
    fun `the identity delete is attempted before the session is signed out`() {
        run(cloudErased = true, identityErased = true)
        assertTrue(
            "signing out first would strip the credential the delete needs",
            steps.indexOf("identity") < steps.indexOf("signOut"),
        )
    }

    /**
     * The caller is a screen's scope, and a rotation cancels it. Pauses the
     * sequence at [step], cancels the caller there, then lets the step finish.
     */
    private fun cancelledDuring(step: String) = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun pauseAt(name: String) {
            if (name == step) {
                reached.complete(Unit)
                release.await()
            }
        }
        val job = launch(Dispatchers.Default) {
            CloudSync.deleteAccount(
                eraseCloud = {
                    synchronized(steps) { steps.add("cloud") }
                    pauseAt("cloud")
                    true
                },
                deleteIdentity = {
                    synchronized(steps) { steps.add("identity") }
                    pauseAt("identity")
                    true
                },
                wipeLocal = { synchronized(steps) { steps.add("local") } },
                signOut = {
                    pauseAt("signOut")
                    synchronized(steps) { steps.add("signOut") }
                },
            )
        }
        reached.await()
        job.cancel()
        release.complete(Unit)
        job.join()
    }

    @Test
    fun `a caller cancelled after the erase still wipes the device and signs out`() {
        // Stopping here would leave local data and a signed-in session for an
        // account the server no longer has.
        cancelledDuring("identity")
        assertEquals(listOf("cloud", "identity", "local", "signOut"), steps)
    }

    @Test
    fun `a caller cancelled during sign-out still finishes signing out`() {
        cancelledDuring("signOut")
        assertEquals(listOf("cloud", "identity", "local", "signOut"), steps)
    }

    @Test
    fun `an identity delete whose Firebase Task was cancelled still wipes and signs out`() {
        // Task.await() throws CancellationException for a cancelled Task while the
        // caller is active. After the erase that must read as "identity kept", not
        // abort the wipe and sign-out.
        val result = runBlocking {
            CloudSync.deleteAccount(
                eraseCloud = {
                    steps.add("cloud")
                    true
                },
                deleteIdentity = {
                    steps.add("identity")
                    throw CancellationException("Task was cancelled")
                },
                wipeLocal = { steps.add("local") },
                signOut = { steps.add("signOut") },
            )
        }

        assertEquals(CloudSync.AccountDeletion.IDENTITY_KEPT, result)
        assertEquals(listOf("cloud", "identity", "local", "signOut"), steps)
    }

    @Test
    fun `a caller cancelled while the erase is in flight still finishes the sequence`() {
        // The server may already have erased the account when the screen goes.
        cancelledDuring("cloud")
        assertEquals(listOf("cloud", "identity", "local", "signOut"), steps)
    }
}
