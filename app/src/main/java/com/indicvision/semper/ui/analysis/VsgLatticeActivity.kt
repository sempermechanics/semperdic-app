// One small method per thing the screen does — node taps, the summary, the
// strain plot, the coach mark — so TooManyFunctions is suppressed here.
@file:Suppress("TooManyFunctions")

@file:SuppressLint("PrivateResource", "ClickableViewAccessibility")

package com.indicvision.semper.ui.analysis

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.indicvision.semper.DicKeys
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.data.CoachPrefs
import com.indicvision.semper.data.ParamClipboard
import com.indicvision.semper.ui.common.CoachMarkController
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.viewer.ResultViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * The swept parameter space as a 2-D lattice (subset across, strain window up):
 * solved combinations filled, skipped ones hollow. Staging screen in front of
 * the result viewer for a sweep — walk solved nodes one-thumb, read each strain
 * curve, then copy the winning params into single-analysis settings.
 *
 * Double-tap or long-press a solved node still opens that frame in the viewer.
 * The lattice stays on the back stack while the viewer is up.
 *
 * The Intent it receives is exactly the one the result viewer needs (plus the
 * sweep lattice arrays); it forwards those extras on, adding only the frame to
 * start at.
 */
class VsgLatticeActivity : AppCompatActivity() {

    private companion object {
        val STRAIN_OPTIONS = listOf(
            R.string.field_exx to DicResult.IDX_EXX,
            R.string.field_eyy to DicResult.IDX_EYY,
            R.string.field_exy to DicResult.IDX_EXY,
        )
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

        // Copy-confirmation "pop + highlight" animation (readout and param chip).
        const val COPY_POP_SCALE = 1.06f
        const val COPY_POP_MS = 120L
        const val COPY_FLASH_MS = 500L
        const val COPY_FLASH_ALPHA = 120
    }

    /**
     * Line-cut profiles per solved combination, in frame order — one entry per strain
     * component. The raw `.dat` payload is never retained: at ~1M points a frame is
     * 32 MB, so holding every frame of a sweep was O(F·n) and could exhaust the heap
     * on its own. A profile is a single grid row/column (~√n points), so this is
     * O(F·√n) resident and the decode stays O(n) transient.
     */
    private var frameProfiles: List<Map<Int, List<Pair<Float, Float>>>> = emptyList()

    /** Solved nodes in lattice order (ascending subset, then window). */
    private var solvedNodes: List<VsgLatticeView.Node> = emptyList()

    /** [solvedNodes] keyed by frame index — see the loop in [buildFrameSeries]. */
    private var nodeByFrame: Map<Int, VsgLatticeView.Node> = emptyMap()

    /** The solved frame currently selected; always a solved index when any exist. */
    private var focusedFrameIndex: Int = -1

    /**
     * Strain component of the last plot draw. Zoom/pan is kept while this is
     * unchanged (node or Highlight/Isolate switch) and reset when it changes,
     * since Exx/Eyy/Exy differ in magnitude and a stale viewport would clip.
     */
    private var lastStrainComponent: Int? = null

    private lateinit var strainPlotSection: View
    private lateinit var latticeView: VsgLatticeView
    private lateinit var strainPlot: VsgPlotView
    private lateinit var strainSpinner: Spinner
    private lateinit var strainPlotTitle: TextView
    private lateinit var strainPlotReadout: TextView
    private lateinit var stepperRow: View
    private lateinit var chipSelectedParams: Chip
    private lateinit var btnPrevNode: ImageButton
    private lateinit var btnNextNode: ImageButton
    private lateinit var togglePlotMode: MaterialButtonToggleGroup
    private lateinit var strainSlider: Slider
    private lateinit var btnSaveGraph: MaterialButton
    private lateinit var btnView: MaterialButton

    /** Suppresses the slider→plot callback while the plot drives the slider. */
    private var syncingSlider = false

    /** The series + axis labels last drawn, reused to render the shared graph. */
    private var exportSeries: List<VsgPlotView.Series> = emptyList()
    private var exportXLabel: String = ""
    private var exportYLabel: String = ""

