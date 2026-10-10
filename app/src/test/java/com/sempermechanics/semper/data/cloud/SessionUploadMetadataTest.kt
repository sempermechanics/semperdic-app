package com.sempermechanics.semper.data.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.net.AccountCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SessionUploadMetadata.buildMetadataJson] against what the org.json writer
 * it replaced wrote for the same records, captured before the move onto
 * [SessionMetadataDoc] (`cloud/upload_metadata_golden.json`).
 *
 * Compared as JSON, not as text: the new writer prints `50.0` where org.json
 * printed `50`, and `/` where it printed `\/`. The upload declares the sha of
 * the staged bytes and the backend re-dumps the file, so neither reaches a
 * reader. Only the clock (`capturedAtUtc`) and the device's own id differ by
 * nature and are left out.
 */
@RunWith(RobolectricTestRunner::class)
class SessionUploadMetadataTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = AccountCache.clear(context)

    @Test
    fun `each record writes what the org json writer wrote`() {
        AccountCache.saveIdentity(context, UploadMetadataFixtures.UID, UploadMetadataFixtures.EMAIL)
        for (record in UploadMetadataFixtures.all) {
            val written = SessionUploadMetadata.buildMetadataJson(record, context)
            assertSameJson(record.id, GOLDEN.getValue(record.id), written)
        }
    }

    @Test
    fun `a signed-out upload writes what the org json writer wrote`() {
        AccountCache.clear(context)
        val label = "b1 signed out"
        assertSameJson(
            label,
            GOLDEN.getValue(label),
            SessionUploadMetadata.buildMetadataJson(UploadMetadataFixtures.batch, context),
        )
    }

    @Test
    fun `the fixtures cover every captured output`() {
        assertEquals(UploadMetadataFixtures.all.map { it.id }.toSet() + "b1 signed out", GOLDEN.keys)
    }

    @Test
    fun `the frames and engine views are the file's own`() {
        AccountCache.saveIdentity(context, UploadMetadataFixtures.UID, UploadMetadataFixtures.EMAIL)
        for (record in UploadMetadataFixtures.all) {
            val file = Json.parseToJsonElement(GOLDEN.getValue(record.id)).jsonObject
            val frames = Json.parseToJsonElement(SessionUploadMetadata.framesJson(record).toString())
            val engine = Json.parseToJsonElement(SessionUploadMetadata.engineJson(record).toString())
            assertTrue(record.id, sameJson(file.getValue("frames"), frames))
            assertTrue(record.id, sameJson(file.getValue("engine"), engine))
        }
    }

    @Test
    fun `a blank or missing sweep label is written as the bundle names the frame`() {
        // The bundle's folder and PDF fall back to Combination_N; a blank label
        // used to reach metadata.json as "" and the folder as processed/ itself.
        val record = UploadMetadataFixtures.sweep.copy(sweepLabels = listOf("S21/st5/w41", " "))
        val frames = SessionUploadMetadata.framesJson(record)
        assertEquals(
            listOf("S21/st5/w41", "Combination_2", "Combination_3"),
            List(frames.length()) { frames.getJSONObject(it).getString("frame") },
        )
    }

    private fun assertSameJson(label: String, expected: String, actual: String) {
        val e = withoutVolatile(Json.parseToJsonElement(expected).jsonObject)
        val a = withoutVolatile(Json.parseToJsonElement(actual).jsonObject)
        assertTrue("$label:\nexpected $e\nactual   $a", sameJson(e, a))
    }

    /** The file without its clock and without the device's generated id. */
    private fun withoutVolatile(file: JsonObject): JsonObject {
        val device = file.getValue("device").jsonObject
        return JsonObject(file - "capturedAtUtc" + ("device" to JsonObject(device - "id")))
    }

    /** Structural equality with numbers compared by value. */
    private fun sameJson(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject ->
            a.keys.toList() == b.keys.toList() && a.keys.all { sameJson(a.getValue(it), b.getValue(it)) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { sameJson(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive -> samePrimitive(a, b)
        else -> a == b
    }

    private fun samePrimitive(a: JsonPrimitive, b: JsonPrimitive): Boolean {
        if (a.isString || b.isString) return a == b
        val number = a.content.toDoubleOrNull()
        return a.content == b.content || (number != null && number == b.content.toDoubleOrNull())
    }

    private companion object {
        /** The org.json writer's output, by record id. */
        val GOLDEN: Map<String, String> by lazy {
            val text = checkNotNull(SessionUploadMetadataTest::class.java.classLoader)
                .getResource(UploadMetadataFixtures.GOLDEN)
                .readText()
            Json.decodeFromString<Map<String, String>>(text)
        }
    }
}
