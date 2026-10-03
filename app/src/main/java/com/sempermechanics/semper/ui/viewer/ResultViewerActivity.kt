// Result viewer Activity: frame scrubbing, overlays, tap-to-probe and export
// live on one screen; the screen's parts are its controllers. onCreate wires
// them all, which is long by nature, and the Activity is what the controllers,
// the sheets and the tests reach the screen's state through.
@file:Suppress("LongMethod", "TooManyFunctions")
@file:SuppressLint("SetTextI18n")

package com.sempermechanics.semper.ui.viewer

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.databinding.ActivityResultViewerBinding
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.FrameParams
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.ValueRange
import com.sempermechanics.semper.navigation.DicKeys
import com.sempermechanics.semper.report.ReportImageNames
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.dialog.CrispToast
import com.sempermechanics.semper.ui.common.dialog.FaqRedirect
import com.sempermechanics.semper.ui.common.transfer.TransferBannerController
import com.sempermechanics.semper.ui.home.HomeActivity
import com.sempermechanics.semper.ui.viewer.inspect.ViewerInspectController
import com.sempermechanics.semper.ui.viewer.share.ShareCenter
import com.sempermechanics.semper.ui.viewer.share.ShareExportUi
import com.sempermechanics.semper.ui.viewer.share.ShareKind
import com.sempermechanics.semper.ui.viewer.share.ViewerReportFactory
import com.sempermechanics.semper.ui.viewer.summary.ViewerSummaryController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Results browser: renders each frame's displacement/strain heatmap over that
 * frame's own photo, drawn where the points moved to (the reference when the
 * photo is not on disk), with frame scrubbing, tap-to-probe readings, custom color scales,
 * and all exports (PDF/CSV/PNG/ZIP via [ShareCenter]).
 *
 * The screen's parts: [ViewerChromeController] (the edge bars),
 * [ViewerImageLoader] (the photo under the map), [ViewerFrameLoader] (frame
 * data and look-ahead), [ViewerScaleController] (the heatmap and its scale),
 * [FrameJumpController] (frame navigation), [ViewerCaptions] (the stats text)
 * and [ViewerShareController] (what exports read).
 */
@MainThread
class ResultViewerActivity : AppCompatActivity() {

    internal val viewModel: ResultViewerViewModel by viewModels()

    internal lateinit var binding: ActivityResultViewerBinding

    private val chrome = ViewerChromeController(this)
    internal val images = ViewerImageLoader(this)
    internal val frames = ViewerFrameLoader(this)
    internal val scale = ViewerScaleController(this)
    private val frameJump = FrameJumpController(this)
    internal val captions = ViewerCaptions(this)
    private val share = ViewerShareController(this)
    private val fieldPopup = FieldPopup(this)

    internal lateinit var shareBanner: TransferBannerController

    /** Shows the running exports, which live in [viewModel] and outlive this screen's rotations. */
    internal lateinit var shareExports: ShareExportUi

    /** Run once the frame set is read; see [whenFrameSetLoaded]. */
    private val afterFrameSet = mutableListOf<() -> Unit>()

    /** Runs [action] now if the frame set is read, else right after [onFrameSetRead]. */
    internal fun whenFrameSetLoaded(action: () -> Unit) {
        if (frameSetLoaded) action() else afterFrameSet += action
    }

    internal lateinit var inspect: ViewerInspectController
        private set

    internal var rawData: FloatArray? = null
        private set

    /** The reference's true size: from the Intent, else read from its header off the main thread. */
    internal var imageSize: ImageSize = ImageSize.UNKNOWN
        private set

    /** Every frame's solver parameters; a sweep varies them frame by frame. */
    internal val frameParams: FrameParams by lazy { args.frameParams }

    /**
     * Step size of the frame on screen. A parameter sweep varies it from frame
     * to frame — rendering, point picking and the report all key off it — so it
     * is re-read whenever a frame loads rather than fixed at launch.
     */
    internal var step = DicParams.DEFAULT_STEP
        private set

    /** Axis of the study's line cut through the ROI centre. */
    internal val lineCutHorizontal: Boolean get() = args.sweep?.lineCutHorizontal ?: true

    /** True when the frames are parameter combinations rather than images. */
    internal val isSweep: Boolean get() = args.sweep != null

    /** The ROI the run solved over. */
    internal val roi: Roi by lazy { args.roi }

    /** The reference at display size. Exports and the report read it; it is never a frame's photo. */
    internal val cachedBaseImage: Bitmap? get() = images.cachedBaseImage

    internal var currentTypeString: String
        get() = viewModel.currentTypeString
        set(value) {
            viewModel.currentTypeString = value
        }

    internal var batchFiles: List<File> = emptyList()
        private set

    /** The planned frame behind each of [batchFiles], by position; set with it. */
    internal var plannedFrames: List<Int> = emptyList()
        private set

