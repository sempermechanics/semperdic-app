package com.sempermechanics.semper.viewer

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.ui.viewer.share.ShareExportJobs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The export jobs the viewer's ViewModel holds: ids, concurrent jobs, cancel
 * and background, and the save-as write they do without a screen.
 */
@RunWith(RobolectricTestRunner::class)
class ShareExportJobsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }

    // Stands in for viewModelScope, off the main looper so nothing needs idling.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ShareExportJobs(
        scope,
        ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver,
    )

    @After
    fun stop() = scope.cancel()

    private fun file(text: String): File = temp.newFile().apply { writeText(text) }

    @Test
    fun `two jobs of one kind never share an id`() {
        // The banner keys entries by id; "share-<progress text>" gave the CSV and
        // the photos the same one, so backgrounding both showed one entry and
        // cancelling either removed the other's.
        assertNotEquals(jobs.newId("csv"), jobs.newId("csv"))
        assertNotEquals(jobs.newId("csv"), jobs.newId("photos"))
    }

    @Test
    fun `jobs run side by side and cancelling one leaves the other`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val a = jobs.newId("csv")
        val b = jobs.newId("csv")
        val out = file("x")
        jobs.start(a, "CSV", null, direct = false) {
            release.await()
            out to "text/csv"
        }
        jobs.start(b, "CSV", null, direct = false) {
            release.await()
            out to "text/csv"
        }
        assertEquals(setOf(a, b), jobs.running.value.keys)

        jobs.cancel(a)
        assertEquals("a cancelled job leaves the screen at once", setOf(b), jobs.running.value.keys)
        val first = withTimeout(TIMEOUT_MS) { jobs.outcomes.first() }
        assertEquals(ShareExportJobs.Outcome.Cancelled(a), first)

        release.complete(Unit)
        val second = withTimeout(TIMEOUT_MS) { jobs.outcomes.first() }
        assertEquals(ShareExportJobs.Outcome.Ready(b, out, "text/csv", direct = false), second)
        assertTrue(jobs.running.value.isEmpty())
    }

    @Test
    fun `a job cancelled before its first dispatch still reports Cancelled`() = runBlocking {
        // The scope's thread is held until after the cancel, so the job cannot
        // have started on it: the case CI hit as a 10 s timeout.
        val gate = CountDownLatch(1)
        val thread = Executors.newSingleThreadExecutor()
        val held = CoroutineScope(
            SupervisorJob() + Executor { task ->
                thread.execute {
                    gate.await()
                    task.run()
                }
            }.asCoroutineDispatcher(),
        )
        try {
            val heldJobs = ShareExportJobs(
                held,
                ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver,
            )
            val id = heldJobs.newId("csv")
            // The generator waits, so it cannot finish on Default before cancel:
            // the undispatched start would then complete Ready inside start(),
            // the race behind this test's CI flake.
            val never = CompletableDeferred<Unit>()
            heldJobs.start(id, "CSV", null, direct = false) {
                never.await()
                file("x") to "text/csv"
            }
            heldJobs.cancel(id)
            gate.countDown()
            assertEquals(ShareExportJobs.Outcome.Cancelled(id), withTimeout(TIMEOUT_MS) { heldJobs.outcomes.first() })
        } finally {
            held.cancel()
            thread.shutdownNow()
        }
    }

    @Test
    fun `progress and the background flag reach the running entry`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val reported = CompletableDeferred<Unit>()
        val id = jobs.newId("pdf")
        jobs.start(id, "PDF", null, direct = true) { report ->
            report(40.5, "Frame 2 of 5…")
            reported.complete(Unit)
            release.await()
            file("pdf") to "application/pdf"
        }
        withTimeout(TIMEOUT_MS) { reported.await() }
        jobs.sendToBackground(id)

        assertEquals(
            ShareExportJobs.Running(id, "PDF", 40.5, "Frame 2 of 5…", background = true),
            jobs.running.value.getValue(id),
        )
        release.complete(Unit)
        assertTrue(withTimeout(TIMEOUT_MS) { jobs.outcomes.first() } is ShareExportJobs.Outcome.Ready)
    }

    @Test
    fun `a save-as job writes the document itself and reports it`() = runBlocking {
        val dest = File(temp.root, "picked.csv")
        val id = jobs.newId("csv")

        jobs.start(id, "CSV", Uri.fromFile(dest), direct = false) { file("a,b\n") to "text/csv" }

        assertEquals(ShareExportJobs.Outcome.Saved(id, ok = true), withTimeout(TIMEOUT_MS) { jobs.outcomes.first() })
        assertEquals("a,b\n", dest.readText())
    }

    @Test
    fun `a save-as job that lands no bytes is not reported saved`() = runBlocking {
        val empty = jobs.newId("csv")
        jobs.start(empty, "CSV", Uri.fromFile(File(temp.root, "empty.csv")), direct = false) {
            file("") to "text/csv"
        }
        assertEquals(
            ShareExportJobs.Outcome.Saved(empty, ok = false),
            withTimeout(TIMEOUT_MS) { jobs.outcomes.first() },
        )

        val unwritable = jobs.newId("csv")
        jobs.start(unwritable, "CSV", Uri.fromFile(File(temp.root, "missing/dir/x.csv")), direct = false) {
            file("a") to "text/csv"
        }
        assertEquals(
            ShareExportJobs.Outcome.Saved(unwritable, ok = false),
            withTimeout(TIMEOUT_MS) { jobs.outcomes.first() },
        )
    }

    /** Jobs that record each save-as document they delete. */
    private fun discardingJobs(discarded: MutableList<Uri>) = ShareExportJobs(
        scope,
        ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver,
        discardDocument = { synchronized(discarded) { discarded += it } },
    )

    @Test
    fun `a save-as that fails, lands no bytes or is cancelled deletes the picked document`() = runBlocking {
        val discarded = mutableListOf<Uri>()
        val saving = discardingJobs(discarded)

        val failedUri = Uri.fromFile(File(temp.root, "failed.pdf"))
        val failed = saving.newId("pdf")
        saving.start(failed, "PDF", failedUri, direct = false) { error("no usable frames") }
        assertEquals(ShareExportJobs.Outcome.Failed(failed), withTimeout(TIMEOUT_MS) { saving.outcomes.first() })

        val emptyUri = Uri.fromFile(File(temp.root, "empty.csv"))
        val empty = saving.newId("csv")
        saving.start(empty, "CSV", emptyUri, direct = false) { file("") to "text/csv" }
        assertEquals(
            ShareExportJobs.Outcome.Saved(empty, ok = false),
            withTimeout(TIMEOUT_MS) { saving.outcomes.first() },
        )

        val cancelledUri = Uri.fromFile(File(temp.root, "cancelled.zip"))
        val cancelled = saving.newId("zip")
        val never = CompletableDeferred<Unit>()
        saving.start(cancelled, "ZIP", cancelledUri, direct = false) {
            never.await()
            file("x") to "application/zip"
        }
        saving.cancel(cancelled)
        assertEquals(ShareExportJobs.Outcome.Cancelled(cancelled), withTimeout(TIMEOUT_MS) { saving.outcomes.first() })

        assertEquals(listOf(failedUri, emptyUri, cancelledUri), synchronized(discarded) { discarded.toList() })
    }

    @Test
    fun `a save-as that lands its bytes keeps the document`() = runBlocking {
        val discarded = mutableListOf<Uri>()
        val saving = discardingJobs(discarded)
        val id = saving.newId("csv")

        saving.start(id, "CSV", Uri.fromFile(File(temp.root, "kept.csv")), direct = false) { file("a") to "text/csv" }

        assertEquals(ShareExportJobs.Outcome.Saved(id, ok = true), withTimeout(TIMEOUT_MS) { saving.outcomes.first() })
        assertTrue(synchronized(discarded) { discarded.isEmpty() })
    }

    @Test
    fun `a build that throws is a failure, not a crash`() = runBlocking {
        val id = jobs.newId("zip")

        jobs.start(id, "ZIP", null, direct = false) { error("no usable files") }

        assertEquals(ShareExportJobs.Outcome.Failed(id), withTimeout(TIMEOUT_MS) { jobs.outcomes.first() })
        assertTrue(jobs.running.value.isEmpty())
    }
}
