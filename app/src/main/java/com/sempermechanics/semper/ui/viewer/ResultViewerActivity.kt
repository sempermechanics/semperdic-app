// Result viewer Activity: frame scrubbing, overlays, tap-to-probe and export
// live on one screen; the screen's parts are its controllers. onCreate wires
// them all, which is long by nature. Each controller declares the small host
// interface it reads the screen through, and this Activity implements them.
@file:Suppress("LongMethod", "TooManyFunctions")
@file:SuppressLint("SetTextI18n")

package com.sempermechanics.semper.ui.viewer

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.integrity.RecordIntegrity
import com.sempermechanics.semper.data.integrity.RecordVerifier
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.databinding.ActivityResultViewerBinding
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.FrameParams
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.report.ReportImageNames
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.dialog.CrispToast
import com.sempermechanics.semper.ui.common.dialog.FaqRedirect
import com.sempermechanics.semper.ui.common.dialog.LicenseLock
import com.sempermechanics.semper.ui.common.transfer.TransferBannerController
import com.sempermechanics.semper.ui.home.HomeActivity
import com.sempermechanics.semper.ui.viewer.inspect.InspectHost
import com.sempermechanics.semper.ui.viewer.inspect.ViewerInspectController
import com.sempermechanics.semper.ui.viewer.share.ShareCenter
import com.sempermechanics.semper.ui.viewer.share.ShareExportController
import com.sempermechanics.semper.ui.viewer.share.ViewerReportFactory
import com.sempermechanics.semper.ui.viewer.summary.SummaryHost
import com.sempermechanics.semper.ui.viewer.summary.ViewerSummaryController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
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
 * and [ViewerShareController] (what exports read). Each reads the screen
 * through its own host interface ([FrameLoaderHost], [ScaleHost], ...), which
 * this Activity implements, and is handed the parts it calls directly. The
 * frame and field on screen are the [viewModel]'s ([ViewerState]).
 */
