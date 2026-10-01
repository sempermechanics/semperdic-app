package com.indicvision.semper.data.session

import android.app.Application
import com.indicvision.semper.data.cloud.SessionUploadMetadata
import com.indicvision.semper.data.session.SessionRecord.SyncState
import com.indicvision.semper.field.DicParams
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.field.Roi
import com.indicvision.semper.field.RunStop
import com.indicvision.semper.fixtures.sessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The typed views over a [SessionRecord] read exactly the fields the code reads today. */
@RunWith(RobolectricTestRunner::class) // org.json for the metadata comparison
@Config(application = Application::class)
class SessionRecordExtTest {

    private val sweep = sessionRecord(
        subset = 41,
        step = 5,
        strainWindow = 21,
        imgW = 640,
        imgH = 480,
        roiX = 12,
        roiY = 34,
        roiW = 560,
        roiH = 400,
        defNames = listOf("a", "b", "c"),
    ).copy(
        sweepSubsets = listOf(31, 51, 71),
        sweepSteps = listOf(10, 17),
        sweepStrainWindows = listOf(31, 69, 95),
        stopCode = -96,
    )

    @Test
    fun `roi, image size and params read the flat fields`() {
        assertEquals(Roi(12, 34, 560, 400), sweep.roi)
        assertEquals(ImageSize(640, 480), sweep.imageSize)
        assertEquals(DicParams(41, 5, 21), sweep.dicParams)
        assertEquals(RunStop.LowConvergence, sweep.runStop)
    }

    @Test
    fun `paramsAt is the per-frame lookup metadata json writes`() {
        val frames = SessionUploadMetadata.framesJson(sweep)
        for (i in 0 until frames.length()) {
            val f = frames.getJSONObject(i)
            val written = DicParams(f.getInt("subset"), f.getInt("step"), f.getInt("strainWindow"))
            assertEquals("frame $i", written, sweep.paramsAt(i))
        }
        // The short steps list falls back on its own, as getOrElse does.
        assertEquals(DicParams(71, 5, 95), sweep.paramsAt(2))
        assertEquals(sweep.isSweep, sweep.frameParams.isSweep)
    }

    @Test
    fun `an ordinary analysis has its scalars at every frame`() {
        val single = sessionRecord(subset = 41, step = 5, strainWindow = 21)
        assertEquals(DicParams(41, 5, 21), single.paramsAt(0))
        assertEquals(DicParams(41, 5, 21), single.paramsAt(9))
        assertFalse(single.frameParams.isSweep)
    }

    /**
     * A copy of Home's private rule (`SessionSelectionController.isCloudOnly` /
     * `hasCloudCopy`, inlined again in `HomeActivity.openSession`):
     * `!hasLocalData && (syncState == SYNCED || cloudSessionId.isNotBlank())`.
     * Keep it in step with those until wave 4 points them at [isRestorable].
     */
    private fun homeIsCloudOnly(r: SessionRecord, hasLocal: Boolean) =
        !hasLocal && (r.syncState == SyncState.SYNCED || r.cloudSessionId.isNotBlank())

    @Test
    fun `isRestorable is Home's cloud-only rule for every sync state, id and local state`() {
        for (state in SyncState.entries) {
            for (id in listOf("", "  ", "c1")) {
                for (local in listOf(true, false)) {
                    val r = sessionRecord(syncState = state, cloudSessionId = id)
                    val label = "$state '$id' local=$local"
                    assertEquals(label, homeIsCloudOnly(r, local), r.isRestorable(hasLocalData = local))
                }
            }
        }
    }

    @Test
    fun `isKnownInCloud differs from hasCloudCopy only for pending or failed rows without an id`() {
        for (state in SyncState.entries) {
            for (id in listOf("", "c1")) {
                val r = sessionRecord(syncState = state, cloudSessionId = id)
                val differs = id.isEmpty() && (state == SyncState.PENDING || state == SyncState.FAILED)
                assertEquals("$state '$id'", differs, r.isKnownInCloud != r.hasCloudCopy)
            }
        }
    }

    @Test
    fun `settings and skipped nodes read their fields`() {
        val settings = SessionRecordSettings(41, 5, 21, 1, 2, 3, 4, use6x6 = true)
        assertEquals(Roi(1, 2, 3, 4), settings.roi)
        assertEquals(DicParams(41, 5, 21), settings.dicParams)
        assertEquals(DicParams(31, 10, 41), SkippedNode(31, 10, 41, -2).dicParams)
    }
}
