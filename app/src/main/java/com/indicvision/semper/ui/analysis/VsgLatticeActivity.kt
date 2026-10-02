package com.indicvision.semper.ui.analysis

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import com.indicvision.semper.data.prefs.CoachPrefs
import com.indicvision.semper.data.prefs.ParamClipboard
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.databinding.ActivityVsgLatticeBinding
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.field.Roi
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.ui.analysis.recommend.StrainWindowText
import com.indicvision.semper.ui.analysis.run.EngineFailure
import com.indicvision.semper.ui.analysis.sweep.LatticeGraphExport
import com.indicvision.semper.ui.analysis.sweep.StrainProfiles
import com.indicvision.semper.ui.analysis.sweep.VsgLatticeView
import com.indicvision.semper.ui.analysis.sweep.VsgPlotView
import com.indicvision.semper.ui.analysis.sweep.VsgStudy
import com.indicvision.semper.ui.analysis.sweep.animateCopyConfirmation
import com.indicvision.semper.ui.analysis.sweep.bindCopyGestures
import com.indicvision.semper.ui.analysis.sweep.latticeSummary
import com.indicvision.semper.ui.analysis.sweep.loadSweepFrameProfiles
import com.indicvision.semper.ui.analysis.sweep.scrubReadout
import com.indicvision.semper.ui.analysis.sweep.skippedLatticeNodes
import com.indicvision.semper.ui.analysis.sweep.solvedLatticeNodes
import com.indicvision.semper.ui.analysis.sweep.sweepFrameProfiles
import com.indicvision.semper.ui.common.CoachMarkController
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.common.dialog.CrispToast
import com.indicvision.semper.ui.common.dialog.FaqRedirect
import com.indicvision.semper.ui.common.dialog.Feedback
import com.indicvision.semper.ui.common.onButtonChecked
import com.indicvision.semper.ui.viewer.ViewerArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * The swept parameter space as a 2-D lattice (subset across, strain window up):
 * solved combinations filled, skipped ones hollow. Staging screen in front of
 * the result viewer for a sweep — walk solved nodes one-thumb, read each strain
 * curve, then copy the winning params into single-analysis settings.
 *
 * Double-tap or long-press a solved node still opens that frame in the viewer.
 * The lattice stays on the back stack while the viewer is up.
 *
 * It reads its Intent as [ViewerArgs] and opens the viewer with the same
 * arguments plus the node's [ViewerArgs.startFrame].
 */
@MainThread
@Suppress("TooManyFunctions") // one small method per thing the screen does: node taps, summary, plot, coach mark
class VsgLatticeActivity : AppCompatActivity() {

    private companion object {
        /** Every combination of a sweep solves the same one deformed image. */
        const val SWEEP_DEFORMED_IMAGES = 1

        val STRAIN_OPTIONS = listOf(
            R.string.field_exx to DicResult.IDX_EXX,
            R.string.field_eyy to DicResult.IDX_EYY,
            R.string.field_exy to DicResult.IDX_EXY,
        )
    }

    /**
     * Line-cut profiles per solved combination, keyed by frame index in ascending
     * order ([sweepFrameProfiles]) — one entry per strain component. A frame that
     * could not be read is absent. The raw `.dat` payload is never retained: at ~1M
     * points a frame is 32 MB, so holding every frame of a sweep was O(F·n) and could
     * exhaust the heap on its own. A profile is a single grid row/column (~√n points), so this is
     * O(F·√n) resident and the decode stays O(n) transient.
     */
    private var frameProfiles: Map<Int, StrainProfiles> = emptyMap()

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

    /** The sweep's arguments, parsed once (ADR-003); the record is read only for an Intent missing a key. */
    private val args: ViewerArgs by lazy {
        ViewerArgs.from(intent) {
            intent.getStringExtra(DicKeys.SESSION_LOCAL_ID)?.let { SessionStore.get(this, it) }
        }
    }

    private lateinit var binding: ActivityVsgLatticeBinding

    /** Suppresses the slider→plot callback while the plot drives the slider. */
    private var syncingSlider = false

    /** The series + axis labels last drawn, reused to render the shared graph. */
    private var exportSeries: List<VsgPlotView.Series> = emptyList()
    private var exportXLabel: String = ""
    private var exportYLabel: String = ""

