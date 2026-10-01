package com.indicvision.semper.cloud

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.net.AppConfigDto
import com.indicvision.semper.data.net.ListSessionsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Home starts one reconcile per finished upload/restore job WorkManager still
 * keeps, all at once, on every open. The throttle has to hold for calls that
 * start together, not only for one that starts after another has finished:
 * on 2026-09-24 one app open sent 8 session listings and 10 config reads
 * (docs/perf/request-volume.md).
 */
@RunWith(RobolectricTestRunner::class)
class CloudSyncConcurrencyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val listed = AtomicInteger()
    private val configs = AtomicInteger()

    private fun answerSlowly() {
        api.onGetConfig = {
            configs.incrementAndGet()
            delay(NETWORK_MS)
            AppConfigDto(mode = "demo", maxSessions = 10)
        }
        api.onListSessions = { _, _ ->
            listed.incrementAndGet()
            delay(NETWORK_MS)
            ListSessionsResponse()
        }
    }

    private fun reconcileAtOnce(vararg deep: Boolean): List<CloudSync.Outcome> = runBlocking {
        deep.map { d ->
            async(Dispatchers.Default) {
                CloudSync.reconcile(context, reupload = false, deep = d, api = api, tokens = FakeTokens())
            }
        }.awaitAll()
    }

    /** Forget the last check, as on an open more than five minutes after it. */
    private fun forgetLastCheck() {
        context.getSharedPreferences("indic_cloudsync", Context.MODE_PRIVATE).edit { clear() }
        listed.set(0)
        configs.set(0)
    }

    @Test
    fun `resumes that start together list the cloud once`() {
        answerSlowly()

        val outcomes = reconcileAtOnce(*BooleanArray(RESUMES))

        assertEquals("session listings / config reads", "1 / 1", "${listed.get()} / ${configs.get()}")
        assertEquals(1, outcomes.count { it is CloudSync.Outcome.Ok })
        assertEquals(RESUMES - 1, outcomes.count { it == CloudSync.Outcome.Skipped })
    }

    @Test
    fun `the number of kept jobs does not change the cost`() {
        answerSlowly()

        val calls = SIZES.associateWith { k ->
            forgetLastCheck()
            reconcileAtOnce(*BooleanArray(k))
            "${listed.get()} / ${configs.get()}"
        }

        assertEquals("session listings / config reads per K", SIZES.associateWith { "1 / 1" }, calls)
    }

    @Test
    fun `a pull-to-refresh beside a resume still goes to the cloud`() {
        answerSlowly()

        val deepListings = AtomicInteger()
        val listSlowly = api.onListSessions
        api.onListSessions = { token, verify ->
            if (verify) deepListings.incrementAndGet()
            listSlowly(token, verify)
        }

        val (resume, pull) = reconcileAtOnce(false, true)

        // Whichever takes the lock first, the pull verifies the cloud. A resume
        // that waits behind it is then fresh and skips; one ahead of it lists too.
        assertEquals("deep listings", 1, deepListings.get())
        assertTrue("pull: $pull", pull is CloudSync.Outcome.Ok)
        assertEquals("resume: $resume", if (resume == CloudSync.Outcome.Skipped) 1 else 2, listed.get())
    }

    private companion object {
        const val RESUMES = 8
        const val NETWORK_MS = 50L
        val SIZES = listOf(2, 8, 32)
    }
}
