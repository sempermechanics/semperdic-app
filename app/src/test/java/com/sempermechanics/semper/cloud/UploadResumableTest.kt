package com.sempermechanics.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.UploadLinkExpiredException
import com.sempermechanics.semper.data.net.drive.MAX_STALLED_PUTS
import com.sempermechanics.semper.util.Digests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The Drive resumable-upload state machine in [SemperApi.uploadResumable],
 * exercised against a fake Drive (MockWebServer).
 *
 * Every case here encodes a bug this project actually shipped once:
 * resuming re-sent bytes Drive already had (size-mismatch 400s), an upload
 * that was already complete "finished without a final Drive response", and
 * the server-supplied chunk size being trusted blindly. And one it would have:
 * moving past a chunk Drive only partly kept (TD-158).
 */
@RunWith(RobolectricTestRunner::class)
class UploadResumableTest {

    @get:Rule
    val serverRule = MockWebServerRule()
    private val server get() = serverRule.server
    private lateinit var api: SemperApi
    private lateinit var file: File

    private val chunk256k = 256 * 1024

    @Before
    fun setUp() {
        api = SemperApi.get(ApplicationProvider.getApplicationContext<Context>())
        file = File.createTempFile("upload", ".bin")
    }

    @After
    fun tearDown() {
        file.delete()
    }

    private fun writeBytes(n: Int) = file.writeBytes(ByteArray(n) { (it % 251).toByte() })

    private fun enqueue(code: Int, headers: Headers = Headers.headersOf(), body: String = "") {
        server.enqueue(MockResponse(code = code, headers = headers, body = body))
    }

    @Test
    fun `resumes from the offset Drive reports instead of resending`() = runBlocking {
        writeBytes(1000)
        // Probe: Drive already holds bytes 0-399.
        enqueue(308, Headers.headersOf("Range", "bytes=0-399"))
        // The continuation PUT completes the file — no md5Checksum (Drive v3 default).
        enqueue(200, body = """{"id":"drv1"}""")

        val (driveId, md5) = api.uploadResumable(server.url("/u").toString(), file, chunk256k)

        assertEquals("drv1", driveId)
        assertEquals(Digests.md5Hex(file), md5)
        // Request 1 = probe; request 2 must continue at byte 400, not 0.
        assertEquals("bytes */1000", server.takeRequest().headers["Content-Range"])
        val put = server.takeRequest()
        assertEquals("bytes 400-999/1000", put.headers["Content-Range"])
        assertEquals(600L, put.body?.size?.toLong() ?: 0L)
    }

