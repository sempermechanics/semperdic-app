// ROI drawing/editing: dense gesture hit-testing and canvas math read clearest
// as cohesive methods, so the structural rules are suppressed for this file.
@file:Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod")

@file:SuppressLint("SetTextI18n")

package com.indicvision.semper.ui.analysis

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.data.CacheJanitor
import com.indicvision.semper.ui.common.Insets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen region-of-interest editor: draw or type a rectangular crop over
 * the reference image; optional erase punches exclude regions from the mask.
 */
@MainThread
class RoiDrawActivity : AppCompatActivity() {

    private lateinit var imgRoiCanvas: ImageView
    private lateinit var overlayRoi: StudioOverlayView
    private lateinit var tvHud: TextView
    private lateinit var rgEditMode: MaterialButtonToggleGroup
    private lateinit var rgCropErase: MaterialButtonToggleGroup
    private lateinit var rgDrawMode: MaterialButtonToggleGroup
    private lateinit var drawTools: LinearLayout
    private lateinit var manualTools: LinearLayout
    private lateinit var btnSaveRoi: MaterialButton
    private lateinit var btnCancelRoi: MaterialButton
    private lateinit var btnResetRoi: MaterialButton
    private lateinit var btnResetManualRoi: MaterialButton
    private lateinit var btnFullImageRoi: Chip
    private lateinit var btnApplyManualRoi: MaterialButton
    private lateinit var etRoiX: TextInputEditText
    private lateinit var etRoiY: TextInputEditText
    private lateinit var etRoiW: TextInputEditText
    private lateinit var etRoiH: TextInputEditText

    private var realImageWidth = 0
    private var realImageHeight = 0

    /** True while syncing manual fields from the overlay — skip apply-on-change loops. */
    private var syncingManualFields = false

