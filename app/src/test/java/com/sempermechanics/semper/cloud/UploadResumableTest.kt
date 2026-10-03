package com.sempermechanics.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.UploadLinkExpiredException
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
 * the server-supplied chunk size being trusted blindly.
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
