package com.indicvision.semper.ui.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.ui.analysis.StaticAnalysisActivity
import com.indicvision.semper.ui.common.Insets
import timber.log.Timber

/**
 * Collects capture rate / duration / resolution, runs the budget gate, then
 * hands off to [CaptureSessionActivity] for the test shot and locked recording.
 *
 * The rate is picked from [CapturePlanOptions], not requested through a
 * slider. A slider let the user ask for anything and had the app trim it
 * afterwards, which put the explanation in the wrong place — after the run,
 * about a number they had already planned around. Offering only rates the
 * device can hold removes the trim entirely.
 *
 * Setup stays on the stack until recording succeeds so Back from the session
 * can return here to change the plan — and so the capture screen can send the
 * run back deliberately, carrying the resolution that would let the speckle be
 * measured. On success this screen starts the wizard and finishes so Back from
 * the wizard lands on Home.
 */
// One function over the threshold: the screen has a single job and each of
// these is one step of it — read the plan, offer the rates, price them, launch,
// and take back the recommendation the capture screen sends home.
@Suppress("TooManyFunctions")
class CaptureSetupActivity : AppCompatActivity() {

    private lateinit var caps: CameraCapabilities.Info
    private lateinit var etDuration: EditText
    private lateinit var chipsFps: ChipGroup
    private lateinit var tvEstimate: TextView
    private lateinit var tvAssurance: TextView
    private lateinit var tvMode: TextView
    private lateinit var resPicker: CaptureResolutionPicker
    private lateinit var btnContinue: MaterialButton
    private var options: List<CapturePlanOptions.Option> = emptyList()

    /** Cost of one still at the current resolution, recomputed by [rebuild]. */
    private var perFrameMs = 0L

    /**
     * Rate the user last chose. A duration or resolution change rebuilds the
     * list, and their intent ("as fast as it goes" / "one every few seconds")
     * should survive that rather than snapping back to a default.
     */
    private var preferredFps = 0f

    /**
     * Last duration that parsed. The field can legitimately hold "4:" mid-edit,
     * and the rest of the screen should keep showing the plan for 4 minutes
     * rather than blanking or snapping to a default on every keystroke.
     */
    private var durationSec = DEFAULT_DURATION_SEC

