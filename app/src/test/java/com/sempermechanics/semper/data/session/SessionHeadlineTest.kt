package com.sempermechanics.semper.data.session

import android.app.Application
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc
import com.sempermechanics.semper.report.EngineStats
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Home headline is the first frame's convergence, the only frame whose
 * stats a run keeps. With several frames it says so, and a restored session
 * reads the same as the one that was backed up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionHeadlineTest {

    @Test
    fun `a single frame needs no label`() {
        assertEquals("97.5% converged", SessionHeadline.firstFrameConvergence(97.5f, 1))
    }

    @Test
    fun `several frames say which frame it is about`() {
        assertEquals("97.5% converged on frame 1", SessionHeadline.firstFrameConvergence(97.46f, 12))
    }

    @Test
    fun `a restored batch gets the same headline as the saved one`() {
        val stats = List(EngineStats.SLOT_COUNT) { 0f }.toMutableList()
            .also { it[EngineStats.SLOT_CONVERGENCE] = 88.25f }
        val frames = JSONArray(listOf("a.png", "b.png", "c.png").map { JSONObject().put("image", it) })
        val text = JSONObject()
            .put("frameCount", 3)
            .put("frames", frames)
            .put("engine", JSONObject().put("stats", JSONArray(stats)))
            .toString()
        val restored = SessionMetadataDoc.decode(text).toRecord("s", "c", File("r"), "", null).headline
        assertEquals(SessionHeadline.firstFrameConvergence(88.25f, 3), restored)
        assertEquals("88.3% converged on frame 1", restored)
    }
}