    @Test
    fun `already-complete upload returns local md5 when Drive omits md5Checksum`() = runBlocking {
        writeBytes(500)
        enqueue(200, body = """{"id":"done1"}""")

        val (driveId, md5) = api.uploadResumable(server.url("/u").toString(), file, chunk256k)

        assertEquals("done1", driveId)
        assertEquals(Digests.md5Hex(file), md5)
        // No bytes may be re-sent: the probe must be the only request.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `already-complete upload returns the local md5 even when Drive sends its own`() = runBlocking {
        writeBytes(500)
        // Drive holds different bytes than the file on disk. `:complete` compares
        // the md5 we send with Drive's, so echoing Drive's would hide the mismatch.
        enqueue(200, body = """{"id":"done2","md5Checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}""")

        val (driveId, md5) = api.uploadResumable(server.url("/u").toString(), file, chunk256k)

        assertEquals("done2", driveId)
        assertEquals(Digests.md5Hex(file), md5)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a cancelled upload stops before the next chunk`() = runBlocking {
        val total = 3 * chunk256k
        writeBytes(total)
        enqueue(308) // probe: nothing received yet
        enqueue(308, Headers.headersOf("Range", "bytes=0-${chunk256k - 1}"))
        enqueue(308, Headers.headersOf("Range", "bytes=0-${2 * chunk256k - 1}"))
        enqueue(200, body = """{"id":"never"}""")

        val upload = async(Dispatchers.Default) {
            // Cancel as soon as the first chunk lands, as a stopped worker would.
            api.uploadResumable(server.url("/u").toString(), file, chunk256k) { coroutineContext.cancel() }
        }
        try {
            upload.await()
            fail("a cancelled upload must not finish")
        } catch (_: CancellationException) {
            // expected
        }

        // Probe + the first chunk only: no further PUT after the cancel.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `fresh upload with no Range header starts at zero and chunks correctly`() = runBlocking {
        val total = chunk256k + 1000 // forces exactly two chunks at the min chunk size
        writeBytes(total)
        enqueue(308) // probe: nothing received yet
        enqueue(308, Headers.headersOf("Range", "bytes=0-${chunk256k - 1}"))
        enqueue(200, body = """{"id":"drv2"}""")

        // chunkSize=1 is below Drive's 256 KiB minimum — the clamp must raise it.
        val (driveId, md5) = api.uploadResumable(server.url("/u").toString(), file, 1)

        assertEquals("drv2", driveId)
        assertNotNull(md5)
        assertEquals(Digests.md5Hex(file), md5)
        server.takeRequest() // probe
        assertEquals("bytes 0-${chunk256k - 1}/$total", server.takeRequest().headers["Content-Range"])
        assertEquals("bytes $chunk256k-${total - 1}/$total", server.takeRequest().headers["Content-Range"])
    }

    @Test
    fun `the next chunk starts where Drive's Range says it stopped, not after what was sent`() = runBlocking {
        val total = 2 * chunk256k
        writeBytes(total)
        val keptFirst = 100_000 // Drive persisted only part of the first chunk
        enqueue(308) // probe: nothing received yet
        enqueue(308, Headers.headersOf("Range", "bytes=0-${keptFirst - 1}"))
        enqueue(308, Headers.headersOf("Range", "bytes=0-${keptFirst + chunk256k - 1}"))
        enqueue(200, body = """{"id":"short1"}""")
        var reported = 0L

        val (driveId, md5) = api.uploadResumable(server.url("/u").toString(), file, chunk256k) { reported += it }

        assertEquals("short1", driveId)
        // The digest was rebuilt over [0, keptFirst), so it is the whole file's.
        assertEquals(Digests.md5Hex(file), md5)
        assertEquals(total.toLong(), reported)
        server.takeRequest() // probe
        assertEquals("bytes 0-${chunk256k - 1}/$total", server.takeRequest().headers["Content-Range"])
        assertEquals(
            "bytes $keptFirst-${keptFirst + chunk256k - 1}/$total",
            server.takeRequest().headers["Content-Range"],
        )
        val last = server.takeRequest()
        assertEquals("bytes ${keptFirst + chunk256k}-${total - 1}/$total", last.headers["Content-Range"])
    }

    @Test
    fun `a 308 with no Range mid-upload restarts from zero and still hashes the whole file`() = runBlocking {
        val total = chunk256k + 1000
        writeBytes(total)
        enqueue(308, Headers.headersOf("Range", "bytes=0-${chunk256k - 1}")) // probe: first chunk held
        enqueue(308) // Drive now holds nothing
        enqueue(308, Headers.headersOf("Range", "bytes=0-${chunk256k - 1}"))
        enqueue(200, body = """{"id":"again1"}""")

        val (driveId, md5) = api.uploadResumable(server.url("/u").toString(), file, chunk256k)

        assertEquals("again1", driveId)
        assertEquals(Digests.md5Hex(file), md5)
        server.takeRequest() // probe
        assertEquals("bytes $chunk256k-${total - 1}/$total", server.takeRequest().headers["Content-Range"])
        assertEquals("bytes 0-${chunk256k - 1}/$total", server.takeRequest().headers["Content-Range"])
        assertEquals("bytes $chunk256k-${total - 1}/$total", server.takeRequest().headers["Content-Range"])
    }

