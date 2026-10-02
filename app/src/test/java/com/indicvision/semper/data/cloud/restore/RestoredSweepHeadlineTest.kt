package com.indicvision.semper.data.cloud.restore

import android.app.Application
import com.indicvision.semper.data.cloud.SessionMetadataDoc
import com.indicvision.semper.data.session.SkippedNode
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

    @Test
    fun `skips written as nodes are counted`() {
        val nodes = SkippedNode.toMetadataJsonArray(
            listOf(
                SkippedNode(subset = 21, step = 5, strainWindow = 15, code = 12),
                SkippedNode(subset = 25, step = 5, strainWindow = 15, code = 7),
            ),
        )
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
