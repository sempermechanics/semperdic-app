package com.indicvision.semper.ui.analysis

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.data.CacheJanitor
import com.indicvision.semper.fixtures.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * The ROI editor as the wizard sees it: what comes back in the result Intent
 * (the rect the engine is handed and the mask file beside it), the typed-entry
 * path, and the mode toggles that survive a recreate.
 *
 * The reference is a headerless RAW RGBA blob, which the editor previews
 * without the native decoder, so the whole load runs on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoiDrawActivityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun intent(path: String?, w: Int = IMG_W, h: Int = IMG_H) =
        Intent(app, RoiDrawActivity::class.java).apply {
            path?.let { putExtra(DicKeys.IMAGE_FILE_PATH, it) }
            putExtra(DicKeys.IMAGE_WIDTH, w)
            putExtra(DicKeys.IMAGE_HEIGHT, h)
        }

    /** A RAW reference: exactly w × h × 4 bytes, so RawRgba.matches picks the JVM preview. */
    private fun rawReference(): File =
        temp.newFile("ref.raw").apply { writeBytes(ByteArray(IMG_W * IMG_H * 4) { 0x7F }) }

    private fun launch(intent: Intent): ActivityController<RoiDrawActivity> =
        Robolectric.buildActivity(RoiDrawActivity::class.java, intent).setup()

    /** Waits for the preview decode (Dispatchers.Default) and the post that hands it to the overlay. */
    private fun awaitCanvas(activity: RoiDrawActivity) {
        val overlay = activity.findViewById<StudioOverlayView>(R.id.overlayRoi)
        idleUntil("the preview to reach the overlay") { overlay.imageView != null }
    }

    /** Waits for Save: the mask is built and written off the main thread before the editor finishes. */
    private fun awaitFinish(activity: RoiDrawActivity) {
        idleUntil("the editor to finish") { activity.isFinishing }
    }

    private fun RoiDrawActivity.hud() = findViewById<TextView>(R.id.tvHud).text.toString()

    private fun RoiDrawActivity.type(x: String, y: String, w: String, h: String) {
        findViewById<TextInputEditText>(R.id.etRoiX).setText(x)
        findViewById<TextInputEditText>(R.id.etRoiY).setText(y)
        findViewById<TextInputEditText>(R.id.etRoiW).setText(w)
        findViewById<TextInputEditText>(R.id.etRoiH).setText(h)
    }

    private fun RoiDrawActivity.click(id: Int) = findViewById<View>(id).performClick()

    private fun RoiDrawActivity.check(group: Int, button: Int) =
        findViewById<MaterialButtonToggleGroup>(group).check(button)

    private fun RoiDrawActivity.resultRect(): List<Int> {
        val data = shadowOf(this).resultIntent
        return listOf(DicKeys.ROI_X, DicKeys.ROI_Y, DicKeys.ROI_W, DicKeys.ROI_H).map { data.getIntExtra(it, -1) }
    }

    // ── Result Intent ────────────────────────────────────────────────────────

    @Test
    fun `Full Image returns the whole frame and an all-included mask`() {
        val activity = launch(intent(path = null)).get()
        activity.click(R.id.btnFullImageRoi)
        awaitFinish(activity)

        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals(listOf(0, 0, IMG_W, IMG_H), activity.resultRect())
        val mask = File(shadowOf(activity).resultIntent.getStringExtra(DicKeys.MASK_FILE_PATH)!!)
        assertEquals(File(activity.cacheDir, CacheJanitor.ROI_MASK_CACHE), mask)
        val bytes = mask.readBytes()
        assertEquals(IMG_W * IMG_H, bytes.size)
        assertTrue("every pixel correlated", bytes.all { it == 0xFF.toByte() })
        assertEquals(activity.getString(R.string.roi_full_image_selected), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `saving with nothing drawn also means the full image`() {
        val activity = launch(intent(path = null)).get()
        activity.click(R.id.btnSaveRoi)
        awaitFinish(activity)
        assertEquals(listOf(0, 0, IMG_W, IMG_H), activity.resultRect())
    }

    @Test
    fun `Save writes the mask off the main thread and then finishes`() {
        val activity = launch(intent(path = null)).get()
        activity.click(R.id.btnSaveRoi)
        // A second tap while the mask is written must not start another save.
        activity.click(R.id.btnSaveRoi)

        // The click returned without the 256 000-byte mask written on Main.
        assertFalse("Save blocked the main thread until the mask was on disk", activity.isFinishing)
        awaitFinish(activity)
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        val mask = File(shadowOf(activity).resultIntent.getStringExtra(DicKeys.MASK_FILE_PATH)!!)
        assertEquals(IMG_W.toLong() * IMG_H, mask.length())
        // One save, not two: a save of the full image toasts as it starts, so
        // a second toast means the second tap built and wrote the mask again.
        assertEquals(1, ShadowToast.shownToastCount())
    }

    @Test
    fun `a missing reference file closes the editor with a message`() {
        val activity = launch(intent(path = File(temp.root, "gone.png").path)).get()
        awaitFinish(activity)

        assertTrue(activity.isFinishing)
        assertEquals(activity.getString(R.string.roi_image_not_found), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `cancel returns nothing`() {
        val activity = launch(intent(path = null)).get()
        activity.click(R.id.btnCancelRoi)
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    // ── Typed ROI on a loaded reference ──────────────────────────────────────

    @Test
    fun `a typed ROI is echoed in the HUD and returned as typed`() {
        val activity = launch(intent(rawReference().path)).get()
        awaitCanvas(activity)
        activity.check(R.id.rgEditMode, R.id.rbModeManual)
        activity.type("100", "200", "300", "150")
        activity.click(R.id.btnApplyManualRoi)

        assertEquals(activity.getString(R.string.roi_hud_dimensions, 300, 150, 100, 200), activity.hud())
        activity.click(R.id.btnSaveRoi)
        awaitFinish(activity)
        assertEquals(listOf(100, 200, 300, 150), activity.resultRect())
        val mask = File(shadowOf(activity).resultIntent.getStringExtra(DicKeys.MASK_FILE_PATH)!!)
        assertTrue("one mask byte per image pixel or more", mask.length() >= IMG_W.toLong() * IMG_H)
    }

    @Test
    fun `a typed ROI that comes back from the view a hair low is saved as typed`() {
        // The overlay holds the ROI in view pixels; on a 4032-wide photo a typed
        // (1000, 750, 300, 200) returns from that round trip as n − ε.
        val back = RectF(999.9997f, 749.99994f, 1299.9998f, 949.9999f)
        assertEquals(Rect(1000, 750, 1300, 950), roiPixels(back, 4032, 3024))
    }

    @Test
    fun `saved ROI pixels are clipped to the image`() {
        assertEquals(Rect(0, 0, 640, 400), roiPixels(RectF(-0.4f, -3f, 700.2f, 400.4f), 640, 400))
    }

    @Test
    fun `a typed ROI past the image edge is clipped, not rejected`() {
        val activity = launch(intent(rawReference().path)).get()
        awaitCanvas(activity)
        activity.check(R.id.rgEditMode, R.id.rbModeManual)
        activity.type("600", "0", "500", "100")
        activity.click(R.id.btnApplyManualRoi)
        activity.click(R.id.btnSaveRoi)
        awaitFinish(activity)

        assertEquals(listOf(600, 0, IMG_W - 600, 100), activity.resultRect())
    }

    @Test
    fun `blank or zero-size manual fields are refused`() {
        val activity = launch(intent(rawReference().path)).get()
        awaitCanvas(activity)
        activity.check(R.id.rgEditMode, R.id.rbModeManual)

        activity.click(R.id.btnApplyManualRoi)
        assertEquals(activity.getString(R.string.roi_invalid_size), ShadowToast.getTextOfLatestToast())

        ShadowToast.reset()
        activity.type("10", "10", "0", "10")
        activity.click(R.id.btnApplyManualRoi)
        assertEquals(activity.getString(R.string.roi_invalid_size), ShadowToast.getTextOfLatestToast())
        assertFalse(activity.findViewById<StudioOverlayView>(R.id.overlayRoi).hasValidRoi)
    }

    @Test
    fun `a typed erase rect is added as a hole, not a new crop`() {
        val activity = launch(intent(rawReference().path)).get()
        awaitCanvas(activity)
        activity.check(R.id.rgEditMode, R.id.rbModeManual)
        activity.check(R.id.rgCropErase, R.id.rbErase)
        activity.type("50", "60", "70", "80")
        activity.click(R.id.btnApplyManualRoi)

        val overlay = activity.findViewById<StudioOverlayView>(R.id.overlayRoi)
        assertEquals(1, overlay.holes.size)
        assertFalse(overlay.hasValidRoi)
        assertEquals(activity.getString(R.string.roi_hud_dimensions, 70, 80, 50, 60), activity.hud())
    }

    @Test
    fun `reset clears the ROI and the typed fields`() {
        val activity = launch(intent(rawReference().path)).get()
        awaitCanvas(activity)
        activity.check(R.id.rgEditMode, R.id.rbModeManual)
        activity.type("100", "200", "300", "150")
        activity.click(R.id.btnApplyManualRoi)
        activity.click(R.id.btnResetManualRoi)

        assertFalse(activity.findViewById<StudioOverlayView>(R.id.overlayRoi).hasValidRoi)
        assertEquals("", activity.findViewById<TextInputEditText>(R.id.etRoiX).text.toString())
        assertEquals(activity.getString(R.string.roi_hud_canvas_cleared), activity.hud())
    }

    // ── Modes ────────────────────────────────────────────────────────────────

    @Test
    fun `manual mode swaps the tool rows without collapsing the toolbar`() {
        val activity = launch(intent(path = null)).get()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.drawTools).visibility)

        activity.check(R.id.rgEditMode, R.id.rbModeManual)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.manualTools).visibility)
        assertEquals(
            "INVISIBLE keeps the height",
            View.INVISIBLE,
            activity.findViewById<View>(R.id.drawTools).visibility,
        )
        assertEquals(activity.getString(R.string.roi_hud_mode_manual), activity.hud())
    }

    @Test
    fun `erase mode puts the overlay in subtract mode`() {
        val activity = launch(intent(path = null)).get()
        activity.check(R.id.rgCropErase, R.id.rbErase)

        assertTrue(activity.findViewById<StudioOverlayView>(R.id.overlayRoi).isSubtractMode)
        assertEquals(activity.getString(R.string.roi_hud_mode_erase), activity.hud())
    }

    @Test
    fun `the square tool reaches the overlay`() {
        val activity = launch(intent(path = null)).get()
        activity.check(R.id.rgDrawMode, R.id.rbSquare)
        assertEquals(
            StudioOverlayView.RoiMode.SQUARE,
            activity.findViewById<StudioOverlayView>(R.id.overlayRoi).currentMode,
        )
    }

    @Test
    fun `manual, erase and square survive a recreate`() {
        val controller = launch(intent(path = null))
        controller.get().apply {
            check(R.id.rgEditMode, R.id.rbModeManual)
            check(R.id.rgCropErase, R.id.rbErase)
            check(R.id.rgDrawMode, R.id.rbSquare)
        }
        val activity = controller.recreate().get()

        assertEquals(
            R.id.rbModeManual,
            activity.findViewById<MaterialButtonToggleGroup>(R.id.rgEditMode).checkedButtonId,
        )
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.manualTools).visibility)
        assertEquals(R.id.rbErase, activity.findViewById<MaterialButtonToggleGroup>(R.id.rgCropErase).checkedButtonId)
        assertTrue(activity.findViewById<StudioOverlayView>(R.id.overlayRoi).isSubtractMode)
        assertEquals(R.id.rbSquare, activity.findViewById<MaterialButtonToggleGroup>(R.id.rgDrawMode).checkedButtonId)
    }

    private companion object {
        const val IMG_W = 640
        const val IMG_H = 400
    }
}
