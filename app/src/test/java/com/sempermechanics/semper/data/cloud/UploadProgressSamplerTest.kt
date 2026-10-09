package com.sempermechanics.semper.data.cloud

import androidx.work.Data
import com.sempermechanics.semper.navigation.IntentKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * The upload progress sampler publishes a change, not a heartbeat (FI-15).
 *
 * A long upload spends most samples on an unchanged percent; each publish is a
 * WorkManager DB write plus a LiveData dispatch to Home, so repeats are dropped.
 */
class UploadProgressSamplerTest {

    private suspend fun awaitCount(published: List<Data>, n: Int) = withTimeout(TIMEOUT_MS) {
        while (published.size < n) delay(1)
    }

    @Test
    fun `an unchanged sample is published once`() = runBlocking {
        val sampler = UploadProgressSampler("local-1", initialPhase = "prepare", initialTotal = 10)
        val published = Collections.synchronizedList(mutableListOf<Data>())
        val job = sampler.launchIn(this, INTERVAL_MS) { published.add(it) }

        awaitCount(published, 1)
        delay(INTERVAL_MS * 10) // ten more samples, nothing moved
        assertEquals(1, published.size)

        sampler.done.set(5)
        awaitCount(published, 2)
        assertEquals(50, published[1].getInt(IntentKeys.UPLOAD_PERCENT, -1))

        sampler.phase.set("upload")
        sampler.done.set(0)
        awaitCount(published, 3)
        assertEquals("upload", published[2].getString(IntentKeys.UPLOAD_PHASE))
        assertEquals("local-1", published[2].getString(IntentKeys.SESSION_LOCAL_ID))
        job.cancel()
    }

    @Test
    fun `percent is clamped and a zero total reads as zero`() {
        val sampler = UploadProgressSampler("local-1", initialPhase = "upload", initialTotal = 0)
        assertEquals(0, sampler.percent())
        sampler.total.set(4)
        sampler.done.set(9)
        assertEquals(100, sampler.percent())
    }

    @Test
    fun `bytes to Drive travel with the percent and the notification hears each change`() = runBlocking {
        var now = 0L
        val sampler = UploadProgressSampler(
            "local-1",
            initialPhase = "prepare",
            initialTotal = 4,
            meter = TransferMeter { now },
        )
        val published = Collections.synchronizedList(mutableListOf<Data>())
        val shown = Collections.synchronizedList(mutableListOf<TransferNotifications.Progress>())
        val job = sampler.launchIn(this, INTERVAL_MS, onReading = { shown.add(it) }) { published.add(it) }

        awaitCount(published, 1)
        // Staging counts frames: no bytes in the Data, "preparing" for the notification.
        assertNull(TransferBytes.read(published[0]))
        assertTrue(shown[0].preparing)

        sampler.begin(TransferPhase.UPLOAD, total = 20_000L, bytes = true)
        now = 1_000L
        sampler.done.set(10_010L)
        awaitCount(published, 2)
        val bytes = TransferBytes.read(published.last())!!
        assertEquals(20_000L, bytes.total)
        assertEquals(50, published.last().getInt(IntentKeys.UPLOAD_PERCENT, -1))
        assertFalse(shown.last().preparing)
        assertEquals(50.05, shown.last().reading.percent, 1e-9)

        // A tenth of a percent is a change worth publishing; less is not.
        val before = published.size
        sampler.done.set(10_015L)
        delay(INTERVAL_MS * 10)
        assertEquals(before, published.size)
        sampler.done.set(10_020L)
        awaitCount(published, before + 1)
        job.cancel()
    }

    private companion object {
        const val INTERVAL_MS = 5L
        const val TIMEOUT_MS = 5_000L
    }
}
