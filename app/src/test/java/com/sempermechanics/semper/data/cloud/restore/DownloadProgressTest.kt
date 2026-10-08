package com.sempermechanics.semper.data.cloud.restore

import com.sempermechanics.semper.data.cloud.TransferBytes
import com.sempermechanics.semper.data.cloud.TransferPhase
import com.sempermechanics.semper.data.cloud.TransferWork
import com.sempermechanics.semper.navigation.IntentKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadProgressTest {

    @Test
    fun `percent survives a bundle past 2 GB and an unknown size`() {
        val threeGb = 3L * 1024 * 1024 * 1024
        assertEquals(50, DownloadProgress.percent(threeGb / 2, threeGb))
        assertEquals(0, DownloadProgress.percent(123L, 0L))
        assertEquals(100, DownloadProgress.percent(threeGb + 1, threeGb))
    }

    @Test
    fun `restore progress names its row and the bundle download does not`() {
        val restore = DownloadProgress.data(done = 1, total = 4, localId = "s1")
        assertEquals("s1", restore.getString(IntentKeys.SESSION_LOCAL_ID))
        assertEquals(IntentKeys.PHASE_DOWNLOAD, restore.getString(IntentKeys.UPLOAD_PHASE))
        assertEquals(25, restore.getInt(IntentKeys.UPLOAD_PERCENT, -1))

        val bundle = DownloadProgress.data(done = 1, total = 4)
        assertFalse(bundle.keyValueMap.containsKey(IntentKeys.SESSION_LOCAL_ID))
    }

    @Test
    fun `progress carries the bytes and the rate for the banner`() {
        val data = DownloadProgress.data(done = 3, total = 8, localId = "s1", perSecond = 1_500.0)
        assertEquals(TransferBytes(3, 8, 1_500.0), TransferBytes.read(data))
        val running = TransferWork.State.Running(TransferPhase.DOWNLOAD, 37, "s1", TransferBytes.read(data))
        assertEquals(37.5, running.exactPercent!!, 0.0)
        // Before the size is known there are no bytes to show, and the whole percent stands.
        assertNull(TransferBytes.read(DownloadProgress.data(done = 0, total = 0)))
        assertEquals(12.0, TransferWork.State.Running(TransferPhase.DOWNLOAD, 12, null).exactPercent!!, 0.0)
    }
}