    /** True from a Save until the editor finishes: the mask is still being written. */
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_roi_draw)

        imgRoiCanvas = findViewById(R.id.imgRoiCanvas)
        overlayRoi = findViewById(R.id.overlayRoi)
        tvHud = findViewById(R.id.tvHud)
        rgEditMode = findViewById(R.id.rgEditMode)
        rgCropErase = findViewById(R.id.rgCropErase)
        rgDrawMode = findViewById(R.id.rgDrawMode)
        drawTools = findViewById(R.id.drawTools)
        manualTools = findViewById(R.id.manualTools)
        btnSaveRoi = findViewById(R.id.btnSaveRoi)
        btnCancelRoi = findViewById(R.id.btnCancelRoi)
        btnResetRoi = findViewById(R.id.btnResetRoi)
        btnResetManualRoi = findViewById(R.id.btnResetManualRoi)
        btnFullImageRoi = findViewById(R.id.btnFullImageRoi)
        btnApplyManualRoi = findViewById(R.id.btnApplyManualRoi)
        etRoiX = findViewById(R.id.etRoiX)
        etRoiY = findViewById(R.id.etRoiY)
        etRoiW = findViewById(R.id.etRoiW)
        etRoiH = findViewById(R.id.etRoiH)

        Insets.padTop(findViewById(R.id.headerChrome))
        // The dock rises above the keyboard so the typed X/Y/W/H and Apply stay
        // reachable; the canvas shrinks and the overlay remaps the crop onto the
        // smaller image (StudioOverlayView.updateImageBounds).
        Insets.padBottomAboveIme(findViewById(R.id.bottomToolbar))

        val imageFilePath = intent.getStringExtra(DicKeys.IMAGE_FILE_PATH)
        realImageWidth = intent.getIntExtra(DicKeys.IMAGE_WIDTH, 0)
        realImageHeight = intent.getIntExtra(DicKeys.IMAGE_HEIGHT, 0)

        overlayRoi.realImageWidth = realImageWidth
        overlayRoi.realImageHeight = realImageHeight

        if (imageFilePath != null) {
            // Twice the screen, as the beam-edge editor, so a zoomed-in
            // crop edge still lands on visible speckle.
            val screen = resources.displayMetrics
            val maxEdge = PREVIEW_OVERSAMPLE * max(screen.widthPixels, screen.heightPixels)
            val longEdge = max(realImageWidth, realImageHeight).takeIf { it > 0 } ?: maxEdge

            lifecycleScope.launch {
                // The decoded reference: tens of megabytes for a RAW frame.
                val bytes = withContext(Dispatchers.IO) { readReference(File(imageFilePath)) }
                if (bytes == null) {
                    Toast.makeText(this@RoiDrawActivity, R.string.roi_image_not_found, Toast.LENGTH_SHORT).show()
                    finish()
                    return@launch
                }
                val loaded = ReferencePreviewLoader.load(
                    ReferencePreviewLoader.Request(bytes, realImageWidth, realImageHeight, min(longEdge, maxEdge)),
                )
                realImageWidth = loaded.width
                realImageHeight = loaded.height
                overlayRoi.realImageWidth = realImageWidth
                overlayRoi.realImageHeight = realImageHeight
                val bitmap = loaded.bitmap

                if (bitmap == null) {
                    Toast.makeText(this@RoiDrawActivity, R.string.roi_decode_failed, Toast.LENGTH_LONG).show()
                } else {
                    imgRoiCanvas.setImageBitmap(bitmap)
                    imgRoiCanvas.post {
                        overlayRoi.imageView = imgRoiCanvas

                        if (savedInstanceState != null) {
                            val left = savedInstanceState.getFloat(DicKeys.ROI_L, -1f)
                            if (left != -1f) {
                                val top = savedInstanceState.getFloat(DicKeys.ROI_T)
                                val right = savedInstanceState.getFloat(DicKeys.ROI_R)
                                val bottom = savedInstanceState.getFloat(DicKeys.ROI_B)
                                overlayRoi.restoreRelativeRoi(RectF(left, top, right, bottom))
                                fillManualFields(overlayRoi.getRelativeRoi())
                            }
                        }
                    }
                }
            }
        }

        rgEditMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val manual = checkedId == R.id.rbModeManual
            setEditMode(manual)
        }

        rgCropErase.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val erase = checkedId == R.id.rbErase
            overlayRoi.isSubtractMode = erase
            tvHud.text = getString(
                if (erase) R.string.roi_hud_mode_erase else R.string.roi_hud_mode_crop,
            )
            syncManualFieldsForMode()
        }

        rgDrawMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            overlayRoi.currentMode = when (checkedId) {
                R.id.rbSquare -> StudioOverlayView.RoiMode.SQUARE
                else -> StudioOverlayView.RoiMode.RECTANGLE
            }
            tvHud.text = getString(R.string.roi_hud_mode_switched, overlayRoi.currentMode)
        }

        if (savedInstanceState != null) {
            val modeId = savedInstanceState.getInt(DicKeys.DRAW_MODE, R.id.rbRect)
            rgDrawMode.check(if (modeId == R.id.rbSquare) R.id.rbSquare else R.id.rbRect)
            val erase = savedInstanceState.getBoolean(STATE_ERASE, false)
            rgCropErase.check(if (erase) R.id.rbErase else R.id.rbCrop)
            overlayRoi.isSubtractMode = erase
            val editManual = savedInstanceState.getBoolean(STATE_MANUAL, false)
            rgEditMode.check(if (editManual) R.id.rbModeManual else R.id.rbModeDraw)
            setEditMode(editManual)
        } else {
            setEditMode(false)
            tvHud.text = getString(R.string.roi_hud_zoom_hint)
        }

        overlayRoi.onZoomChangedListener = { zoom ->
            tvHud.text = if (zoom > 1f) {
                getString(R.string.roi_hud_zoom, zoom)
            } else {
                getString(R.string.roi_hud_zoom_fit)
            }
        }

        btnCancelRoi.setOnClickListener { finish() }

        val clearCanvas = {
            overlayRoi.reset()
            clearManualFields()
            tvHud.text = getString(R.string.roi_hud_canvas_cleared)
        }
        btnResetRoi.setOnClickListener { clearCanvas() }
        btnResetManualRoi.setOnClickListener { clearCanvas() }

        btnFullImageRoi.setOnClickListener { saveFullImageAndFinish() }

        btnApplyManualRoi.setOnClickListener { applyManualFields() }

        overlayRoi.onRoiChangedListener = { roi ->
            if (rgCropErase.checkedButtonId == R.id.rbErase) {
                val hole = overlayRoi.lastHoleRelative()
                if (hole.width() > 0 && hole.height() > 0) {
                    tvHud.text = getString(
                        R.string.roi_hud_dimensions,
                        hole.width().roundToInt(),
                        hole.height().roundToInt(),
                        hole.left.roundToInt(),
                        hole.top.roundToInt(),
                    )
                    fillManualFields(hole)
                } else {
                    tvHud.text = getString(R.string.roi_hud_mode_erase)
                }
            } else if (roi.width() > 0 && roi.height() > 0) {
                tvHud.text = getString(
                    R.string.roi_hud_dimensions,
                    roi.width().roundToInt(),
                    roi.height().roundToInt(),
                    roi.left.roundToInt(),
                    roi.top.roundToInt(),
                )
                fillManualFields(roi)
            } else {
                tvHud.text = getString(R.string.roi_hud_select_tool)
                if (!syncingManualFields) clearManualFields()
            }
        }

        btnSaveRoi.setOnClickListener { saveAndFinish() }
    }

    private fun setEditMode(manual: Boolean) {
        // INVISIBLE (not GONE) keeps bottomToolbar height stable so the
        // fitCenter image does not jump when switching Draw ↔ Manual.
        drawTools.visibility = if (manual) View.INVISIBLE else View.VISIBLE
        manualTools.visibility = if (manual) View.VISIBLE else View.INVISIBLE
        if (!manual) {
            hideSoftKeyboard()
        }
        // Crop/Erase stays visible and keeps its selection in both modes.
        val erase = rgCropErase.checkedButtonId == R.id.rbErase
        overlayRoi.isSubtractMode = erase
        if (manual) {
            syncManualFieldsForMode()
            tvHud.text = getString(
                if (erase) R.string.roi_hud_mode_erase else R.string.roi_hud_mode_manual,
            )
        } else {
            tvHud.text = getString(
                if (erase) R.string.roi_hud_mode_erase else R.string.roi_hud_mode_crop,
            )
        }
    }

    private fun hideSoftKeyboard() {
        val focus = currentFocus ?: return
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(focus.windowToken, 0)
        focus.clearFocus()
    }

    /** Prefill manual fields from the main crop or the last erase rect. */
    private fun syncManualFieldsForMode() {
        if (rgCropErase.checkedButtonId == R.id.rbErase) {
            val hole = overlayRoi.lastHoleRelative()
            if (hole.width() > 0f && hole.height() > 0f) {
                fillManualFields(hole)
            } else {
                clearManualFields()
            }
        } else {
            val roi = overlayRoi.getRelativeRoi()
            if (roi.width() > 0f && roi.height() > 0f) {
                fillManualFields(roi)
            } else {
                clearManualFields()
            }
        }
    }

    private fun fillManualFields(roi: RectF) {
        if (roi.width() <= 0f || roi.height() <= 0f) return
        syncingManualFields = true
        etRoiX.setText(roi.left.roundToInt().toString())
        etRoiY.setText(roi.top.roundToInt().toString())
        etRoiW.setText(roi.width().roundToInt().toString())
        etRoiH.setText(roi.height().roundToInt().toString())
        syncingManualFields = false
    }

    private fun clearManualFields() {
        syncingManualFields = true
        etRoiX.text = null
        etRoiY.text = null
        etRoiW.text = null
        etRoiH.text = null
        syncingManualFields = false
    }

    private fun applyManualFields() {
        val x = etRoiX.text?.toString()?.toIntOrNull()
        val y = etRoiY.text?.toString()?.toIntOrNull()
        val w = etRoiW.text?.toString()?.toIntOrNull()
        val h = etRoiH.text?.toString()?.toIntOrNull()
        if (x == null || y == null || w == null || h == null || w <= 0 || h <= 0) {
            Toast.makeText(this, R.string.roi_invalid_size, Toast.LENGTH_SHORT).show()
            return
        }
        val erase = rgCropErase.checkedButtonId == R.id.rbErase
        val ok = if (erase) {
            overlayRoi.applyImageHole(x, y, w, h)
        } else {
            overlayRoi.applyImageRoi(x, y, w, h)
        }
        if (!ok) {
            Toast.makeText(this, R.string.roi_invalid_size, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveFullImageAndFinish() {
        overlayRoi.reset()
        clearManualFields()
        saveAndFinish()
    }

    /**
     * Returns the ROI and its mask to the wizard. The rect is read here, on
     * Main; the mask (one byte per reference pixel, tens of megabytes on a
     * modern sensor) is built on Default and written on IO, so Save no longer
     * freezes the editor for the length of both. Further taps are ignored
     * until it is done.
     */
    private fun saveAndFinish() {
        if (saving) return
        val rect: Rect
        val buildMask: () -> ByteArray

        if (!overlayRoi.hasValidRoi && overlayRoi.holes.isEmpty()) {
            rect = Rect(0, 0, realImageWidth, realImageHeight)
            val pixels = realImageWidth * realImageHeight
            buildMask = { ByteArray(pixels) { 255.toByte() } }
            Toast.makeText(this, R.string.roi_full_image_selected, Toast.LENGTH_SHORT).show()
        } else {
            rect = if (overlayRoi.hasValidRoi) {
                roiPixels(overlayRoi.getRelativeRoi(), realImageWidth, realImageHeight)
            } else {
                Rect(0, 0, realImageWidth, realImageHeight)
            }

            if (rect.width() <= 0 || rect.height() <= 0) {
                Toast.makeText(this, R.string.roi_invalid_size, Toast.LENGTH_SHORT).show()
                return
            }
            val input = overlayRoi.maskInput()
            buildMask = { StudioOverlayMaskEncoder.encode(input) }
        }

        saving = true
        val maskFile = File(cacheDir, CacheJanitor.ROI_MASK_CACHE)
        lifecycleScope.launch {
            val maskBytes = withContext(Dispatchers.Default) { buildMask() }
            val written = withContext(Dispatchers.IO) { writeMask(maskFile, maskBytes) }
            if (!written) {
                saving = false
                Toast.makeText(this@RoiDrawActivity, R.string.failed_save_temp_file, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val resultIntent = Intent()
            resultIntent.putExtra(DicKeys.ROI_X, rect.left)
            resultIntent.putExtra(DicKeys.ROI_Y, rect.top)
            resultIntent.putExtra(DicKeys.ROI_W, rect.width())
            resultIntent.putExtra(DicKeys.ROI_H, rect.height())
            resultIntent.putExtra(DicKeys.MASK_FILE_PATH, maskFile.absolutePath)

            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(DicKeys.DRAW_MODE, rgDrawMode.checkedButtonId)
        outState.putBoolean(STATE_MANUAL, rgEditMode.checkedButtonId == R.id.rbModeManual)
        outState.putBoolean(STATE_ERASE, rgCropErase.checkedButtonId == R.id.rbErase)

        if (overlayRoi.hasValidRoi) {
            val relativeRoi = overlayRoi.getRelativeRoi()
            outState.putFloat(DicKeys.ROI_L, relativeRoi.left)
            outState.putFloat(DicKeys.ROI_T, relativeRoi.top)
            outState.putFloat(DicKeys.ROI_R, relativeRoi.right)
            outState.putFloat(DicKeys.ROI_B, relativeRoi.bottom)
        }
    }

    private companion object {
        const val STATE_MANUAL = "roi_edit_manual"
        const val STATE_ERASE = "roi_edit_erase"
        const val PREVIEW_OVERSAMPLE = 2
    }
}

/**
 * The saved ROI in whole image pixels, clipped to the image. Edges are rounded,
 * as the HUD rounds them: the overlay keeps the ROI in view pixels, so a typed
 * 1000 comes back from the round trip as 999.9997, and truncating it handed the
 * engine 999.
 */
internal fun roiPixels(roi: RectF, imageWidth: Int, imageHeight: Int): Rect {
    val x = roi.left.roundToInt().coerceAtLeast(0)
    val y = roi.top.roundToInt().coerceAtLeast(0)
    val right = roi.right.roundToInt().coerceAtMost(imageWidth)
    val bottom = roi.bottom.roundToInt().coerceAtMost(imageHeight)
    return Rect(x, y, right, bottom)
}

/** The reference the wizard staged for the ROI editor, or null when it is gone. */
@WorkerThread
private fun readReference(file: File): ByteArray? = try {
    file.takeIf(File::exists)?.readBytes()
} catch (e: IOException) {
    Timber.e(e, "Could not read the ROI reference")
    null
}

/** Writes the ROI mask the wizard reads back; false when it could not. */
@WorkerThread
private fun writeMask(file: File, bytes: ByteArray): Boolean = try {
    FileOutputStream(file).use { it.write(bytes) }
    true
} catch (e: IOException) {
    Timber.e(e, "Could not write the ROI mask")
    false
}
