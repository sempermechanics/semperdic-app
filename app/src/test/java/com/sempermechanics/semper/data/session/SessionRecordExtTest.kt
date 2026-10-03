package com.sempermechanics.semper.data.session

import android.app.Application
import com.sempermechanics.semper.data.cloud.SessionUploadMetadata
import com.sempermechanics.semper.data.session.SessionRecord.SyncState
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `the cloud knows a synced row, or one that carries a cloud id`() {
        val known = mapOf(
            sessionRecord(syncState = SyncState.SYNCED) to true,
            sessionRecord(syncState = SyncState.SYNCED, cloudSessionId = "c1") to true,
            sessionRecord(syncState = SyncState.PENDING, cloudSessionId = "c1") to true,
            sessionRecord(syncState = SyncState.FAILED, cloudSessionId = "c1") to true,
            sessionRecord(syncState = SyncState.LOCAL_ONLY, cloudSessionId = "c1") to true,
            sessionRecord(syncState = SyncState.PENDING) to false,
            sessionRecord(syncState = SyncState.FAILED) to false,
            sessionRecord(syncState = SyncState.LOCAL_ONLY) to false,
            // A blank id is no id.
            sessionRecord(syncState = SyncState.PENDING, cloudSessionId = "  ") to false,
        )
        known.forEach { (r, expected) ->
            assertEquals("${r.syncState} '${r.cloudSessionId}'", expected, r.isKnownInCloud)
        }
    }

    @Test
    fun `a row is restorable when its frames are gone and the cloud knows it`() {
        val synced = sessionRecord(syncState = SyncState.SYNCED)
        val withId = sessionRecord(syncState = SyncState.PENDING, cloudSessionId = "c1")
        val unknown = sessionRecord(syncState = SyncState.PENDING, cloudSessionId = "  ")

        assertTrue(synced.isRestorable(hasLocalData = false))
        assertTrue(withId.isRestorable(hasLocalData = false))
        assertFalse("nothing in the cloud to restore from", unknown.isRestorable(hasLocalData = false))
        for (r in listOf(synced, withId, unknown)) {
            assertFalse("frames already on the phone", r.isRestorable(hasLocalData = true))
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