    private data class FrameSeries(val frameIndex: Int, val series: VsgPlotView.Series)

    private val graphExport = LatticeGraphExport(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVsgLatticeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Insets.padTop(binding.toolbar)
        Insets.padBottom(binding.actionBarRow)

        binding.toolbar.apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }

        val solved = solvedLatticeNodes(args.sweep)
        val skipped = skippedLatticeNodes(args.sweep) { code -> EngineFailure.shortReason(this, code) }
        val nodes = (solved + skipped).sortedWith(compareBy({ it.subset }, { it.vsg }))
        solvedNodes = nodes.filter { it.solved }
        // Frame-index lookup, so per-frame loops don't scan solvedNodes (was O(F²)).
        nodeByFrame = solvedNodes.associateBy { it.frameIndex }

        binding.latticeView.apply {
            interactionEnabled = true
            compact = true
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
            binding.stepperRow.visibility = View.GONE
            binding.btnView.isEnabled = false
            binding.btnSaveGraph.isEnabled = false
        }

        loadStrainProfiles()
        maybeCoachTheGraph()
    }

    /** Binds the strain-plot section views and wires their listeners. */
    private fun bindStrainControls() {
        binding.plotLatticeStrain.zoomEnabled = true
        binding.plotLatticeStrain.compactAxes = true
        binding.togglePlotModeClip.clipToOutline = true

        binding.btnPrevNode.setOnClickListener { stepFocus(-1) }
        binding.btnNextNode.setOnClickListener { stepFocus(1) }
        binding.togglePlotMode.onButtonChecked { redrawStrainPlot() }
        binding.btnView.setOnClickListener { if (focusedFrameIndex >= 0) openViewer(focusedFrameIndex) }
        binding.btnSaveGraph.setOnClickListener { saveGraph() }

        binding.plotLatticeStrain.onScrub = { x, samples ->
            binding.tvStrainPlotReadout.text = scrubReadout(this, x, samples)
        }
        binding.plotLatticeStrain.onScrubMove = { fraction ->
            syncingSlider = true
            binding.sliderScrub.value = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
            syncingSlider = false
        }
        binding.sliderScrub.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !syncingSlider) binding.plotLatticeStrain.scrubToFraction(value)
        }
        // The chip is now the only params surface (the readout dropped its params
        // tail, see scrubReadout), so it is the only remaining copy target.
        bindCopyGestures(binding.chipSelectedParams) { copySelectedParams(binding.chipSelectedParams) }
        setupStrainSpinner()
    }

    private fun maybeCoachTheGraph() {
        binding.latticeView.post {
            CoachMarkController(this).maybeShow(
                CoachPrefs.Screen.SWEEP_LATTICE,
                listOf(
                    CoachMarkController.Step(
                        binding.latticeView,
                        getString(R.string.coach_sweep_graph),
                    ),
                    CoachMarkController.Step(
                        binding.plotLatticeStrain,
                        getString(R.string.coach_sweep_scrub),
                    ),
                ),
            )
        }
    }

    private fun setupStrainSpinner() {
        val labels = STRAIN_OPTIONS.map { getString(it.first) }
        binding.spinnerStrainComponent.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )
        binding.spinnerStrainComponent.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
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
        val summary = binding.tvLatticeSummary
        if (solvedCount == 0) {
            summary.text = getString(R.string.vsg_lattice_all_failed)
            summary.isClickable = true
            summary.setOnClickListener {
                FaqRedirect.confirm(this, R.string.url_faq_engine_vsg)
            }
            return
        }
        summary.isClickable = false
        summary.setOnClickListener(null)
        summary.text = latticeSummary(resources, nodes, solvedCount, skippedCount, args.plannedFrames)
    }

    private fun showSkipReason(node: VsgLatticeView.Node) {
        val reason = node.failureReason.ifEmpty { getString(R.string.sweep_node_skipped) }
        val faqRes = node.failureCode?.let { EngineFailure.faqUrlRes(it) }
            ?: R.string.url_faq_engine_vsg
        FaqRedirect.errorDialog(
            this,
            getString(R.string.sweep_node_title_fmt, node.subset, node.step, windowText(node)),
            reason,
            faqRes,
        )
    }

    private fun loadStrainProfiles() {
        val batchDirPath = args.batchDirPath ?: return
        val steps = args.sweep?.steps?.takeIf { it.isNotEmpty() } ?: return
        val line = centreLine()
        val baseStep = args.step.coerceAtLeast(1)
        val components = VsgStudy.STRAIN_COMPONENTS.toIntArray()

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                loadSweepFrameProfiles(File(batchDirPath), steps, baseStep, components, line)
            }
            if (loaded.isEmpty()) return@launch
            frameProfiles = loaded
            redrawStrainPlot()
        }
    }

    /** The ROI centre line every profile is cut along — fixed for the activity's lifetime. */
    private fun centreLine(): VsgStudy.StudyLine =
        VsgStudy.centreLine(Roi(args.roiX, args.roiY, args.roiW, args.roiH), lineCutHorizontal())

    private fun lineCutHorizontal(): Boolean = args.sweep?.lineCutHorizontal ?: true

    /**
     * Rebuilds the line-cut plot for Highlight or Isolate mode: hidden with
     * nothing to show, or moved onto a frame that has a curve when the focused
     * one has none.
     */
    private fun redrawStrainPlot() {
        if (frameProfiles.isEmpty()) {
            binding.strainPlotSection.visibility = View.GONE
            return
        }
        val component = selectedStrainComponent()
        // Keep the zoom across node / mode switches; reset it when the component changes.
        val preserveViewport = component == lastStrainComponent
        lastStrainComponent = component
        val seriesByFrame = buildFrameSeries(component)
        when {
            seriesByFrame.isEmpty() -> binding.strainPlotSection.visibility = View.GONE
            focusedFrameIndex >= 0 && seriesByFrame.none { it.frameIndex == focusedFrameIndex } ->
                selectFocus(seriesByFrame.first().frameIndex)
            else -> showStrainPlot(seriesByFrame, preserveViewport)
        }
    }

    /** Draws [seriesByFrame], the focused curve in colour and on top; Isolate drops the rest. */
    private fun showStrainPlot(seriesByFrame: List<FrameSeries>, preserveViewport: Boolean) {
        val horizontal = lineCutHorizontal()
        val isolate = binding.togglePlotMode.checkedButtonId == R.id.btnPlotIsolate
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

        binding.strainPlotSection.visibility = View.VISIBLE
        binding.tvStrainPlotTitle.text = getString(
            R.string.line_cut_title_axis_fmt,
            getString(if (horizontal) R.string.axis_x else R.string.axis_y),
        )
        exportSeries = toShow
        exportXLabel = getString(if (horizontal) R.string.line_cut_axis_x else R.string.line_cut_axis_y)
        exportYLabel = getString(R.string.line_cut_axis_strain)
        binding.plotLatticeStrain.setData(
            toShow,
            exportXLabel,
            exportYLabel,
            preserveViewport = preserveViewport,
            xUnit = getString(R.string.scale_unit_px),
            yUnit = getString(R.string.scale_unit_strain),
        )
        binding.tvStrainPlotReadout.text = ""
        syncingSlider = true
        binding.sliderScrub.value = 0f
        syncingSlider = false
    }

    /**
     * One plot series per solved frame along the centre line, muted except the focused
     * one. Reads the profiles computed once at load; no frame is re-scanned per tap.
     */
    private fun buildFrameSeries(component: Int): List<FrameSeries> =
        frameProfiles.mapNotNull { (index, profiles) ->
            val node = nodeByFrame[index]
            val points = profiles[component].orEmpty()
            if (points.isEmpty()) return@mapNotNull null
            val label = if (node != null) {
                getString(R.string.vsg_lattice_param_labeled_fmt, node.subset, node.step, windowText(node))
            } else {
                getString(R.string.sweep_frame_btn_fmt, index + 1)
            }
            FrameSeries(
                frameIndex = index,
                series = VsgPlotView.Series(
                    label = label,
                    color = VsgPlotView.paletteColor(this, index),
                    points = points,
                    markers = false,
                    muted = index != focusedFrameIndex,
                ),
            )
        }

    private fun selectFocus(frameIndex: Int) {
        focusedFrameIndex = frameIndex
        binding.latticeView.selectedFrameIndex = focusedFrameIndex
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
            binding.stepperRow.visibility = View.GONE
            binding.btnView.isEnabled = false
            return
        }
        binding.stepperRow.visibility = View.VISIBLE
        binding.chipSelectedParams.text = getString(
            R.string.vsg_lattice_param_fmt,
            node.subset,
            node.step,
            windowText(node),
        )
        val idx = solvedNodes.indexOfFirst { it.frameIndex == focusedFrameIndex }
        binding.btnPrevNode.isEnabled = idx > 0
        binding.btnNextNode.isEnabled = idx in 0 until solvedNodes.lastIndex
        binding.btnView.isEnabled = true
        binding.btnSaveGraph.isEnabled = true
    }

    private fun windowText(node: VsgLatticeView.Node): String = StrainWindowText.of(this, node.vsg, node.step)

    private fun selectedNode(): VsgLatticeView.Node? =
        solvedNodes.find { it.frameIndex == focusedFrameIndex }

    private fun copySelectedParams(animateOn: View) {
        val node = selectedNode() ?: return
        ParamClipboard.copy(this, node.subset, node.step, node.vsg)
        animateCopyConfirmation(animateOn)
        CrispToast.show(this, getString(R.string.vsg_lattice_params_copied))
    }

    private fun saveGraph() {
        val series = exportSeries
        if (series.isEmpty() || focusedFrameIndex < 0) return
        lifecycleScope.launch {
            val bitmap = try {
                graphExport.render(series, exportHeaderLines(series), exportXLabel, exportYLabel)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.w(e, "Failed to render strain graph")
                null
            }
            if (bitmap == null) {
                Feedback.toast(this@VsgLatticeActivity, R.string.save_failed)
                return@launch
            }
            val file = withContext(Dispatchers.IO) { graphExport.writePng(bitmap) }
            bitmap.recycle()
            if (file == null) {
                Feedback.toast(this@VsgLatticeActivity, R.string.save_failed)
                return@launch
            }
            graphExport.share(file)
        }
    }

    /** Study type, image names, and settings for the export header. */
    private fun exportHeaderLines(series: List<VsgPlotView.Series>): List<String> {
        val lines = mutableListOf<String>()
        val horizontal = lineCutHorizontal()
        val axis = getString(if (horizontal) R.string.axis_x else R.string.axis_y)
        val index = binding.spinnerStrainComponent.selectedItemPosition.coerceIn(0, STRAIN_OPTIONS.lastIndex)
        lines += getString(
            R.string.vsg_export_title_fmt,
            getString(R.string.setting_vsg),
            getString(STRAIN_OPTIONS[index].first),
            axis,
        )
        val ref = args.refName
        if (ref.isNotBlank()) {
            // A sweep solves one deformed image (`RunSpec.Sweep.frameIndex`) with
            // every combination. `frameNames` holds the combination labels, and
            // counting those made a 12-node sweep "12 deformed images".
            lines += resources.getQuantityString(
                R.plurals.vsg_export_images_fmt,
                SWEEP_DEFORMED_IMAGES,
                ref,
                SWEEP_DEFORMED_IMAGES,
            )
        }
        val node = selectedNode()
        if (node != null) {
            lines += getString(R.string.vsg_lattice_param_labeled_fmt, node.subset, node.step, windowText(node))
        }
        val isolate = binding.togglePlotMode.checkedButtonId == R.id.btnPlotIsolate
        if (!isolate) {
            lines += resources.getQuantityString(R.plurals.vsg_export_combos_fmt, series.size, series.size)
        }
        return lines
    }

    private fun selectedStrainComponent(): Int {
        val index = binding.spinnerStrainComponent.selectedItemPosition.coerceIn(0, STRAIN_OPTIONS.lastIndex)
        return STRAIN_OPTIONS[index].second
    }

    /** The viewer on one combination: the same arguments, re-packed with the node's frame. */
    private fun openViewer(frameIndex: Int) {
        startActivity(args.copy(startFrame = frameIndex).toIntent(this))
    }
}
