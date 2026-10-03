package com.sempermechanics.semper.data.cloud.restore

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc
import com.sempermechanics.semper.data.cloud.SessionUploadMetadata
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.fixtures.sessionRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A run that stopped short must still say so after a backup and restore; it
 * used to come back as a clean run of however many frames it reached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RestoredStopReasonTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun record(stopCode: Int, planned: Int) = sessionRecord(
        id = "s_test",
        name = "test",
        createdAt = 0,
        frameCount = 2,
        subset = 21,
        imgW = 64,
        imgH = 64,
        defNames = listOf("a.png", "b.png"),
    ).copy(stopCode = stopCode, plannedFrameCount = planned)

    private fun roundTrip(record: SessionRecord): SessionRecord =
        restore(SessionUploadMetadata.buildMetadataJson(record, context))

    private fun restore(metadataJson: String): SessionRecord =
        SessionMetadataDoc.decode(metadataJson).toRecord(
            localId = "s_restored",
            cloudSessionId = "c1",
            sessionDir = File("restored"),
            refPath = "",
            existing = null,
        )

    @Test
    fun `a run that stopped early comes back stopped early`() {
        val restored = roundTrip(record(stopCode = -3, planned = 50))
        assertTrue(restored.stoppedEarly)
        assertEquals(-3, restored.stopCode)
        assertEquals(50, restored.plannedFrameCount)
    }

    @Test
    fun `a backup made before the fields existed restores as a completed run`() {
        val meta = JSONObject(SessionUploadMetadata.buildMetadataJson(record(0, 0), context))
        meta.getJSONObject("metrics").apply {
            remove("stopCode")
            remove("plannedFrameCount")
        }
        val restored = restore(meta.toString())
        assertFalse(restored.stoppedEarly)
        assertEquals(0, restored.plannedFrameCount)
    }
}
