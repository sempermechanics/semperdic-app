package com.sempermechanics.semper.ui.analysis.sweep

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputLayout
import com.sempermechanics.semper.R
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.data.session.originalNameOr
import com.sempermechanics.semper.databinding.DialogSweepFramePickBinding
import com.sempermechanics.semper.databinding.SweepFrameOverlayBinding
import com.sempermechanics.semper.imaging.BitmapDecoder
import com.sempermechanics.semper.imaging.RawRgba
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.common.SerialJob
import com.sempermechanics.semper.ui.common.commitOnDone
import com.sempermechanics.semper.ui.common.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * "Frame to sweep" on the sweep setup page: a field naming the picked frame,
 * "Frame 23 of 40" under it, and a thumbnail beside it. A tap on the field
 * opens the frame dialog -- a scrolling list of the frames, a typed frame
 * number, and a preview of the one picked -- whose OK hands the index to
 * [onPicked]. A tap on the thumbnail shows the frame large over the dimmed
 * page; a tap anywhere closes it. The block hides for a sequence of one frame.
 */
internal class SweepFramePicker(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val onPicked: (Int) -> Unit,
) {
    /** The thumbnail decode; a new pick cancels it. */
    private val thumbDecode = SerialJob()

    /** The dialog's preview decode; a new pick in the dialog or closing it cancels it. */
    private val dialogDecode = SerialJob()

    private var block: View? = null
    private lateinit var field: TextInputLayout
    private lateinit var name: EditText
    private lateinit var thumb: ImageView

    /** The frame the field shows; the dialog opens on it. */
    private var picked = 0

    /** The frame the thumbnail shows or is decoding. */
    private var thumbPath: String? = null

    /** A frame's file name, or "Frame n" when it has none (or a restored draft padded it blank). */
    fun frameLabel(index: Int): String = viewModel.defOriginalNames
        .originalNameOr(index, activity.getString(R.string.sweep_frame_btn_fmt, index + 1))
        .substringAfterLast('/')

    /** Finds the views; call once the settings page is inflated. */
    fun bind() {
        block = activity.findViewById(R.id.sweepFrameBlock)
        field = activity.findViewById(R.id.tilSweepFrame)
        name = activity.findViewById(R.id.ddSweepFrame)
        thumb = activity.findViewById(R.id.imgSweepFrame)
        name.setOnClickListener { openDialog(picked) }
        field.setEndIconOnClickListener { openDialog(picked) }
        thumb.setOnClickListener { showOverlay() }
    }

    /** Shows frame [index] as the one picked. */
    fun show(index: Int) {
        val block = block ?: return
        val count = viewModel.defCount
        block.isVisible = count > 1
        if (count <= 1) {
            thumbDecode.cancel()
            thumbPath = null
            return
        }
        picked = index.coerceIn(0, count - 1)
        name.setText(frameLabel(picked))
        field.helperText = activity.getString(R.string.sweep_frame_position_fmt, picked + 1, count)
        showThumb(viewModel.defFilePaths.getOrNull(picked))
    }

    /**
     * The picked frame large over the darkened page, with its
     * name and position under it. A tap anywhere, or Back, closes it.
     */
    private fun showOverlay() {
        val image = thumb.drawable ?: return
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content = SweepFrameOverlayBinding.inflate(LayoutInflater.from(activity))
        content.imgSweepFrameOverlay.setImageDrawable(image)
        content.imgSweepFrameOverlay.contentDescription = name.text
        content.tvSweepFrameOverlay.text = activity.getString(
            R.string.sweep_frame_overlay_caption_fmt,
            name.text,
            field.helperText,
        )
        content.root.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(content.root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            // The layout's own scrim darkens the page; the window adds none.
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
    }

    /** Decodes the frame at [path] into the thumbnail; a result for a frame no longer picked is dropped. */
    private fun showThumb(path: String?) {
        if (path == thumbPath) return
        thumbPath = path
        thumbDecode.cancel()
        thumb.setImageDrawable(null)
        if (path.isNullOrBlank()) return
        thumbDecode.launch(activity.lifecycleScope) {
            val bmp = decodeFramePreview(path, viewModel.defFrameSizes[path])
            if (path != thumbPath) {
                bmp?.recycle()
                return@launch
            }
            thumb.setImageBitmap(bmp)
        }
    }

    /** Opens the frame dialog on frame [initial]; does nothing for a sequence of one frame. */
    private fun openDialog(initial: Int) {
        val count = viewModel.defCount
        if (count <= 1) return
        var selected = initial.coerceIn(0, count - 1)
        val builder = MaterialAlertDialogBuilder(activity)
        // Inflate against the builder's context so the rows pick up the dialog
        // theme overlay rather than the activity's.
        val content = DialogSweepFramePickBinding.inflate(LayoutInflater.from(builder.context))
        val numberField = content.etSweepFrameNumber
        content.tvSweepFrameTotal.text = activity.getString(R.string.sweep_frame_out_of_fmt, count)

        bindDialogPreview(content, selected) { selected }
        val rows = fillFrameChoices(content, count, selected) { which ->
            selected = which
            numberField.setText(frameNumberText(which + 1))
            bindDialogPreview(content, which) { selected }
        }
        numberField.setText(frameNumberText(selected + 1))
        wireFrameNumberField(numberField, current = { selected + 1 }) { typed ->
            val index = (typed - 1).coerceIn(0, count - 1)
            rows.getOrNull(index)?.let { row ->
                row.isChecked = true
                scrollFrameRowIntoView(content, row)
            }
            // Also normalises what was typed -- "007" or an out-of-range number.
            numberField.setText(frameNumberText(index + 1))
        }

        builder
            .setTitle(R.string.sweep_frame)
            .setView(content.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                dialogDecode.cancel()
                onPicked(selected)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> dialogDecode.cancel() }
            .setOnDismissListener { dialogDecode.cancel() }
            .show()
    }

    /**
     * Decodes frame [index] into the dialog's preview; a result that arrives
     * after [selected] has moved on is dropped.
     */
    private fun bindDialogPreview(content: DialogSweepFramePickBinding, index: Int, selected: () -> Int) {
        val preview = content.imgSweepFramePreview
        val progress = content.progressSweepFramePreview
        val path = viewModel.defFilePaths.getOrNull(index)
        dialogDecode.cancel()
        if (path.isNullOrBlank()) {
            preview.setImageDrawable(null)
            progress.isVisible = false
            return
        }
        progress.isVisible = true
        dialogDecode.launch(activity.lifecycleScope) {
            val bmp = decodeFramePreview(path, viewModel.defFrameSizes[path])
            if (index != selected()) {
                bmp?.recycle()
                return@launch
            }
            progress.isVisible = false
            if (bmp != null) {
                preview.setImageBitmap(bmp)
            } else {
                preview.setImageDrawable(null)
            }
        }
    }

    /**
     * Fills the dialog's scrolling frame list and reports the picked index.
     * The row for [selected] is scrolled into view, so reopening the dialog on
     * frame 30 of 50 does not land the user at the top of the list.
     */
    private fun fillFrameChoices(
        content: DialogSweepFramePickBinding,
        count: Int,
        selected: Int,
        onPick: (Int) -> Unit,
    ): List<MaterialRadioButton> {
        val group = content.rgSweepFrames
        val rowPadding = group.dp(FRAME_ROW_PADDING_DP).toInt()
        val rows = List(count) { index ->
            MaterialRadioButton(group.context).apply {
                id = View.generateViewId()
                text = frameLabel(index)
                tag = index
                minimumHeight = group.dp(FRAME_ROW_MIN_HEIGHT_DP).toInt()
                setPadding(paddingLeft, rowPadding, paddingRight, rowPadding)
                group.addView(this)
                isChecked = index == selected
            }
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val index = group.findViewById<View>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
            onPick(index)
        }
        rows.getOrNull(selected)?.let { scrollFrameRowIntoView(content, it) }
        return rows
    }

    /**
     * Frame number entry: commits on focus loss (IME Done just drops focus, as
     * the sweep fields do). Anything unparseable reverts to [current].
     */
    private fun wireFrameNumberField(field: EditText, current: () -> Int, onPick: (Int) -> Unit) {
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val typed = field.text.toString().trim().toIntOrNull()
            if (typed == null) field.setText(frameNumberText(current())) else onPick(typed)
        }
        field.commitOnDone()
    }

    private companion object {
        /** Longest edge of a decoded frame: enough for the overlay and the dialog preview. */
        const val PREVIEW_MAX_EDGE = 1080

        /** A frame row in the pick dialog: vertical padding and touch-target height. */
        const val FRAME_ROW_PADDING_DP = 8f
        const val FRAME_ROW_MIN_HEIGHT_DP = 48f

        /** ASCII digits, so the field round-trips through toIntOrNull() in any locale. */
        fun frameNumberText(oneBased: Int): String = String.format(Locale.US, "%d", oneBased)

        fun scrollFrameRowIntoView(content: DialogSweepFramePickBinding, row: View) {
            val scroll = content.scrollSweepFrames
            scroll.post { scroll.scrollTo(0, row.top) }
        }

        /**
         * Decode a deformed-frame path for the thumbnail and the dialog. Handles
         * ordinary containers, native-only formats (TIFF), and RAW RGBA blobs
         * written at import ([size] is the frame's measured size, for those).
         *
         * Each rung runs where it belongs: file reads on IO, the native decoder on
         * [SemperNativeLib.nativeDispatcher] (every JNI call is pinned there), the
         * JVM decodes on Default.
         */
        @Suppress("ReturnCount") // a ladder of decoders; each rung returns what it managed
        suspend fun decodeFramePreview(path: String, size: Pair<Int, Int>?): Bitmap? {
            withContext(Dispatchers.IO) {
                BitmapDecoder.decodeFileForView(path, PREVIEW_MAX_EDGE, PREVIEW_MAX_EDGE, PREVIEW_MAX_EDGE)
            }?.let { return it }

            val bytes = withContext(Dispatchers.IO) {
                File(path).takeIf(File::exists)?.let { runCatching { it.readBytes() }.getOrNull() }
            } ?: return null

            withContext(SemperNativeLib.nativeDispatcher) {
                runCatching { SemperNativeLib.getPreviewFromBytes(bytes, PREVIEW_MAX_EDGE) }.getOrNull()
            }?.let { return it }

            return withContext(Dispatchers.Default) {
                decodeRawRgba(bytes, size)
                    // Last resort: bounds-free BitmapFactory (may still fail for RAW).
                    ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }

        /** A RAW RGBA blob written at import, sampled down to preview size. */
        fun decodeRawRgba(bytes: ByteArray, size: Pair<Int, Int>?): Bitmap? {
            val (w, h) = size ?: return null
            return RawRgba.preview(bytes, w, h, PREVIEW_MAX_EDGE)
        }
    }
}