@MainThread
class ResultViewerActivity :
    AppCompatActivity(),
    FrameLoaderHost,
    ImageHost,
    ScaleHost,
    FrameJumpHost,
    CaptionsHost,
    FieldPopupHost,
    ShareControllerHost,
    SummaryHost,
    InspectHost {

    override val viewModel: ResultViewerViewModel by viewModels()

    override lateinit var binding: ActivityResultViewerBinding
        private set

    // Built with the Activity, each before the parts that call it; none reads
    // the views until onCreate has set [binding].
    internal val chrome = ViewerChromeController(this)
    internal val summary = ViewerSummaryController(this, this)
    internal val captions = ViewerCaptions(this, this, summary)
    internal val images = ViewerImageLoader(this, this)
    internal val frames = ViewerFrameLoader(this, this, images, captions)
    internal val scale = ViewerScaleController(this, this, images, captions, summary)
    internal val inspect = ViewerInspectController(this, this, chrome, images)
    private val frameJump = FrameJumpController(this, chrome, frames, summary, inspect, captions)
    internal val share = ViewerShareController(this, this, images, summary) { shareExports }
    private val fieldPopup = FieldPopup(this, this, chrome, scale, summary, inspect)

    internal lateinit var shareBanner: TransferBannerController

    /** Shows the running exports, which live in [viewModel] and outlive this screen's rotations. */
    internal lateinit var shareExports: ShareExportController

    override var rawData: FloatArray? = null
        private set

    /**
     * The frame set this screen has opened ([onFrameSetRead]): the view
     * model's, listed once and kept across a rotation. Null until then.
     */
    private var opened: ViewerFrameLoader.FrameSet? = null

    /**
     * The frame [rawData] is, set with it ([applyLoadedFrame]); -1 until one is
     * drawn. Not [ResultViewerViewModel.currentFrameIndex]: Prev / Next move
     * that at once, before the debounced decode brings the frame's data.
     */
    override var rawFrameIndex = -1
        private set

    /** True once [onFrameSetRead] has run: [batchFiles] and the rest are final. */
    override val frameSetLoaded: Boolean get() = opened != null

    /** The reference's true size: from the Intent, else read from its header off the main thread. */
    override val imageSize: ImageSize get() = opened?.imageSize ?: args.imageSize

    /** Every frame's solver parameters; a sweep varies them frame by frame. */
    override val frameParams: FrameParams by lazy { args.frameParams }

    /**
     * Step size of the frame on screen. A parameter sweep varies it from frame
     * to frame — rendering, point picking and the report all key off it — so it
     * is re-read whenever a frame loads rather than fixed at launch.
     */
    override var step = DicParams.DEFAULT_STEP
        private set

    /** Axis of the study's line cut through the ROI centre. */
    internal val lineCutHorizontal: Boolean get() = args.sweep?.lineCutHorizontal ?: true

    /** True when the frames are parameter combinations rather than images. */
    override val isSweep: Boolean get() = args.sweep != null

    /** The ROI the run solved over. */
    override val roi: Roi by lazy { args.roi }

    override val batchFiles: List<File> get() = opened?.batchFiles.orEmpty()

    /** The planned frame behind each of [batchFiles], by position. */
    override val plannedFrames: List<Int> get() = opened?.plannedFrames.orEmpty()

    override val defImagePaths: List<String> get() = opened?.defImagePaths.orEmpty()

    override val scrubCache = ScrubFrameCache()

    /** True while the looping summary GIF is the thing on screen; see [FrameJumpController.showingSummary]. */
    override val isShowingSummary: Boolean get() = frameJump.showingSummary

    /** How many frames this analysis actually holds. */
    internal fun frameCount(): Int = batchFiles.size

    /** Called when [ViewerSummaryController] finishes the whole-sequence range pass. */
    override fun onSequenceRangesReady() {
        // The summary colour bar is sequence-global; refresh ⓘ so it quotes
        // the same ends instead of the hidden first frame's extrema.
        if (!isShowingSummary) return
        val data = rawData ?: return
        val metrics = viewModel.fieldMetricsFor(rawFrameIndex, viewModel.field.dataIndex, data)
        captions.applyFieldMetrics(metrics, viewModel.field.dataIndex)
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
        shareExports = ShareExportController(this, viewModel.exports, shareBanner).also { it.attach() }

        Insets.padTop(binding.viewerTopStack)
        // Lifted, not padded, above the keyboard: the image is fitted to the
        // scrubber's height (wireContentInsets), so growing it would refit the frame.
        Insets.padBottomLiftAboveIme(binding.layoutScrubber)
        chrome.wireContentInsets()

        // Warm [sessionRecord] here rather than at the share tap that needs it:
        // the lazy reads the session index off disk, and by lazy is synchronized,
        // so a tap arriving mid-read waits on the read it would have done itself
        // and never on a second one.
        lifecycleScope.launch(Dispatchers.IO) { sessionRecord }

        if (savedInstanceState != null) {
            val savedProbe = savedInstanceState.getInt(STATE_PROBE_INDEX, -1)
            inspect.restoreProbe(savedProbe)
            viewModel.currentFrameIndex = savedInstanceState.getInt(STATE_FRAME, 0)
            frameJump.showingSummary = savedInstanceState.getBoolean(STATE_SHOWING_SUMMARY, false)
            share.restoreState(savedInstanceState)
        } else {
            // A lattice node tap asks to open on a specific frame; clamped once
            // the batch is loaded below.
            viewModel.currentFrameIndex = args.startFrame ?: 0
            // Otherwise the summary is what the viewer opens on — it answers
            // "what happened across the test" before any single frame does.
            // Sweeps never use the summary slot (combinations are not a time series).
            frameJump.showingSummary = args.startFrame == null
        }
        step = frameParams.base.step
        // Sweep extras are available now; drop any restored summary flag.
        if (isSweep) frameJump.showingSummary = false

        val refPath = args.refPath.ifBlank { null }
        // Every writer puts the image size on the Intent; only an old one makes
        // the reference's header be read for it, off the main thread below.
        val dimsKnown = args.imageSize.isKnown
        if (dimsKnown) images.showReference(refPath)

        summary.wire()

        // Past 300 ms, "Opening … · N frames" until the first frame draws.
        captions.showOpening()
        // The directory listings, and a stat per frame, used to run here on the
        // main thread on every open. The view model reads them once, off the
        // main thread, and keeps them across a rotation; the first frame (and,
        // with it, everything the batch drives) starts once they are there.
        val knownSize = args.imageSize
        val viewerArgs = args
        val recordDir = args.batchDirPath?.let(::File)
        val appContext = applicationContext
        viewModel.listFrames(
            frameSetDispatcher,
            check = { recordDir?.let { RecordVerifier.verify(appContext, it) } ?: RecordIntegrity.NotRecorded },
        ) { ViewerFrameLoader.readFrameSet(viewerArgs, refPath, knownSize) }
        lifecycleScope.launch {
            val set = viewModel.state.mapNotNull { it.frameSet }.first()
            onFrameSetRead(set, refPath, dimsKnown)
        }

        binding.btnPrevFrame.setOnClickListener {
            chrome.bumpChrome()
            frameJump.stepFrame(-1)
        }
        binding.btnNextFrame.setOnClickListener {
            chrome.bumpChrome()
            frameJump.stepFrame(1)
        }

        frameJump.wireFrameJump()

        binding.imgBaseResult.onMatrixChangedListener = {
            scale.applyHeatmapMatrix()
            inspect.refreshCrosshairs()
            chrome.bumpChrome()
        }

        // Field FAB: shows the current field, tap opens a glass-pill popup of all
        // five with the live field checked. The live field comes back from the
        // ViewModel after a rotation, so re-derive the label or it disagrees
        // with the heatmap.
        binding.btnFieldFab.text = viewModel.field.label
        binding.btnFieldFab.setOnClickListener {
            chrome.bumpChrome()
            fieldPopup.show(it)
        }

        binding.btnViewerBack.setOnClickListener { finish() }
        // Greyed, never hidden: locked without the licence, paused by the kill switch.
        LicenseLock.mark(binding.btnViewerShare, ShareCenter.access(this))
        binding.btnViewerShare.setOnClickListener {
            val blocked = ShareCenter.blockedMessage(ShareCenter.access(this))
            if (blocked != null) {
                CrispToast.show(this, getString(blocked), long = true)
                return@setOnClickListener
            }
            showShareSheet()
        }
        binding.btnViewerHome.setOnClickListener { goHome() }
        binding.btnViewerSettingsInfo.setOnClickListener {
            chrome.bumpChrome()
            SettingsUsedSheet.show(this)
        }

        binding.layoutColorScale.setOnClickListener {
            chrome.bumpChrome()
            scale.showCustomScaleDialog()
        }
        inspect.wireTapHandling(onScrub = frameJump::stepFrame)
        frameJump.wireSummaryGestures()

        binding.imgBaseResult.post {
            inspect.refreshCrosshairs()
            chrome.bumpChrome()
        }
    }

    /** Where the frame set is read. A test holds it to see what onCreate does without it. */
    @VisibleForTesting
    internal var frameSetDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** The rest of [onCreate], once the frame set is back: open the batch on its first frame or summary. */
    private fun onFrameSetRead(set: ViewerFrameLoader.FrameSet, refPath: String?, referenceShownAlready: Boolean) {
        opened = set
        if (!referenceShownAlready) images.showReference(refPath)
        frames.useFrameSet(set)

        if (batchFiles.isNotEmpty()) {
            // A START_FRAME (or restored index) past the batch would load nothing.
            viewModel.currentFrameIndex = viewModel.currentFrameIndex.coerceIn(0, batchFiles.lastIndex)
            binding.tvFrameTotal.text = getString(R.string.frame_total_fmt, batchFiles.size)
            frames.loadFrameData(viewModel.currentFrameIndex)
            // Summary GIF / share animations are single-setting only.
            if (!isSweep && batchFiles.size > 1) summary.start()
            if (isShowingSummary && !isSweep) {
                frameJump.enterSummary()
            } else {
                frameJump.showingSummary = false
                frameJump.updateNavButtons()
                chrome.bumpChrome()
            }
        } else {
            frameJump.showingSummary = false
            captions.openingDone()
            FaqRedirect.snackbar(this, R.string.no_batch_data, R.string.url_faq_no_batch_data)
        }
        // A save-as picked before this viewer had its frames (picked on a
        // viewer a rotation replaced, or before process death) starts now.
        share.startPendingSave()
    }

    /** Clears the back stack to Home. Used by the top-bar Home action. */
    internal fun goHome() {
        val home = Intent(this, HomeActivity::class.java)
        home.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(home)
        finish()
    }

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
        outState.putInt(STATE_FRAME, viewModel.currentFrameIndex)
        outState.putBoolean(STATE_SHOWING_SUMMARY, isShowingSummary)
        share.saveState(outState)
    }

    override fun applyLoadedFrame(index: Int, data: FloatArray) {
        rawData = data
        rawFrameIndex = index
        // A sweep's frames each have their own grid pitch.
        step = frameParams.at(index).step
        // Invalidate rather than rebuild: the O(n) bucket map is only needed for
        // probe nearest-point taps, and findNearestDataPoint builds it lazily
        // for the new frame. Scrubbing large frames no longer pays for an unused index.
        inspect.clearSpatialIndex()
        images.updateHeatmapFitBounds(data)
        images.showFrameBase(index)
        // The frame's name is the edge title (ViewerCaptions.title); the jump field counts.
        frameJump.syncFrameNumber()
        // updateVisualization warms this frame's stats off the main thread
        // and pushes them to the caption when the render completes.
        scale.updateVisualization(viewModel.field.dataIndex)
        if (inspect.lastClosestIdx != -1) {
            inspect.refreshCrosshairs()
        }
    }

    /**
     * Names the frame that could not be read and puts the frame field back on
     * the frame still on screen, so the number never claims a picture that is
     * not there (readiness AE1-013).
     */
    override fun onFrameUnreadable(index: Int) {
        val shown = rawFrameIndex
        val message = if (backToShownFrame(index)) {
            getString(R.string.viewer_frame_unreadable_fmt, index + 1, shown + 1)
        } else {
            getString(R.string.viewer_frame_unreadable_none_fmt, index + 1)
        }
        CrispToast.show(this, message, long = true)
    }

    /** The loader has said why (its out-of-memory snackbar); the frame field goes back to the frame on screen. */
    override fun onFrameOutOfMemory(index: Int) {
        backToShownFrame(index)
    }

    /**
     * Puts the frame field (and [ResultViewerViewModel.currentFrameIndex]) back
     * on the frame still on screen, in place of [index] that did not load.
     * False when there is no other frame on screen to go back to.
     */
    private fun backToShownFrame(index: Int): Boolean {
        val shown = rawFrameIndex
        if (shown !in batchFiles.indices || shown == index) return false
        viewModel.currentFrameIndex = shown
        frameJump.updateNavButtons()
        frameJump.syncFrameNumber()
        return true
    }

    /**
     * Same rest-fit box the summary GIF should fill. Custom ROI when set;
     * otherwise null so [com.sempermechanics.semper.ui.viewer.summary.SummaryAnimation]
     * discovers accepted points from the first readable frame.
     */
    override fun summaryFitBounds(): FloatArray? = roi.takeIf { it.isCustomFor(imageSize) }?.toLtrb()

    /**
     * The planned frame behind the [position]-th `.dat` on disk. A frame the
     * batch skipped leaves a gap in the numbering, so the two part ways there.
     */
    override fun plannedFrameIndex(position: Int): Int = plannedFrames.getOrElse(position) { position }

    /**
     * What the frame at [position] is called on screen: its image name (or a
     * sweep's combination label), looked up by the planned frame, not the
     * position, so a frame after a skipped one keeps its own name.
     */
    override fun frameDisplayName(position: Int): String {
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
    override fun deformedImagePathAt(position: Int): String? =
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
    override val sessionRecord: SessionRecord? by lazy {
        intent.getStringExtra(IntentKeys.SESSION_LOCAL_ID)
            ?.let { runCatching { SessionStore.get(this, it) }.getOrNull() }
    }

    /**
     * This viewer's arguments, parsed once (ADR-003). The record fallback reads
     * the session index only for an Intent missing a key, which no current
     * writer produces.
     */
    override val args: ViewerArgs by lazy { ViewerArgs.from(intent) { sessionRecord } }

    /** Share sheet (wireframe 08) - targets wired via ShareCenter. */
    private fun showShareSheet() {
        ShareCenter(this, share).show()
    }

    private companion object {
        // Saved-state keys; their strings are what a restored viewer reads back.
        const val STATE_PROBE_INDEX = "LAST_CLOSEST_IDX"
        const val STATE_FRAME = "CURRENT_FRAME"
        const val STATE_SHOWING_SUMMARY = "SHOWING_SUMMARY"
    }
}
