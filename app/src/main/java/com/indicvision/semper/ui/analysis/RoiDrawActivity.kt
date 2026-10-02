@file:SuppressLint("SetTextI18n")

package com.indicvision.semper.ui.analysis

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import com.indicvision.semper.data.session.CacheJanitor
import com.indicvision.semper.databinding.ActivityRoiDrawBinding
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.field.Roi
import com.indicvision.semper.field.fromImageRect
import com.indicvision.semper.field.getRoiEdges
import com.indicvision.semper.field.getRoiEditorImageSize
import com.indicvision.semper.field.putRoiEdges
import com.indicvision.semper.field.putRoiExtras
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.ui.analysis.roi.StudioOverlayMaskEncoder
import com.indicvision.semper.ui.analysis.roi.StudioOverlayView
import com.indicvision.semper.ui.analysis.wizard.ReferencePreviewLoader
import com.indicvision.semper.ui.common.Feedback
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.common.hideKeyboard
import com.indicvision.semper.ui.common.onButtonChecked
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
@Suppress("TooManyFunctions") // one small step per toggle, field and button of the editor
class RoiDrawActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoiDrawBinding

    /** The reference's true size; the preview decode may correct the wizard's. */
    private var imageSize = ImageSize.UNKNOWN

    /** True while syncing manual fields from the overlay — skip apply-on-change loops. */
    private var syncingManualFields = false

    /** True from a Save until the editor finishes: the mask is still being written. */
    private var saving = false

    /** True while the Crop/Erase toggle is on Erase. */
    private val erasing: Boolean get() = binding.rgCropErase.checkedButtonId == R.id.rbErase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoiDrawBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Insets.padTop(binding.headerChrome)
        // The dock rises above the keyboard so the typed X/Y/W/H and Apply stay
        // reachable; the canvas shrinks and the overlay remaps the crop onto the
        // smaller image (StudioOverlayView.updateImageBounds).
        Insets.padBottomAboveIme(binding.bottomToolbar)

        setImageSize(intent.getRoiEditorImageSize())
        intent.getStringExtra(DicKeys.IMAGE_FILE_PATH)?.let { loadReference(File(it), savedInstanceState) }

        wireModeToggles()
        if (savedInstanceState != null) {
            restoreModes(savedInstanceState)
        } else {
            setEditMode(false)
            binding.tvHud.text = getString(R.string.roi_hud_zoom_hint)
        }
        wireOverlay()
        wireButtons()
    }

    /**
     * Decodes [file] for the canvas, then hands it to the overlay with the
     * selection [savedInstanceState] kept, if any. A missing file closes the editor.
     */
    private fun loadReference(file: File, savedInstanceState: Bundle?) {
        // Twice the screen, as the beam-edge editor, so a zoomed-in
        // crop edge still lands on visible speckle.
        val screen = resources.displayMetrics
        val maxEdge = PREVIEW_OVERSAMPLE * max(screen.widthPixels, screen.heightPixels)
        val longEdge = max(imageSize.width, imageSize.height).takeIf { it > 0 } ?: maxEdge

        lifecycleScope.launch {
            // The decoded reference: tens of megabytes for a RAW frame.
            val bytes = withContext(Dispatchers.IO) { readReference(file) }
            if (bytes == null) {
                Feedback.toast(this@RoiDrawActivity, R.string.roi_image_not_found)
                finish()
                return@launch
            }
            val loaded = ReferencePreviewLoader.load(
                ReferencePreviewLoader.Request(bytes, imageSize.width, imageSize.height, min(longEdge, maxEdge)),
            )
            setImageSize(ImageSize(loaded.width, loaded.height))
            val bitmap = loaded.bitmap
            if (bitmap == null) {
                Feedback.toast(this@RoiDrawActivity, R.string.roi_decode_failed, long = true)
                return@launch
            }
            binding.imgRoiCanvas.setImageBitmap(bitmap)
            binding.imgRoiCanvas.post {
                binding.overlayRoi.imageView = binding.imgRoiCanvas
                savedInstanceState?.getRoiEdges()?.let { saved ->
                    binding.overlayRoi.restoreRelativeRoi(saved)
                    fillManualFields(binding.overlayRoi.getRelativeRoi())
                }
            }
        }
    }

    /** The reference size, here and on the overlay that maps view px to image px. */
    private fun setImageSize(size: ImageSize) {
        imageSize = size
        binding.overlayRoi.realImageWidth = size.width
        binding.overlayRoi.realImageHeight = size.height
    }

    private fun wireModeToggles() {
        binding.rgEditMode.onButtonChecked { checkedId -> setEditMode(manual = checkedId == R.id.rbModeManual) }

        binding.rgCropErase.onButtonChecked { checkedId ->
            val erase = checkedId == R.id.rbErase
            binding.overlayRoi.isSubtractMode = erase
            binding.tvHud.text = getString(if (erase) R.string.roi_hud_mode_erase else R.string.roi_hud_mode_crop)
            syncManualFieldsForMode()
        }

        binding.rgDrawMode.onButtonChecked { checkedId ->
            binding.overlayRoi.currentMode = when (checkedId) {
                R.id.rbSquare -> StudioOverlayView.RoiMode.SQUARE
                else -> StudioOverlayView.RoiMode.RECTANGLE
            }
            binding.tvHud.text = getString(R.string.roi_hud_mode_switched, binding.overlayRoi.currentMode)
        }
    }

    /** Puts back the draw shape, Crop/Erase and Draw/Manual toggles [onSaveInstanceState] kept. */
    private fun restoreModes(state: Bundle) {
        val modeId = state.getInt(DicKeys.DRAW_MODE, R.id.rbRect)
        binding.rgDrawMode.check(if (modeId == R.id.rbSquare) R.id.rbSquare else R.id.rbRect)
        val erase = state.getBoolean(STATE_ERASE, false)
        binding.rgCropErase.check(if (erase) R.id.rbErase else R.id.rbCrop)
        binding.overlayRoi.isSubtractMode = erase
        val editManual = state.getBoolean(STATE_MANUAL, false)
        binding.rgEditMode.check(if (editManual) R.id.rbModeManual else R.id.rbModeDraw)
        setEditMode(editManual)
    }

    private fun wireOverlay() {
        binding.overlayRoi.onZoomChangedListener = { zoom ->
            binding.tvHud.text = if (zoom > 1f) {
                getString(R.string.roi_hud_zoom, zoom)
            } else {
                getString(R.string.roi_hud_zoom_fit)
            }
        }
        binding.overlayRoi.onRoiChangedListener = ::showSelection
    }

    private fun wireButtons() {
        binding.btnCancelRoi.setOnClickListener { finish() }
        binding.btnResetRoi.setOnClickListener { clearCanvas() }
        binding.btnResetManualRoi.setOnClickListener { clearCanvas() }
        binding.btnFullImageRoi.setOnClickListener { saveFullImageAndFinish() }
        binding.btnApplyManualRoi.setOnClickListener { applyManualFields() }
        binding.btnSaveRoi.setOnClickListener { saveAndFinish() }
    }

    private fun clearCanvas() {
        binding.overlayRoi.reset()
        clearManualFields()
        binding.tvHud.text = getString(R.string.roi_hud_canvas_cleared)
    }

    /**
     * The HUD and manual fields after the overlay reports [roi] (image px): the
     * last hole's size in erase mode, else the crop's; a prompt when there is none.
     */
    private fun showSelection(roi: RectF) {
        val shown = if (erasing) binding.overlayRoi.lastHoleRelative() else roi
        if (shown.width() > 0 && shown.height() > 0) {
            binding.tvHud.text = getString(
                R.string.roi_hud_dimensions,
                shown.width().roundToInt(),
                shown.height().roundToInt(),
                shown.left.roundToInt(),
                shown.top.roundToInt(),
            )
            fillManualFields(shown)
        } else if (erasing) {
            binding.tvHud.text = getString(R.string.roi_hud_mode_erase)
        } else {
            binding.tvHud.text = getString(R.string.roi_hud_select_tool)
            if (!syncingManualFields) clearManualFields()
        }
    }

    private fun setEditMode(manual: Boolean) {
        // INVISIBLE (not GONE) keeps bottomToolbar height stable so the
        // fitCenter image does not jump when switching Draw ↔ Manual.
        binding.drawTools.visibility = if (manual) View.INVISIBLE else View.VISIBLE
        binding.manualTools.visibility = if (manual) View.VISIBLE else View.INVISIBLE
        if (!manual) hideSoftKeyboard()
        // Crop/Erase stays visible and keeps its selection in both modes.
        val erase = erasing
        binding.overlayRoi.isSubtractMode = erase
        if (manual) syncManualFieldsForMode()
        @StringRes val cropHud = if (manual) R.string.roi_hud_mode_manual else R.string.roi_hud_mode_crop
        binding.tvHud.text = getString(if (erase) R.string.roi_hud_mode_erase else cropHud)
    }

    private fun hideSoftKeyboard() {
        val focus = currentFocus ?: return
        focus.hideKeyboard()
        focus.clearFocus()
    }

    /** Prefill manual fields from the main crop or the last erase rect. */
    private fun syncManualFieldsForMode() {
        val rect = if (erasing) binding.overlayRoi.lastHoleRelative() else binding.overlayRoi.getRelativeRoi()
        if (rect.width() > 0f && rect.height() > 0f) fillManualFields(rect) else clearManualFields()
    }

    private fun fillManualFields(roi: RectF) {
        if (roi.width() <= 0f || roi.height() <= 0f) return
        syncingManualFields = true
        binding.etRoiX.setText(roi.left.roundToInt().toString())
        binding.etRoiY.setText(roi.top.roundToInt().toString())
        binding.etRoiW.setText(roi.width().roundToInt().toString())
        binding.etRoiH.setText(roi.height().roundToInt().toString())
        syncingManualFields = false
    }

    private fun clearManualFields() {
        syncingManualFields = true
        binding.etRoiX.text = null
        binding.etRoiY.text = null
        binding.etRoiW.text = null
        binding.etRoiH.text = null
        syncingManualFields = false
    }

    /** The typed X, Y, W, H in image px, or null unless all four are whole numbers and W, H are positive. */
    private fun typedRoi(): Roi? {
        fun typed(field: EditText): Int? = field.text?.toString()?.toIntOrNull()
        val x = typed(binding.etRoiX)
        val y = typed(binding.etRoiY)
        val w = typed(binding.etRoiW)
        val h = typed(binding.etRoiH)
        if (x == null || y == null) return null
        return if (w != null && h != null) Roi(x, y, w, h).takeIf { w > 0 && h > 0 } else null
    }

    private fun applyManualFields() {
        val roi = typedRoi()
        val ok = when {
            roi == null -> false
            erasing -> binding.overlayRoi.applyImageHole(roi.x, roi.y, roi.w, roi.h)
            else -> binding.overlayRoi.applyImageRoi(roi.x, roi.y, roi.w, roi.h)
        }
        if (!ok) Feedback.toast(this, R.string.roi_invalid_size)
    }

    private fun saveFullImageAndFinish() {
        binding.overlayRoi.reset()
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
        val overlay = binding.overlayRoi
        val nothingDrawn = !overlay.hasValidRoi && overlay.holes.isEmpty()
        val roi = if (overlay.hasValidRoi) {
            Roi.fromImageRect(overlay.getRelativeRoi(), imageSize)
        } else {
            Roi.full(imageSize)
        }
        if (!nothingDrawn && (roi.w <= 0 || roi.h <= 0)) {
            Feedback.toast(this, R.string.roi_invalid_size)
            return
        }
        // Copied here, on Main: the overlay's rects keep changing under touch.
        val maskInput = if (nothingDrawn) null else overlay.maskInput()
        if (nothingDrawn) Feedback.toast(this, R.string.roi_full_image_selected)
        val pixels = imageSize.width * imageSize.height

        saving = true
        val maskFile = File(cacheDir, CacheJanitor.ROI_MASK_CACHE)
        lifecycleScope.launch {
            val maskBytes = withContext(Dispatchers.Default) {
                if (maskInput == null) fullMask(pixels) else StudioOverlayMaskEncoder.encode(maskInput)
            }
            val written = withContext(Dispatchers.IO) { writeMask(maskFile, maskBytes) }
            if (!written) {
                saving = false
                Feedback.toast(this@RoiDrawActivity, R.string.failed_save_temp_file)
                return@launch
            }

            val resultIntent = Intent()
                .putRoiExtras(roi)
                .putExtra(DicKeys.MASK_FILE_PATH, maskFile.absolutePath)
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(DicKeys.DRAW_MODE, binding.rgDrawMode.checkedButtonId)
        outState.putBoolean(STATE_MANUAL, binding.rgEditMode.checkedButtonId == R.id.rbModeManual)
        outState.putBoolean(STATE_ERASE, erasing)

        if (binding.overlayRoi.hasValidRoi) {
            outState.putRoiEdges(binding.overlayRoi.getRelativeRoi())
        }
    }

    private companion object {
        const val STATE_MANUAL = "roi_edit_manual"
        const val STATE_ERASE = "roi_edit_erase"
        const val PREVIEW_OVERSAMPLE = 2
    }
}

/** A mask that correlates every one of [pixels]. */
private fun fullMask(pixels: Int): ByteArray = ByteArray(pixels) { FULL_MASK }

/** A mask byte that marks its pixel as correlated. */
private const val FULL_MASK: Byte = 255.toByte()

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
