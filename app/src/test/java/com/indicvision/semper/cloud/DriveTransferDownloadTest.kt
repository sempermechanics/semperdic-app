package com.indicvision.semper.cloud

import com.indicvision.semper.data.LogCapture
import com.indicvision.semper.data.net.HttpStatus
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.drive.DriveTransfer
import com.indicvision.semper.util.AtomicFiles
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The proxied restore-download state machine in [DriveTransfer.downloadFile],
 * exercised against a fake backend (MockWebServer).
 *
 * The upload half of [DriveTransfer] is covered by [UploadResumableTest] through
 * `IndicApi.uploadResumable` (which takes an explicit upload URL). The download
 * half cannot be reached that way — `IndicApi.downloadFile` builds its URL from
 * `BuildConfig.INDIC_API_BASE_URL` — so these drive [DriveTransfer] directly and
 * inject the signed-header lambda, which also keeps device attestation out of
 * the picture.
 *
 * Every case here guards a way a Session.zip restore can silently corrupt:
 * a resumed Range that restarts at zero, a short chunk spliced into the middle
 * of the file, and a truncated body finalized as if it were complete.
 */
@RunWith(RobolectricTestRunner::class)
class DriveTransferDownloadTest {

    @get:Rule
    val serverRule = MockWebServerRule()
    private val server get() = serverRule.server
    private lateinit var drive: DriveTransfer
    private lateinit var dir: File
    private lateinit var dest: File

    /** As the backend builds it, `{session}_{role}_{name}`; a legacy name is the user's own. */
    private val fileId = "sid1_raw_SpecimenA-07.png"

