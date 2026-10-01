package com.indicvision.semper.field

import android.app.Application
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.cloud.SessionUploadMetadata
import com.indicvision.semper.data.cloud.restore.CloudRestore
import com.indicvision.semper.fixtures.sessionRecord
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.report.RoiData
import com.indicvision.semper.report.toRoi
import com.indicvision.semper.report.toRoiData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The wire forms of a [Roi] and [ImageSize] write exactly what the call sites
 * write today, under the same keys, and read back what they read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoiCodecsTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val roi = Roi(12, 34, 560, 400)

    @Test
    fun `Intent extras use the existing ROI_X to ROI_H int keys`() {
        val intent = Intent().putRoiExtras(roi)
        assertEquals(12, intent.getIntExtra(DicKeys.ROI_X, -1))
        assertEquals(34, intent.getIntExtra(DicKeys.ROI_Y, -1))
        assertEquals(560, intent.getIntExtra(DicKeys.ROI_W, -1))
        assertEquals(400, intent.getIntExtra(DicKeys.ROI_H, -1))
        assertEquals(setOf("ROI_X", "ROI_Y", "ROI_W", "ROI_H"), intent.extras!!.keySet())
        assertEquals(roi, intent.getRoiExtras(Roi.full(ImageSize(640, 480))))
    }

    @Test
    fun `Intent extras written the old way read back as the same ROI`() {
        val legacy = Intent()
            .putExtra(DicKeys.ROI_X, 12)
            .putExtra(DicKeys.ROI_Y, 34)
            .putExtra(DicKeys.ROI_W, 560)
            .putExtra(DicKeys.ROI_H, 400)
        assertEquals(roi, legacy.getRoiExtras(Roi.full(ImageSize(640, 480))))
    }

    @Test
    fun `absent ROI extras fall back per key to the default, as the wizard reads them`() {
        val size = ImageSize(640, 480)
        assertEquals(Roi.full(size), Intent().getRoiExtras(Roi.full(size)))
        val partial = Intent().putExtra(DicKeys.ROI_X, 7)
        assertEquals(Roi(7, 0, 640, 480), partial.getRoiExtras(Roi.full(size)))
    }

    @Test
    fun `Bundle form is the wizard's four-int array`() {
        val b = Bundle()
        b.putRoi("roi", roi)
        assertEquals(listOf(12, 34, 560, 400), b.getIntArray("roi")!!.toList())
        assertEquals(roi, b.getRoi("roi"))
        assertNull(Bundle().getRoi("roi"))
        assertNull(Bundle().apply { putIntArray("roi", intArrayOf(1, 2)) }.getRoi("roi"))
    }

    @Test
    fun `editor edges round-trip under ROI_L to ROI_B as floats`() {
        val b = Bundle()
        val edges = RectF(1.5f, 2.25f, 300.75f, 200f)
        b.putRoiEdges(edges)
        assertEquals(1.5f, b.getFloat(DicKeys.ROI_L), 0f)
        assertEquals(2.25f, b.getFloat(DicKeys.ROI_T), 0f)
        assertEquals(300.75f, b.getFloat(DicKeys.ROI_R), 0f)
        assertEquals(200f, b.getFloat(DicKeys.ROI_B), 0f)
        assertEquals(edges, b.getRoiEdges())
        assertNull(Bundle().getRoiEdges())
    }

    @Test
    fun `JSON is byte-identical to the roi object metadata json carries`() {
        val record = sessionRecord(imgW = 640, imgH = 480, roiX = 12, roiY = 34, roiW = 560, roiH = 400)
        val engine = SessionUploadMetadata.engineJson(record)
        assertEquals(engine.getJSONObject(ROI_JSON_KEY).toString(), roi.toJson().toString())
        assertEquals(roi, roiFromJson(engine.getJSONObject(ROI_JSON_KEY)))
    }

    @Test
    fun `JSON reads like the restore does, including a missing object`() {
        assertEquals(Roi(0, 0, 0, 0), roiFromJson(null))
        assertEquals(Roi(5, 0, 0, 9), roiFromJson(JSONObject().put("x", 5).put("h", 9)))

        val record = sessionRecord(imgW = 640, imgH = 480, roiX = 12, roiY = 34, roiW = 560, roiH = 400)
        val meta = JSONObject(SessionUploadMetadata.buildMetadataJson(record, context))
        val restored = CloudRestore.recordFrom(
            meta,
            CloudRestore.RestoreRecordTarget("s_r", "c1", File("r"), "", null),
        )
        val engine = meta.getJSONObject("engine")
        val read = roiFromJson(engine.optJSONObject("roi"))
        assertEquals(Roi(restored.roiX, restored.roiY, restored.roiW, restored.roiH), read)
        assertEquals(ImageSize(restored.imgW, restored.imgH), ImageSize.fromEngineJson(engine))
    }

    @Test
    fun `image size JSON uses the engine object's imageWidth and imageHeight`() {
        val record = sessionRecord(imgW = 640, imgH = 480)
        val engine = SessionUploadMetadata.engineJson(record)
        assertEquals(ImageSize(640, 480), ImageSize.fromEngineJson(engine))
        val written = ImageSize(640, 480).putInto(JSONObject())
        assertEquals("""{"imageWidth":640,"imageHeight":480}""", written.toString())
        assertEquals(ImageSize.UNKNOWN, ImageSize.fromEngineJson(JSONObject()))
    }

    @Test
    fun `image size extras use the viewer and editor keys`() {
        val size = ImageSize(640, 480)
        val viewer = ImageSizeExtras.VIEWER.put(Intent(), size)
        assertEquals(640, viewer.getIntExtra(DicKeys.IMG_W, 0))
        assertEquals(480, viewer.getIntExtra(DicKeys.IMG_H, 0))
        val editor = ImageSizeExtras.ROI_EDITOR.put(Intent(), size)
        assertEquals(640, editor.getIntExtra(DicKeys.IMAGE_WIDTH, 0))
        assertEquals(480, editor.getIntExtra(DicKeys.IMAGE_HEIGHT, 0))
        assertEquals(size, editor.getRoiEditorImageSize())
        assertEquals(ImageSize.UNKNOWN, Intent().getRoiEditorImageSize())
    }

    @Test
    fun `image size helpers`() {
        assertEquals(ImageSize(3, 4), ImageSize.of(3 to 4))
        assertEquals(3 to 4, ImageSize(3, 4).toPair())
        assertEquals(true, ImageSize(3, 4).isKnown)
        assertEquals(false, ImageSize(0, 4).isKnown)
        assertEquals(false, ImageSize(3, -1).isKnown)
    }

    @Test
    fun `report row round-trips`() {
        assertEquals(RoiData(12, 34, 560, 400), roi.toRoiData())
        assertEquals(roi, RoiData(12, 34, 560, 400).toRoi())
    }
}