    internal var defImagePaths: List<String> = emptyList()
        private set
    internal var currentFrameIndex: Int
        get() = viewModel.currentFrameIndex
        set(value) {
            viewModel.currentFrameIndex = value
        }

    internal val scrubCache = ScrubFrameCache()

    internal var currentDataIndex: Int
        get() = viewModel.currentDataIndex
        set(value) {
            viewModel.currentDataIndex = value
        }

    internal lateinit var summary: ViewerSummaryController
        private set

    /** True while the looping summary GIF is the thing on screen; see [FrameJumpController.showingSummary]. */
    internal val isShowingSummary: Boolean get() = frameJump.showingSummary

    internal fun summaryBatchFiles(): List<File> = batchFiles

    /** How many frames this analysis actually holds. */
    internal fun frameCount(): Int = batchFiles.size

    /**
     * The fixed colour scale of field [dataIndex], or null for the engine's
     * auto scale (the frame's own clamped range). Sequence-global scale is
     * reserved for the summary GIF / share animations, not the on-screen frame.
     */
    internal fun customBoundsFor(dataIndex: Int): ValueRange? = viewModel.customBounds[dataIndex]

    /** Called when [ViewerSummaryController] finishes the whole-sequence range pass. */
    internal fun onSequenceRangesReady() {
        // The summary colour bar is sequence-global; refresh ⓘ so it quotes
        // the same ends instead of the hidden first frame's extrema.
        if (!isShowingSummary) return
        val data = rawData ?: return
        val metrics = viewModel.fieldMetricsFor(currentFrameIndex, currentDataIndex, data)
        captions.applyFieldMetrics(metrics, currentDataIndex)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.viewColorScale.background = ColorScaleBar.drawable(resources)

        binding.imgHeatmapOverlay.setOnTouchListener { _, event -> binding.imgBaseResult.dispatchTouchEvent(event) }

        shareBanner = TransferBannerController(binding.transferBannerRoot.root)
        // Re-attaches any export a rotation left running.
        shareExports = ShareExportUi(this, viewModel.exports).also { it.attach() }

        Insets.padTop(binding.viewerTopStack)
        // Lifted, not padded, above the keyboard: the image is fitted to the
        // scrubber's height (wireContentInsets), so growing it would refit the frame.
        Insets.padBottomLiftAboveIme(binding.layoutScrubber)
        chrome.wireContentInsets()

        inspect = ViewerInspectController(this)
        // Warm [sessionRecord] here rather than at the share tap that needs it:
        // the lazy reads the session index off disk, and by lazy is synchronized,
        // so a tap arriving mid-read waits on the read it would have done itself
        // and never on a second one.
        lifecycleScope.launch(Dispatchers.IO) { sessionRecord }

        if (savedInstanceState != null) {
            val savedProbe = savedInstanceState.getInt(STATE_PROBE_INDEX, -1)
            inspect.restoreProbe(savedProbe)
            currentFrameIndex = savedInstanceState.getInt(STATE_FRAME, 0)
            frameJump.showingSummary = savedInstanceState.getBoolean(STATE_SHOWING_SUMMARY, false)
            share.restoreState(savedInstanceState)
        } else {
            // A lattice node tap asks to open on a specific frame; clamped once
            // the batch is loaded below.
            currentFrameIndex = args.startFrame ?: 0
            // Otherwise the summary is what the viewer opens on — it answers
            // "what happened across the test" before any single frame does.
            // Sweeps never use the summary slot (combinations are not a time series).
            frameJump.showingSummary = args.startFrame == null
        }
        // A save-as picked before the last viewer had listed its frames (a
        // rotation, or process death) waits in the ViewModel for this one.
        if (viewModel.hasPendingSave) share.startPendingSave()

        imageSize = args.imageSize
        step = frameParams.base.step
        // Sweep extras are available now; drop any restored summary flag.
        if (isSweep) frameJump.showingSummary = false

        val refPath = args.refPath.ifBlank { null }
        // Every writer puts the image size on the Intent; only an old one makes
        // the reference's header be read for it, off the main thread below.
        val dimsKnown = imageSize.isKnown
        if (dimsKnown) images.showReference(refPath)

        summary = ViewerSummaryController(this)

        // The directory listings, and a stat per frame, used to run here on the
        // main thread on every open. The first frame (and, with it, everything
        // the batch drives) starts once they are read, as it did before.
        val knownSize = imageSize
        lifecycleScope.launch {
            val set = withContext(frameSetDispatcher) { frames.readFrameSet(refPath, knownSize) }
            onFrameSetRead(set, refPath, dimsKnown)
        }

        binding.btnPrevFrame.setOnClickListener {
            bumpChrome()
            stepFrame(-1)
        }
        binding.btnNextFrame.setOnClickListener {
            bumpChrome()
            stepFrame(1)
        }

        frameJump.wireFrameJump()

        binding.imgBaseResult.onMatrixChangedListener = {
            scale.applyHeatmapMatrix()
            inspect.refreshCrosshairs()
            bumpChrome()
        }

        // Field FAB: shows the current field, tap opens a glass-pill popup of all
        // five with the live field checked. The live field comes back from the
        // ViewModel after a rotation, so re-derive the label or it disagrees
        // with the heatmap.
        binding.btnFieldFab.text = ViewerFieldPills.BY_ID[ViewerFieldPills.idFor(currentDataIndex)]?.first ?: "U"
        binding.btnFieldFab.setOnClickListener {
            bumpChrome()
            fieldPopup.show(it)
        }

        binding.btnViewerBack.setOnClickListener { finish() }
        val canShare = LicenseEntitlements.shareEnabled(this)
        binding.btnViewerShare.alpha = if (canShare) 1f else LicenseEntitlements.BLOCKED_ALPHA
        binding.btnViewerShare.setOnClickListener {
            if (!LicenseEntitlements.shareEnabled(this)) {
                CrispToast.show(this, getString(R.string.share_licensed_only), long = true)
                return@setOnClickListener
            }
            showShareSheet()
        }
        binding.btnViewerHome.setOnClickListener { goHome() }
        binding.btnViewerSettingsInfo.setOnClickListener {
            bumpChrome()
            ViewerSettingsSheet.show(this)
        }

        binding.layoutColorScale.setOnClickListener {
            bumpChrome()
            scale.showCustomScaleDialog()
        }
        inspect.wireTapHandling()
        frameJump.wireSummaryGestures()

        binding.imgBaseResult.post {
            inspect.refreshCrosshairs()
            bumpChrome()
        }
    }