    @Test
    fun `an unreadable Range after a chunk fails the attempt instead of guessing`() {
        writeBytes(chunk256k + 1000)
        enqueue(308) // probe
        enqueue(308, Headers.headersOf("Range", "bytes=0-abc"))
        enqueue(200, body = """{"id":"never"}""")

        val e = assertThrows(ApiException::class.java) {
            runBlocking { api.uploadResumable(server.url("/u").toString(), file, chunk256k) }
        }

        assertEquals(308, e.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a probe Range past the end of the file fails without sending bytes`() {
        writeBytes(1000)
        // Before: read as offset 5001, skipped every PUT and re-probed.
        enqueue(308, Headers.headersOf("Range", "bytes=0-5000"))
        enqueue(200, body = """{"id":"never"}""")

        val e = assertThrows(ApiException::class.java) {
            runBlocking { api.uploadResumable(server.url("/u").toString(), file, chunk256k) }
        }

        assertEquals(308, e.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `when Drive keeps everything each byte is sent once`() = runBlocking {
        val total = 3 * chunk256k + 500
        writeBytes(total)
        enqueue(308) // probe
        for (k in 1..3) enqueue(308, Headers.headersOf("Range", "bytes=0-${k * chunk256k - 1}"))
        enqueue(200, body = """{"id":"whole1"}""")
        var reported = 0L

        val (driveId, md5) = api.uploadResumable(server.url("/u").toString(), file, chunk256k) { reported += it }

        assertEquals("whole1", driveId)
        assertEquals(Digests.md5Hex(file), md5)
        assertEquals(total.toLong(), reported)
        server.takeRequest() // probe
        var next = 0L
        var sent = 0L
        repeat(4) {
            val put = server.takeRequest()
            val start = put.headers["Content-Range"]!!.removePrefix("bytes ").substringBefore('-').toLong()
            assertEquals(next, start)
            val size = put.body?.size?.toLong() ?: 0L
            next = start + size
            sent += size
        }
        assertEquals(total.toLong(), sent)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `a Drive that keeps nothing ends the attempt instead of looping`() {
        writeBytes(chunk256k + 1000)
        enqueue(308) // probe
        repeat(MAX_STALLED_PUTS + 2) { enqueue(308) } // never any progress
        enqueue(200, body = """{"id":"never"}""")

        val e = assertThrows(ApiException::class.java) {
            runBlocking { api.uploadResumable(server.url("/u").toString(), file, chunk256k) }
        }

        assertEquals(308, e.code)
        // Probe + MAX_STALLED_PUTS PUTs, each from byte 0, then stop.
        assertEquals(1 + MAX_STALLED_PUTS, server.requestCount)
        server.takeRequest() // probe
        val firstChunk = "bytes 0-${chunk256k - 1}/${chunk256k + 1000}"
        repeat(MAX_STALLED_PUTS) { assertEquals(firstChunk, server.takeRequest().headers["Content-Range"]) }
    }

    @Test
    fun `an expired upload link fails at the probe without sending bytes`() {
        writeBytes(1000)
        enqueue(404, body = "Not Found")
        // Before: the probe read 404 as "start at zero" and PUT the whole file.
        enqueue(200, body = """{"id":"never"}""")

        val e = assertThrows(UploadLinkExpiredException::class.java) {
            runBlocking { api.uploadResumable(server.url("/u").toString(), file, chunk256k) }
        }

        assertEquals(404, e.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 410 from the probe is an expired link too`() {
        writeBytes(1000)
        enqueue(410)

        val e = assertThrows(UploadLinkExpiredException::class.java) {
            runBlocking { api.uploadResumable(server.url("/u").toString(), file, chunk256k) }
        }

        assertEquals(410, e.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 499 from the probe is a cancelled session, so an expired link too`() {
        writeBytes(1000)
        enqueue(499)

        val e = assertThrows(UploadLinkExpiredException::class.java) {
            runBlocking { api.uploadResumable(server.url("/u").toString(), file, chunk256k) }
        }

        assertEquals(499, e.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a probe that fails transiently does not restart the upload at zero`() {
        writeBytes(1000)
        enqueue(500, body = "backend error")
        enqueue(200, body = """{"id":"never"}""")

        val e = assertThrows(ApiException::class.java) {
            runBlocking { api.uploadResumable(server.url("/u").toString(), file, chunk256k) }
        }

        assertEquals(500, e.code)
        assertEquals(1, server.requestCount)
    }
}
