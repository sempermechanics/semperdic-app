package com.indicvision.semper.ui.analysis.sweep

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.indicvision.semper.R
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.data.session.originalNameOr
import com.indicvision.semper.databinding.DialogSweepFramePickBinding
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.imaging.RawRgba
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.common.SerialJob
import com.indicvision.semper.ui.common.commitOnDone
import com.indicvision.semper.ui.common.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The dialog that picks which deformed frame a sweep solves: a scrolling list
 * of the frames, a typed frame number, and a preview of the one picked.
 * OK hands the picked index to [onPicked].
 */
internal class SweepFramePicker(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val onPicked: (Int) -> Unit,
) {
    /** The frame-pick preview decode; a new pick or closing the dialog cancels it. */
    private val framePreview = SerialJob()

    /** A frame's file name, or "Frame n" when it has none (or a restored draft padded it blank). */
    fun frameLabel(index: Int): String = viewModel.defOriginalNames
        .originalNameOr(index, activity.getString(R.string.sweep_frame_btn_fmt, index + 1))
        .substringAfterLast('/')

    /** Opens the dialog on frame [initial]; does nothing for a sequence of one frame. */
    fun show(initial: Int) {
        val count = viewModel.defCount
        if (count <= 1) return
        var selected = initial.coerceIn(0, count - 1)
        val builder = MaterialAlertDialogBuilder(activity)
        // Inflate against the builder's context so the rows pick up the dialog
        // theme overlay rather than the activity's.
        val content = DialogSweepFramePickBinding.inflate(LayoutInflater.from(builder.context))
        val numberField = content.etSweepFrameNumber
        content.tvSweepFrameTotal.text = activity.getString(R.string.sweep_frame_out_of_fmt, count)

        bindPreview(content, selected) { selected }
        val rows = fillFrameChoices(content, count, selected) { which ->
            selected = which
            numberField.setText(frameNumberText(which + 1))
            bindPreview(content, which) { selected }
        }
        numberField.setText(frameNumberText(selected + 1))
        wireFrameNumberField(numberField, current = { selected + 1 }) { typed ->
            val index = (typed - 1).coerceIn(0, count - 1)
            rows.getOrNull(index)?.let { row ->
                row.isChecked = true
                scrollFrameRowIntoView(content, row)
            }
            // Also normalises what was typed — "007" or an out-of-range number.
            numberField.setText(frameNumberText(index + 1))
        }

        builder
            .setTitle(R.string.sweep_frame)
            .setView(content.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                framePreview.cancel()
                onPicked(selected)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> framePreview.cancel() }
            .setOnDismissListener { framePreview.cancel() }
            .show()
    }

    /**
     * Decodes frame [index] into the dialog's preview; a result that arrives
     * after [selected] has moved on is dropped.
     */
    private fun bindPreview(content: DialogSweepFramePickBinding, index: Int, selected: () -> Int) {
        val preview = content.ivSweepFrameDialogPreview
        val progress = content.progressSweepFramePreview
        val path = viewModel.defFilePaths.getOrNull(index)
        framePreview.cancel()
        if (path.isNullOrBlank()) {
            preview.setImageDrawable(null)
            progress.isVisible = false
            return
        }
        progress.isVisible = true
        framePreview.launch(activity.lifecycleScope) {
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

    /** ASCII digits, so the field round-trips through toIntOrNull() in any locale. */
    private fun frameNumberText(oneBased: Int): String = String.format(Locale.US, "%d", oneBased)

    private fun scrollFrameRowIntoView(content: DialogSweepFramePickBinding, row: View) {
        val scroll = content.scrollSweepFrames
        scroll.post { scroll.scrollTo(0, row.top) }
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

    /**
     * Decode a deformed-frame path for the pick dialog. Handles ordinary
     * containers, native-only formats (TIFF), and RAW RGBA blobs written at import
     * ([size] is the frame's measured size, for those).
     *
     * Each rung runs where it belongs: file reads on IO, the native decoder on
     * [SemperNativeLib.nativeDispatcher] (every JNI call is pinned there), the
     * JVM decodes on Default.
     */
    @Suppress("ReturnCount") // a ladder of decoders; each rung returns what it managed
    private suspend fun decodeFramePreview(path: String, size: Pair<Int, Int>?): Bitmap? {
        withContext(Dispatchers.IO) {
            BitmapDecode.decodeFileForView(path, PREVIEW_MAX_EDGE, PREVIEW_MAX_EDGE, PREVIEW_MAX_EDGE)
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
    private fun decodeRawRgba(bytes: ByteArray, size: Pair<Int, Int>?): Bitmap? {
        val (w, h) = size ?: return null
        return RawRgba.preview(bytes, w, h, PREVIEW_MAX_EDGE)
    }

    private companion object {
        /** Longest edge of a frame thumbnail in the pick dialog. */
        const val PREVIEW_MAX_EDGE = 480

        /** A frame row in the pick dialog: vertical padding and touch-target height. */
        const val FRAME_ROW_PADDING_DP = 8f
        const val FRAME_ROW_MIN_HEIGHT_DP = 48f
    }
}