    /** True once [onFrameSetRead] has run: [batchFiles] and the rest are final. */
    internal var frameSetLoaded = false
        private set

    /** Where the frame set is read. A test holds it to see what onCreate does without it. */
    @VisibleForTesting
    internal var frameSetDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** The rest of [onCreate], once the frame set is back: open the batch on its first frame or summary. */
    private fun onFrameSetRead(set: ViewerFrameLoader.FrameSet, refPath: String?, referenceShownAlready: Boolean) {
        imageSize = set.imageSize
        if (!referenceShownAlready) images.showReference(refPath)
        defImagePaths = set.defImagePaths
        batchFiles = set.batchFiles
        plannedFrames = set.plannedFrames
        frames.useFrameSet(set)
        viewModel.useFrameListing(set.batchFiles)
        frameSetLoaded = true

        if (batchFiles.isNotEmpty()) {
            // A START_FRAME (or restored index) past the batch would load nothing.
            currentFrameIndex = currentFrameIndex.coerceIn(0, batchFiles.lastIndex)
            binding.tvFrameTotal.text = getString(R.string.frame_total_fmt, batchFiles.size)
            frames.loadFrameData(currentFrameIndex)
            // Summary GIF / share animations are single-setting only.
            if (!isSweep && batchFiles.size > 1) summary.start()
            if (isShowingSummary && !isSweep) {
                frameJump.enterSummary()
            } else {
                frameJump.showingSummary = false
                frameJump.updateNavButtons()
                bumpChrome()
            }
        } else {
            frameJump.showingSummary = false
            FaqRedirect.snackbar(this, R.string.no_batch_data, R.string.url_faq_no_batch_data)
        }
        val waiting = afterFrameSet.toList()
        afterFrameSet.clear()
        waiting.forEach { it() }
    }

    /** Clears the back stack to Home. Used by the top-bar Home action. */
    internal fun goHome() {
        val home = Intent(this, HomeActivity::class.java)
        home.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(home)
        finish()
    }

    /** Max / min (with coordinates) / mean for the info peek sheet. */
    internal fun detailStatsText(): String = captions.detailStatsText()

    /** Bring edge chrome back, then schedule auto-hide. */
    internal fun bumpChrome() = chrome.bumpChrome()

    /**
     * Centre double-tap while chrome is hidden: show the bars. Returns true when
     * consumed so zoom does not also run.
     */
    internal fun showChromeIfHidden(): Boolean = chrome.showChromeIfHidden()

    /** Advance or retreat one frame (or leave/enter the summary). Used by buttons and fling. */
    internal fun stepFrame(delta: Int) = frameJump.stepFrame(delta)