    private val captureSession = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            // Not every cancel is a Back press. The capture screen sends the
            // run back here when the speckle cannot be resolved at the chosen
            // size, and the size that would resolve it rides on the cancelled
            // result — so the extra is read before the early return, not after.
            applyRecommendedResolution(result.data)
            return@registerForActivityResult
        }
        val data = result.data ?: return@registerForActivityResult
        startActivity(
            Intent(this, StaticAnalysisActivity::class.java).apply {
                putExtra(DicKeys.PICKED_REF_URI, data.getStringExtra(DicKeys.PICKED_REF_URI))
                putStringArrayListExtra(
                    DicKeys.PICKED_DEF_URIS,
                    data.getStringArrayListExtra(DicKeys.PICKED_DEF_URIS),
                )
                data.getStringExtra(DicKeys.CAPTURE_NOISE_FLOOR)?.let {
                    putExtra(DicKeys.CAPTURE_NOISE_FLOOR, it)
                }
                putExtra(DicKeys.LAUNCHED_FROM_CAPTURE, true)
            },
        )
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_setup)
        Insets.padTop(findViewById(R.id.toolbarCaptureSetup))

        caps = CameraCapabilities.query(this)

        findViewById<MaterialToolbar>(R.id.toolbarCaptureSetup).apply {
            setNavigationOnClickListener { finish() }
        }

        etDuration = findViewById(R.id.etCaptureDuration)
        chipsFps = findViewById(R.id.chipsCaptureFps)
        tvEstimate = findViewById(R.id.tvCaptureEstimate)
        tvAssurance = findViewById(R.id.tvCaptureAssurance)
        tvMode = findViewById(R.id.tvCaptureMode)

        // A bigger frame costs more to read out and more to encode, so what
        // this device can assure is a property of the chosen resolution, not
        // of the device alone: a change here rebuilds the rates.
        resPicker = CaptureResolutionPicker(
            findViewById<Spinner>(R.id.spinnerCaptureResolution),
            offerableSizes(),
        ) { rebuild() }

        btnContinue = findViewById(R.id.btnCaptureContinue)
        wireDurationField()
        chipsFps.setOnCheckedStateChangeListener { _, checked ->
            checked.firstOrNull()?.let { id ->
                preferredFps = findViewById<Chip>(id).tag as? Float ?: preferredFps
            }
            refreshLine()
        }
        rebuild()

        btnContinue.setOnClickListener { onContinue() }
    }

    /**
     * The field drives the plan as it is typed, but is only rewritten when the
     * user is done with it. Reformatting mid-keystroke fights the caret and
     * makes "10:00" impossible to type — the first "1" would become "00:01".
     */
    private fun wireDurationField() {
        etDuration.setText(CaptureDurationText.format(durationSec))
        etDuration.doAfterTextChanged { text ->
            val parsed = CaptureDurationText.parse(text) ?: return@doAfterTextChanged
            if (parsed == durationSec) return@doAfterTextChanged
            durationSec = parsed
            rebuild()
        }
        etDuration.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) normaliseDuration() }
        etDuration.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) normaliseDuration()
            false
        }
    }

    /** Snap the field back to canonical mm:ss inside the allowed range. */
    private fun normaliseDuration() {
        durationSec = CaptureDurationText.clamp(
            CaptureDurationText.parse(etDuration.text) ?: durationSec,
        )
        val canonical = CaptureDurationText.format(durationSec)
        if (etDuration.text.toString() != canonical) etDuration.setText(canonical)
        rebuild()
    }

    /**
     * The resolutions worth putting in the drawer: the ones this camera can
     * actually hold [CapturePlanOptions.MIN_FPS] at.
     *
     * A resolution that cannot reach the floor has no rate to offer, so
     * choosing it can only produce the "no usable capture rate" refusal. Naming
     * it in the list is an offer the screen has already decided to decline —
     * better not to make it.
     *
     * The filter is only ever the **camera's** limit. Max frames and the run
     * length bind every resolution alike, so if they are what is wrong, hiding
     * resolutions would hide the whole list and point the user at the one thing
     * that is not the problem; that case keeps the full list and is explained
     * by [showNoRate] instead. For the same reason, if *nothing* clears the
     * floor the list is left whole: an empty drawer says less than a populated
     * one beside a message naming the camera.
     */
    private fun offerableSizes(): List<CameraCapabilities.Resolution> {
        val all = caps.yuvSizes
        val sustainable = all.filter {
            CapturePlanOptions.sustainableAtFloor(CaptureFrameCost.perFrameMs(this, caps, it))
        }
        if (sustainable.size < all.size) {
            Timber.i(
                "capture setup: %d of %d resolutions cannot hold %s fps; not offering them",
                all.size - sustainable.size,
                all.size,
                CapturePlanOptions.MIN_FPS,
            )
        }
        return sustainable.ifEmpty { all }
    }

    private fun maxFramesSetting(): Int =
        DicSettings.maxFrames(this, AppRemoteConfig.maxFrames(this))

    /** Rebuild the offered rates for the current duration and resolution. */
    private fun rebuild() {
        val duration = durationSec
        perFrameMs = CaptureFrameCost.perFrameMs(this, caps, resPicker.selected)
        options = CapturePlanOptions.of(duration, perFrameMs, maxFramesSetting())
        populateChips()
        refreshLine()
    }

    private fun populateChips() {
        chipsFps.removeAllViews()
        // Keep the closest rate to what they had; on first open that is the
        // top of the list, which is what most runs want.
        val target = options.minByOrNull { kotlin.math.abs(it.fps - preferredFps) } ?: return
        preferredFps = target.fps
        for (option in options) {
            val chip = Chip(this).apply {
                text = getString(R.string.capture_fps_value_fmt, CaptureEstimateText.fps(option.fps))
                tag = option.fps
                isCheckable = true
                isChecked = option.fps == target.fps
            }
            chipsFps.addView(chip)
        }
    }

    private fun selectedOption(): CapturePlanOptions.Option? =
        options.firstOrNull { it.fps == preferredFps } ?: options.firstOrNull()

    private fun refreshLine() {
        val modeLabel = getString(R.string.capture_mode_stills)
        tvMode.text = modeLabel
        // One prefs read: this runs on every keystroke in the duration field.
        val frameCap = maxFramesSetting()
        val cappedBySetting = CapturePlanOptions.cappedByFrameSetting(
            perFrameMs,
            durationSec,
            frameCap,
        )
        val option = selectedOption()
        if (option == null) {
            showNoRate(cappedBySetting, frameCap)
            return
        }
        btnContinue.isEnabled = true
        tvEstimate.text = CaptureEstimateText.line(this, option, modeLabel)
        tvAssurance.text = getString(
            R.string.capture_fps_assured_with_ceiling,
            getString(R.string.capture_fps_assured),
            CaptureEstimateText.ceilingNote(this, cappedBySetting, frameCap),
        )
    }

    /**
     * No rate on the ladder fits this plan, so there is nothing to continue to.
     *
     * The button used to stay enabled and do nothing at all: [selectedOption]
     * returned null on an empty list and [onContinue] returned silently. A
     * disabled button plus the binding limit named in the line below the chips
     * is the same information the user needed, in a place they will read it.
     */
    private fun showNoRate(cappedBySetting: Boolean, frameCap: Int) {
        btnContinue.isEnabled = false
        val res = resPicker.selected
        tvEstimate.text = getString(R.string.capture_no_rate_title)
        val why = if (cappedBySetting) {
            CaptureEstimateText.noRateFromSetting(this, durationSec, frameCap)
        } else {
            CaptureEstimateText.noRateFromCamera(this, res.label, perFrameMs)
        }
        tvAssurance.text = getString(
            R.string.capture_fps_assured_with_ceiling,
            why,
            getString(R.string.capture_no_rate_why),
        )
    }

    @Suppress("ReturnCount")
    private fun onContinue() {
        if (!SystemCamera.canCaptureStill(this)) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.capture_no_camera_app)
                .setPositiveButton(R.string.cancel, null)
                .show()
            return
        }
        val option = selectedOption() ?: return
        val res = resPicker.selected
        val duration = durationSec
        val estimate = CaptureBudget.estimateStills(res.width, res.height, option.frames)
        val check = CaptureBudget.check(
            estimate,
            CaptureResources.availRamBytes(this),
            CaptureResources.availStorageBytes(this),
        )
        if (!check.ok) {
            CaptureBudgetUi.showFailDialog(this, check)
            return
        }

        // Stay alive: Back from the session returns here with the plan intact.
        captureSession.launch(
            Intent(this, CaptureSessionActivity::class.java).apply {
                putExtra(EXTRA_DURATION_SEC, duration)
                putExtra(EXTRA_FRAME_COUNT, option.frames)
                putExtra(EXTRA_INTERVAL_MS, option.intervalMs)
                putExtra(EXTRA_WIDTH, res.width)
                putExtra(EXTRA_HEIGHT, res.height)
                putExtra(EXTRA_CAMERA_ID, caps.cameraId)
            },
        )
    }

    /**
     * Move the spinner to the resolution the capture screen recommended, and
     * say on screen that it moved.
     *
     * Silently changing a setting the user chose is worse than not changing it:
     * they picked that size for a reason, and a screen that quietly disagrees
     * teaches them not to trust it. So the change is made — it is the whole
     * point of coming back here — and then named.
     */
    private fun applyRecommendedResolution(data: Intent?) {
        val longEdge = data?.getIntExtra(EXTRA_RECOMMENDED_LONG_EDGE, 0) ?: 0
        if (longEdge <= 0) return
        val picked = resPicker.select(longEdge) ?: return
        Snackbar.make(
            findViewById(R.id.spinnerCaptureResolution),
            getString(R.string.capture_resolution_moved_fmt, picked.label),
            Snackbar.LENGTH_LONG,
        ).show()
    }

    companion object {
        const val EXTRA_DURATION_SEC = "capture_duration_sec"
        const val EXTRA_FRAME_COUNT = "capture_frame_count"
        const val EXTRA_INTERVAL_MS = "capture_interval_ms"
        const val EXTRA_WIDTH = "capture_width"
        const val EXTRA_HEIGHT = "capture_height"
        const val EXTRA_CAMERA_ID = "capture_camera_id"

        /**
         * Long edge, in pixels, that the capture screen worked out would put
         * this specimen's speckle in the DIC band. Rides on a *cancelled*
         * result, because nothing was recorded.
         */
        const val EXTRA_RECOMMENDED_LONG_EDGE = "capture_recommended_long_edge"

        /** Short enough to be a first run, long enough to be a real one. */
        const val DEFAULT_DURATION_SEC = 10
    }
}
