package com.sempermechanics.semper.viewer

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.ui.viewer.share.ShareExportJobs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    fun `progress and the background flag reach the running entry`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val reported = CompletableDeferred<Unit>()
        val id = jobs.newId("pdf")
        jobs.start(id, "PDF", null, direct = true) { report ->
            report(40, "Frame 2 of 5…")
            reported.complete(Unit)
            release.await()
            file("pdf") to "application/pdf"
        }
        withTimeout(TIMEOUT_MS) { reported.await() }
        jobs.sendToBackground(id)

        assertEquals(
            ShareExportJobs.Running(id, "PDF", 40, "Frame 2 of 5…", background = true),
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
    fun `a build that throws is a failure, not a crash`() = runBlocking {
        val id = jobs.newId("zip")

        jobs.start(id, "ZIP", null, direct = false) { error("no usable files") }

        assertEquals(ShareExportJobs.Outcome.Failed(id), withTimeout(TIMEOUT_MS) { jobs.outcomes.first() })
        assertTrue(jobs.running.value.isEmpty())
    }
}
