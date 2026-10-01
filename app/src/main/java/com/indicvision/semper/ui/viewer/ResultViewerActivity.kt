// Result viewer Activity: frame scrubbing, overlays, tap-to-probe and export
// live on one screen. Size and branching are inherent; suppress rather than
// baseline so new findings elsewhere still fail CI.

@file:Suppress(
    "TooManyFunctions",
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "LongMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "LargeClass",
)
@file:SuppressLint("SetTextI18n")

package com.indicvision.semper.ui.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Trace
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.indicvision.semper.R
import com.indicvision.semper.data.account.LicenseEntitlements
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.ReportImageNames
import com.indicvision.semper.report.RoiData
import com.indicvision.semper.report.VisualizationEngine
import com.indicvision.semper.ui.common.CrispToast
import com.indicvision.semper.ui.common.FaqRedirect
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.viewer.inspect.InspectOverlayView
import com.indicvision.semper.ui.viewer.inspect.TouchImageView
import com.indicvision.semper.ui.viewer.inspect.ViewerInspectHelper
import com.indicvision.semper.ui.viewer.share.ShareCenter
import com.indicvision.semper.ui.viewer.share.ShareExportUi
import com.indicvision.semper.ui.viewer.share.ViewerReportFactory
import com.indicvision.semper.ui.viewer.summary.SummaryAnimation
import com.indicvision.semper.ui.viewer.summary.SummaryCaption
import com.indicvision.semper.ui.viewer.summary.ViewerSummaryHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Results browser: renders each frame's displacement/strain heatmap over that
 * frame's own photo, drawn where the points moved to (the reference when the
 * photo is not on disk), with frame scrubbing, tap-to-probe readings, custom color scales,
 * and all exports (PDF/CSV/PNG/ZIP via [ShareCenter]).
 */
@MainThread
class ResultViewerActivity : AppCompatActivity() {

    private val viewerVm: ResultViewerViewModel by viewModels()

    internal lateinit var imgMain: TouchImageView
    private lateinit var imgHeatmap: ImageView

    private lateinit var btnPrevFrame: ImageButton
    private lateinit var btnNextFrame: ImageButton
    private lateinit var tvFrameCounter: TextView
    private lateinit var tvFinding: TextView
    private lateinit var tvStatsCaption: TextView

    private lateinit var layoutColorScale: LinearLayout
    private lateinit var tvScaleMax: TextView
    private lateinit var tvScaleMin: TextView
    private lateinit var chromeTop: View
    private lateinit var layoutScrubber: View
    private lateinit var btnFieldFab: MaterialButton
    private var chromeVisible = true

    internal lateinit var tvProbeReadout: TextView
    internal lateinit var glassShield: InspectOverlayView

    /** Max / min (with coordinates) / mean for the info peek sheet. */
    private var detailStats: String = ""

    // Not while a frame number is being typed: hiding the scrubber takes the
    // field's focus, which commits it and closes the keyboard mid-number. The
    // commit on focus loss bumps the chrome, so the hide is rescheduled then.
    private val hideChromeRunnable = Runnable {
        if (!etFrameNumber.hasFocus()) fadeChrome(visible = false)
    }

    internal lateinit var shareBanner: com.indicvision.semper.ui.common.TransferBannerController
    private val chromeHideDelayMs = 2_500L

    /** Shows the running exports, which live in [viewerVm] and outlive this screen's rotations. */
    internal lateinit var shareExports: ShareExportUi

    /** Stashed while the SAF save-as picker is open for a slow share export. */
    internal var pendingShareKind: String? = null

    /** Run once the frame set is read; see [whenFrameSetLoaded]. */
    private val afterFrameSet = mutableListOf<() -> Unit>()

