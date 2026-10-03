package com.sempermechanics.semper.data.cloud.restore

import android.app.Application
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A restored sweep's Home headline counts what was skipped. Backups write the
 * skipped combinations as a `nodes` array, and the headline used to count only
 * the legacy `subsets` list, so every new backup came back "N of N solved".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RestoredSweepHeadlineTest {

    private fun engine(skipped: JSONObject) = JSONObject()
        .put("subset", 31)
        .put(
            "sweep",
            JSONObject()
                .put("subsets", JSONArray(listOf(31, 41, 51)))
                .put("skipped", skipped),
        )

    private val meta = JSONObject().put("frameCount", 3)

    private fun headline(skipped: JSONObject): String {
        val text = JSONObject(meta.toString())
            .put("frames", JSONArray().put(JSONObject().put("image", "def.png")))
            .put("engine", engine(skipped))
            .toString()
        return SessionMetadataDoc.decode(text).toRecord("s", "c", File("r"), "", null).headline
    }

    private fun node(subset: Int, code: Int) = JSONObject()
        .put("subset", subset)
        .put("step", 5)
        .put("strainWindow", 15)
        .put("code", code)

    @Test
    fun `skips written as nodes are counted`() {
        // As SessionMetadataDoc writes `engine.sweep.skipped.nodes`.
        val nodes = JSONArray()
            .put(node(subset = 21, code = 12))
            .put(node(subset = 25, code = 7))
        assertEquals(
            "def.png · 3 of 5 solved · subset 31–51",
            headline(JSONObject().put("nodes", nodes)),
        )
    }

    @Test
    fun `legacy skip lists are still counted`() {
        val legacy = JSONObject()
            .put("subsets", JSONArray(listOf(21)))
            .put("steps", JSONArray(listOf(5)))
            .put("strainWindows", JSONArray(listOf(15)))
            .put("codes", JSONArray(listOf(12)))
        assertEquals("def.png · 3 of 4 solved · subset 31–51", headline(legacy))
    }
}
