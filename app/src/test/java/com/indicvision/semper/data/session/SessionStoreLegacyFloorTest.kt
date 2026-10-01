package com.indicvision.semper.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Upgrading over a build that recorded a capture noise floor.
 *
 * The in-app camera flow wrote a `captureFloor` object onto every session it
 * produced, and that object is still sitting in `index.json` on every phone
 * that used it. The field is gone from [SessionRecord]; the index is not.
 *
 * This is the one failure mode the camera removal could plausibly ship — an
 * unreadable index takes the whole home screen with it — so it is pinned here
 * rather than left to [SessionStore]'s `ignoreUnknownKeys` being noticed by the
 * next person to touch that parser.
 */
@RunWith(RobolectricTestRunner::class)
class SessionStoreLegacyFloorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "sessions").deleteRecursively()
    }

    @Test
    fun `a session index written before the camera was removed still loads`() {
        val dir = File(context.filesDir, "sessions").apply { mkdirs() }
        File(dir, "index.json").writeText(LEGACY_INDEX)

        val records = SessionStore.list(context)

        assertEquals(1, records.size)
        val record = records.first()
        assertEquals("sess-legacy", record.id)
        assertEquals("Legacy run", record.name)
        assertEquals(3, record.frameCount)
        assertNotNull(SessionStore.get(context, "sess-legacy"))
    }

    private companion object {
        /**
         * Trimmed from a real pre-removal index: the fields the record still has,
         * plus the whole nested floor object it no longer knows about.
         */
        val LEGACY_INDEX = """
            [
              {
                "id": "sess-legacy",
                "name": "Legacy run",
                "createdAt": 1750000000000,
                "updatedAt": 1750000000000,
                "frameCount": 3,
                "subset": 41,
                "step": 5,
                "strainWindow": 15,
                "imgW": 640,
                "imgH": 480,
                "roiX": 0,
                "roiY": 0,
                "roiW": 640,
                "roiH": 480,
                "refPath": "/data/sessions/sess-legacy/ref.png",
                "refName": "ref.png",
                "sessionDir": "/data/sessions/sess-legacy",
                "captureFloor": {
                  "microstrain": 237.0,
                  "vsgPx": 15.0,
                  "sigmaPx": 0.0025,
                  "frames": 5,
                  "exceeded": false,
                  "overridden": false,
                  "noiseVariance": 4.1,
                  "noiseCorrelation": 0.08
                }
              }
            ]
        """.trimIndent()
    }
}