    private val createShareDocument = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val kind = pendingShareKind
        pendingShareKind = null
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null || kind == null) return@registerForActivityResult
        viewerVm.setPendingSave(kind, uri)
        startPendingSave()
    }

    /**
     * Starts the ViewModel's pending save-as export once the frames it covers
     * are known. Taking it clears it, so a viewer recreated after the export
     * started does not start it again.
     */
    private fun startPendingSave() = whenFrameSetLoaded {
        val (kind, uri) = viewerVm.takePendingSave() ?: return@whenFrameSetLoaded
        ShareCenter(this).writeKindToUri(kind, uri)
    }

    /** Runs [action] now if the frame set is read, else right after [onFrameSetRead]. */
    private fun whenFrameSetLoaded(action: () -> Unit) {
        if (frameSetLoaded) action() else afterFrameSet += action
    }

    private lateinit var inspect: ViewerInspectHelper

    internal var rawData: FloatArray? = null
    internal var imgW = 0
    internal var imgH = 0

    /**
     * Step size of the frame on screen. A parameter sweep varies it from frame
     * to frame — rendering, point picking and the report all key off it — so it
     * is re-read whenever a frame loads rather than fixed at launch.
     */
    internal var step = 5

    /** Step size for an ordinary analysis, where every frame shares one. */
    internal var baseStep = 5

    // A sweep hands over one setting triple per frame; null for a normal run.
    internal var sweepSubsets: IntArray? = null
    internal var sweepSteps: IntArray? = null
    internal var sweepStrainWins: IntArray? = null

    /** Axis of the study's line cut through the ROI centre. */
    internal var lineCutHorizontal = true

    /** True when the frames are parameter combinations rather than images. */
    internal val isSweep: Boolean get() = sweepSteps != null

    internal var roiX = 0
    internal var roiY = 0
    internal var roiW = 0
    internal var roiH = 0

    /** The reference at display size. Exports and the report read it; it is never a frame's photo. */
    internal var cachedBaseImage: Bitmap? = null
    private var cachedHeatmap: Bitmap? = null

    /**
     * Each frame's own photo by position, looked up off the main thread in
     * [readFrameDat]; "" when it is not on disk. See [onFramePhoto].
     */
    private val framePhotos = ConcurrentHashMap<Int, String>()

    /** The frame photo under the map now, and its path; null while the reference is shown. */
    private var framePhotoBitmap: Bitmap? = null
    private var framePhotoPath: String? = null
    private var framePhotoJob: Job? = null

    /** True once [cachedBaseImage] is the image under the map. */
    private var referenceShown = false
    internal var currentTypeString: String
        get() = viewerVm.currentTypeString
        set(value) {
            viewerVm.currentTypeString = value
        }
    private var isGeneratingHeatmap = false

    private var batchFiles: List<File> = emptyList()

    /** The planned frame behind each of [batchFiles], by position; set with it. */
    private var plannedFrames: List<Int> = emptyList()

    /**
     * Largest `.dat` size, computed once when [batchFiles] is set. The prefetch
     * heap guard used to `stat()` every file on every frame load (3F syscalls per
     * scrub step); the file set never changes after onCreate, so one scan suffices.
     */
    private var maxDatBytes: Long = 0L
    internal var originalDefNames: List<String> = emptyList()

    private var refImagePath: String? = null
    private var defImagePaths: List<String> = emptyList()
    internal var currentFrameIndex: Int
        get() = viewerVm.currentFrameIndex
        set(value) {
            viewerVm.currentFrameIndex = value
        }
    private var loadFrameJob: Job? = null

    /** The single in-flight look-ahead worker; see [prefetchAround]. */
    private var prefetchJob: Job? = null

    /** Previous look-ahead centre, used to infer scrub direction. */
    private var lastPrefetchCenter = 0
    private var visualizationJob: Job? = null
    private var scrubDebounceJob: Job? = null
    private var refDecodeJob: Job? = null

    private val scrubCache = ScrubFrameCache()

    internal var currentDataIndex: Int
        get() = viewerVm.currentDataIndex
        set(value) {
            viewerVm.currentDataIndex = value
        }

    private var currentHeatmapMin = 0f
    private var currentHeatmapMax = 0f

    /** Fixed colour scales per field; in the ViewModel so a rotation keeps them. */
    private val customBoundsMap: MutableMap<Int, Pair<Float, Float>> get() = viewerVm.customBounds

    private lateinit var etFrameNumber: EditText
    private lateinit var tvFrameTotal: TextView
    private lateinit var layoutFrameJump: View
    private lateinit var summary: ViewerSummaryHelper

    /**
     * True while the summary animation is up instead of a frame. It sits before
     * frame 1: Prev from frame 1 reaches it, Next leaves it.
     */
    private var showingSummary = false

    /** True while the looping summary GIF is the thing on screen. */
    internal val isShowingSummary: Boolean get() = showingSummary

    internal fun summaryBatchFiles(): List<File> = batchFiles

    /** How many frames this analysis actually holds. */
    internal fun frameCount(): Int = batchFiles.size

    internal fun customBoundsFor(dataIndex: Int): Pair<Float, Float>? = customBoundsMap[dataIndex]

    /**
     * Colour-scale bounds for the heatmap: custom override, else the current
     * frame's own min/max (engine Auto). Sequence-global scale is reserved for
     * the summary GIF / share animations, not the on-screen frame.
     */
    private fun scaleBoundsFor(dataIndex: Int): Pair<Float, Float>? {
        return customBoundsMap[dataIndex]
    }

    /** Called when [ViewerSummaryHelper] finishes the whole-sequence range pass. */
    internal fun onSequenceRangesReady() {
        // The summary colour bar is sequence-global; refresh ⓘ so it quotes
        // the same ends instead of the hidden first frame's extrema.
        if (!showingSummary) return
        val data = rawData ?: return
        applyFieldMetrics(fieldMetricsFor(currentFrameIndex, currentDataIndex, data), currentDataIndex)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result_viewer)

        imgMain = findViewById(R.id.imgBaseResult)
        imgHeatmap = findViewById(R.id.imgHeatmapOverlay)
        imgHeatmap.setOnTouchListener { _, event -> imgMain.dispatchTouchEvent(event) }

        btnPrevFrame = findViewById(R.id.btnPrevFrame)
        btnNextFrame = findViewById(R.id.btnNextFrame)
        tvFrameCounter = findViewById(R.id.tvFrameCounter)
        tvFinding = findViewById(R.id.tvFinding)
        tvStatsCaption = findViewById(R.id.tvStatsCaption)
        etFrameNumber = findViewById(R.id.etFrameNumber)
        tvFrameTotal = findViewById(R.id.tvFrameTotal)
        layoutFrameJump = findViewById(R.id.layoutFrameJump)

        layoutColorScale = findViewById(R.id.layoutColorScale)
        tvScaleMax = findViewById(R.id.tvScaleMax)
        tvScaleMin = findViewById(R.id.tvScaleMin)
        chromeTop = findViewById(R.id.chromeTop)
        layoutScrubber = findViewById(R.id.layoutScrubber)
        btnFieldFab = findViewById(R.id.btnFieldFab)
        shareBanner = com.indicvision.semper.ui.common.TransferBannerController(
            findViewById(R.id.transferBannerRoot),
        )
        // Re-attaches any export a rotation left running.
        shareExports = ShareExportUi(this, viewerVm.exports).also { it.attach() }

        Insets.padTop(findViewById(R.id.viewerTopStack))
        // Lifted, not padded, above the keyboard: the image is fitted to the
        // scrubber's height (wireContentInsets), so growing it would refit the frame.
        Insets.padBottomLiftAboveIme(layoutScrubber)
        wireContentInsets()

        tvProbeReadout = findViewById(R.id.tvProbeReadout)
        glassShield = findViewById(R.id.glassShield)

        inspect = ViewerInspectHelper(this)
        // Warm [sessionRecord] here rather than at the share tap that needs it:
        // the lazy reads the session index off disk, and by lazy is synchronized,
        // so a tap arriving mid-read waits on the read it would have done itself
        // and never on a second one.
        lifecycleScope.launch(Dispatchers.IO) { sessionRecord }

        if (savedInstanceState != null) {
            val savedProbe = savedInstanceState.getInt("LAST_CLOSEST_IDX", -1)
            inspect.restoreProbe(savedProbe)
            currentFrameIndex = savedInstanceState.getInt("CURRENT_FRAME", 0)
            showingSummary = savedInstanceState.getBoolean("SHOWING_SUMMARY", false)
            pendingShareKind = savedInstanceState.getString(STATE_SHARE_KIND)
        } else {
            // A lattice node tap asks to open on a specific frame; clamped once
            // the batch is loaded below.
            currentFrameIndex = args.startFrame ?: 0
            // Otherwise the summary is what the viewer opens on — it answers
            // "what happened across the test" before any single frame does.
            // Sweeps never use the summary slot (combinations are not a time series).
            showingSummary = args.startFrame == null
        }
        // A save-as picked before the last viewer had listed its frames (a
        // rotation, or process death) waits in the ViewModel for this one.
        if (viewerVm.hasPendingSave) startPendingSave()

        imgW = args.imgW
        imgH = args.imgH
        baseStep = args.step
        step = baseStep
        sweepSubsets = args.sweep?.subsets?.toIntArray()
        sweepSteps = args.sweep?.steps?.toIntArray()
        sweepStrainWins = args.sweep?.strainWindows?.toIntArray()
        lineCutHorizontal = args.sweep?.lineCutHorizontal ?: true
        // Sweep extras are available now; drop any restored summary flag.
        if (isSweep) showingSummary = false

        roiX = args.roiX
        roiY = args.roiY
        roiW = args.roiW
        roiH = args.roiH

        val refPath = args.refPath.ifBlank { null }
        originalDefNames = args.frameNames
        refImagePath = refPath
        // Every writer puts the image size on the Intent; only an old one makes
        // the reference's header be read for it, off the main thread below.
        val dimsKnown = imgW > 0 && imgH > 0
        if (dimsKnown) showReference(refPath)

        summary = ViewerSummaryHelper(this)

        // The directory listings, and a stat per frame, used to run here on the
        // main thread on every open. The first frame (and, with it, everything
        // the batch drives) starts once they are read, as it did before.
        val knownW = imgW
        val knownH = imgH
        lifecycleScope.launch {
            val set = withContext(frameSetDispatcher) { readFrameSet(refPath, knownW, knownH) }
            onFrameSetRead(set, refPath, dimsKnown)
        }

        btnPrevFrame.setOnClickListener {
            bumpChrome()
            stepFrame(-1)
        }
        btnNextFrame.setOnClickListener {
            bumpChrome()
            stepFrame(1)
        }

        wireFrameJump()

        imgMain.onMatrixChangedListener = {
            applyHeatmapMatrix()
            inspect.refreshCrosshairs()
            bumpChrome()
        }

        // Field FAB: shows the current field, tap opens a glass-pill popup of all
        // five with the live field checked. The live field comes back from the
        // ViewModel after a rotation, so re-derive the label or it disagrees
        // with the heatmap.
        btnFieldFab.text = ViewerFieldPills.BY_ID[ViewerFieldPills.idFor(currentDataIndex)]?.first ?: "U"
        btnFieldFab.setOnClickListener {
            bumpChrome()
            showFieldPopup(it)
        }

        findViewById<View>(R.id.btnViewerBack).setOnClickListener { finish() }
        val shareBtn = findViewById<View>(R.id.btnViewerShare)
        val canShare = LicenseEntitlements.shareEnabled(this)
        shareBtn.alpha = if (canShare) 1f else LicenseEntitlements.BLOCKED_ALPHA
        shareBtn.setOnClickListener {
            if (!LicenseEntitlements.shareEnabled(this)) {
                CrispToast.show(this, getString(R.string.share_licensed_only), long = true)
                return@setOnClickListener
            }
            showShareSheet()
        }
        findViewById<View>(R.id.btnViewerHome).setOnClickListener { goHome() }
        findViewById<View>(R.id.btnViewerSettingsInfo).setOnClickListener {
            bumpChrome()
            ViewerSettingsSheet.show(this)
        }

        layoutColorScale.setOnClickListener {
            bumpChrome()
            showCustomScaleDialog()
        }
        inspect.wireTapHandling()
        wireSummaryGestures()

        imgMain.post {
            inspect.refreshCrosshairs()
            bumpChrome()
        }
    }

    /** What [onCreate] needs from disk before the first frame can load; see [readFrameSet]. */
    private class FrameSet(
        val imgW: Int,
        val imgH: Int,
        val defImagePaths: List<String>,
        val batchFiles: List<File>,
        val plannedFrames: List<Int>,
        val maxDatBytes: Long,
    )

    /** True once [onFrameSetRead] has run: [batchFiles] and the rest are final. */
    internal var frameSetLoaded = false
        private set

    /** Where [readFrameSet] runs. A test holds it to see what onCreate does without it. */
    @VisibleForTesting
    internal var frameSetDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Reads the batch listing, the deformed originals, each frame's size and (for an
     * Intent without it) the reference's dimensions. Disk only — call it off the
     * main thread.
     */
    private fun readFrameSet(refPath: String?, knownW: Int, knownH: Int): FrameSet {
        var w = knownW
        var h = knownH
        if ((w <= 0 || h <= 0) && refPath != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(refPath, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                w = bounds.outWidth
                h = bounds.outHeight
            }
        }
        val batchDirPath = args.batchDirPath
        // Prefer the raw deformed originals persisted in the session dir (survive
        // reopen/eviction); fall back to the just-analysed session's temp paths.
        val rawDeformedDir = batchDirPath?.let { File(it, SessionPaths.RAW_DEFORMED_SUBDIR) }
        val defPaths = rawDeformedDir?.takeIf { it.isDirectory }
            ?.listFiles()?.sortedBy { it.name }?.map { it.absolutePath }
            ?: args.defFilePaths
        val dir = batchDirPath?.let { File(it) }?.takeIf { it.isDirectory }
        val files = dir?.listFiles { file -> file.extension == "dat" }?.sortedBy { it.name }.orEmpty()
        return FrameSet(
            imgW = w,
            imgH = h,
            defImagePaths = defPaths,
            batchFiles = files,
            plannedFrames = SessionPaths.plannedFrameIndices(files),
            // Once, here: the prefetch heap guard used to stat() every file per load.
            maxDatBytes = files.maxOfOrNull { it.length() } ?: 0L,
        )
    }

    /** Puts the reference on screen at its true dimensions (decoded off-main, display size). */
    private fun showReference(refPath: String?) {
        // True sensor dims stay on the intent for math / probe / export; the
        // on-screen bitmap is decoded off-main at ImageView scale.
        imgMain.setTrueImageDimensions(imgW, imgH)
        updateHeatmapFitBounds(data = null)
        if (refPath != null) {
            decodeReferenceForDisplay(refPath)
        }
    }

    /** The rest of [onCreate], once [readFrameSet] is back: open the batch on its first frame or summary. */
    private fun onFrameSetRead(set: FrameSet, refPath: String?, referenceShownAlready: Boolean) {
        imgW = set.imgW
        imgH = set.imgH
        if (!referenceShownAlready) showReference(refPath)
        defImagePaths = set.defImagePaths
        batchFiles = set.batchFiles
        plannedFrames = set.plannedFrames
        maxDatBytes = set.maxDatBytes
        frameSetLoaded = true

        if (batchFiles.isNotEmpty()) {
            // A START_FRAME (or restored index) past the batch would load nothing.
            currentFrameIndex = currentFrameIndex.coerceIn(0, batchFiles.lastIndex)
            tvFrameTotal.text = getString(R.string.frame_total_fmt, batchFiles.size)
            loadFrameData(currentFrameIndex)
            // Summary GIF / share animations are single-setting only.
            if (!isSweep && batchFiles.size > 1) summary.start()
            if (showingSummary && !isSweep) {
                enterSummary()
            } else {
                showingSummary = false
                updateNavButtons()
                bumpChrome()
            }
        } else {
            showingSummary = false
            FaqRedirect.snackbar(this, R.string.no_batch_data, R.string.url_faq_no_batch_data)
        }
        val waiting = afterFrameSet.toList()
        afterFrameSet.clear()
        waiting.forEach { it() }
    }

    /**
     * Measures the top bar and scrub bar once they've laid out and feeds those
     * sizes to [imgMain] as content insets, so the heatmap's fit-to-screen view
     * fills the space between them (full width). The colour scale is a sibling
     * overlay on the right — it may cover the image; it is not a reserved inset.
     * Inset values only change on a real layout event (initial layout, rotation)
     * — [fadeChrome] toggles VISIBLE/INVISIBLE, never GONE, so a bar keeps its
     * laid-out size while faded and the safe area stays stable through the
     * auto-hide animation.
     */
    private fun wireContentInsets() {
        imgMain.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val top = chromeTop.height
                    val bottom = layoutScrubber.height
                    if (top > 0 && bottom > 0) {
                        imgMain.setContentInsets(top = top, bottom = bottom)
                    }
                }
            },
        )
    }

    /** Glass-pill popup listing every field; the live field is checked. */
    private fun showFieldPopup(anchor: View) {
        @SuppressLint("InflateParams")
        val popupView = layoutInflater.inflate(R.layout.popup_field_options, null)
        val window = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        )
        window.isOutsideTouchable = true
        ViewerFieldPills.BY_ID.forEach { (id, pair) ->
            val (label, index) = pair
            val button = popupView.findViewById<MaterialButton>(id)
            button.text = label
            button.isCheckable = true
            button.isChecked = index == currentDataIndex
            button.setOnClickListener {
                if (index == currentDataIndex) {
                    window.dismiss()
                    return@setOnClickListener
                }
                currentTypeString = label
                currentDataIndex = index
                btnFieldFab.text = label
                // Caption + probe value are refreshed by updateVisualization once the
                // new field's metrics are computed off the main thread.
                updateVisualization(currentDataIndex)
                summary.onFieldChanged()
                if (showingSummary) tvFrameCounter.text = summary.counterText()
                inspect.refreshCrosshairs()
                bumpChrome()
                window.dismiss()
            }
        }
        window.showAsDropDown(anchor, 0, 4)
    }

    /** Clears the back stack to Home. Used by the top-bar Home action. */
    internal fun goHome() {
        val home = Intent(this, com.indicvision.semper.ui.home.HomeActivity::class.java)
        home.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(home)
        finish()
    }

    /** Max / min (with coordinates) / mean for the info peek sheet. */
    internal fun detailStatsText(): String = detailStats.ifBlank { getString(R.string.stat_empty) }

    /** Bring edge chrome back, then schedule auto-hide. */
    internal fun bumpChrome() {
        if (chromeVisible) {
            chromeTop.removeCallbacks(hideChromeRunnable)
            chromeTop.postDelayed(hideChromeRunnable, chromeHideDelayMs)
            return
        }
        fadeChrome(visible = true)
        chromeTop.removeCallbacks(hideChromeRunnable)
        chromeTop.postDelayed(hideChromeRunnable, chromeHideDelayMs)
    }

    internal fun hideChrome() {
        chromeTop.removeCallbacks(hideChromeRunnable)
        fadeChrome(visible = false)
    }

    /**
     * Centre double-tap while chrome is hidden: show the bars. Returns true when
     * consumed so zoom does not also run.
     */
    internal fun showChromeIfHidden(): Boolean {
        if (chromeVisible) return false
        bumpChrome()
        return true
    }

    private fun fadeChrome(visible: Boolean) {
        chromeVisible = visible
        listOf(chromeTop, layoutScrubber, btnFieldFab, layoutColorScale).forEach { bar ->
            bar.animate().cancel()
            if (visible) {
                bar.visibility = View.VISIBLE
                if (bar.alpha < 0.99f) {
                    bar.animate().alpha(1f).setDuration(180L).start()
                } else {
                    bar.alpha = 1f
                }
            } else {
                bar.animate()
                    .alpha(0f)
                    .setDuration(320L)
                    .withEndAction { bar.visibility = View.INVISIBLE }
                    .start()
            }
        }
    }

    /** Advance or retreat one frame (or leave/enter the summary). Used by buttons and fling. */
    internal fun stepFrame(delta: Int) {
        bumpChrome()
        if (delta == 0) return
        if (delta > 0) {
            if (showingSummary) {
                leaveSummary()
            } else if (currentFrameIndex < batchFiles.size - 1) {
                currentFrameIndex++
                updateNavButtons()
                requestFrameLoad(debounced = true)
            }
            return
        }
        when {
            showingSummary -> Unit
            currentFrameIndex == 0 -> if (!isSweep) enterSummary()
            else -> {
                currentFrameIndex--
                updateNavButtons()
                requestFrameLoad(debounced = true)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::chromeTop.isInitialized) {
            chromeTop.removeCallbacks(hideChromeRunnable)
            chromeTop.animate().cancel()
            layoutScrubber.animate().cancel()
            btnFieldFab.animate().cancel()
            layoutColorScale.animate().cancel()
        }
        // The exports themselves run on in the ViewModel; only their dialogs go.
        if (::shareExports.isInitialized) shareExports.detach()
        loadFrameJob?.cancel()
        visualizationJob?.cancel()
        scrubDebounceJob?.cancel()
        refDecodeJob?.cancel()
        framePhotoJob?.cancel()
        summary.cancel()
        scrubCache.clear(except = cachedHeatmap)
        inspect.clearSpatialIndex()
    }

    /**
     * Decode the reference off the main thread with [BitmapFactory.Options.inSampleSize]
     * sized to the ImageView (capped by [VisualizationEngine.DISPLAY_MAX_EDGE]).
     */
    private fun decodeReferenceForDisplay(refPath: String) {
        imgMain.post {
            val viewW = imgMain.width.coerceAtLeast(1)
            val viewH = imgMain.height.coerceAtLeast(1)
            val reqW = viewW.coerceAtMost(VisualizationEngine.DISPLAY_MAX_EDGE)
            val reqH = viewH.coerceAtMost(VisualizationEngine.DISPLAY_MAX_EDGE)
            refDecodeJob?.cancel()
            refDecodeJob = lifecycleScope.launch(Dispatchers.IO) {
                val bmp = BitmapDecode.decodeFileForView(
                    refPath,
                    reqW,
                    reqH,
                    rawWidth = imgW,
                    rawHeight = imgH,
                )
                withContext(Dispatchers.Main) {
                    if (isDestroyed || isFinishing) {
                        bmp?.recycle()
                        return@withContext
                    }
                    cachedBaseImage = bmp
                    // A frame already on its own photo keeps it.
                    if (!onFramePhoto) showReferenceBase()
                }
            }
        }
    }

    /** Debounce rapid Next/Prev so only the settled frame is decoded. */
    private fun requestFrameLoad(debounced: Boolean) {
        scrubDebounceJob?.cancel()
        if (!debounced) {
            loadFrameData(currentFrameIndex)
            return
        }
        scrubDebounceJob = lifecycleScope.launch {
            delay(SCRUB_DEBOUNCE_MS)
            loadFrameData(currentFrameIndex)
        }
    }

    private fun applyHeatmapMatrix() {
        val hm = cachedHeatmap
        val zoom = imgMain.getZoomMatrix()
        if (hm == null || hm.isRecycled || hm.width <= 0 || imgW <= 0) {
            imgHeatmap.imageMatrix = zoom
        } else {
            val m = Matrix(zoom)
            m.preScale(imgW.toFloat() / hm.width, imgH.toFloat() / hm.height)
            imgHeatmap.imageMatrix = m
        }
        imgHeatmap.invalidate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("LAST_CLOSEST_IDX", inspect.lastClosestIdx)
        outState.putInt("CURRENT_FRAME", currentFrameIndex)
        outState.putBoolean("SHOWING_SUMMARY", showingSummary)
        pendingShareKind?.let { outState.putString(STATE_SHARE_KIND, it) }
    }

    private fun loadFrameData(index: Int) {
        if (index < 0 || index >= batchFiles.size) return

        scrubCache.getData(index)?.let { cached ->
            applyLoadedFrame(index, cached)
            prefetchAround(index)
            return
        }

        loadFrameJob?.cancel()
        loadFrameJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val data = readFrameDat(index) ?: return@launch
                scrubCache.putData(index, data)

                withContext(Dispatchers.Main) {
                    if (currentFrameIndex != index) return@withContext
                    applyLoadedFrame(index, data)
                }
                prefetchAround(index)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: OutOfMemoryError) {
                // Error, not Exception — must be caught explicitly or the process dies.
                Timber.e(e, "OOM loading frame $index")
                scrubCache.clear()
                withContext(Dispatchers.Main) {
                    FaqRedirect.snackbar(
                        this@ResultViewerActivity,
                        R.string.viewer_frame_oom,
                        R.string.url_faq_viewer_oom,
                    )
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.e(e, "Failed to load frame $index")
            }
        }
    }

    /**
     * Fills [scrubCache] with a bounded look-ahead window around [center], so a scrub
     * step is usually a cache hit without letting memory grow with scrub speed.
     *
     * One serialized worker, not a job per neighbour: the previous version launched up
     * to two uncancelled coroutines on *every* frame load, so a fast scrub could have a
     * dozen concurrent decodes in flight — each holding a full frame — while the cache
     * only ever kept the last two, so most of that work became garbage on arrival. Peak
     * memory then scaled with how fast the user scrubbed rather than with any bound.
     *
     * Here exactly one decode runs at a time, the window is cancelled and restarted when
     * the user moves on, and each frame is admitted only if the cache still has room
     * (count *and* bytes) and the heap guard passes — so the queue stays warm while peak
     * stays flat.
     */
    private fun prefetchAround(center: Int) {
        prefetchJob?.cancel()
        val direction = if (center >= lastPrefetchCenter) 1 else -1
        lastPrefetchCenter = center

        prefetchJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                for (offset in lookAheadOffsets(direction)) {
                    val index = center + offset
                    if (index < 0 || index >= batchFiles.size) continue
                    if (scrubCache.getData(index) != null) continue
                    // Re-checked per frame: both the cache budget and the heap can be
                    // consumed by the foreground frame while this window is filling.
                    if (scrubCache.freeSlots(maxDatBytes) <= 0) return@launch
                    if (!heapHasRoomForPrefetch()) return@launch
                    val data = readFrameDat(index) ?: continue
                    scrubCache.putData(index, data)
                    yield() // stay promptly cancellable between frames
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: OutOfMemoryError) {
                Timber.w(e, "Prefetch around frame %d OOM — clearing scrub cache", center)
                scrubCache.clear()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.w(e, "Prefetch around frame %d failed", center)
            }
        }
    }

    /**
     * Frames to warm, nearest first and biased to the scrub [direction], with one frame
     * behind so reversing is still a hit. Length is capped by the cache, so this never
     * queues more than can be held.
     */
    private fun lookAheadOffsets(direction: Int): IntArray {
        val ahead = ScrubFrameCache.DEFAULT_MAX_FRAMES - 1
        val offsets = IntArray(ahead + 1)
        for (i in 0 until ahead) offsets[i] = direction * (i + 1)
        offsets[ahead] = -direction
        return offsets
    }

    private fun readFrameDat(index: Int): FloatArray? {
        Trace.beginSection("Semper.viewer.decodeDat")
        try {
            // Off the main thread, before the frame can be shown: which photo it
            // goes on decides how its map is drawn.
            framePhotos.getOrPut(index) { deformedImagePathAt(index).orEmpty() }
            val file = batchFiles[index]
            val data = DicResult.decodeDatFile(file)
            if (data == null) {
                Timber.e("Invalid file size for frame $index")
            }
            return data
        } finally {
            Trace.endSection()
        }
    }

    /** Rough guard: need headroom for another full-frame FloatArray (~file size). */
    private fun heapHasRoomForPrefetch(): Boolean {
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        if (maxDatBytes <= 0L) return false
        return free > maxDatBytes * 3
    }

    private fun applyLoadedFrame(index: Int, data: FloatArray) {
        rawData = data
        // A sweep's frames each have their own grid pitch.
        step = sweepSteps?.getOrNull(index) ?: baseStep
        // Invalidate rather than rebuild: the O(n) bucket map is only needed for
        // probe nearest-point taps, and findNearestDataPoint builds it lazily
        // for the new frame. Scrubbing large frames no longer pays for an unused index.
        inspect.clearSpatialIndex()
        updateHeatmapFitBounds(data)
        showFrameBase(index)
        val displayName = frameDisplayName(index)
        if (!showingSummary) {
            tvFrameCounter.text = "$displayName (${index + 1} / ${batchFiles.size})"
        }
        syncFrameNumber()
        // updateVisualization warms this frame's stats off the main thread
        // and pushes them to the caption when the render completes.
        updateVisualization(currentDataIndex)
        if (inspect.lastClosestIdx != -1) {
            inspect.refreshCrosshairs()
        }
    }

    /**
     * Rest-fit the coloured region: custom ROI if set, else accepted-point
     * bounds for this frame, else the full specimen. On the frame's own photo
     * the box also takes in where the points moved to, so the displaced map
     * stays in view.
     */
    private fun updateHeatmapFitBounds(data: FloatArray?) {
        if (imgW <= 0 || imgH <= 0) return
        val box = HeatmapFit.resolve(
            imgW,
            imgH,
            roiX,
            roiY,
            roiW,
            roiH,
            accepted = data?.let { DicResult.acceptedPointsBounds(it) },
        )
        val moved = data?.takeIf { onFramePhoto }?.let { DicResult.acceptedPointsBounds(it, displaced = true) }
        if (moved != null) {
            box[HeatmapFit.LEFT] = minOf(box[HeatmapFit.LEFT], moved[HeatmapFit.LEFT])
            box[HeatmapFit.TOP] = minOf(box[HeatmapFit.TOP], moved[HeatmapFit.TOP])
            box[HeatmapFit.RIGHT] = maxOf(box[HeatmapFit.RIGHT], moved[HeatmapFit.RIGHT])
            box[HeatmapFit.BOTTOM] = maxOf(box[HeatmapFit.BOTTOM], moved[HeatmapFit.BOTTOM])
        }
        imgMain.setFitBounds(box[0], box[1], box[2], box[3])
    }

    /** The photo of the frame at [position] when it is on disk, else null (the reference is shown). */
    private fun framePhotoPathFor(position: Int): String? = framePhotos[position]?.ifEmpty { null }

    /**
     * True when the frame on screen is drawn over its own photo, with its map
     * at the displaced positions. False shows the reference under the
     * reference-position map: a frame whose photo is gone (a restore without
     * it, storage reclaim) still lines up.
     */
    internal val onFramePhoto: Boolean get() = framePhotoPathFor(currentFrameIndex) != null

    /**
     * Puts the photo of the frame at [index] under its map — or the reference,
     * when that frame has none. Decoded off the main thread at display size,
     * like the reference; a photo that fails to decode drops the frame back to
     * the reference and its map with it.
     */
    private fun showFrameBase(index: Int) {
        framePhotoJob?.cancel()
        val path = framePhotoPathFor(index)
        if (path == null) {
            showReferenceBase()
            return
        }
        if (path == framePhotoPath) return
        val reqW = imgMain.width.takeIf { it > 0 }?.coerceAtMost(VisualizationEngine.DISPLAY_MAX_EDGE)
            ?: VisualizationEngine.DISPLAY_MAX_EDGE
        val reqH = imgMain.height.takeIf { it > 0 }?.coerceAtMost(VisualizationEngine.DISPLAY_MAX_EDGE)
            ?: VisualizationEngine.DISPLAY_MAX_EDGE
        framePhotoJob = lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                BitmapDecode.decodeFileForView(path, reqW, reqH, rawWidth = imgW, rawHeight = imgH)
            }
            if (isDestroyed || isFinishing) {
                bmp?.recycle()
                return@launch
            }
            if (bmp == null) {
                Timber.w("Frame %d photo did not decode; showing it on the reference", index)
                framePhotos[index] = ""
                val data = rawData
                if (currentFrameIndex == index && data != null) applyLoadedFrame(index, data)
                return@launch
            }
            if (framePhotoPathFor(currentFrameIndex) != path) {
                bmp.recycle()
                return@launch
            }
            val previous = framePhotoBitmap
            framePhotoBitmap = bmp
            framePhotoPath = path
            imgMain.setImageBitmap(bmp)
            referenceShown = false
            previous?.recycle()
        }
    }

    /** Puts the reference back under the map, once it is decoded, and frees any frame photo. */
    private fun showReferenceBase() {
        val reference = cachedBaseImage ?: return
        val previous = framePhotoBitmap
        if (previous == null && framePhotoPath == null && referenceShown) return
        framePhotoBitmap = null
        framePhotoPath = null
        imgMain.setImageBitmap(reference)
        referenceShown = true
        previous?.recycle()
    }

    /**
     * Same rest-fit box the summary GIF should fill. Custom ROI when set;
     * otherwise null so [SummaryAnimation] discovers accepted points from the
     * first readable frame.
     */
    internal fun summaryFitBounds(): FloatArray? {
        if (!HeatmapFit.isCustomRoi(imgW, imgH, roiX, roiY, roiW, roiH)) return null
        return floatArrayOf(
            roiX.toFloat(),
            roiY.toFloat(),
            (roiX + roiW).toFloat(),
            (roiY + roiH).toFloat(),
        )
    }

    private fun showCustomScaleDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_scale, null)
        val etMax = dialogView.findViewById<TextInputEditText>(R.id.etScaleMax)
        val etMin = dialogView.findViewById<TextInputEditText>(R.id.etScaleMin)

        val isStrain = DicResult.isStrainFieldIndex(currentDataIndex)
        val multiplier = DicResult.strainMultiplier(currentDataIndex)
        val unit = getString(if (isStrain) R.string.scale_unit_strain else R.string.scale_unit_px)

        dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilScaleMax).hint =
            getString(R.string.scale_max_value, unit)
        dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilScaleMin).hint =
            getString(R.string.scale_min_value, unit)

        val shown = cachedHeatmap != null && !showingSummary && !isGeneratingHeatmap
        CustomScalePrefill.text(
            custom = scaleBoundsFor(currentDataIndex),
            shownMin = currentHeatmapMin.takeIf { shown },
            shownMax = currentHeatmapMax.takeIf { shown },
            multiplier = multiplier,
        )?.let { (min, max) ->
            etMin.setText(min)
            etMax.setText(max)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.scale_dialog_title, currentTypeString))
            .setView(dialogView)
            .setPositiveButton(R.string.apply) { _, _ ->
                val maxVal = etMax.text?.toString()?.toFloatOrNull()
                val minVal = etMin.text?.toString()?.toFloatOrNull()

                if (maxVal != null && minVal != null && maxVal > minVal) {
                    customBoundsMap[currentDataIndex] = Pair(minVal / multiplier, maxVal / multiplier)
                    updateVisualization(currentDataIndex)
                    summary.onScaleChanged(currentDataIndex)
                } else {
                    FaqRedirect.snackbar(
                        this,
                        R.string.invalid_scale_inputs,
                        R.string.url_faq_custom_scale,
                    )
                }
            }
            .setNeutralButton(R.string.auto_scale) { _, _ ->
                customBoundsMap.remove(currentDataIndex)
                updateVisualization(currentDataIndex)
                summary.onScaleChanged(currentDataIndex)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateVisualization(index: Int) {
        val data = rawData ?: return
        isGeneratingHeatmap = true

        val bounds = scaleBoundsFor(index)
        val forceMin = bounds?.first
        val forceMax = bounds?.second
        val displaced = onFramePhoto
        val heatKey = ScrubFrameCache.HeatKey(
            frame = currentFrameIndex,
            field = index,
            step = step,
            customMin = forceMin,
            customMax = forceMax,
            displaced = displaced,
        )

        val frameAtStart = currentFrameIndex

        scrubCache.getHeat(heatKey)?.let { hit ->
            visualizationJob?.cancel()
            showHeatmap(hit.bitmap, hit.minV, hit.maxV, index)
            // A heatmap hit means this (frame,field) was visited before, so its
            // metrics are already cached — this read is O(1) on the main thread.
            applyFieldMetrics(fieldMetricsFor(frameAtStart, index, data), index)
            return
        }

        visualizationJob?.cancel()
        visualizationJob = lifecycleScope.launch(Dispatchers.Default) {
            // Warm the stats/extrema off the main thread, next to the heatmap render,
            // so the scrub settle never pays the O(n)+sort on the UI thread.
            val metrics = fieldMetricsFor(frameAtStart, index, data)
            val result = if (displaced) {
                VisualizationEngine.generateDeformedHeatmap(
                    data,
                    imgW,
                    imgH,
                    index,
                    step,
                    forceMin,
                    forceMax,
                    maxLongEdge = VisualizationEngine.DISPLAY_MAX_EDGE,
                )
            } else {
                VisualizationEngine.generateHeatmap(
                    data,
                    imgW,
                    imgH,
                    index,
                    step,
                    forceMin,
                    forceMax,
                    maxLongEdge = VisualizationEngine.DISPLAY_MAX_EDGE,
                )
            }

            val heatmap = result.first
            val actualMin = result.second
            val actualMax = result.third
            scrubCache.putHeat(
                heatKey,
                ScrubFrameCache.HeatEntry(heatmap, actualMin, actualMax),
            )

            withContext(Dispatchers.Main) {
                if (currentDataIndex != index || currentFrameIndex != frameAtStart) return@withContext
                showHeatmap(heatmap, actualMin, actualMax, index)
                applyFieldMetrics(metrics, index)
            }
        }
    }

    private fun showHeatmap(heatmap: Bitmap, actualMin: Float, actualMax: Float, index: Int) {
        cachedHeatmap = heatmap
        imgHeatmap.scaleType = ImageView.ScaleType.MATRIX
        imgHeatmap.setImageBitmap(heatmap)
        applyHeatmapMatrix()

        currentHeatmapMin = actualMin
        currentHeatmapMax = actualMax

        // While the summary is up the labels belong to its whole-sequence scale,
        // not to whichever frame happens to be loaded behind it.
        if (!showingSummary) {
            val isStrain = DicResult.isStrainFieldIndex(index)
            val multiplier = DicResult.strainMultiplier(index)
            val unit = getString(if (isStrain) R.string.scale_unit_strain else R.string.scale_unit_px)
            // ≤/≥, not "Min:"/"Max:": these are the 2nd/98th-percentile clamp the
            // colour ramp is built on (VisualizationEngine.computeSigmaClampedRange),
            // not the field's true extrema -- the ⓘ details sheet shows those,
            // via DicResult.fieldStats. Same wording as the summary-mode scale
            // (ViewerSummaryHelper) so the two paths agree.
            val minText = ReportBuilder.formatMetric(actualMin * multiplier)
            val maxText = ReportBuilder.formatMetric(actualMax * multiplier)
            tvScaleMin.text = getString(R.string.scale_min_fmt, minText, unit)
            tvScaleMax.text = getString(R.string.scale_max_fmt, maxText, unit)
        }
        isGeneratingHeatmap = false
    }

    /** What a report page reads from this viewer, as plain data an export can keep. */
    private fun reportSource(): ViewerReportFactory.Source = ViewerReportFactory.Source(
        args = args,
        imgW = imgW,
        imgH = imgH,
        baseStep = baseStep,
        sweepSteps = sweepSteps,
        sweepSubsets = sweepSubsets,
        sweepStrainWins = sweepStrainWins,
        roi = RoiData(roiX, roiY, roiW, roiH),
        frameNames = originalDefNames,
        plannedFrames = plannedFrames,
        defImagePaths = defImagePaths,
        displayBase = cachedBaseImage,
    )

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
        return ReportImageNames.frameName(originalDefNames, planned) ?: "Frame ${planned + 1}"
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
            originalDefNames,
            plannedFrameIndex(position),
        )

    /**
     * A filename-safe base for exports, drawn from the specimen/reference name so
     * shared files read like "IMG_0768_report.pdf" instead of a generic prefix.
     * Falls back to the session name, then the first deformed frame, then "analysis".
     */
    private fun shareBaseName(): String {
        val record = sessionRecord
        val raw = record?.refName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: record?.name?.takeIf { it.isNotBlank() }
            ?: originalDefNames.firstOrNull()?.substringBeforeLast('.')
            ?: "analysis"
        return raw.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(60).ifBlank { "analysis" }
    }

    /**
     * The stored record behind this viewer, or null when it was opened without
     * one (a run still in flight, or a legacy Intent).
     *
     * Read once and kept: the share name, the CSV and every page of an
     * all-frames report want the same few fields off it, and re-reading the
     * session index per report page would be a file read per page.
     */
    internal val sessionRecord: com.indicvision.semper.data.session.SessionRecord? by lazy {
        intent.getStringExtra(DicKeys.SESSION_LOCAL_ID)
            ?.let { runCatching { com.indicvision.semper.data.session.SessionStore.get(this, it) }.getOrNull() }
    }

    /**
     * This viewer's arguments, parsed once (ADR-003). The record fallback reads
     * the session index only for an Intent missing a key, which no current
     * writer produces.
     */
    internal val args: ViewerArgs by lazy { ViewerArgs.from(intent) { sessionRecord } }

    /**
     * Everything an export needs, or null before the frame set is read or when
     * there are no frames. The frame on screen may still be loading ([rawData]
     * null): only the photo kinds need it, and they read it from disk then.
     */
    internal fun buildShareSnapshot(): ShareCenter.Snapshot? {
        if (!frameSetLoaded || batchFiles.isEmpty()) return null
        val data = rawData
        // Snapshot can open with ref path alone while display decode is still in flight.
        val base = cachedBaseImage
        val summaryHelper = summary
        return ShareCenter.Snapshot(
            data = data,
            batchFiles = batchFiles,
            defNames = originalDefNames,
            plannedFrames = plannedFrames,
            baseName = shareBaseName(),
            frameIndex = currentFrameIndex,
            imgW = imgW,
            imgH = imgH,
            step = step,
            stepPerFrame = sweepSteps,
            subsetPerFrame = sweepSubsets,
            strainWindowPerFrame = sweepStrainWins,
            dataIndex = currentDataIndex,
            typeString = currentTypeString,
            baseImage = base,
            refImagePath = refImagePath,
            defImagePaths = defImagePaths,
            summary = if (isSweep) null else summaryHelper.animation,
            summaryBounds = if (isSweep) {
                emptyMap()
            } else {
                SummaryAnimation.FIELDS.mapNotNull { (_, index) -> summaryHelper.boundsFor(index)?.let { index to it } }
                    .toMap()
            },
            reportSource = reportSource(),
            referenceName = args.refName,
            strainMethod = args.strainMethod,
            subset = args.subsetSize,
            strainWindow = args.strainWindow,
            roiX = roiX,
            roiY = roiY,
            roiW = roiW,
            roiH = roiH,
        )
    }

    /** Share sheet (wireframe 08) - targets wired via ShareCenter. */
    private fun showShareSheet() {
        ShareCenter(this).show()
    }

    /** SAF CreateDocument for a slow export; generation starts only after a URI returns. */
    internal fun pickShareDocument(kind: String, mime: String, filename: String) {
        pendingShareKind = kind
        createShareDocument.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, filename)
            },
        )
    }

    /** Edge title + peek-sheet stats for [index], from pre-computed [metrics]. */
    private fun updateCaptionsFrom(metrics: FieldMetrics, index: Int) {
        val unit = if (DicResult.isStrainFieldIndex(index)) "m\u03b5" else "px"
        val frameBit = if (showingSummary) {
            getString(R.string.summary_title)
        } else {
            "${currentFrameIndex + 1} / ${batchFiles.size.coerceAtLeast(1)}"
        }
        tvFinding.text = getString(R.string.viewer_edge_title_fmt, currentTypeString, frameBit)

        if (showingSummary) {
            detailStats = SummaryCaption.text(resources, summary.boundsFor(index), index, unit)
            tvStatsCaption.text = detailStats
            return
        }

        val stats = metrics.stats
        if (stats == null) {
            detailStats = getString(R.string.stat_empty)
            tvStatsCaption.text = detailStats
            return
        }
        val maxText = ReportBuilder.formatMetric(stats[0])
        val minText = ReportBuilder.formatMetric(stats[1])
        val meanText = ReportBuilder.formatMetric(stats[2])
        val data = rawData
        detailStats = if (
            data != null &&
            metrics.maxIdx in data.indices &&
            metrics.minIdx in data.indices
        ) {
            getString(
                R.string.viewer_stats_fmt,
                maxText,
                data[metrics.maxIdx].toInt(),
                data[metrics.maxIdx + 1].toInt(),
                minText,
                data[metrics.minIdx].toInt(),
                data[metrics.minIdx + 1].toInt(),
                meanText,
                unit,
            )
        } else {
            getString(R.string.viewer_stats_plain_fmt, maxText, minText, meanText, unit)
        }
        tvStatsCaption.text = detailStats
    }

    private fun updateNavButtons() {
        // Sweep: no summary slot, so Prev is inert on the first combination.
        btnPrevFrame.isEnabled =
            !showingSummary &&
            batchFiles.isNotEmpty() &&
            (currentFrameIndex > 0 || !isSweep)
        btnNextFrame.isEnabled = showingSummary || currentFrameIndex < batchFiles.size - 1

        btnPrevFrame.alpha = if (btnPrevFrame.isEnabled) 1.0f else 0.5f
        btnNextFrame.alpha = if (btnNextFrame.isEnabled) 1.0f else 0.5f
        // The number tracks the buttons, not the decode: a debounced scrub would
        // otherwise leave it a frame behind for as long as the load takes.
        syncFrameNumber()
    }

    // ── Summary slot ─────────────────────────────────────────────────────

    private fun wireSummaryGestures() {
        val gif = findViewById<TouchImageView>(R.id.imgSummary)
        gif.onScrubListener = { stepFrame(it) }
        gif.onCenterDoubleTapShowChrome = { showChromeIfHidden() }
        gif.onChromeSwipeListener = { show -> if (show) bumpChrome() }
        gif.onTapListener = { _, _ -> bumpChrome() }
    }

    private fun enterSummary() {
        if (isSweep) return
        showingSummary = true
        inspect.dismissProbe()
        summary.show()
        tvFrameCounter.text = summary.counterText()
        layoutFrameJump.visibility = View.GONE
        tvFinding.text = getString(
            R.string.viewer_edge_title_fmt,
            currentTypeString,
            getString(R.string.summary_title),
        )
        updateNavButtons()
        bumpChrome()
    }

    private fun leaveSummary() {
        showingSummary = false
        summary.hide()
        layoutFrameJump.visibility = View.VISIBLE
        updateNavButtons()
        // Re-apply the frame's own labels and heatmap after the summary's.
        requestFrameLoad(debounced = false)
        bumpChrome()
    }

    // ── Typed frame jump ─────────────────────────────────────────────────

    private fun wireFrameJump() {
        etFrameNumber.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                commitFrameJump()
                true
            } else {
                false
            }
        }
        etFrameNumber.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitFrameJump()
        }
    }

    /**
     * Applies what is typed in the frame field. Anything unparseable or outside
     * the batch restores the current number rather than jumping somewhere the
     * user did not ask for.
     */
    private fun commitFrameJump() {
        bumpChrome()
        val typed = etFrameNumber.text?.toString()?.trim()?.toIntOrNull()
        val target = typed?.minus(1)?.takeIf { it in batchFiles.indices }
        if (target == null) {
            syncFrameNumber()
        } else if (target != currentFrameIndex || showingSummary) {
            if (showingSummary) leaveSummary()
            currentFrameIndex = target
            updateNavButtons()
            // A typed number is a settled destination, unlike a Next/Prev burst.
            requestFrameLoad(debounced = false)
        }
        dismissFrameJumpKeyboard()
    }

    private fun dismissFrameJumpKeyboard() {
        etFrameNumber.clearFocus()
        val ime = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ime?.hideSoftInputFromWindow(etFrameNumber.windowToken, 0)
    }

    private fun syncFrameNumber() {
        val shown = if (showingSummary) "" else (currentFrameIndex + 1).toString()
        if (etFrameNumber.text?.toString() != shown) etFrameNumber.setText(shown)
    }

    // ── Field metrics cache (caption + peek-sheet extrema) ───────────────────

    /** Immutable per-(frame,field) result: `[max,min,mean]` stats and extrema indices. */
    internal class FieldMetrics(val stats: FloatArray?, val maxIdx: Int, val minIdx: Int)

    /**
     * Memoised [FieldMetrics] keyed by (frameIndex, dataIndex). A decoded frame is
     * immutable, so these never need invalidation — only an LRU size bound. Computing
     * them is one walk of accepted points; caching means a field toggle or a
     * revisited frame costs nothing, and [updateVisualization] warms the entry on its
     * background thread so a scrub settle never does the work on the main thread.
     * Guarded by its own monitor (read on Main, written on Dispatchers.Default).
     */
    private val fieldMetricsCache = LinkedHashMap<Long, FieldMetrics>()

    internal fun fieldMetricsFor(frameIndex: Int, dataIndex: Int, data: FloatArray): FieldMetrics {
        val key = (frameIndex.toLong() shl Int.SIZE_BITS) or (dataIndex.toLong() and 0xFFFF_FFFFL)
        synchronized(fieldMetricsCache) { fieldMetricsCache[key]?.let { return it } }
        val stats = DicResult.fieldStats(data, dataIndex)
        val (maxIdx, minIdx) = trueExtremaIndices(data, dataIndex)
        val metrics = FieldMetrics(stats, maxIdx, minIdx)
        synchronized(fieldMetricsCache) {
            fieldMetricsCache[key] = metrics
            if (fieldMetricsCache.size > FIELD_METRICS_CACHE_MAX) {
                val eldest = fieldMetricsCache.keys.iterator()
                eldest.next()
                eldest.remove()
            }
        }
        return metrics
    }

    /**
     * Indices of the accepted points that carry this field's true min and max —
     * the same values [DicResult.fieldStats] reports — so the ⓘ coordinates
     * match the printed numbers (not the colour-bar percentile clamp).
     */
    private fun trueExtremaIndices(data: FloatArray, dataIndex: Int): Pair<Int, Int> {
        var maxIdx = -1
        var minIdx = -1
        var maxV = Float.NEGATIVE_INFINITY
        var minV = Float.POSITIVE_INFINITY
        var i = 0
        while (i < data.size) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                val v = data[i + dataIndex]
                if (v > maxV) {
                    maxV = v
                    maxIdx = i
                }
                if (v < minV) {
                    minV = v
                    minIdx = i
                }
            }
            i += DicResult.STRIDE
        }
        return maxIdx to minIdx
    }

    /** Push cached stats into the finding caption. Main thread only. */
    private fun applyFieldMetrics(metrics: FieldMetrics, index: Int) {
        updateCaptionsFrom(metrics, index)
    }

    private companion object {
        const val SCRUB_DEBOUNCE_MS = 70L

        /** Field metrics are tiny (5 floats + 2 ints); keep plenty across frames/fields. */
        const val FIELD_METRICS_CACHE_MAX = 64

        const val STATE_SHARE_KIND = "PENDING_SHARE_KIND"
    }
}
