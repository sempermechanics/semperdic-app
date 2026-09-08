@file:Suppress("TooManyFunctions", "LongMethod", "LargeClass")

package com.indicvision.semper.ui.capture

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.data.CaptureNoiseFloor
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.ui.analysis.NoiseFloorPixels
import com.indicvision.semper.ui.analysis.NoiseFloorStats
import com.indicvision.semper.ui.analysis.RoiDrawActivity
import com.indicvision.semper.ui.analysis.SpeckleScale
import com.indicvision.semper.ui.analysis.SubsetRecommender
import com.indicvision.semper.ui.common.FaqRedirect
import com.indicvision.semper.ui.common.Insets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Test shot (Camera app) → ROI for contrast → SSSIG → focus lock →
 * locked reference → timed locked stills → setup result → wizard.
 *
 * The test shot is a check, not data: everything handed to the wizard comes
 * off the locked session. See [captureLockedReference].
 *
 * Setup stays under this screen so Back returns to fps / duration / resolution
 * instead of Home. On success this finishes with [Activity.RESULT_OK] and the
 * picked-frame extras; setup starts the wizard.
 */
class CaptureSessionActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnFocusConfirm: MaterialButton
    private lateinit var focusMarker: View
    private lateinit var preview: TextureView

    /** Magnified crop and sharpness reading at the focus point; see [FocusLoupe]. */
    private lateinit var loupe: FocusLoupe

    /**
     * Watches for the rig being re-aimed between the floor being measured and
     * the run starting. See [onFramingChanged] for what that costs if missed.
     */
    private val framingSensor by lazy { FramingSensor(this, ::onFramingChanged) }

    /**
     * The buffer / view / upright-fraction geometry, from
     * [applyPreviewTransform]. Null until the preview has a size and a session.
     *
     * The [TextureView] transform alone is no use for reading a tap back out —
     * it is this map composed with an undo of the view's own stretch. Keeping
     * the un-composed map means a focus tap inverts through exactly the picture
     * the user is looking at.
     */
    private var previewMap: PreviewMap? = null

    /** Completed when the user accepts the focus the lock landed on. */
    private var focusAccepted: CompletableDeferred<Unit>? = null

    /** True while a tap is being turned into a new lock; further taps wait. */
    private var refocusing = false

    /** Gap between frame starts. The only thing calibration is allowed to move:
     *  [frameCount] was promised on the setup screen and is never reduced. */
    private var frameIntervalMs = DEFAULT_DURATION_SEC.toLong() * MILLIS_PER_SECOND / DEFAULT_FRAME_COUNT
    private var durationSec = DEFAULT_DURATION_SEC
    private var frameCount = DEFAULT_FRAME_COUNT
    private var planWidth = DEFAULT_WIDTH
    private var planHeight = DEFAULT_HEIGHT
    private var cameraId = "0"

    private var focusLock: CaptureFocusLock? = null
    private var lockedSession: LockedCameraSession? = null
    private var testShotFile: File? = null

    /**
     * Cost of one still at the locked exposure and this run's resolution,
     * from the noise burst's first frame. Zero until the burst runs, which
     * is also why [averagingFrames] falls back to one frame without it.
     */
    private var measuredFrameCostMs = 0L

    /** True while timed stills (or the locked reference) are in flight. */
    private var recordingActive = false

    /**
     * True after the noise-floor gate has handed off to [showReady] (or the
     * gate was skipped). Used so a FAQ hop that kills the camera can re-lock
     * without re-running the burst.
     */
    private var readyToRecord = false

    /** Vendor-camera test shot through speckle check, before [focusLock] is set. */
    private var awaitingTestShot = false

    private var relocking = false

    private val noiseGate by lazy {
        NoiseFloorGateUi(
            activity = this,
            onRetry = ::launchTestShot,
            onBurstFailed = { offerRetryTestShot(getString(R.string.capture_test_shot_empty)) },
            onChangeResolution = ::returnForResolutionChange,
            onProceed = ::showReady,
            onFrameCost = ::applyMeasuredFrameCost,
        )
    }

    /** Reference taken through the locked session; see [captureLockedReference]. */
    private var referenceFile: File? = null
    private val deformedPaths = mutableListOf<String>()

    private val takeTestShot = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            offerRetryTestShot(getString(R.string.capture_test_shot_empty))
            return@registerForActivityResult
        }
        val file = testShotFile
        if (file == null || !file.exists() || file.length() == 0L) {
            offerRetryTestShot(getString(R.string.capture_test_shot_empty))
            return@registerForActivityResult
        }
        openContrastRoi(file)
    }

    private val pickContrastRoi = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val file = testShotFile
        if (file == null || !file.exists()) {
            offerRetryTestShot(getString(R.string.capture_test_shot_empty))
            return@registerForActivityResult
        }
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            offerRoiAgainOrRetake()
            return@registerForActivityResult
        }
        val data = result.data!!
        val (imgW, imgH) = imageBounds(file)
        val x = data.getIntExtra(DicKeys.ROI_X, 0).coerceIn(0, imgW - 1)
        val y = data.getIntExtra(DicKeys.ROI_Y, 0).coerceIn(0, imgH - 1)
        val w = data.getIntExtra(DicKeys.ROI_W, imgW).coerceAtLeast(1)
            .coerceAtMost(imgW - x)
        val h = data.getIntExtra(DicKeys.ROI_H, imgH).coerceAtLeast(1)
            .coerceAtMost(imgH - y)
        onContrastRoiReady(file, Rect(x, y, x + w, y + h))
    }

    private var afterCameraGranted: (() -> Unit)? = null

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val next = afterCameraGranted
            afterCameraGranted = null
            next?.invoke()
        } else {
            val retry = afterCameraGranted
            afterCameraGranted = null
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.capture_permission_needed)
                .setPositiveButton(R.string.capture_retry) { _, _ ->
                    if (retry != null) withCameraPermission(retry) else finish()
                }
                .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                .show()
        }
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = navigateBack()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_session)
        Insets.padTop(findViewById(R.id.toolbarCaptureSession))
        // A locked capture takes no touch input for its whole duration, so any
        // run longer than the display timeout puts the screen to sleep — which
        // backgrounds the app and has the HAL revoke the camera
        // (ERROR_CAMERA_DEVICE) mid-sequence. Observed killing a run at ~37s
        // against a 30s screen timeout, and it would do the same on any device.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        durationSec = intent.getIntExtra(CaptureSetupActivity.EXTRA_DURATION_SEC, DEFAULT_DURATION_SEC)
            .coerceAtLeast(1)
        frameCount = intent.getIntExtra(CaptureSetupActivity.EXTRA_FRAME_COUNT, DEFAULT_FRAME_COUNT)
            .coerceAtLeast(1)
        frameIntervalMs = intent
            .getLongExtra(
                CaptureSetupActivity.EXTRA_INTERVAL_MS,
                durationSec.toLong() * MILLIS_PER_SECOND / frameCount,
            )
            .coerceAtLeast(1L)
        planWidth = intent.getIntExtra(CaptureSetupActivity.EXTRA_WIDTH, DEFAULT_WIDTH)
        planHeight = intent.getIntExtra(CaptureSetupActivity.EXTRA_HEIGHT, DEFAULT_HEIGHT)
        cameraId = intent.getStringExtra(CaptureSetupActivity.EXTRA_CAMERA_ID) ?: "0"

        tvStatus = findViewById(R.id.tvCaptureStatus)
        tvProgress = findViewById(R.id.tvCaptureProgress)
        btnStart = findViewById(R.id.btnCaptureStart)
        btnFocusConfirm = findViewById(R.id.btnCaptureFocusConfirm)
        focusMarker = findViewById(R.id.captureFocusMarker)
        preview = findViewById(R.id.capturePreview)
        loupe = FocusLoupe(
            preview = preview,
            loupe = findViewById(R.id.captureFocusLoupe),
            reading = findViewById(R.id.tvCaptureFocusSharpness),
        )
        installFocusTapListener()

        onBackPressedDispatcher.addCallback(this, backCallback)
        findViewById<MaterialToolbar>(R.id.toolbarCaptureSession).apply {
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            setNavigationIconTint(getColor(R.color.text_on_primary))
        }

        btnStart.setOnClickListener { startRecording() }
        btnFocusConfirm.setOnClickListener { focusAccepted?.complete(Unit) }

        val restored = restoreFrom(savedInstanceState)
        if (!restored) {
            awaitingTestShot = savedInstanceState?.getBoolean(STATE_AWAITING_TEST_SHOT, false) ?: false
        }
        if (restored) {
            // Process death after a passing test shot: the focus point and
            // the file it came from are still on disk — re-lock directly
            // instead of asking for a new test shot.
            ensureCameraThenLock()
        } else {
            // IMAGE_CAPTURE crashes if CAMERA is declared but not granted (Android 11+).
            withCameraPermission { launchTestShot() }
        }
    }

    override fun onResume() {
        super.onResume()
        maybeRelockAfterBackground()
    }

    /** Back: confirm while recording or after setup work; otherwise pop to setup. */
    private fun navigateBack() {
        if (recordingActive) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.capture_stop_title)
                .setMessage(R.string.capture_stop_message)
                .setPositiveButton(R.string.capture_stop_confirm) { _, _ -> finish() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        if (hasSetupProgress()) {
            showLeaveSetupDialog()
            return
        }
        finish()
    }

    private fun hasSetupProgress(): Boolean =
        awaitingTestShot || focusLock != null || readyToRecord || noiseGate.measured() != null

    private fun showLeaveSetupDialog(
        message: CharSequence = getText(R.string.capture_leave_setup_message),
        onRetry: (() -> Unit)? = null,
    ) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.capture_leave_setup_title)
            .setMessage(message)
            .setPositiveButton(R.string.capture_leave_setup_confirm) { _, _ -> finish() }
        if (onRetry != null) {
            builder.setNegativeButton(R.string.capture_retry) { _, _ -> onRetry() }
        } else {
            builder.setNegativeButton(R.string.cancel, null)
        }
        builder.show()
    }

    /**
     * Opening the FAQ backgrounds this screen; the HAL often revokes the
     * camera. If focus was already locked and the floor gate already ran (or
     * was skipped), re-open the session without re-measuring — otherwise
     * Continue / Start recording would fail after the browser hop.
     */
    @Suppress("ReturnCount")
    private fun maybeRelockAfterBackground() {
        if (focusLock == null || recordingActive || relocking) return
        if (noiseGate.floor == null && !readyToRecord) return
        val session = lockedSession
        if (session != null && session.isUsable) return
        withCameraPermission { reopenLockedPreview() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        focusLock?.let { outState.putSerializable(STATE_FOCUS_LOCK, it) }
        outState.putStringArrayList(STATE_DEFORMED_PATHS, ArrayList(deformedPaths))
        outState.putInt(STATE_PLAN_WIDTH, planWidth)
        outState.putInt(STATE_PLAN_HEIGHT, planHeight)
        outState.putString(STATE_CAMERA_ID, cameraId)
        outState.putLong(STATE_INTERVAL_MS, frameIntervalMs)
        outState.putString(STATE_REFERENCE_PATH, referenceFile?.absolutePath)
        outState.putLong(STATE_MEASURED_FRAME_COST_MS, measuredFrameCostMs)
        outState.putBoolean(STATE_READY_TO_RECORD, readyToRecord)
        outState.putBoolean(STATE_AWAITING_TEST_SHOT, awaitingTestShot)
        outState.putBoolean(STATE_FLOOR_OVERRIDDEN, noiseGate.overridden)
        noiseGate.measured()?.let { outState.putString(STATE_NOISE_FLOOR, it.encode()) }
    }

    /** True when a resumable [CaptureFocusLock] and its source file survived. */
    @Suppress("ReturnCount")
    private fun restoreFrom(savedInstanceState: Bundle?): Boolean {
        val bundle = savedInstanceState ?: return false
        val lock = BundleCompat.getSerializable(bundle, STATE_FOCUS_LOCK, CaptureFocusLock::class.java)
            ?: return false
        val file = File(lock.testShotPath)
        if (!file.exists() || file.length() == 0L) return false
        focusLock = lock
        testShotFile = file
        deformedPaths.clear()
        deformedPaths.addAll(bundle.getStringArrayList(STATE_DEFORMED_PATHS).orEmpty())
        planWidth = bundle.getInt(STATE_PLAN_WIDTH, planWidth)
        planHeight = bundle.getInt(STATE_PLAN_HEIGHT, planHeight)
        cameraId = bundle.getString(STATE_CAMERA_ID) ?: cameraId
        frameIntervalMs = bundle.getLong(STATE_INTERVAL_MS, frameIntervalMs).coerceAtLeast(1L)
        // Only if it survived: a half-written reference from a killed process
        // would be handed to the wizard as though it were a real frame.
        referenceFile = bundle.getString(STATE_REFERENCE_PATH)
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.length() > 0L }
        measuredFrameCostMs = bundle.getLong(STATE_MEASURED_FRAME_COST_MS, measuredFrameCostMs)
        readyToRecord = bundle.getBoolean(STATE_READY_TO_RECORD, readyToRecord)
        awaitingTestShot = false
        CaptureNoiseFloor.decode(bundle.getString(STATE_NOISE_FLOOR))?.let { saved ->
            noiseGate.restorePersistedFloor(
                saved,
                bundle.getBoolean(STATE_FLOOR_OVERRIDDEN, noiseGate.overridden),
            )
        }
        return true
    }

    override fun onDestroy() {
        lockedSession?.close()
        lockedSession = null
        framingSensor.stop()
        loupe.release()
        super.onDestroy()
    }

    private fun withCameraPermission(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onGranted()
        } else {
            afterCameraGranted = onGranted
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchTestShot() {
        readyToRecord = false
        awaitingTestShot = true
        // A new test shot is a new framing by definition; the next showReady
        // holds whatever the rig is pointing at then.
        framingSensor.stop()
        focusLock = null
        lockedSession?.close()
        lockedSession = null
        tvStatus.setText(R.string.capture_status_test_shot)
        btnStart.isVisible = false
        val dir = SystemCamera.captureDir(this)
        val file = File(dir, CaptureWorkspace.TEST_SHOT_NAME)
        file.delete()
        testShotFile = file
        val uri = SystemCamera.fileProviderUri(this, file)
        val intent = SystemCamera.stillCaptureIntent(this, uri)
        if (intent == null) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.capture_no_camera_app)
                .setPositiveButton(R.string.cancel) { _, _ -> finish() }
                .show()
            return
        }
        try {
            takeTestShot.launch(intent)
        } catch (_: SecurityException) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.capture_permission_needed)
                .setPositiveButton(R.string.capture_retry) { _, _ ->
                    withCameraPermission { launchTestShot() }
                }
                .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                .show()
        }
    }

    private fun offerRetryTestShot(message: String) =
        showLeaveSetupDialog(message) { launchTestShot() }

    /** Mid-recording retry; finishing drops frames already captured. */
    private fun showRetryDialog(message: CharSequence, onRetry: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(R.string.capture_retry) { _, _ -> onRetry() }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private fun offerRoiAgainOrRetake() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.capture_roi_needed_title)
            .setMessage(R.string.capture_roi_needed_body)
            .setPositiveButton(R.string.capture_roi_select) { _, _ ->
                testShotFile?.let { openContrastRoi(it) } ?: launchTestShot()
            }
            .setNeutralButton(R.string.capture_retry) { _, _ -> launchTestShot() }
            .setNegativeButton(R.string.capture_leave_setup_confirm) { _, _ -> finish() }
            .show()
    }

    /** Opens the ROI editor so the user picks where contrast is measured. */
    private fun openContrastRoi(file: File) {
        tvStatus.setText(R.string.capture_status_select_roi)
        btnStart.isVisible = false
        // Camera JPEGs often tag ORIENTATION_ROTATE_90 while OpenCV preview is
        // upright — mismatched dims made ROI Y past the declared height.
        lifecycleScope.launch {
            // Baking EXIF orientation into the pixels is a full-resolution
            // decode, rotate and re-encode — seconds and ~100MB of bitmap on a
            // 12MP shot. On the main thread that is an ANR, and this is reached
            // straight from an activity-result callback.
            val bounds = withContext(Dispatchers.IO) {
                CaptureJpegOrient.uprightInPlace(file)
                imageBounds(file)
            }
            if (bounds.first <= 1 && bounds.second <= 1) {
                offerRetryTestShot(getString(R.string.capture_test_shot_empty))
                return@launch
            }
            pickContrastRoi.launch(
                Intent(this@CaptureSessionActivity, RoiDrawActivity::class.java).apply {
                    putExtra(DicKeys.IMAGE_FILE_PATH, file.absolutePath)
                    putExtra(DicKeys.IMAGE_WIDTH, bounds.first)
                    putExtra(DicKeys.IMAGE_HEIGHT, bounds.second)
                },
            )
        }
    }

    /** Pixel size of [file] from its header alone, clamped so callers can
     *  divide by it. (1, 1) means nothing decodable was there. */
    private fun imageBounds(file: File): Pair<Int, Int> {
        val bounds = BitmapDecode.storedBounds(file.absolutePath)
        return bounds ?: (1 to 1)
    }

    /**
     * Hand the run back to the setup screen so a different resolution can be
     * chosen, carrying the long edge that would put this specimen's speckle
     * where DIC wants it.
     *
     * Cancelled rather than OK, because no recording happened; setup is still
     * on the stack by design (see [CaptureSetupActivity]) and reads the
     * recommendation off the cancelled result. Zero means "no recommendation",
     * which setup treats as simply coming back with the plan intact.
     */
    private fun returnForResolutionChange(recommendedLongEdge: Int) {
        Timber.i("capture: returning to setup, recommended long edge %d px", recommendedLongEdge)
        setResult(
            Activity.RESULT_CANCELED,
            Intent().putExtra(CaptureSetupActivity.EXTRA_RECOMMENDED_LONG_EDGE, recommendedLongEdge),
        )
        finish()
    }

    private fun onContrastRoiReady(file: File, roi: Rect) {
        tvStatus.setText(R.string.capture_status_checking)
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val bytes = file.readBytes()
                SpeckleLoad(
                    bounds = BitmapDecode.storedBounds(bytes),
                    outcome = evaluateSpeckleBounded(bytes, roi),
                    // Measured on the same full-resolution window the noise
                    // maths uses, inside the ROI the user drew, before any
                    // resolution change: this is a length in the test shot's
                    // pixels and is scaled onto whatever frame is being judged.
                    speckleDiameterPx = SpeckleScale.diameterPx(
                        NoiseFloorPixels.grayWindow(bytes, roi),
                    ),
                )
            }
            val outcome = loaded.outcome
            if (outcome == SpeckleOutcome.TimedOut) {
                offerRetryTestShot(getString(R.string.capture_speckle_check_timeout))
                return@launch
            }
            val check = (outcome as SpeckleOutcome.Done).result
            if (check == null || check.lowTexture) {
                showSpeckleFailDialog(file, tooSmall = check == null)
                return@launch
            }

            val (w, h) = loaded.bounds ?: (1 to 1)
            noiseGate.onSpeckleChecked(roi, w, h, check.subsetSize, loaded.speckleDiameterPx)
            val caps = CameraCapabilities.query(this@CaptureSessionActivity)

            // The test shot's own size is the vendor Camera app's choice, not
            // the user's — the budget has to be checked against what this run
            // will actually write.
            applySupportedResolution(caps)
            if (!checkBudgetOrShowDialog(planWidth, planHeight)) return@launch

            focusLock = CaptureFocusLock.fromExif(file, check.focusNormX, check.focusNormY, w, h)
            awaitingTestShot = false
            // Software PNG encode has no Camera2-reported stall (unlike JPEG),
            // so pacing is measured on a real locked still once the session is
            // up — see [calibrateAgainstRealCapture] — not guessed from here.
            ensureCameraThenLock()
        }
    }

    private fun showSpeckleFailDialog(file: File, tooSmall: Boolean) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(
                if (tooSmall) R.string.capture_roi_too_small_title else R.string.capture_speckle_fail_title,
            )
            .setMessage(
                if (tooSmall) R.string.capture_roi_too_small_body else R.string.capture_speckle_fail_body,
            )
            .setPositiveButton(R.string.capture_roi_select) { _, _ -> openContrastRoi(file) }
            .setNegativeButton(R.string.capture_retry) { _, _ -> launchTestShot() }
        if (!tooSmall) {
            // Placeholder: a real listener would dismiss, and returning from the
            // FAQ would leave no Select area / Retry.
            dialog.setNeutralButton(R.string.action_why, null)
        } else {
            dialog.setNeutralButton(R.string.cancel) { _, _ -> finish() }
        }
        // Deferred a frame: showing a dialog synchronously right after an
        // activity-result callback returns has been unreliable in testing.
        tvStatus.post {
            if (isFinishing || isDestroyed) return@post
            val alert = dialog.show()
            if (!tooSmall) {
                alert.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    FaqRedirect.confirm(this, R.string.url_faq_speckle)
                }
            }
        }
    }

    /**
     * No bytesPer from the test shot: deformed frames are lossless PNG, not
     * comparable in size to the vendor Camera app's JPEG — the budget's own
     * conservative PNG-per-pixel constant applies instead. Shows the fail
     * dialog and returns false when the plan doesn't fit.
     */
    private fun checkBudgetOrShowDialog(w: Int, h: Int): Boolean {
        val estimate = CaptureBudget.estimateStills(w, h, frameCount)
        val budget = CaptureBudget.check(
            estimate,
            CaptureResources.availRamBytes(this),
            CaptureResources.availStorageBytes(this),
        )
        if (budget.ok) return true
        CaptureBudgetUi.showFailDialog(this, budget, R.string.cancel) { finish() }
        return false
    }

    /**
     * Pins the run to the resolution chosen on the setup screen.
     *
     * This used to match against the *test shot's* dimensions instead, which
     * silently threw the choice away: the vendor Camera app shoots at whatever
     * size it likes, so picking 3264×2448 in setup and getting the camera
     * app's 4080×3072 back meant every frame was written at a size the user
     * never asked for and the plan was never costed against.
     *
     * The list matched against is [CameraCapabilities.Info.yuvSizes], not the
     * camera's JPEG sizes: the locked session's still ImageReader requests
     * YUV_420_888 (for lossless PNG), and real hardware often supports a
     * smaller max YUV size than JPEG. Matching against JPEG sizes here can
     * request an unsupported stream configuration and fail session creation
     * outright.
     *
     * The chosen size came from that same list, so the nearest-match is a
     * no-op in the normal case; it only bites if the catalogue changed between
     * setup and now (camera hot-swap, process death, a restored instance
     * state).
     */
    private fun applySupportedResolution(caps: CameraCapabilities.Info) {
        val matched = caps.nearest(planWidth, planHeight)
        if (matched != null && (matched.width != planWidth || matched.height != planHeight)) {
            Timber.w(
                "Chosen %dx%d is not in this camera's YUV catalogue; using nearest %dx%d",
                planWidth,
                planHeight,
                matched.width,
                matched.height,
            )
        }
        planWidth = matched?.width ?: planWidth
        planHeight = matched?.height ?: planHeight
    }

    /**
     * Re-paces the run against the cost a real still just took, without ever
     * changing the frame count.
     *
     * The count is a promise made on the setup screen, where it was chosen
     * from [CapturePlanOptions] — counts that already carry the calibration's
     * safety factor and [CapturePlanOptions.ASSURANCE_MARGIN] on top. Trimming
     * it here is what produced "I picked 150 and got 60": the user plans an
     * experiment around a number and the app quietly delivers a different one.
     * If this device turns out slower than both margins allowed, the run
     * overruns its duration slightly and still delivers every frame promised —
     * a late frame is usable data, a missing one is not.
     *
     * The interval only ever grows: pacing faster than a frame physically
     * costs would just queue captures that arrive late anyway.
     */
    private fun applyMeasuredFrameCost(perFrameMs: Long) {
        measuredFrameCostMs = perFrameMs
        // Spread the promised frames across the whole requested duration.
        // Pacing at the raw per-frame cost instead would bunch them into the
        // start of the run — 30 frames of a 120s test crammed into the first
        // 10s, missing the deformation that follows.
        frameIntervalMs = (durationSec * MILLIS_PER_SECOND / frameCount.coerceAtLeast(1))
            .coerceAtLeast(perFrameMs.coerceAtLeast(1L))
        val projectedSec = frameCount * frameIntervalMs / MILLIS_PER_SECOND
        if (projectedSec > durationSec) {
            Timber.d(
                "Frame cost %dms exceeds the assured budget; %d frames will take ~%ds not %ds",
                perFrameMs,
                frameCount,
                projectedSec,
                durationSec,
            )
        }
    }

    /**
     * How many stills [captureLockedReference] averages into the reference.
     *
     * Derived, never offered: the interval the user's rate already implies,
     * the per-frame cost measured at the locked exposure this run will
     * actually use, and whether the noise burst found the setup holding
     * still. [AveragingPlan] takes no ISO or exposure input, so nothing here
     * can buy a higher count by shortening either — see [AveragingPlan] for
     * why that would cost more than it returns.
     *
     * A burst that could not judge drift confidently
     * ([NoiseFloorStats.Outcome.INSUFFICIENT]) is treated the same as one
     * that found drift: crediting averaging on an unconfirmed setup would
     * understate the very floor the burst exists to report honestly.
     *
     * Deliberately reference-only — deformed frames are always a single
     * shot. The reference is taken before any load exists, with nothing else
     * competing for time; a deformed frame is taken on the run's own pacing
     * and right after a step, where a multi-shot burst would either eat into
     * the promised interval or risk catching the specimen mid-settle rather
     * than truly held. Averaging only where the state is most assuredly
     * still is the robust choice, not the ambitious one.
     */
    private fun averagingFrames(): Int {
        val outcome = noiseGate.floor?.verdict?.outcome
        val steady = outcome == NoiseFloorStats.Outcome.PASS || outcome == NoiseFloorStats.Outcome.HIGH_FLOOR
        return AveragingPlan.framesFor(frameIntervalMs, measuredFrameCostMs, steady)
    }

    private fun evaluateSpeckle(bytes: ByteArray, roi: Rect): SubsetRecommender.Result? {
        val (w, h) = BitmapDecode.storedBounds(bytes) ?: return null
        return SubsetRecommender.recommend(
            refBytes = bytes,
            imgW = w,
            imgH = h,
            roi = roi,
        )
    }

    private sealed interface SpeckleOutcome {
        data class Done(val result: SubsetRecommender.Result?) : SpeckleOutcome
        data object TimedOut : SpeckleOutcome
    }

    private data class SpeckleLoad(
        val bounds: Pair<Int, Int>?,
        val outcome: SpeckleOutcome,
        /** Speckle diameter in test-shot pixels, or null when unmeasurable. */
        val speckleDiameterPx: Double? = null,
    )

    /**
     * [SubsetRecommender.recommend] ultimately calls into `BitmapRegionDecoder`,
     * a native codec call observed to hang indefinitely on some device/emulator
     * media stacks with no exception and no ANR (it blocks a background
     * thread, not the main thread). A coroutine `withTimeoutOrNull` around a
     * plain blocking call does not actually preempt it — the dispatcher
     * thread executing it never returns control. Racing it on its own
     * executor with a bounded `Future.get` does: on timeout this abandons
     * the stuck thread (leaked, harmless) and lets the UI recover instead of
     * freezing forever.
     */
    private fun evaluateSpeckleBounded(bytes: ByteArray, roi: Rect): SpeckleOutcome {
        val future = speckleCheckExecutor.submit<SubsetRecommender.Result?> { evaluateSpeckle(bytes, roi) }
        return try {
            SpeckleOutcome.Done(future.get(SPECKLE_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            Timber.w("Speckle check timed out")
            future.cancel(true)
            SpeckleOutcome.TimedOut
        } catch (e: ExecutionException) {
            Timber.w(e, "Speckle check failed")
            SpeckleOutcome.Done(null)
        }
    }

    private fun ensureCameraThenLock() {
        withCameraPermission { openLockedSession(runNoiseGate = true) }
    }

    /**
     * Re-open a dropped camera after a FAQ / browser hop without re-running the
     * noise-floor burst (that would re-pop the gate dialogs).
     */
    private fun reopenLockedPreview() {
        if (relocking) return
        relocking = true
        lockedSession?.close()
        lockedSession = null
        openLockedSession(runNoiseGate = false) {
            relocking = false
        }
    }

    private fun openLockedSession(runNoiseGate: Boolean, onDone: (() -> Unit)? = null) {
        tvStatus.setText(R.string.capture_status_locking)
        val focus = focusLock ?: run {
            onDone?.invoke()
            return
        }
        val jpeg = CameraCapabilities.Resolution(planWidth, planHeight)
        val session = LockedCameraSession(this, cameraId, jpeg, focus)
        lockedSession = session

        fun startLock(texture: SurfaceTexture?) {
            lifecycleScope.launch {
                try {
                    val ok = runCatching { session.openAndLock(texture) }.getOrDefault(false)
                    if (!ok) {
                        session.close()
                        lockedSession = null
                        MaterialAlertDialogBuilder(this@CaptureSessionActivity)
                            .setTitle(R.string.capture_af_fail_title)
                            .setMessage(R.string.capture_af_fail_body)
                            .setPositiveButton(R.string.capture_retry) { _, _ -> launchTestShot() }
                            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                            .show()
                        return@launch
                    }
                    applyPreviewTransform(session)
                    if (runNoiseGate) {
                        // Focus first, and confirmed by the person who can see
                        // the screen: everything after this — the burst, the
                        // floor it reports, the run the floor describes — is
                        // measured through whatever focus is standing here.
                        if (!confirmFocus(session)) return@launch
                        focusLock = session.focus
                        tvStatus.setText(R.string.capture_status_noise_check)
                        if (!noiseGate.run(session, planWidth, planHeight)) return@launch
                        showReady()
                    } else if (readyToRecord) {
                        // Gate already acted on before the camera was dropped;
                        // restore Start recording. Leave any still-showing
                        // dialog alone when readyToRecord is still false.
                        showReady()
                    } else {
                        // Floor measured, dialog still up — keep the measuring
                        // status until Continue / Record anyway.
                        tvStatus.setText(R.string.capture_status_noise_check)
                    }
                } finally {
                    onDone?.invoke()
                }
            }
        }

        if (preview.isAvailable) {
            startLock(preview.surfaceTexture)
        } else {
            preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    startLock(st)
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    // The letterboxing is computed from the view's size, so it
                    // has to be redone when that changes — and the focus ring
                    // is placed through that same map, so it moves with it or
                    // it points at the wrong pixel.
                    lockedSession?.let {
                        applyPreviewTransform(it)
                        if (focusAccepted != null && !refocusing) showFocusMarker(it.focus)
                    }
                }
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
    }

    /**
     * Makes the preview show exactly the frame that will be written: same
     * rotation, same field of view, same shape.
     *
     * A [TextureView] left alone stretches the camera buffer to its own bounds
     * and ignores sensor orientation entirely, which on a portrait phone means
     * a sideways, distorted picture. That is bad as a picture and worse as a
     * framing surface — the user lines a specimen up against the frame edges,
     * and until now those edges were not where the capture's edges are.
     *
     * The transform is composed on top of the default buffer-to-view stretch,
     * so it starts by undoing it: back into buffer pixels, rotate about the
     * centre, scale to fit, then centre in the view.
     *
     * Everything after that undo *is* the buffer-to-view map, and a focus tap
     * has to invert it, so the whole fit lives in [PreviewMap] rather than
     * being derived once for the picture and again for the tap.
     */
    private fun applyPreviewTransform(session: LockedCameraSession) {
        val buffer = session.previewBufferSize
        val map = PreviewMap.of(
            viewW = preview.width,
            viewH = preview.height,
            bufW = buffer.width,
            bufH = buffer.height,
            rotationDegrees = session.frameRotationDegrees,
        ) ?: return
        previewMap = map
        preview.setTransform(map.textureTransform)
    }

    /**
     * Hold the flow at the locked preview until the user says the focus is
     * sharp, letting them move it anywhere in the frame first.
     *
     * This is the one decision in the whole capture path that is genuinely
     * better made by a person. Autofocus is weakest on fine repeating texture,
     * and a speckle pattern is nothing but fine repeating texture, so the point
     * the speckle check picked is a starting guess rather than an answer. A
     * soft reference frame sets a floor nothing downstream can recover: defocus
     * blurs the intensity gradients the whole correlation is built on, and in
     * the result it is indistinguishable from a bad pattern.
     *
     * It sits here, after the lock and before the burst, because that is where
     * the preview finally shows the run's own frame — same lens, same size,
     * same frozen exposure, same pipeline lockdown. Confirming earlier would
     * confirm a different camera. It cannot sit before the *test shot*, as
     * originally sketched, while the test shot is taken by the vendor camera
     * app: that app runs its own autofocus and there is no lock to carry into
     * it.
     *
     * @return false when the session died while waiting, in which case the
     *   caller must not go on to measure anything.
     */
    private suspend fun confirmFocus(session: LockedCameraSession): Boolean {
        val accepted = CompletableDeferred<Unit>()
        focusAccepted = accepted
        tvStatus.setText(R.string.capture_status_confirm_focus)
        btnStart.isVisible = false
        btnFocusConfirm.isVisible = true
        showFocusMarker(session.focus)
        loupe.reset()
        val sampler = startLoupeSampler(session)
        try {
            accepted.await()
        } finally {
            sampler.cancel()
            focusAccepted = null
            btnFocusConfirm.isVisible = false
            focusMarker.isVisible = false
            loupe.hide()
        }
        return session.isUsable
    }

    /**
     * Keeps the magnified view and the sharpness reading live while the confirm
     * step is up, and stops the moment it closes.
     *
     * Polled rather than driven by [TextureView.SurfaceTextureListener]'s
     * per-frame callback: reading the preview back costs a full view-sized copy,
     * and doing that at the preview's own rate would compete with the camera for
     * the exact frames the user is trying to judge. A few times a second is
     * enough to feel live against a static specimen on a tripod, which is the
     * only thing this is ever pointed at.
     *
     * Samples are skipped mid-re-lock: the lens is moving and [focus] still
     * holds the old point, so a reading taken then describes neither.
     */
    private fun startLoupeSampler(session: LockedCameraSession) = lifecycleScope.launch {
        while (isActive) {
            if (!refocusing) {
                loupe.update(previewMap?.viewPointOf(session.focus.normX, session.focus.normY))
            }
            delay(LOUPE_SAMPLE_MS)
        }
    }

    /**
     * A tap on the preview moves the focus point, once the confirm step is up.
     *
     * Installed for the life of the screen and gated on [focusAccepted] rather
     * than attached and detached, so there is no window where a tap lands on a
     * listener that is being swapped. Taps outside the confirm step, on the
     * letterbox bars, or while a previous tap is still being locked are
     * ignored — quietly, because a focus tap that does nothing is a normal
     * thing for a camera to do and a message for each one would be noise.
     *
     * Lint's `ClickableViewAccessibility` wants the *view class* to override
     * `performClick`, which is not available for a framework [TextureView]. The
     * mitigation it exists to enforce is done here instead: every tap calls
     * `performClick`, so an accessibility service still sees a click. There is
     * no click listener to route it to because a focus tap is defined by where
     * it landed, and a click carries no position.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun installFocusTapListener() {
        preview.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                view.performClick()
                onPreviewTapped(event.x, event.y)
            }
            true
        }
    }

    private fun onPreviewTapped(viewX: Float, viewY: Float) {
        val confirming = focusAccepted != null && !refocusing
        val session = lockedSession?.takeIf { confirming && it.isUsable } ?: return
        val point = previewMap?.uprightPointAt(viewX, viewY) ?: return
        refocusTo(session, point)
    }

    private fun refocusTo(session: LockedCameraSession, point: PointF) {
        refocusing = true
        btnFocusConfirm.isEnabled = false
        focusMarker.isVisible = false
        tvStatus.setText(R.string.capture_status_refocusing)
        lifecycleScope.launch {
            val locked = runCatching { session.refocusAt(point.x, point.y) }.getOrDefault(false)
            refocusing = false
            btnFocusConfirm.isEnabled = true
            if (!session.isUsable) {
                // Neither the new point nor the old one would lock, so there is
                // no focus left to confirm. Same path a failed initial lock
                // takes: offer a fresh test shot rather than measure through a
                // floating lens.
                focusAccepted?.complete(Unit)
                offerRetryTestShot(getString(R.string.capture_af_fail_body))
                return@launch
            }
            if (!locked) {
                Toast.makeText(
                    this@CaptureSessionActivity,
                    R.string.capture_refocus_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
            tvStatus.setText(R.string.capture_status_confirm_focus)
            showFocusMarker(session.focus)
        }
    }

    /** Put the ring on the point [lock] is aimed at, or hide it if there is no map yet. */
    private fun showFocusMarker(lock: CaptureFocusLock) {
        val point = previewMap?.viewPointOf(lock.normX, lock.normY)
        if (point == null) {
            focusMarker.isVisible = false
            return
        }
        // The ring's own size from resources, not its measured width: the first
        // call lands before the marker has ever been laid out, and a measured
        // zero would centre the ring's corner on the focus point instead of
        // its middle — a marker that quietly points a ring-radius away.
        val half = resources.getDimension(R.dimen.capture_focus_marker_size) / 2f
        focusMarker.translationX = point.x - half
        focusMarker.translationY = point.y - half
        focusMarker.isVisible = true
    }

    /** The preview is the frame you will get: setup is done, recording may start. */
    private fun showReady() {
        readyToRecord = true
        tvStatus.setText(R.string.capture_status_ready)
        btnStart.isVisible = true
        // From here the focus and the floor describe one particular framing, and
        // nothing downstream re-checks either.
        framingSensor.holdCurrentFraming()
    }

    /**
     * The rig was re-aimed while the run was waiting to start, so the two things
     * measured before it — the frozen focus distance and the noise floor — now
     * describe a scene that is no longer in front of the camera. Neither is
     * re-checked anywhere downstream, so the only honest move is to stop and
     * measure again.
     *
     * Start is withdrawn *and* the status line says why, so a dismissed dialog
     * leaves a screen that explains itself rather than a missing button. The
     * retake is the primary action; leaving is the other one.
     */
    private fun onFramingChanged() {
        if (recordingActive || isFinishing || isDestroyed) return
        readyToRecord = false
        btnStart.isVisible = false
        tvStatus.setText(R.string.capture_status_framing_changed)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.capture_framing_changed_title)
            .setMessage(R.string.capture_framing_changed_body)
            .setPositiveButton(R.string.capture_framing_changed_retry) { _, _ -> launchTestShot() }
            .setNegativeButton(R.string.capture_leave_setup_confirm) { _, _ -> finish() }
            .show()
    }

    private fun startRecording() {
        // The framing is now whatever the run is measuring; from here a movement
        // is the experiment, not a re-frame, and Part 1's drift test owns it.
        framingSensor.stop()
        btnStart.isVisible = false
        runStills()
    }

    /**
     * Takes the reference through the locked session, at the same resolution
     * and under the same locked focus and exposure as every deformed frame.
     *
     * The camera-app test shot cannot serve as the reference: it is JPEG, it
     * is shot with the vendor app's own auto-everything, and it comes out at
     * whatever resolution that app prefers — which then forced every lossless
     * frame to be resampled to match it. Correlation compares the reference
     * against each frame pixel for pixel, so all three of those differences
     * land directly in the measurement. The test shot stays what it is good
     * for: picking the contrast ROI and measuring speckle before committing.
     */
    private suspend fun captureLockedReference(session: LockedCameraSession, frames: Int): Boolean {
        tvStatus.setText(R.string.capture_status_reference)
        tvProgress.text = ""
        val file = File(SystemCamera.captureDir(this), CaptureWorkspace.REFERENCE_NAME)
        file.delete()
        val ok = session.captureAveragedStill(file, frames)
        referenceFile = if (ok) file else null
        return ok
    }

    private fun runStills() {
        tvStatus.setText(R.string.capture_status_recording_stills)
        val session = lockedSession ?: return
        val dir = SystemCamera.captureDir(this)
        val runner = StillSequenceRunner(intervalMs = frameIntervalMs, frameCount = frameCount)
        // Decided once, before the first frame: how many shots the reference
        // averages. See [averagingFrames] for why deformed frames never do.
        val frames = averagingFrames()
        var budgetExceededMidRun = false
        recordingActive = true
        lifecycleScope.launch {
            try {
                // Before the first frame, not after the last: a crash or a cancel
                // must not leave one run's frames where the next run will pick
                // them up as its own.
                val cleared = withContext(Dispatchers.IO) {
                    CaptureWorkspace.clearPreviousRun(dir)
                }
                if (cleared > 0) Timber.d("Cleared %d file(s) from a previous run", cleared)
                if (frames > 1) {
                    Toast.makeText(
                        this@CaptureSessionActivity,
                        resources.getQuantityString(R.plurals.capture_averaging_fmt, frames, frames),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                if (!captureLockedReference(session, frames)) {
                    showRetryDialog(getText(incompleteRunMessage(session))) { startRecording() }
                    return@launch
                }
                tvStatus.setText(R.string.capture_status_recording_stills)
                val result = runner.run(
                    sink = object : StillSequenceRunner.CaptureSink {
                        override suspend fun capture(index: Int): String? {
                            val file = File(dir, CaptureWorkspace.frameName(index))
                            // Never averaged — see [averagingFrames].
                            val ok = session.captureStill(file)
                            return if (ok) file.absolutePath else null
                        }
                    },
                    onProgress = { done, total ->
                        // PNG size is far less predictable than JPEG's — recheck
                        // storage from the first real frame's byte size before
                        // committing to the rest of the sequence.
                        if (done == 1) {
                            val remaining = total - done
                            if (remaining > 0) {
                                val firstBytes = File(dir, CaptureWorkspace.frameName(0)).length()
                                    .takeIf { it > 0 }
                                val remainingEstimate = CaptureBudget.estimateStills(
                                    planWidth,
                                    planHeight,
                                    remaining,
                                    firstBytes,
                                )
                                val remainingCheck =
                                    CaptureBudget.check(
                                        remainingEstimate,
                                        CaptureResources.availRamBytes(this@CaptureSessionActivity),
                                        CaptureResources.availStorageBytes(this@CaptureSessionActivity),
                                    )
                                if (!remainingCheck.ok) budgetExceededMidRun = true
                            }
                        }
                        tvProgress.text = getString(R.string.capture_progress_fmt, done, total)
                    },
                    isActive = { !isFinishing && !isDestroyed && !budgetExceededMidRun && session.isUsable },
                )
                if (budgetExceededMidRun) {
                    MaterialAlertDialogBuilder(this@CaptureSessionActivity)
                        .setTitle(R.string.capture_budget_fail_title)
                        .setMessage(R.string.capture_budget_fail_storage)
                        .setPositiveButton(R.string.cancel) { _, _ -> finish() }
                        .show()
                    return@launch
                }
                if (!result.completed || result.paths.isEmpty()) {
                    showRetryDialog(getText(incompleteRunMessage(session))) { startRecording() }
                    return@launch
                }
                deformedPaths.clear()
                deformedPaths.addAll(result.paths)
                handOffToWizard()
            } finally {
                recordingActive = false
            }
        }
    }

    /**
     * Put a second copy of the frames somewhere the user already knows how to
     * find, then say in one line what happened.
     *
     * Before the size match rather than after, so what lands in the gallery is
     * what the camera produced — the match resamples frames to the reference,
     * which is right for the engine and wrong for an archive.
     *
     * Never blocks and never asks. The frames are already safe in app storage,
     * so a copy that could not be made is worth a sentence and nothing more.
     */
    private suspend fun saveToGallery(reference: File) {
        tvStatus.setText(R.string.capture_status_saving_gallery)
        tvProgress.text = ""
        val files = listOf(reference) + deformedPaths.map { File(it) }
        when (val outcome = CaptureGallerySave.save(this, files)) {
            is CaptureGallerySave.Outcome.Saved ->
                Timber.i("gallery: saved %d of %d", outcome.saved, outcome.total)

            is CaptureGallerySave.Outcome.NoRoom ->
                Toast.makeText(this, R.string.capture_gallery_no_room, Toast.LENGTH_LONG).show()

            CaptureGallerySave.Outcome.Unsupported ->
                Timber.i("gallery: not supported on API %d", android.os.Build.VERSION.SDK_INT)
        }
    }

    /**
     * Why a run ended short. Nothing here cancelled it unless the user left,
     * so name which of the three actually happened rather than blaming a
     * mistake they did not make.
     */
    private fun incompleteRunMessage(session: LockedCameraSession): Int = when {
        session.deviceFailed -> R.string.capture_camera_lost
        session.isUsable && !isFinishing -> R.string.capture_frames_failed
        else -> R.string.capture_cancelled
    }

    private suspend fun handOffToWizard() {
        lockedSession?.close()
        lockedSession = null
        // The locked reference when there is one; the test shot only as a
        // fallback, since it costs the frames a resample (see below).
        val ref = (referenceFile ?: testShotFile)?.absolutePath
        if (ref == null || deformedPaths.isEmpty()) {
            finish()
            return
        }
        saveToGallery(File(ref))
        // Why this has to happen at all, and why it is usually a no-op:
        // see [CaptureFrameSizeMatcher].
        tvStatus.setText(R.string.capture_status_matching_sizes)
        tvProgress.text = ""
        val matchedPaths = CaptureFrameSizeMatcher
            .matchToReference(File(ref), deformedPaths) { done, total ->
                runOnUiThread {
                    tvProgress.text = getString(R.string.capture_progress_fmt, done, total)
                }
            }
        // Setup stays under us until RESULT_OK; it starts the wizard and
        // finishes so Back from the wizard returns to Home.
        val data = Intent().apply {
            putExtra(
                DicKeys.PICKED_REF_URI,
                SystemCamera.fileProviderUri(this@CaptureSessionActivity, File(ref)).toString(),
            )
            putStringArrayListExtra(
                DicKeys.PICKED_DEF_URIS,
                ArrayList(
                    matchedPaths.map { path ->
                        SystemCamera.fileProviderUri(this@CaptureSessionActivity, File(path)).toString()
                    },
                ),
            )
            // The floor this run was captured at, so the analysis can stamp it
            // on the session, the report and the CSV. Absent when the burst
            // could not run at all, which is already its own failure path.
            noiseGate.measured()?.let { putExtra(DicKeys.CAPTURE_NOISE_FLOOR, it.encode()) }
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        /** Only reached when the setup extra is missing; the setup screen owns the value. */
        const val DEFAULT_DURATION_SEC = CaptureSetupActivity.DEFAULT_DURATION_SEC
        const val DEFAULT_FRAME_COUNT = 10
        const val SPECKLE_CHECK_TIMEOUT_MS = 8_000L

        /** Gap between loupe samples. See [startLoupeSampler] for why it is polled. */
        const val LOUPE_SAMPLE_MS = 300L
        val speckleCheckExecutor: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "SpeckleCheck").apply { isDaemon = true }
        }
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val STATE_FOCUS_LOCK = "capture_focus_lock"
        const val STATE_DEFORMED_PATHS = "capture_deformed_paths"
        const val STATE_PLAN_WIDTH = "capture_plan_width"
        const val STATE_PLAN_HEIGHT = "capture_plan_height"
        const val STATE_CAMERA_ID = "capture_camera_id_state"
        const val STATE_INTERVAL_MS = "capture_interval_ms_state"
        const val STATE_REFERENCE_PATH = "capture_reference_path"
        const val STATE_MEASURED_FRAME_COST_MS = "capture_measured_frame_cost_ms"
        const val STATE_READY_TO_RECORD = "capture_ready_to_record"
        const val STATE_AWAITING_TEST_SHOT = "capture_awaiting_test_shot"
        const val STATE_NOISE_FLOOR = "capture_noise_floor_state"
        const val STATE_FLOOR_OVERRIDDEN = "capture_floor_overridden"
    }
}
