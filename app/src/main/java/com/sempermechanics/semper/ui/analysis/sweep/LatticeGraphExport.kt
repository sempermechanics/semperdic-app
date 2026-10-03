package com.sempermechanics.semper.ui.analysis.sweep

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.CacheJanitor
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * The strain graph as a shareable PNG: a header (study, images, settings), the
 * plot body rendered from a detached [VsgPlotView] at full fit, and a colour
 * legend of the curves. Detached so it never disturbs the on-screen (scrolled)
 * plot.
 */
internal class LatticeGraphExport(private val activity: AppCompatActivity) {

    /** Composes the PNG's bitmap for [series], titled by [header] (first line bold). */
    fun render(series: List<VsgPlotView.Series>, header: List<String>, xLabel: String, yLabel: String): Bitmap {
        val plotBitmap = VsgPlotView(activity).apply {
            zoomEnabled = false
            setData(series, xLabel, yLabel)
        }.renderToBitmap(EXPORT_PLOT_WIDTH_PX, EXPORT_PLOT_HEIGHT_PX)

        val legendRows = (series.size + EXPORT_LEGEND_COLS - 1) / EXPORT_LEGEND_COLS
        val headerHeight = EXPORT_MARGIN_PX * 2 + header.size * EXPORT_LINE_PX
        val legendHeight = EXPORT_MARGIN_PX + legendRows * EXPORT_LINE_PX
        val total = (headerHeight + EXPORT_PLOT_HEIGHT_PX + legendHeight).toInt()

        val out = createBitmap(EXPORT_PLOT_WIDTH_PX, total)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        drawHeader(canvas, header)
        canvas.drawBitmap(plotBitmap, 0f, headerHeight, null)
        plotBitmap.recycle()
        drawLegend(canvas, series, headerHeight + EXPORT_PLOT_HEIGHT_PX)
        return out
    }

    /** Writes [bitmap] as a PNG in the share cache; null when it could not. Blocking file IO. */
    @WorkerThread
    fun writePng(bitmap: Bitmap): File? {
        return try {
            val dir = CacheJanitor.shareDir(activity.cacheDir)
            val file = File(dir, "vsg_strain_graph_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            }
            file
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Failed to write strain graph PNG")
            null
        }
    }

    /** Opens the share sheet on [file]. */
    fun share(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.vsg_lattice_share_graph)))
    }

    private fun drawHeader(canvas: Canvas, lines: List<String>) {
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = EXPORT_TITLE_PX
            isFakeBoldText = true
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = EXPORT_BODY_PX
        }
        var y = EXPORT_MARGIN_PX + EXPORT_TITLE_PX
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, EXPORT_MARGIN_PX, y, if (i == 0) titlePaint else bodyPaint)
            y += EXPORT_LINE_PX
        }
    }

    /** One colour swatch + param label per curve, laid out in [EXPORT_LEGEND_COLS] columns. */
    private fun drawLegend(canvas: Canvas, series: List<VsgPlotView.Series>, top: Float) {
        val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = EXPORT_BODY_PX
        }
        val colWidth = (EXPORT_PLOT_WIDTH_PX - EXPORT_MARGIN_PX * 2) / EXPORT_LEGEND_COLS
        series.forEachIndexed { i, s ->
            val x = EXPORT_MARGIN_PX + (i % EXPORT_LEGEND_COLS) * colWidth
            val y = top + EXPORT_MARGIN_PX + (i / EXPORT_LEGEND_COLS) * EXPORT_LINE_PX
            swatchPaint.color = s.color
            canvas.drawRect(x, y - EXPORT_SWATCH_PX, x + EXPORT_SWATCH_PX, y, swatchPaint)
            canvas.drawText(s.label, x + EXPORT_SWATCH_PX + EXPORT_MARGIN_PX / 2, y, textPaint)
        }
    }

    private companion object {
        const val PNG_QUALITY = 100

        // Exported PNG geometry (px). Plot body kept at a size where SP-sized axis
        // text stays legible, then header + colour legend are composed around it.
        const val EXPORT_PLOT_WIDTH_PX = 1600
        const val EXPORT_PLOT_HEIGHT_PX = 1000
        const val EXPORT_MARGIN_PX = 44f
        const val EXPORT_TITLE_PX = 46f
        const val EXPORT_BODY_PX = 34f
        const val EXPORT_LINE_PX = 52f
        const val EXPORT_SWATCH_PX = 30f
        const val EXPORT_LEGEND_COLS = 1
    }
}
