package com.indicvision.semper.field

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.cloud.SessionMetadataDoc
import com.indicvision.semper.data.cloud.SessionUploadMetadata
import com.indicvision.semper.fixtures.sessionRecord
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.ui.viewer.ViewerArgs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** [DicParams], [FrameParams] and [StrainMethod] against the forms the code writes and reads today. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DicParamsTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val params = DicParams(subset = 31, step = 7, strainWindow = 29)

    @Test
    fun `defaults are the viewer's and the restore's`() {
        assertEquals(ViewerArgs.DEFAULT_SUBSET, DicParams.DEFAULT.subset)
        assertEquals(ViewerArgs.DEFAULT_STEP, DicParams.DEFAULT.step)
        assertEquals(ViewerArgs.DEFAULT_STRAIN_WINDOW, DicParams.DEFAULT.strainWindow)
        val restored = SessionMetadataDoc.decode("{}").toRecord("s", "c", File("r"), "", null)
        assertEquals(DicParams(restored.subset, restored.step, restored.strainWindow), DicParams.DEFAULT)
        assertEquals(DicParams.DEFAULT, DicParams.fromJson(JSONObject()))
    }

    @Test
    fun `JSON writes the engine object's keys in its order and reads back`() {
        val record = sessionRecord(subset = 31, step = 7, strainWindow = 29)
        val engine = SessionUploadMetadata.engineJson(record)
        assertEquals(params, DicParams.fromJson(engine))
        assertEquals("""{"subset":31,"step":7,"strainWindow":29}""", params.putInto(JSONObject()).toString())
        val engineKeys = engine.keys().asSequence().toList()
        assertEquals(listOf("subset", "step", "strainWindow"), engineKeys.take(3))
    }

    @Test
    fun `JSON read matches the restore of an uploaded record`() {
        val record = sessionRecord(subset = 31, step = 7, strainWindow = 29)
        val text = SessionUploadMetadata.buildMetadataJson(record, context)
        val meta = JSONObject(text)
        val restored = SessionMetadataDoc.decode(text).toRecord("s", "c", File("r"), "", null)
        val read = DicParams.fromJson(meta.getJSONObject("engine"))
        assertEquals(DicParams(restored.subset, restored.step, restored.strainWindow), read)
    }

    @Test
    fun `viewer extras are the keys ViewerArgs writes and reads`() {
        val intent = params.putViewerExtras(Intent())
        assertEquals(31, intent.getIntExtra(DicKeys.SUBSET_SIZE, 0))
        assertEquals(7, intent.getIntExtra(DicKeys.STEP, 0))
        assertEquals(29, intent.getIntExtra(DicKeys.STRAIN_WINDOW, 0))

        val args = ViewerArgs.ofFrames("/b", 100, 100, step = 7).copy(subsetSize = 31, strainWindow = 29)
        val written = args.toIntent(context)
        for (key in listOf(DicKeys.SUBSET_SIZE, DicKeys.STEP, DicKeys.STRAIN_WINDOW)) {
            assertEquals(key, written.getIntExtra(key, -1), intent.getIntExtra(key, -2))
        }
        val read = ViewerArgs.from(intent)
        assertEquals(params, DicParams(read.subsetSize, read.step, read.strainWindow))
    }

    @Test
    fun `frame params copy the caller's lists`() {
        val subsets = mutableListOf(31, 51)
        val fp = FrameParams(DicParams(41, 5, 21), subsets = subsets, steps = listOf(10, 17))
        subsets[0] = 99
        assertEquals(31, fp.at(0).subset)
        assertEquals(FrameParams(DicParams(41, 5, 21), listOf(31, 51), listOf(10, 17)), fp)
    }

    @Test
    fun `frame params fall back per list, as getOrElse does`() {
        val base = DicParams(41, 5, 21)
        val single = FrameParams(base)
        assertFalse(single.isSweep)
        assertEquals(base, single.at(0))
        assertEquals(base, single.at(7))

        val sweep = FrameParams(base, subsets = listOf(31, 51), steps = listOf(10, 17), strainWindows = listOf(31))
        assertTrue(sweep.isSweep)
        assertEquals(DicParams(31, 10, 31), sweep.at(0))
        assertEquals(DicParams(51, 17, 21), sweep.at(1))
        assertEquals(base, sweep.at(2))
        assertEquals(base, sweep.at(-1))
    }

    @Test
    fun `frame params from the viewer's arrays match getOrNull with a base fallback`() {
        val base = DicParams(41, 5, 21)
        val subsets = intArrayOf(31, 51)
        val steps: IntArray? = null
        val windows = intArrayOf(31, 41, 51)
        val fp = FrameParams.of(base, subsets, steps, windows)
        for (i in -1..4) {
            val expected = DicParams(
                subsets.getOrNull(i) ?: base.subset,
                steps?.getOrNull(i) ?: base.step,
                windows.getOrNull(i) ?: base.strainWindow,
            )
            assertEquals("frame $i", expected, fp.at(i))
        }
    }

    @Test
    fun `strain method display name`() {
        assertEquals("VSG", StrainMethod.VSG.wireName)
        assertEquals(ViewerArgs.STRAIN_METHOD_VSG, StrainMethod.VSG.wireName)
        assertEquals("VSG", StrainMethod.displayName(""))
        assertEquals("VSG", StrainMethod.displayName(null))
        assertEquals("VSG", StrainMethod.displayName("VSG"))
        assertEquals("Other", StrainMethod.displayName("Other"))
    }
}