    @Before
    fun setUp() {
        val client = OkHttpClient()
        drive = DriveTransfer(client, client, "application/octet-stream".toMediaType())
        dir = File(System.getProperty("java.io.tmpdir"), "driveDl-${System.nanoTime()}")
        dir.mkdirs()
        dest = File(dir, "Session.zip")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Deterministic ASCII so a spliced or truncated body is visible in the diff. */
    private fun payload(n: Int): String = buildString(n) {
        repeat(n) { append('a' + (it % 26)) }
    }

    private fun partFile() = File(dir, "${dest.name}.part")

    private fun contentRange(start: Long, end: Long, total: String) =
        Headers.headersOf("Content-Range", "bytes $start-$end/$total")

    private fun download(
        expectedBytes: Long,
        onBytes: suspend (Long) -> Unit = {},
    ) = runBlocking {
        drive.downloadFile(
            fileId = fileId,
            dest = dest,
            baseUrl = server.url("/").toString().trimEnd('/'),
            expectedBytes = expectedBytes,
            onBytes = onBytes,
            signedGetHeaders = { Headers.headersOf("Authorization", "Bearer test-token") },
        )
    }

    @Test
    fun `a single 206 window completes the download and clears the part file`() {
        val body = payload(1000)
        server.enqueue(
            MockResponse(
                code = HttpStatus.PARTIAL_CONTENT,
                headers = contentRange(0, 999, "1000"),
                body = body,
            ),
        )

        download(expectedBytes = 1000L)

        assertTrue(dest.exists())
        assertEquals(body, dest.readText())
        assertFalse("the .part scratch file must not survive a finalize", partFile().exists())
    }

    @Test
    fun `resumes from the bytes already on disk instead of restarting at zero`() {
        val whole = payload(1000)
        server.enqueue(
            MockResponse(
                code = HttpStatus.PARTIAL_CONTENT,
                headers = contentRange(0, 399, "1000"),
                body = whole.substring(0, 400),
            ),
        )
        server.enqueue(
            MockResponse(
                code = HttpStatus.PARTIAL_CONTENT,
                headers = contentRange(400, 999, "1000"),
                body = whole.substring(400),
            ),
        )

        download(expectedBytes = 1000L)

        assertEquals(whole, dest.readText())
        server.takeRequest() // first window
        val resumed = server.takeRequest()
        assertTrue(
            "second GET must Range-resume at byte 400, not restart",
            resumed.headers["Range"].orEmpty().startsWith("bytes=400-"),
        )
    }

    @Test
    fun `a transient 503 is retried and the download still completes`() {
        server.enqueue(MockResponse(code = HttpStatus.SERVICE_UNAVAILABLE, body = ""))
        server.enqueue(
            MockResponse(
                code = HttpStatus.PARTIAL_CONTENT,
                headers = contentRange(0, 999, "1000"),
                body = payload(1000),
            ),
        )

        download(expectedBytes = 1000L)

        assertEquals(payload(1000), dest.readText())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `persistent 5xx fails without leaving a destination file behind`() {
        repeat(8) { server.enqueue(MockResponse(code = HttpStatus.SERVICE_UNAVAILABLE, body = "")) }

        val failure = assertThrows(IndicApi.ApiException::class.java) {
            download(expectedBytes = 1000L)
        }

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.code)
        assertFalse(
            "a failed restore must not leave a partial Session.zip at the destination",
            dest.exists(),
        )
        // Attempts 1..7 resume; the 8th is terminal (DOWNLOAD_MAX_ATTEMPTS = 8).
        assertEquals(8, server.requestCount)
    }

    @Test
    fun `a short 206 window is rewound rather than spliced into the file`() {
        // Server promises bytes 0-999 but delivers only 500 of them.
        server.enqueue(
            MockResponse(
                code = HttpStatus.PARTIAL_CONTENT,
                headers = contentRange(0, 999, "1000"),
                body = payload(500),
            ),
        )
        server.enqueue(
            MockResponse(
                code = HttpStatus.PARTIAL_CONTENT,
                headers = contentRange(0, 999, "1000"),
                body = payload(1000),
            ),
        )

        download(expectedBytes = 1000L)

        // Without the rewind, the retry would append onto the orphaned 500 bytes.
        assertEquals(payload(1000), dest.readText())
        assertEquals(1000L, dest.length())
    }

    @Test
    fun `a 206 window that does not start at the resume offset is rejected`() {
        val whole = payload(1000)
        server.enqueue(
            MockResponse(
                code = HttpStatus.PARTIAL_CONTENT,
                headers = contentRange(0, 399, "1000"),
                body = whole.substring(0, 400),
            ),
        )
        // Wrong start: replays from 0 while 400 bytes are already on disk.
        server.enqueue(
            MockResponse(
                code = HttpStatus.PARTIAL_CONTENT,
                headers = contentRange(0, 999, "1000"),
                body = whole,
            ),
        )
        server.enqueue(
            MockResponse(
                code = HttpStatus.PARTIAL_CONTENT,
                headers = contentRange(400, 999, "1000"),
                body = whole.substring(400),
            ),
        )

        download(expectedBytes = 1000L)

        // The mis-aligned window must be discarded, not appended.
        assertEquals(whole, dest.readText())
    }

    @Test
    fun `a truncated full-body 200 never becomes the destination file`() {
        // Proxy ignored Range and replied with a short full body.
        server.enqueue(MockResponse(code = HttpStatus.OK, body = payload(500)))
        server.enqueue(MockResponse(code = HttpStatus.OK, body = payload(1000)))

        download(expectedBytes = 1000L)

        assertEquals(payload(1000), dest.readText())
        assertEquals(1000L, dest.length())
    }

    @Test
    fun `a windowed fetch sends the absolute remote offset, not a window-relative one`() {
        // rangeStart greater than 0 is the legacy ranged-prefix / central-directory-tail
        // path (CloudRestore.planPrefixFetch). The window itself always starts its
        // local `part` file at 0, but the wire Range header must be the *absolute*
        // position in the remote object — sending the window-relative offset here was
        // a real bug: it always requested `bytes=0-…` instead of the intended window.
        server.enqueue(
            MockResponse(
                code = HttpStatus.PARTIAL_CONTENT,
                headers = contentRange(9000, 9499, "10000"),
                body = payload(500),
            ),
        )

        runBlocking {
            drive.downloadFile(
                fileId = fileId,
                dest = dest,
                baseUrl = server.url("/").toString().trimEnd('/'),
                expectedBytes = 500L,
                rangeStart = 9000L,
                signedGetHeaders = { Headers.headersOf("Authorization", "Bearer test-token") },
            )
        }

        val req = server.takeRequest()
        assertTrue(
            "a windowed fetch must request the absolute remote offset 9000, not 0",
            req.headers["Range"].orEmpty().startsWith("bytes=9000-"),
        )
        assertEquals(payload(500), dest.readText())
    }

    @Test
    fun `resuming within a window uses the absolute offset, not the window-local one`() {
        val whole = payload(1000)
        server.enqueue(
            MockResponse(
                code = HttpStatus.PARTIAL_CONTENT,
                headers = contentRange(5000, 5399, "20000"),
                body = whole.substring(0, 400),
            ),
        )
        server.enqueue(
            MockResponse(
                code = HttpStatus.PARTIAL_CONTENT,
                headers = contentRange(5400, 5999, "20000"),
                body = whole.substring(400),
            ),
        )

        runBlocking {
            drive.downloadFile(
                fileId = fileId,
                dest = dest,
                baseUrl = server.url("/").toString().trimEnd('/'),
                expectedBytes = 1000L,
                rangeStart = 5000L,
                signedGetHeaders = { Headers.headersOf("Authorization", "Bearer test-token") },
            )
        }

        assertEquals(whole, dest.readText())
        server.takeRequest() // first window: bytes=5000-...
        val resumed = server.takeRequest()
        assertTrue(
            "resume within a window must request absolute offset 5400 (5000+400), not window-local 400",
            resumed.headers["Range"].orEmpty().startsWith("bytes=5400-"),
        )
    }

    @Test
    fun `progress callbacks never move backwards and end at the full size`() {
        val whole = payload(1000)
        server.enqueue(
            MockResponse(
                code = HttpStatus.PARTIAL_CONTENT,
                headers = contentRange(0, 399, "1000"),
                body = whole.substring(0, 400),
            ),
        )
        server.enqueue(
            MockResponse(
                code = HttpStatus.PARTIAL_CONTENT,
                headers = contentRange(400, 999, "1000"),
                body = whole.substring(400),
            ),
        )

        val seen = mutableListOf<Long>()
        download(expectedBytes = 1000L) { seen.add(it) }

        assertTrue("restore UI needs at least one progress tick", seen.isNotEmpty())
        assertEquals("progress must be monotonic", seen.sorted(), seen)
        assertEquals(1000L, seen.last())
    }

    @Test
    fun `an interrupted download logs neither a local path nor the file's name`() {
        // A directory where the full-body scratch file goes makes every write fail
        // with an IOException whose message is that path.
        val scratch = AtomicFiles.fullOf(dest)
        File(scratch, "blocker").apply { parentFile?.mkdirs() }.writeText("x")
        repeat(DOWNLOAD_ATTEMPTS) { server.enqueue(MockResponse(code = HttpStatus.OK, body = payload(10))) }

        LogCapture().use { log ->
            assertThrows(java.io.IOException::class.java) { download(expectedBytes = 10L) }

            assertTrue("the retries are logged", log.warnings.isNotEmpty())
            assertTrue(log.warnings.joinToString(" / "), log.warnings.none { dir.name in it })
            assertTrue(log.warnings.joinToString(" / "), log.warnings.none { "SpecimenA" in it })
        }
    }

    @Test
    fun `a transient failure is logged with its reason, under a label instead of the file id`() {
        server.enqueue(MockResponse(code = HttpStatus.SERVICE_UNAVAILABLE, body = "upstream busy"))
        server.enqueue(
            MockResponse(code = HttpStatus.PARTIAL_CONTENT, headers = contentRange(0, 9, "10"), body = payload(10)),
        )

        LogCapture().use { log ->
            download(expectedBytes = 10L)

            val line = log.warnings.single()
            assertTrue(line, "transient HTTP 503" in line && "upstream busy" in line)
            assertTrue(line, "file " in line)
            assertFalse(line, "SpecimenA" in line || "sid1" in line)
        }
    }

    private companion object {
        /** DriveTransfer's DOWNLOAD_MAX_ATTEMPTS. */
        const val DOWNLOAD_ATTEMPTS = 8
    }
}
