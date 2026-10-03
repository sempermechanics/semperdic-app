package com.sempermechanics.semper.cloud

import android.app.ActivityManager
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.uploadChunkBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private const val SERVER_CHUNK = 32 shl 20 // the flat chunkSize in backend/app/repo/sessions.py

/**
 * [uploadChunkBytes] budgets the upload pipeline's live chunk
 * buffers (chunk size × concurrency) against *actual* available memory, rather
 * than trusting the server's flat 32 MiB chunkSize or the coarse
 * `isLowRamDevice` boolean alone — a device can be tight on headroom right now
 * (a big DIC batch still resident) independent of its RAM class.
 */
@RunWith(RobolectricTestRunner::class)
class UploadChunkSizingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun setAvailMem(bytes: Long) {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().apply { availMem = bytes }
        shadowOf(am).setMemoryInfo(info)
    }

    @Test
    fun `plentiful memory still respects the server-declared ceiling`() {
        // 10% of 8 GB is 800 MB per chunk at concurrency 1 — far above the
        // server's 32 MiB, which must remain the ceiling.
        setAvailMem(8L shl 30)
        val got = uploadChunkBytes(context, SERVER_CHUNK, concurrency = 1)
        assertEquals(SERVER_CHUNK, got)
    }

    @Test
    fun `tight memory shrinks the chunk well below the server ceiling`() {
        // 10% of 64 MB / concurrency 4 = 1.6 MB per chunk — rounds down to a
        // 256 KiB multiple, well under the server's 32 MiB offer.
        setAvailMem(64L shl 20)
        val got = uploadChunkBytes(context, SERVER_CHUNK, concurrency = 4)
        assertTrue("expected a small chunk, got $got", got < SERVER_CHUNK)
        assertTrue("must never go below the 256 KiB Drive floor", got >= 256 * 1024)
    }

    @Test
    fun `concurrency divides the same memory budget across parallel uploads`() {
        setAvailMem(1L shl 30) // 1 GB
        val single = uploadChunkBytes(context, SERVER_CHUNK, concurrency = 1)
        val quad = uploadChunkBytes(context, SERVER_CHUNK, concurrency = 4)
        assertTrue(
            "4x concurrency must not get the same per-chunk budget as 1x",
            quad < single,
        )
    }

    @Test
    fun `result is always a 256 KiB multiple`() {
        setAvailMem(777_777_777L) // an intentionally un-round available-memory value
        val got = uploadChunkBytes(context, SERVER_CHUNK, concurrency = 3)
        assertEquals(0L, got.toLong() % (256 * 1024))
    }

    @Test
    fun `zero or negative availMem falls back to the server value, clamped`() {
        setAvailMem(0L)
        val got = uploadChunkBytes(context, SERVER_CHUNK, concurrency = 4)
        assertEquals(SERVER_CHUNK, got)
    }

    @Test
    fun `never returns below the Drive minimum even under extreme memory pressure`() {
        setAvailMem(1L shl 20) // 1 MB available, concurrency 4
        val got = uploadChunkBytes(context, SERVER_CHUNK, concurrency = 4)
        assertEquals(256 * 1024, got)
    }
}