    private data class FrameSeries(val frameIndex: Int, val series: VsgPlotView.Series)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vsg_lattice)

        Insets.padTop(findViewById(R.id.toolbar))

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }

        val solved = nodesFrom(
            DicKeys.SWEEP_SUBSETS,
            DicKeys.SWEEP_STEPS,
            DicKeys.SWEEP_STRAIN_WINS,
            solved = true,
        )
        val skippedCodes = intent.getIntArrayExtra(DicKeys.SWEEP_SKIP_CODES) ?: IntArray(0)
        val skipped = nodesFrom(
            DicKeys.SWEEP_SKIP_SUBSETS,
            DicKeys.SWEEP_SKIP_STEPS,
            DicKeys.SWEEP_SKIP_STRAIN_WINS,
            solved = false,
            codes = skippedCodes,
        )
        val nodes = (solved + skipped).sortedWith(compareBy({ it.subset }, { it.window }))
        solvedNodes = nodes.filter { it.solved }
        // Frame-index lookup, so per-frame loops don't scan solvedNodes (was O(F²)).
        nodeByFrame = solvedNodes.associateBy { it.frameIndex }

        latticeView = findViewById(R.id.latticeView)
        latticeView.apply {
            interactionEnabled = true
            setNodes(nodes)
            onNodeClick = { node ->
                if (node.solved) selectFocus(node.frameIndex) else showSkipReason(node)
            }
            onNodeDoubleClick = { node -> if (node.solved) openViewer(node.frameIndex) }
            onNodeLongClick = { node -> if (node.solved) openViewer(node.frameIndex) }
        }

        showSummary(nodes, solved.size, skipped.size)

        bindStrainControls()

        if (solvedNodes.isNotEmpty()) {
            selectFocus(solvedNodes.first().frameIndex)
        } else {
            stepperRow.visibility = View.GONE
            btnView.isEnabled = false
            btnSaveGraph.isEnabled = false
        }

        loadStrainProfiles()
        maybeCoachTheGraph()
    }

    /** Binds the strain-plot section views and wires their listeners. */
    private fun bindStrainControls() {
        strainPlotSection = findViewById(R.id.strainPlotSection)
        strainPlot = findViewById(R.id.plotLatticeStrain)
        strainPlot.zoomEnabled = true
        strainSpinner = findViewById(R.id.spinnerStrainComponent)
        strainPlotTitle = findViewById(R.id.tvStrainPlotTitle)
        strainPlotReadout = findViewById(R.id.tvStrainPlotReadout)
        stepperRow = findViewById(R.id.stepperRow)
        chipSelectedParams = findViewById(R.id.chipSelectedParams)
        btnPrevNode = findViewById(R.id.btnPrevNode)
        btnNextNode = findViewById(R.id.btnNextNode)
        togglePlotMode = findViewById(R.id.togglePlotMode)
        strainSlider = findViewById(R.id.sliderScrub)
        btnSaveGraph = findViewById(R.id.btnSaveGraph)
        btnView = findViewById(R.id.btnView)

        btnPrevNode.setOnClickListener { stepFocus(-1) }
        btnNextNode.setOnClickListener { stepFocus(1) }
        togglePlotMode.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) redrawStrainPlot()
        }
        btnView.setOnClickListener { if (focusedFrameIndex >= 0) openViewer(focusedFrameIndex) }
        btnSaveGraph.setOnClickListener { saveGraph() }

        strainPlot.onScrub = { x, samples -> strainPlotReadout.text = scrubReadout(x, samples) }
        strainPlot.onScrubMove = { fraction ->
            syncingSlider = true
            strainSlider.value = fraction.coerceIn(0f, 1f)
            syncingSlider = false
        }
        strainSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !syncingSlider) strainPlot.scrubToFraction(value)
        }
        bindDoubleTapCopy(strainPlotReadout)
        bindDoubleTapCopy(chipSelectedParams)
        setupStrainSpinner()
    }

    /** Double-tapping [target] copies the selected node's params (readout or param chip). */
    private fun bindDoubleTapCopy(target: View) {
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    copySelectedParams(target)
                    return true
                }
            },
        )
        target.setOnTouchListener { v, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    /** Scrub readout for the selected node only: labeled (x, y) and subset/step/strain. */
    private fun scrubReadout(x: Float, samples: List<VsgPlotView.Sample>): CharSequence {
        val node = selectedNode()
        if (x.isNaN() || samples.isEmpty() || node == null) return ""
        val params = getString(
            R.string.vsg_lattice_param_labeled_fmt,
            node.subset,
            node.step,
            node.window,
        )
        return getString(R.string.vsg_lattice_scrub_value_fmt, x, samples.first().value, params)
    }

    private fun maybeCoachTheGraph() {
        latticeView.post {
            CoachMarkController(this).maybeShow(
                CoachPrefs.Screen.SWEEP_LATTICE,
                listOf(
                    CoachMarkController.Step(
                        latticeView,
                        getString(R.string.coach_sweep_graph),
                    ),
                    CoachMarkController.Step(
                        strainPlot,
                        getString(R.string.coach_sweep_scrub),
                    ),
                ),
            )
        }
    }

    private fun setupStrainSpinner() {
        val labels = STRAIN_OPTIONS.map { getString(it.first) }
        strainSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        strainSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                redrawStrainPlot()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showSummary(nodes: List<VsgLatticeView.Node>, solvedCount: Int, skippedCount: Int) {
        val stepDenom = nodes.firstOrNull()?.takeIf { it.step > 0 }
            ?.let { (it.subset.toDouble() / it.step).roundToInt() } ?: 0
        val summary = findViewById<TextView>(R.id.tvLatticeSummary)
        if (solvedCount == 0) {
            summary.text = getString(R.string.vsg_lattice_all_failed)
            return
        }
        summary.text = if (stepDenom > 0) {
            resources.getQuantityString(
                R.plurals.vsg_lattice_summary_fmt,
                nodes.size,
                nodes.size,
                solvedCount,
                skippedCount,
                stepDenom,
            )
        } else {
            resources.getQuantityString(
                R.plurals.vsg_lattice_summary_short_fmt,
                nodes.size,
                nodes.size,
                solvedCount,
                skippedCount,
            )
        }
    }

    private fun showSkipReason(node: VsgLatticeView.Node) {
        val reason = node.failureReason.ifEmpty { getString(R.string.sweep_node_skipped) }
        MaterialAlertDialogBuilder(this)
            .setTitle(
                getString(R.string.sweep_node_title_fmt, node.subset, node.step, node.window, node.vsg),
            )
            .setMessage(reason)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun nodesFrom(
        subsetsKey: String,
        stepsKey: String,
        windowsKey: String,
        solved: Boolean,
        codes: IntArray = IntArray(0),
    ): List<VsgLatticeView.Node> {
        val subsets = intent.getIntArrayExtra(subsetsKey) ?: IntArray(0)
        val steps = intent.getIntArrayExtra(stepsKey) ?: IntArray(0)
        val windows = intent.getIntArrayExtra(windowsKey) ?: IntArray(0)
        val count = minOf(subsets.size, steps.size, windows.size)
        return (0 until count).map { i ->
            VsgLatticeView.Node(
                subset = subsets[i],
                step = steps[i],
                window = windows[i],
                vsg = VsgStudy.vsgFor(steps[i], windows[i]),
                solved = solved,
                frameIndex = if (solved) i else -1,
                failureReason = codes.getOrNull(i)
                    ?.let { code -> getString(EngineFailure.shortReasonRes(code)) }
                    .orEmpty(),
            )
        }
    }

    @Suppress("ReturnCount")
    private fun loadStrainProfiles() {
        val batchDirPath = intent.getStringExtra(DicKeys.BATCH_DIR_PATH) ?: return
        val steps = intent.getIntArrayExtra(DicKeys.SWEEP_STEPS) ?: return
        if (steps.isEmpty()) return

        val line = centreLine()
        val baseStep = intent.getIntExtra(DicKeys.STEP, 1).coerceAtLeast(1)
        val componentsArray = VsgStudy.STRAIN_COMPONENTS.toIntArray()

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val dir = File(batchDirPath)
                if (!dir.isDirectory) return@withContext emptyList()
                val files = dir.listFiles { file -> file.extension == "dat" }
                    ?.sortedBy { it.name }
                    ?: return@withContext emptyList()
                files.mapIndexedNotNull { index, file ->
                    try {
                        // Decode → profile → discard, one frame at a time. Only the
                        // profiles survive the loop, so peak is one frame, not all of them.
                        val data = DicResult.decodeDatFile(file)
                        if (data == null) {
                            Timber.w("Invalid .dat size for %s", file.name)
                            null
                        } else {
                            val step = steps.getOrNull(index)?.coerceAtLeast(1) ?: baseStep
                            VsgStudy.profileAlong(data, componentsArray, line, step / 2f)
                        }
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        Timber.w(e, "Failed to read %s", file.name)
                        null
                    }
                }
            }
            if (loaded.isEmpty()) return@launch
            frameProfiles = loaded
            redrawStrainPlot()
        }
    }

    /** The ROI centre line every profile is cut along — fixed for the activity's lifetime. */
    private fun centreLine(): VsgStudy.StudyLine = VsgStudy.centreLine(
        intent.getIntExtra(DicKeys.ROI_X, 0),
        intent.getIntExtra(DicKeys.ROI_Y, 0),
        intent.getIntExtra(DicKeys.ROI_W, 0),
        intent.getIntExtra(DicKeys.ROI_H, 0),
        intent.getBooleanExtra(DicKeys.LINE_CUT_HORIZONTAL, true),
    )

    /** Rebuilds the line-cut plot for Highlight or Isolate mode. */
    @Suppress("ReturnCount")
    private fun redrawStrainPlot() {
        if (frameProfiles.isEmpty()) {
            strainPlotSection.visibility = View.GONE
            return
        }
        val component = selectedStrainComponent()
        val horizontal = intent.getBooleanExtra(DicKeys.LINE_CUT_HORIZONTAL, true)
        val isolate = togglePlotMode.checkedButtonId == R.id.btnPlotIsolate
        // Keep the zoom across node / mode switches; reset it when the component changes.
        val preserveViewport = component == lastStrainComponent
        lastStrainComponent = component

        val seriesByFrame = buildFrameSeries(component)
        if (seriesByFrame.isEmpty()) {
            strainPlotSection.visibility = View.GONE
            return
        }
        if (focusedFrameIndex >= 0 && seriesByFrame.none { it.frameIndex == focusedFrameIndex }) {
            val fallback = seriesByFrame.first().frameIndex
            selectFocus(fallback)
            return
        }

        val toShow = if (isolate) {
            seriesByFrame.filter { it.frameIndex == focusedFrameIndex }
                .map { it.series.copy(muted = false) }
        } else {
            // Selected last so it paints bold on top of muted curves.
            val muted = seriesByFrame.filter { it.frameIndex != focusedFrameIndex }.map { it.series }
            val selected = seriesByFrame.filter { it.frameIndex == focusedFrameIndex }
                .map { it.series.copy(muted = false) }
            muted + selected
        }

        strainPlotSection.visibility = View.VISIBLE
        strainPlotTitle.text = getString(
            R.string.line_cut_title_axis_fmt,
            getString(if (horizontal) R.string.axis_x else R.string.axis_y),
        )
        exportSeries = toShow
        exportXLabel = getString(if (horizontal) R.string.line_cut_axis_x else R.string.line_cut_axis_y)
        exportYLabel = getString(R.string.line_cut_axis_strain)
        strainPlot.setData(toShow, exportXLabel, exportYLabel, preserveViewport = preserveViewport)
        strainPlotReadout.text = ""
        syncingSlider = true
        strainSlider.value = 0f
        syncingSlider = false
    }

    /**
     * One plot series per solved frame along the centre line, muted except the focused
     * one. Reads the profiles computed once at load; no frame is re-scanned per tap.
     */
    private fun buildFrameSeries(component: Int): List<FrameSeries> =
        frameProfiles.mapIndexedNotNull { index, profiles ->
            val node = nodeByFrame[index]
            val points = profiles[component].orEmpty()
            if (points.isEmpty()) return@mapIndexedNotNull null
            val label = if (node != null) {
                getString(R.string.vsg_lattice_param_labeled_fmt, node.subset, node.step, node.window)
            } else {
                getString(R.string.sweep_frame_btn_fmt, index + 1)
            }
            FrameSeries(
                frameIndex = index,
                series = VsgPlotView.Series(
                    label = label,
                    color = VsgPlotView.paletteColor(index),
                    points = points,
                    markers = false,
                    muted = index != focusedFrameIndex,
                ),
            )
        }

    private fun selectFocus(frameIndex: Int) {
        focusedFrameIndex = frameIndex
        latticeView.selectedFrameIndex = focusedFrameIndex
        updateChipAndStepper()
        redrawStrainPlot()
    }

    private fun stepFocus(delta: Int) {
        if (solvedNodes.isEmpty()) return
        val current = solvedNodes.indexOfFirst { it.frameIndex == focusedFrameIndex }
            .coerceAtLeast(0)
        val next = (current + delta).coerceIn(0, solvedNodes.lastIndex)
        selectFocus(solvedNodes[next].frameIndex)
    }

    private fun updateChipAndStepper() {
        val node = solvedNodes.find { it.frameIndex == focusedFrameIndex }
        if (node == null) {
            stepperRow.visibility = View.GONE
            btnView.isEnabled = false
            return
        }
        stepperRow.visibility = View.VISIBLE
        chipSelectedParams.text = getString(
            R.string.vsg_lattice_param_fmt,
            node.subset,
            node.step,
            node.window,
        )
        val idx = solvedNodes.indexOfFirst { it.frameIndex == focusedFrameIndex }
        btnPrevNode.isEnabled = idx > 0
        btnNextNode.isEnabled = idx in 0 until solvedNodes.lastIndex
        btnView.isEnabled = true
        btnSaveGraph.isEnabled = true
    }

    private fun selectedNode(): VsgLatticeView.Node? =
        solvedNodes.find { it.frameIndex == focusedFrameIndex }

    private fun copySelectedParams(animateOn: View) {
        val node = selectedNode() ?: return
        ParamClipboard.copy(this, node.subset, node.step, node.window)
        animateCopyConfirmation(animateOn)
    }

    /** A quick "pop + highlight" on [view] to confirm the params were copied. */
    private fun animateCopyConfirmation(view: View) {
        view.announceForAccessibility(getString(R.string.vsg_lattice_params_copied))
        view.animate()
            .scaleX(COPY_POP_SCALE).scaleY(COPY_POP_SCALE)
            .setDuration(COPY_POP_MS)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(COPY_POP_MS).start()
            }
            .start()
        // A foreground scrim flashes over both the transparent readout and the filled chip.
        val scrim = ContextCompat.getColor(this, R.color.sky_primary).toDrawable()
        view.foreground = scrim
        ObjectAnimator.ofInt(scrim, "alpha", COPY_FLASH_ALPHA, 0)
            .apply { duration = COPY_FLASH_MS }
            .apply { doOnEnd { view.foreground = null } }
            .start()
    }

    private fun saveGraph() {
        val series = exportSeries
        if (series.isEmpty() || focusedFrameIndex < 0) return
        lifecycleScope.launch {
            val bitmap = try {
                buildExportGraph(series)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.w(e, "Failed to render strain graph")
                null
            }
            if (bitmap == null) {
                Toast.makeText(this@VsgLatticeActivity, R.string.save_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val file = withContext(Dispatchers.IO) { writePng(bitmap) }
            bitmap.recycle()
            if (file == null) {
                Toast.makeText(this@VsgLatticeActivity, R.string.save_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            sharePng(file)
        }
    }

    /**
     * Composes the shared PNG: a header (study, images, settings), the plot body
     * rendered from a detached view at full fit, and a colour legend of the curves.
     * Detached so it never disturbs the on-screen (scrolled) plot.
     */
    private fun buildExportGraph(series: List<VsgPlotView.Series>): Bitmap {
        val header = exportHeaderLines(series)
        val plotBitmap = VsgPlotView(this).apply {
            zoomEnabled = false
            setData(series, exportXLabel, exportYLabel)
        }.renderToBitmap(EXPORT_PLOT_WIDTH_PX, EXPORT_PLOT_HEIGHT_PX)

        val legendRows = (series.size + EXPORT_LEGEND_COLS - 1) / EXPORT_LEGEND_COLS
        val headerHeight = EXPORT_MARGIN_PX * 2 + header.size * EXPORT_LINE_PX
        val legendHeight = EXPORT_MARGIN_PX + legendRows * EXPORT_LINE_PX
        val total = (headerHeight + EXPORT_PLOT_HEIGHT_PX + legendHeight).toInt()

        val out = createBitmap(EXPORT_PLOT_WIDTH_PX, total)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        drawExportHeader(canvas, header)
        canvas.drawBitmap(plotBitmap, 0f, headerHeight, null)
        plotBitmap.recycle()
        drawExportLegend(canvas, series, headerHeight + EXPORT_PLOT_HEIGHT_PX)
        return out
    }

    /** Study type, image names, and settings for the export header. */
    private fun exportHeaderLines(series: List<VsgPlotView.Series>): List<String> {
        val lines = mutableListOf<String>()
        val horizontal = intent.getBooleanExtra(DicKeys.LINE_CUT_HORIZONTAL, true)
        val axis = getString(if (horizontal) R.string.axis_x else R.string.axis_y)
        val index = strainSpinner.selectedItemPosition.coerceIn(0, STRAIN_OPTIONS.lastIndex)
        lines += getString(
            R.string.vsg_export_title_fmt,
            getString(R.string.setting_vsg),
            getString(STRAIN_OPTIONS[index].first),
            axis,
        )
        val ref = intent.getStringExtra(DicKeys.REF_NAME)
        if (!ref.isNullOrBlank()) {
            val defs = intent.getStringArrayExtra(DicKeys.DEF_FILE_NAMES)?.size ?: 0
            lines += resources.getQuantityString(R.plurals.vsg_export_images_fmt, defs, ref, defs)
        }
        val node = selectedNode()
        if (node != null) {
            lines += getString(R.string.vsg_lattice_param_labeled_fmt, node.subset, node.step, node.window)
        }
        val isolate = togglePlotMode.checkedButtonId == R.id.btnPlotIsolate
        if (!isolate) {
            lines += resources.getQuantityString(R.plurals.vsg_export_combos_fmt, series.size, series.size)
        }
        return lines
    }

    private fun drawExportHeader(canvas: Canvas, lines: List<String>) {
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
    private fun drawExportLegend(canvas: Canvas, series: List<VsgPlotView.Series>, top: Float) {
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

    private fun writePng(bitmap: Bitmap): File? {
        return try {
            val dir = File(cacheDir, "share").apply { mkdirs() }
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

    private fun sharePng(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.vsg_lattice_share_graph)))
    }

    private fun selectedStrainComponent(): Int {
        val index = strainSpinner.selectedItemPosition.coerceIn(0, STRAIN_OPTIONS.lastIndex)
        return STRAIN_OPTIONS[index].second
    }

    private fun openViewer(frameIndex: Int) {
        val extras = intent.extras ?: return
        startActivity(
            Intent(this, ResultViewerActivity::class.java)
                .putExtras(extras)
                .putExtra(DicKeys.START_FRAME, frameIndex),
        )
    }
}
