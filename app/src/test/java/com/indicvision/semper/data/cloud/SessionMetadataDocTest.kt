package com.indicvision.semper.data.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.cloud.restore.CloudRestore
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SkippedNode
import com.indicvision.semper.fixtures.sessionRecord
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [SessionMetadataDoc] against the two hands it replaces.
 *
 * Writer: the live [SessionUploadMetadata.buildMetadataJson] output decodes and
 * re-encodes to the same JSON (numbers compared by value), and [SessionMetadataDoc.forUpload]
 * builds an equal document.
 *
 * Reader: for every fixture — today's files, the `/2` files from before the
 * split, the legacy skip lists, and files with missing, null, mistyped and
 * unknown fields — [SessionMetadataDoc.toRecord] must give the very record
 * `CloudRestore.recordFrom` builds from the same bytes through org.json.
 */
@RunWith(RobolectricTestRunner::class)
class SessionMetadataDocTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sessionDir = File("sessions/r1")
    private val now = 1_700_000_000_000L

    @After
    fun tearDown() = TokenStore.clear(context)

    // ── Records the writer is fed ──────────────────────────────────────────

    private val batch = sessionRecord(
        id = "b1",
        name = "Tensile A · Sep 30",
        frameCount = 3,
        defNames = listOf("def_001.tif", "def_002.tif", "def_003.tif"),
        imgW = 4000,
        imgH = 3000,
        roiX = 10,
        roiY = 20,
        roiW = 300,
        roiH = 400,
        refName = "ref.tif",
    ).copy(
        engineStats = listOf(
            1200f, 1180f, 20f, 900f, 280f, 3f, 2f, 1f, 2.3f, 812.5f,
            0.1f, 50f, 7.25f, 33f, 1.4777f, 97.5f, 2f, 0.003f, 410f,
        ),
        strainMethod = "VSG",
        use6x6 = true,
        pointsConverged = 1180,
        avgIterations = 2.3f,
        executionTimeMs = 812,
        stopCode = -7,
        plannedFrameCount = 5,
    )

    private val sweep = sessionRecord(
        id = "s1",
        name = "Sweep",
        frameCount = 2,
        defNames = listOf("def.png", "def.png"),
        refName = "ref.png",
    ).copy(
        engineStats = listOf(10f, 9f, 1f),
        sweepSubsets = listOf(21, 31),
        sweepSteps = listOf(5, 7),
        sweepStrainWindows = listOf(41, 85),
        sweepLabels = listOf("S21/st5/w41", "S31/st7/w85"),
        lineCutHorizontal = false,
        sweepSkippedNodes = listOf(SkippedNode(41, 9, 121, -12), SkippedNode(51, 9, 121, -3)),
    )

    /** A sweep stored before typed skips: only the four parallel lists. */
    private val legacySkipSweep = sweep.copy(
        id = "s2",
        sweepSkippedNodes = emptyList(),
        sweepSkipSubsets = listOf(41),
        sweepSkipSteps = listOf(9),
        sweepSkipStrainWindows = listOf(121),
        sweepSkipCodes = listOf(-12),
    )

    private val bare = sessionRecord(id = "e1", name = "", refName = "")

    private val written = listOf(batch, sweep, legacySkipSweep, bare)

    // ── Writer ─────────────────────────────────────────────────────────────

    @Test
    fun `the uploader's file decodes and re-encodes to the same JSON`() {
        TokenStore.saveIdentity(context, "uid-1", "a@b.c")
        for (record in written) {
            val original = SessionUploadMetadata.buildMetadataJson(record, context)

            val doc = SessionMetadataDoc.decode(original)

            assertSameJson(record.id, original, doc.encode())
        }
    }

    @Test
    fun `forUpload builds the uploader's document, signed in or out`() {
        for (signedIn in listOf(true, false)) {
            if (signedIn) TokenStore.saveIdentity(context, "uid-1", "a@b.c") else TokenStore.clear(context)
            for (record in written) {
                val original = SessionUploadMetadata.buildMetadataJson(record, context)
                val decoded = SessionMetadataDoc.decode(original)

                // Only the timestamp is the writer's own clock.
                val built = SessionMetadataDoc.forUpload(record, context).copy(capturedAtUtc = decoded.capturedAtUtc)

                assertEquals(record.id, decoded, built)
                assertSameJson(record.id, original, built.encode())
            }
        }
    }

    @Test
    fun `a signed-out upload writes an empty user object`() {
        TokenStore.clear(context)
        val text = SessionMetadataDoc.forUpload(batch, context).encode()
        val uploaded = SessionUploadMetadata.buildMetadataJson(batch, context)
        assertEquals(JsonObject(emptyMap()), Json.parseToJsonElement(text).jsonObject["user"])
        assertEquals(JsonObject(emptyMap()), Json.parseToJsonElement(uploaded).jsonObject["user"])
    }

    @Test
    fun `the timestamp has the uploader's shape`() {
        assertEquals("2023-11-14T22:13:20Z", SessionMetadataDoc.utcStamp(now))
    }

    @Test
    fun `encoding keeps the writer's key order and two-space indent`() {
        val text = SessionMetadataDoc.forUpload(batch, context).encode()
        val keys = Json.parseToJsonElement(text).jsonObject.keys.toList()
        assertEquals(
            listOf(
                "schema", "localSessionId", "name", "specimen", "capturedAtUtc", "frameCount", "analysisKind",
                "csv", "frames", "app", "device", "user", "engine", "metrics",
            ),
            keys,
        )
        assertTrue(text.lines()[1].startsWith("  \"schema\""))
    }

    // ── Reader ─────────────────────────────────────────────────────────────

    @Test
    fun `today's files restore to the record the reader builds`() {
        TokenStore.saveIdentity(context, "uid-1", "a@b.c")
        for (record in written) {
            assertSameRecord(record.id, SessionUploadMetadata.buildMetadataJson(record, context))
        }
    }

    @Test
    fun `an existing row keeps its name, creation time and rename flag`() {
        val existing = batch.copy(name = "Renamed", createdAt = 5L, renamedByUser = true)
        val text = SessionUploadMetadata.buildMetadataJson(batch, context)
        assertSameRecord("existing", text, existing)
        assertSameRecord("blank existing name", text, existing.copy(name = " "))
    }

    @Test
    fun `older and odd files restore exactly as the reader reads them`() {
        LEGACY_AND_ODD.forEach { (label, text) -> assertSameRecord(label, text) }
    }

    @Test
    fun `the split layout is read from the schema as the restore reads it`() {
        mapOf(
            """{"schema":"indic.session.metadata/3"}""" to true,
            """{"schema":"indic.session.metadata/6"}""" to true,
            """{"schema":"indic.session.metadata/2"}""" to false,
            """{"schema":null}""" to false,
            """{}""" to false,
            """{"schema":3}""" to false,
        ).forEach { (text, split) ->
            val viaOrgJson = CloudRestore.isSplitLayout(JSONObject(text).optString("schema"))
            assertEquals(text, viaOrgJson, SessionMetadataDoc.decode(text).isSplitLayout())
            assertEquals(text, split, SessionMetadataDoc.decode(text).isSplitLayout())
        }
    }

    @Test
    fun `unknown keys are ignored on read and not written back`() {
        val text = """
            {"schema":"indic.session.metadata/5","localSessionId":"mt1","testType":"bending",
             "deflection":{"scale":1.02,"bias":-0.3},
             "frames":[{"index":0,"image":"a.png","csv":"Data_Frame_1.csv","load":12.5}],
             "engine":{"subset":31,"extra":{"x":1},"roi":{"x":1,"y":2,"w":3,"h":4,"z":9},
                       "sweep":{"subsets":[21],"skipped":{"nodes":[{"subset":1,"step":2,"strainWindow":3,"code":4,"why":"x"}]},"future":true}},
             "metrics":{"pointsConverged":5,"loadN":[1,2]}}
        """.trimIndent()

        val doc = SessionMetadataDoc.decode(text)
        val reencoded = Json.parseToJsonElement(doc.encode()).jsonObject

        assertEquals("mt1", doc.localSessionId)
        assertEquals("a.png", doc.frames?.single()?.image)
        assertEquals(SessionMetadataDoc.Roi(1, 2, 3, 4), doc.engine?.roi)
        assertFalse("testType" in reencoded)
        assertFalse("deflection" in reencoded)
        assertFalse("csv" in (reencoded["frames"] as JsonArray).single().jsonObject)
        assertSameRecord("unknown keys", text)
    }

    @Test
    fun `a frames entry that is not an object fails both readers`() {
        val text = """{"frames":[{"image":"a.png"},"b.png"]}"""
        try {
            CloudRestore.recordFrom(JSONObject(text), target(null))
            fail("org.json reader accepted a non-object frame")
        } catch (_: JSONException) {
            // getJSONObject throws: the restore fails as corrupt.
        }
        try {
            SessionMetadataDoc.decode(text)
            fail("the model accepted a non-object frame")
        } catch (_: SerializationException) {
            // Wave 4 maps this to the same corrupt-metadata failure.
        }
    }

    @Test
    fun `legacy skip lists of different lengths fail both readers`() {
        val text = """
            {"engine":{"sweep":{"subsets":[21],
              "skipped":{"subsets":[1,2],"steps":[1],"strainWindows":[1],"codes":[1]}}}}
        """.trimIndent()
        val viaOrgJson = runCatching { CloudRestore.recordFrom(JSONObject(text), target(null)) }.exceptionOrNull()
        val viaModel = runCatching { toRecord(SessionMetadataDoc.decode(text), null) }.exceptionOrNull()
        assertTrue(viaOrgJson is IllegalArgumentException)
        assertTrue(viaModel is IllegalArgumentException)
    }

    @Test
    fun `text that is not a JSON object is refused`() {
        listOf("[]", "\"metadata\"", "", "{\"frames\":").forEach { text ->
            val thrown = runCatching { SessionMetadataDoc.decode(text) }.exceptionOrNull()
            assertTrue(text, thrown is IllegalArgumentException)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun target(existing: SessionRecord?) =
        CloudRestore.RestoreRecordTarget("r1", "cloud-1", sessionDir, "/ref.png", existing)

    private fun toRecord(doc: SessionMetadataDoc, existing: SessionRecord?) =
        doc.toRecord("r1", "cloud-1", sessionDir, "/ref.png", existing, now)

    private fun assertSameRecord(label: String, text: String, existing: SessionRecord? = null) {
        val expected = CloudRestore.recordFrom(JSONObject(text), target(existing))
        // recordFrom stamps the wall clock; only the times it set from "now" differ.
        val actual = toRecord(SessionMetadataDoc.decode(text), existing).copy(
            createdAt = if (existing == null) expected.createdAt else existing.createdAt,
            updatedAt = expected.updatedAt,
        )
        assertEquals(label, expected, actual)
    }

    private fun assertSameJson(label: String, expected: String, actual: String) {
        val e = Json.parseToJsonElement(expected)
        val a = Json.parseToJsonElement(actual)
        assertTrue("$label:\nexpected $e\nactual   $a", sameJson(e, a))
    }

    /** Structural equality with numbers compared by value: org.json writes 50f as 50, kotlinx as 50.0. */
    private fun sameJson(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject ->
            a.keys == b.keys && a.keys.all { sameJson(a.getValue(it), b.getValue(it)) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { sameJson(a[it], b[it]) }
        a is JsonNull || b is JsonNull -> a == b
        a is JsonPrimitive && b is JsonPrimitive -> samePrimitive(a, b)
        else -> false
    }

    private fun samePrimitive(a: JsonPrimitive, b: JsonPrimitive): Boolean {
        if (a.isString || b.isString) return a == b
        val number = a.content.toDoubleOrNull()
        return a.content == b.content || (number != null && number == b.content.toDoubleOrNull())
    }

    private companion object {
        /** Files the reader must keep tolerating, each labelled with what it exercises. */
        val LEGACY_AND_ODD: List<Pair<String, String>> = listOf(
            "schema /2, as first uploaded (per-frame csv, no kind, no stop code)" to """
                {"schema":"indic.session.metadata/2","localSessionId":"old1","name":"Old","specimen":"ref.png",
                 "capturedAtUtc":"2026-07-16T10:00:00Z","frameCount":2,
                 "frames":[{"index":0,"frame":"Frame_1","image":"d1.png","dat":"frame_0000.dat","csv":"Data_Frame_1.csv"},
                           {"index":1,"frame":"Frame_2","image":"d2.png","dat":"frame_0001.dat","csv":"Data_Frame_2.csv"}],
                 "app":{"versionName":"1.0","versionCode":1},
                 "device":{"id":"d","manufacturer":"m","model":"x","os":"Android 14","sdkInt":34},
                 "user":{"uid":"u","email":"e"},
                 "engine":{"subset":31,"step":4,"strainWindow":11,"strainMethod":"VSG","use6x6":false,
                           "imageWidth":100,"imageHeight":80,"roi":{"x":1,"y":2,"w":90,"h":70},"stats":[100,98,2,90,8,0,0,0,3.1,20,1,1,1,1,4.9,98]},
                 "metrics":{"pointsConverged":98,"avgIterations":3.1,"executionTimeMs":20}}
            """.trimIndent(),
            "sweep with the four legacy skip lists" to """
                {"frameCount":2,"frames":[{"image":"d.png"},{"image":"d.png"}],
                 "engine":{"subset":21,"sweep":{"lineCutHorizontal":false,"subsets":[21,31],"steps":[5,7],"strainWindows":[41,85],
                   "labels":["a","b"],"skipped":{"subsets":[41,51],"steps":[9,9],"strainWindows":[121,121],"codes":[-12,-3]}}}}
            """.trimIndent(),
            "empty nodes fall back to the legacy lists" to """
                {"engine":{"sweep":{"subsets":[21],"skipped":{"nodes":[],"subsets":[41],"steps":[9],"strainWindows":[121],"codes":[-12]}}}}
            """.trimIndent(),
            "nodes with no object do not fall back" to """
                {"engine":{"sweep":{"subsets":[21],"skipped":{"nodes":["x",3,null],"subsets":[41],"steps":[9],"strainWindows":[121],"codes":[-12]}}}}
            """.trimIndent(),
            "nodes with missing and mistyped fields" to """
                {"engine":{"sweep":{"skipped":{"nodes":[{"subset":"41","step":9.9},{"code":true},"skip-me",{}]}}}}
            """.trimIndent(),
            "a sweep with no subsets uses the engine subset, else 0, for its span" to """
                {"specimen":"spec.png","engine":{"sweep":{}}}
            """.trimIndent(),
            "an empty object" to "{}",
            "every object explicitly null" to """
                {"name":null,"specimen":null,"frameCount":null,"frames":null,"engine":null,"metrics":null}
            """.trimIndent(),
            "objects and arrays of the wrong kind read as absent" to """
                {"frames":"none","engine":{"roi":"none","stats":{"a":1},"sweep":[1,2]},"metrics":[1,2,3]}
            """.trimIndent(),
            "scalars of the wrong kind coerce or default as opt does" to """
                {"name":"","specimen":12,"frameCount":"4","frames":[{"image":7},{"image":""},{"image":null},{}],
                 "engine":{"subset":"31","step":7.9,"strainWindow":true,"strainMethod":5,"use6x6":"TRUE",
                           "imageWidth":null,"imageHeight":"80.6","roi":{"x":"1e1","y":-2.5,"w":"w","h":3000000000},
                           "stats":[1,"2.5",null,true,"x",1e2,-0.0]},
                 "metrics":{"pointsConverged":"12abc","avgIterations":"3.25","executionTimeMs":1e3,"stopCode":"-4","plannedFrameCount":9.99}}
            """.trimIndent(),
            "a blank name falls back to the specimen, a null one does not" to """
                {"name":"   ","specimen":"Spec"}
            """.trimIndent(),
            "a null name is the text null, as optString reads it" to """
                {"name":null,"specimen":"Spec"}
            """.trimIndent(),
            "booleans as strings, in any case" to """
                {"engine":{"use6x6":"True","sweep":{"lineCutHorizontal":"FALSE","subsets":[5]}}}
            """.trimIndent(),
            "a boolean that is neither reads as its default" to """
                {"engine":{"use6x6":1,"sweep":{"lineCutHorizontal":"no","subsets":[5]}}}
            """.trimIndent(),
            "sweep label and list elements of the wrong kind" to """
                {"frameCount":1,"frames":[{"image":"d.png"}],
                 "engine":{"sweep":{"subsets":["21",null,"x",31.9],"steps":[true],"strainWindows":[],"labels":[1,null,"b",2.5]}}}
            """.trimIndent(),
            "big integers wrap as intValue does" to """
                {"frameCount":4294967297,"metrics":{"pointsConverged":-2147483649,"executionTimeMs":9223372036854775807}}
            """.trimIndent(),
            "the first frame's convergence comes from the stats slot" to """
                {"frames":[{"image":"a"},{"image":"b"}],"engine":{"stats":[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,"88.25"]}}
            """.trimIndent(),
        )
    }
}