    override fun onDestroy() {
        super.onDestroy()
        if (::binding.isInitialized) chrome.cancel()
        // The exports themselves run on in the ViewModel; only their dialogs go.
        if (::shareExports.isInitialized) shareExports.detach()
        frames.cancel()
        scale.cancel()
        frameJump.cancel()
        images.cancel()
        summary.cancel()
        scrubCache.clear(except = scale.cachedHeatmap)
        inspect.clearSpatialIndex()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PROBE_INDEX, inspect.lastClosestIdx)
        outState.putInt(STATE_FRAME, currentFrameIndex)
        outState.putBoolean(STATE_SHOWING_SUMMARY, isShowingSummary)
        share.saveState(outState)
    }

    internal fun applyLoadedFrame(index: Int, data: FloatArray) {
        rawData = data
        // A sweep's frames each have their own grid pitch.
        step = frameParams.at(index).step
        // Invalidate rather than rebuild: the O(n) bucket map is only needed for
        // probe nearest-point taps, and findNearestDataPoint builds it lazily
        // for the new frame. Scrubbing large frames no longer pays for an unused index.
        inspect.clearSpatialIndex()
        images.updateHeatmapFitBounds(data)
        images.showFrameBase(index)
        val displayName = frameDisplayName(index)
        if (!isShowingSummary) {
            binding.tvFrameCounter.text = "$displayName (${index + 1} / ${batchFiles.size})"
        }
        frameJump.syncFrameNumber()
        // updateVisualization warms this frame's stats off the main thread
        // and pushes them to the caption when the render completes.
        scale.updateVisualization(currentDataIndex)
        if (inspect.lastClosestIdx != -1) {
            inspect.refreshCrosshairs()
        }
    }

    /**
     * True when the frame on screen is drawn over its own photo, with its map
     * at the displaced positions. See [ViewerImageLoader.onFramePhoto].
     */
    internal val onFramePhoto: Boolean get() = images.onFramePhoto

    /**
     * Same rest-fit box the summary GIF should fill. Custom ROI when set;
     * otherwise null so [com.sempermechanics.semper.ui.viewer.summary.SummaryAnimation]
     * discovers accepted points from the first readable frame.
     */
    internal fun summaryFitBounds(): FloatArray? = roi.takeIf { it.isCustomFor(imageSize) }?.toLtrb()

    /**
     * The planned frame behind the [position]-th `.dat` on disk. A frame the
     * batch skipped leaves a gap in the numbering, so the two part ways there.
     */
    internal fun plannedFrameIndex(position: Int): Int = plannedFrames.getOrElse(position) { position }

    /**
     * What the frame at [position] is called on screen: its image name (or a
     * sweep's combination label), looked up by the planned frame, not the
     * position, so a frame after a skipped one keeps its own name.
     */
    internal fun frameDisplayName(position: Int): String {
        val planned = plannedFrameIndex(position)
        return ReportImageNames.frameName(args.frameNames, planned) ?: "Frame ${planned + 1}"
    }

    /**
     * The deformed image solved at [position], or null when it is not on disk.
     * Every node of a sweep solves the one deformed image. A batch looks its
     * frame up by the name the run persisted it under, since `raw_deformed/`
     * keeps the user's own file names and sorts them alphabetically, not in
     * frame order.
     */
    internal fun deformedImagePathAt(position: Int): String? =
        ViewerReportFactory.deformedImagePath(
            args,
            isSweep,
            defImagePaths,
            args.frameNames,
            plannedFrameIndex(position),
        )

    /**
     * The stored record behind this viewer, or null when it was opened without
     * one (a run still in flight, or a legacy Intent).
     *
     * Read once and kept: the share name, the CSV and every page of an
     * all-frames report want the same few fields off it, and re-reading the
     * session index per report page would be a file read per page.
     */
    internal val sessionRecord: SessionRecord? by lazy {
        intent.getStringExtra(DicKeys.SESSION_LOCAL_ID)
            ?.let { runCatching { SessionStore.get(this, it) }.getOrNull() }
    }

    /**
     * This viewer's arguments, parsed once (ADR-003). The record fallback reads
     * the session index only for an Intent missing a key, which no current
     * writer produces.
     */
    internal val args: ViewerArgs by lazy { ViewerArgs.from(intent) { sessionRecord } }

    /** Everything an export needs; see [ViewerShareController.buildShareSnapshot]. */
    internal fun buildShareSnapshot(): ShareCenter.Snapshot? = share.buildShareSnapshot()

    /** Share sheet (wireframe 08) - targets wired via ShareCenter. */
    private fun showShareSheet() {
        ShareCenter(this).show()
    }

    /** SAF CreateDocument for a slow export; generation starts only after a URI returns. */
    internal fun pickShareDocument(kind: ShareKind, filename: String) = share.pickShareDocument(kind, filename)

    private companion object {
        // Saved-state keys; their strings are what a restored viewer reads back.
        const val STATE_PROBE_INDEX = "LAST_CLOSEST_IDX"
        const val STATE_FRAME = "CURRENT_FRAME"
        const val STATE_SHOWING_SUMMARY = "SHOWING_SUMMARY"
    }
}
