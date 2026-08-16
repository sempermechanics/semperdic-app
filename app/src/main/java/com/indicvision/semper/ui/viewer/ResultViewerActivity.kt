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
import android.graphics.Matrix
import android.os.Bundle
import android.os.Trace
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.indicvision.semper.DicKeys
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.data.SessionPaths
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.ReportData
import com.indicvision.semper.report.VisualizationEngine
import com.indicvision.semper.ui.common.Insets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import timber.log.Timber
import java.io.File

/**
 * Results browser: renders displacement/strain heatmaps over the reference
 * image, with frame scrubbing, tap-to-probe readings, custom color scales,
 * and all exports (PDF/CSV/PNG/ZIP via [ShareCenter]).
 */
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
    private lateinit var chromeBottom: View
    private var chromeVisible = true

    internal lateinit var tvProbeReadout: TextView
    internal lateinit var glassShield: InspectOverlayView

    /** Max / min (with coordinates) / mean for the info peek sheet. */
    private var detailStats: String = ""

    private val hideChromeRunnable = Runnable { fadeChrome(visible = false) }

    internal lateinit var shareBanner: com.indicvision.semper.ui.common.TransferBannerController
    private val chromeHideDelayMs = 2_500L

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

    internal var cachedBaseImage: Bitmap? = null
    private var cachedHeatmap: Bitmap? = null
    internal var currentTypeString: String
        get() = viewerVm.currentTypeString
        set(value) {
            viewerVm.currentTypeString = value
        }
    private var isGeneratingHeatmap = false

    private var batchFiles: List<File> = emptyList()

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

    internal var currentDefPath: String? = null
    private var currentHeatmapMin = 0f
    private var currentHeatmapMax = 0f

    private val customBoundsMap = mutableMapOf<Int, Pair<Float, Float>>()

    private lateinit var etFrameNumber: EditText
    private lateinit var tvFrameTotal: TextView
    private lateinit var layoutFrameJump: View
    private lateinit var summary: ViewerSummaryHelper

    /**
     * True while the summary animation is up instead of a frame. It sits before
     * frame 1: Prev from frame 1 reaches it, Next leaves it.
     */
    private var showingSummary = false

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
        // Frame heatmaps use per-frame min/max; the summary GIF reads sequence
        // ranges itself. Nothing to refresh here.
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result_viewer)

        imgMain = findViewById(R.id.imgBaseResult)
        imgHeatmap = findViewById(R.id.imgHeatmapOverlay)

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
        chromeBottom = findViewById(R.id.chromeBottom)
        shareBanner = com.indicvision.semper.ui.common.TransferBannerController(
            findViewById(R.id.transferBannerRoot),
        )

        Insets.padTop(findViewById(R.id.viewerTopStack))
        Insets.padBottom(findViewById(R.id.layoutScrubber))

        tvProbeReadout = findViewById(R.id.tvProbeReadout)
        glassShield = findViewById(R.id.glassShield)

        inspect = ViewerInspectHelper(this)

        if (savedInstanceState != null) {
            val savedProbe = savedInstanceState.getInt("LAST_CLOSEST_IDX", -1)
            inspect.restoreProbe(savedProbe)
            currentFrameIndex = savedInstanceState.getInt("CURRENT_FRAME", 0)
            showingSummary = savedInstanceState.getBoolean("SHOWING_SUMMARY", false)
        } else {
            // A lattice node tap asks to open on a specific frame; clamped once
            // the batch is loaded below.
            currentFrameIndex = intent.getIntExtra(DicKeys.START_FRAME, 0)
            // Otherwise the summary is what the viewer opens on — it answers
            // "what happened across the test" before any single frame does.
            showingSummary = !intent.hasExtra(DicKeys.START_FRAME)
        }

        imgW = intent.getIntExtra(DicKeys.IMG_W, 0)
        imgH = intent.getIntExtra(DicKeys.IMG_H, 0)
        baseStep = intent.getIntExtra(DicKeys.STEP, 5)
        step = baseStep
        sweepSubsets = intent.getIntArrayExtra(DicKeys.SWEEP_SUBSETS)
        sweepSteps = intent.getIntArrayExtra(DicKeys.SWEEP_STEPS)
        sweepStrainWins = intent.getIntArrayExtra(DicKeys.SWEEP_STRAIN_WINS)
        lineCutHorizontal = intent.getBooleanExtra(DicKeys.LINE_CUT_HORIZONTAL, true)

        roiX = intent.getIntExtra(DicKeys.ROI_X, 0)
        roiY = intent.getIntExtra(DicKeys.ROI_Y, 0)
        roiW = intent.getIntExtra(DicKeys.ROI_W, imgW)
        roiH = intent.getIntExtra(DicKeys.ROI_H, imgH)

        val refPath = intent.getStringExtra(DicKeys.REF_PATH)
        // True sensor dims stay on the intent for math / probe / export; the
        // on-screen bitmap is decoded off-main at ImageView scale.
        imgMain.setTrueImageDimensions(imgW, imgH)
        if (refPath != null) {
            decodeReferenceForDisplay(refPath)
        }

        currentDefPath = intent.getStringExtra(DicKeys.DEF_PATH)

        val batchDirPath = intent.getStringExtra(DicKeys.BATCH_DIR_PATH)
        originalDefNames = intent.getStringArrayListExtra(DicKeys.DEF_FILE_NAMES) ?: emptyList()
        refImagePath = intent.getStringExtra(DicKeys.REF_PATH)
        // Prefer the raw deformed originals persisted in the session dir (survive
        // reopen/eviction); fall back to the just-analysed session's temp paths.
        val rawDeformedDir = batchDirPath?.let { File(it, SessionPaths.RAW_DEFORMED_SUBDIR) }
        defImagePaths = rawDeformedDir?.takeIf { it.isDirectory }
            ?.listFiles()?.sortedBy { it.name }?.map { it.absolutePath }
            ?: intent.getStringArrayListExtra(DicKeys.DEF_FILE_PATHS)
            ?: emptyList()

        if (batchDirPath != null) {
            val dir = File(batchDirPath)
            if (dir.exists() && dir.isDirectory) {
                batchFiles = dir.listFiles { file -> file.extension == "dat" }?.sortedBy { it.name } ?: emptyList()
                maxDatBytes = batchFiles.maxOfOrNull { it.length() } ?: 0L
            }
        }

        summary = ViewerSummaryHelper(this)

        if (batchFiles.isNotEmpty()) {
            // A START_FRAME (or restored index) past the batch would load nothing.
            currentFrameIndex = currentFrameIndex.coerceIn(0, batchFiles.lastIndex)
            tvFrameTotal.text = getString(R.string.frame_total_fmt, batchFiles.size)
            loadFrameData(currentFrameIndex)
            // Whole-sequence ranges still feed the summary GIF / share animations.
            if (batchFiles.size > 1) summary.start()
            if (showingSummary) summary.show()
            if (showingSummary) layoutFrameJump.visibility = View.GONE
            updateNavButtons()
            bumpChrome()
        } else {
            showingSummary = false
            com.google.android.material.snackbar.Snackbar.make(
                findViewById(android.R.id.content),
                R.string.no_batch_data,
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG,
            ).show()
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

        // Floating glass field pills (separate rounded chips — not a segmented bar).
        val fieldByButton = mapOf(
            R.id.rbFieldU to ("U" to DicResult.IDX_U),
            R.id.rbFieldV to ("V" to DicResult.IDX_V),
            R.id.rbFieldExx to ("Exx" to DicResult.IDX_EXX),
            R.id.rbFieldEyy to ("Eyy" to DicResult.IDX_EYY),
            R.id.rbFieldExy to ("Exy" to DicResult.IDX_EXY),
        )
        val fieldButtons = fieldByButton.keys.map { id ->
            findViewById<com.google.android.material.button.MaterialButton>(id)
        }
        fieldButtons.forEach { button ->
            // Material defaults to 88dp minWidth — collapse so pills hug their label.
            button.minWidth = 0
            button.minimumWidth = 0
            button.setOnClickListener {
                fieldButtons.forEach { it.isChecked = it === button }
                bumpChrome()
                val (label, index) = fieldByButton[button.id] ?: return@setOnClickListener
                currentTypeString = label
                currentDataIndex = index
                // Caption + probe value are refreshed by updateVisualization once the
                // new field's metrics are computed off the main thread.
                updateVisualization(currentDataIndex)
                summary.onFieldChanged()
                if (showingSummary) tvFrameCounter.text = summary.counterText()
                inspect.refreshCrosshairs()
            }
        }

        findViewById<View>(R.id.btnViewerBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnViewerShare).setOnClickListener { showShareSheet() }
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
        fadeChrome(visible = true)
        chromeTop.removeCallbacks(hideChromeRunnable)
        chromeTop.postDelayed(hideChromeRunnable, chromeHideDelayMs)
    }

    internal fun hideChrome() {
        chromeTop.removeCallbacks(hideChromeRunnable)
        fadeChrome(visible = false)
    }

    internal fun toggleChrome() {
        if (chromeVisible) hideChrome() else bumpChrome()
    }

    private fun fadeChrome(visible: Boolean) {
        chromeVisible = visible
        listOf(chromeTop, chromeBottom).forEach { bar ->
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
            currentFrameIndex == 0 -> enterSummary()
            else -> {
                currentFrameIndex--
                updateNavButtons()
                requestFrameLoad(debounced = true)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Keep the Home row's headline in sync with what was on screen.
        // Sweeps already carry a stable caption (image + solved count) — don't
        // overwrite it with the last field's peak reading.
        if (isSweep) return
        intent.getStringExtra(DicKeys.SESSION_LOCAL_ID)?.let { localId ->
            val data = rawData ?: return@let
            val stats = DicResult.fieldStats(data, currentDataIndex) ?: return@let
            val unit = if (DicResult.isStrainFieldIndex(currentDataIndex)) "m\u03b5" else "px"
            val headline = "$currentTypeString max ${ReportBuilder.formatMetric(stats[0])} $unit"
            lifecycleScope.launch(Dispatchers.IO) {
                com.indicvision.semper.data.SessionStore.updateHeadline(
                    this@ResultViewerActivity,
                    localId,
                    headline,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::chromeTop.isInitialized) {
            chromeTop.removeCallbacks(hideChromeRunnable)
            chromeTop.animate().cancel()
            chromeBottom.animate().cancel()
        }
        loadFrameJob?.cancel()
        visualizationJob?.cancel()
        scrubDebounceJob?.cancel()
        refDecodeJob?.cancel()
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
                val bmp = BitmapDecode.decodeFileForView(refPath, reqW, reqH)
                withContext(Dispatchers.Main) {
                    if (isDestroyed || isFinishing) {
                        bmp?.recycle()
                        return@withContext
                    }
                    cachedBaseImage = bmp
                    imgMain.setImageBitmap(bmp)
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
                    Toast.makeText(this@ResultViewerActivity, R.string.viewer_frame_oom, Toast.LENGTH_LONG).show()
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
        val displayName = originalDefNames.getOrNull(index) ?: "Frame ${index + 1}"
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

        val existing = scaleBoundsFor(currentDataIndex)
        if (existing != null) {
            etMin.setText((existing.first * multiplier).toString())
            etMax.setText((existing.second * multiplier).toString())
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
                    Toast.makeText(this, R.string.invalid_scale_inputs, Toast.LENGTH_LONG).show()
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
        val heatKey = ScrubFrameCache.HeatKey(
            frame = currentFrameIndex,
            field = index,
            step = step,
            customMin = forceMin,
            customMax = forceMax,
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
            val result = VisualizationEngine.generateHeatmap(
                data,
                imgW,
                imgH,
                index,
                step,
                forceMin,
                forceMax,
                maxLongEdge = VisualizationEngine.DISPLAY_MAX_EDGE,
            )

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
        imgHeatmap.setImageBitmap(heatmap)
        applyHeatmapMatrix()

        currentHeatmapMin = actualMin
        currentHeatmapMax = actualMax

        // While the summary is up the labels belong to its whole-sequence scale,
        // not to whichever frame happens to be loaded behind it.
        if (!showingSummary) {
            val isStrain = DicResult.isStrainFieldIndex(index)
            val multiplier = DicResult.strainMultiplier(index)
            val unit = if (isStrain) " mε" else " px"
            // Labels are this frame's min/max (same values the colour scale uses).
            tvScaleMin.text = ReportBuilder.formatMetric(actualMin * multiplier)
            tvScaleMax.text = ReportBuilder.formatMetric(actualMax * multiplier) + unit
        }
        isGeneratingHeatmap = false
    }

    private fun buildReportData(): ReportData? =
        rawData?.let { ViewerReportFactory.buildReportData(this, currentFrameIndex, it) }

    private fun buildReportData(frameIndex: Int, data: FloatArray): ReportData? =
        ViewerReportFactory.buildReportData(this, frameIndex, data)

    /** Everything ShareCenter needs, captured from the viewer's state. */
    /**
     * A filename-safe base for exports, drawn from the specimen/reference name so
     * shared files read like "IMG_0768_report.pdf" instead of a generic prefix.
     * Falls back to the session name, then the first deformed frame, then "analysis".
     */
    private fun shareBaseName(): String {
        val record = intent.getStringExtra(DicKeys.SESSION_LOCAL_ID)
            ?.let { runCatching { com.indicvision.semper.data.SessionStore.get(this, it) }.getOrNull() }
        val raw = record?.refName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: record?.name?.takeIf { it.isNotBlank() }
            ?: originalDefNames.firstOrNull()?.substringBeforeLast('.')
            ?: "analysis"
        return raw.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(60).ifBlank { "analysis" }
    }

    internal fun buildShareSnapshot(): ShareCenter.Snapshot? {
        val data = rawData ?: return null
        // Snapshot can open with ref path alone while display decode is still in flight.
        val base = cachedBaseImage
        return ShareCenter.Snapshot(
            data = data,
            batchFiles = batchFiles,
            defNames = originalDefNames,
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
            summary = summary.animation,
            summaryBounds = { index -> summary.boundsFor(index) },
            buildReportAt = { index, frameData -> buildReportData(index, frameData) },
        )
    }

    /** Share sheet (wireframe 08) - targets wired via ShareCenter. */
    private fun showShareSheet() {
        ShareCenter(this).show()
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
        btnPrevFrame.isEnabled = !showingSummary && batchFiles.isNotEmpty()
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
        gif.onCenterTapListener = { toggleChrome() }
        gif.onChromeSwipeListener = { show -> if (show) bumpChrome() else hideChrome() }
        gif.onTapListener = { _, _ -> bumpChrome() }
    }

    private fun enterSummary() {
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
     * them is O(n) + an O(n log n) percentile sort; caching means a field toggle or a
     * revisited frame costs nothing, and [updateVisualization] warms the entry on its
     * background thread so a scrub settle never does the work on the main thread.
     * Guarded by its own monitor (read on Main, written on Dispatchers.Default).
     */
    private val fieldMetricsCache = LinkedHashMap<Long, FieldMetrics>()

    /**
     * Per-thread extrema scratch: [fieldMetricsFor] runs from both the Main thread
     * and a `Dispatchers.Default` coroutine ([updateVisualization]'s scrub-settle
     * warm-up) concurrently, so a single shared buffer would race. `ThreadLocal`
     * gives each caller thread its own reusable array — same allocation saving,
     * no synchronization needed.
     */
    private val fieldMetricsScratch: ThreadLocal<FloatArray> = ThreadLocal.withInitial { FloatArray(0) }

    internal fun fieldMetricsFor(frameIndex: Int, dataIndex: Int, data: FloatArray): FieldMetrics {
        val key = (frameIndex.toLong() shl Int.SIZE_BITS) or (dataIndex.toLong() and 0xFFFF_FFFFL)
        synchronized(fieldMetricsCache) { fieldMetricsCache[key]?.let { return it } }
        val stats = DicResult.fieldStats(data, dataIndex)
        val needed = data.size / DicResult.STRIDE
        var scratch = fieldMetricsScratch.get()
        if (scratch.size < needed) {
            scratch = FloatArray(needed)
            fieldMetricsScratch.set(scratch)
        }
        val extrema = ReportBuilder.computeFieldExtrema(data, dataIndex, absoluteStrainValues = false, scratch)
        val metrics = FieldMetrics(stats, extrema.maxIdx, extrema.minIdx)
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

    /** Push cached stats into the finding caption. Main thread only. */
    private fun applyFieldMetrics(metrics: FieldMetrics, index: Int) {
        updateCaptionsFrom(metrics, index)
    }

    private companion object {
        const val SCRUB_DEBOUNCE_MS = 70L

        /** Field metrics are tiny (5 floats + 2 ints); keep plenty across frames/fields. */
        const val FIELD_METRICS_CACHE_MAX = 64
    }
}
