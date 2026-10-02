// The wizard's host: it builds the parts and implements the sweep setup's
// callbacks, each a one-line hand-off, hence the function count.
@file:Suppress("TooManyFunctions")
@file:SuppressLint("PrivateResource")

package com.indicvision.semper.ui.analysis

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.indicvision.semper.R
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.prefs.DicSettings
import com.indicvision.semper.data.prefs.WizardDraft
import com.indicvision.semper.databinding.ActivityStaticAnalysisBinding
import com.indicvision.semper.databinding.WizardStepSettingsBinding
import com.indicvision.semper.databinding.WizardStepSettingsContentBinding
import com.indicvision.semper.databinding.WizardStepSweepBinding
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.imaging.video.ExtractionRequest
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.ui.analysis.roi.RoiResolveHelper
import com.indicvision.semper.ui.analysis.run.BatchRunController
import com.indicvision.semper.ui.analysis.run.ComputeOverlayHelper
import com.indicvision.semper.ui.analysis.run.RunChrome
import com.indicvision.semper.ui.analysis.sweep.SweepSetupHelper
import com.indicvision.semper.ui.analysis.sweep.VsgStudy
import com.indicvision.semper.ui.analysis.wizard.AnalysisReadyGate
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.analysis.wizard.AnalysisWizardChrome
import com.indicvision.semper.ui.analysis.wizard.AnalysisWizardCoach
import com.indicvision.semper.ui.analysis.wizard.AnalysisWizardSlots
import com.indicvision.semper.ui.analysis.wizard.DraftRestore
import com.indicvision.semper.ui.analysis.wizard.ReferencePreviewLoader
import com.indicvision.semper.ui.analysis.wizard.WizardStep
import com.indicvision.semper.ui.common.CoachMarkController
import com.indicvision.semper.ui.common.FaqRedirect
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.common.Motion
import com.indicvision.semper.ui.common.WarnChip
import com.indicvision.semper.ui.common.onButtonChecked
import com.indicvision.semper.ui.common.showUnlessEditing
import kotlinx.coroutines.launch

/**
 * The analysis setup wizard: page 1 loads reference/deformed images (or
 * extracts frames from a video), page 2 sets parameters + ROI and launches
 * the batch solve via [AnalysisViewModel]; page 3 sets up a parameter sweep.
 * Results open in ResultViewerActivity.
 *
 * Each part is its own class; this screen builds them, routes what one part
 * changes to the parts that show it, and steps between the pages.
 */
@MainThread
class StaticAnalysisActivity :
    AppCompatActivity(),
    AnalysisWizardHost {

    private val viewModel: AnalysisViewModel by viewModels()

    // The wizard's three pages: the host layout (page 1, the bottom nav and the
    // run overlay), and the settings and sweep pages inflated from their stubs.
    private lateinit var binding: ActivityStaticAnalysisBinding
    private lateinit var settingsPage: WizardStepSettingsBinding
    private lateinit var settings: WizardStepSettingsContentBinding
    private lateinit var sweepPage: WizardStepSweepBinding

    private var refPreviewBmp: Bitmap? = null

    // Prominent progress overlay (compute + video extraction), and whether
    // the wizard is busy with either.
    private lateinit var chrome: RunChrome
    private lateinit var status: RunStatusLine
    private lateinit var readyGate: AnalysisReadyGate
    private lateinit var wizardChrome: AnalysisWizardChrome
    private lateinit var wizardSlots: AnalysisWizardSlots
    private lateinit var wizardCoach: AnalysisWizardCoach
    private lateinit var sweepHelper: SweepSetupHelper
    private lateinit var frameOrder: FrameOrderController
    private lateinit var subsets: SubsetRecommendationController
    private lateinit var params: WizardParamFields
    private lateinit var runs: WizardRunLauncher
    private lateinit var imports: FrameImportController
    private lateinit var reference: ReferenceImportController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStaticAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel.attachDraft(WizardDraft(applicationContext))
        // Later wizard pages live in ViewStubs so the host layout stays under
        // lint's TooManyViews cap.
        settingsPage = WizardStepSettingsBinding.bind(binding.stubStepSettings.inflate())
        settings = settingsPage.settingsColumn.binding
        sweepPage = WizardStepSweepBinding.bind(binding.stubStepSweep.inflate())

        chrome = RunChrome(this, ComputeOverlayHelper(binding), binding.btnRunCancel)
        AnalysisLeaveController(this, viewModel, chrome) { goToStep(it, animate = true) }
        status = RunStatusLine(this, chrome, settings.tvStaticResult, settings.btnEngineFailFaq)
        clearRunStatus()
        buildPages()
        val outcomes = buildRuns()
        buildImports()

        binding.btnNext.setOnClickListener {
            when (viewModel.step) {
                WizardStep.IMAGES -> goToStep(WizardStep.SETTINGS, animate = true)
                WizardStep.SETTINGS -> if (viewModel.sweepMode) goToStep(WizardStep.SWEEP, animate = true)
                WizardStep.SWEEP -> Unit
            }
        }
        binding.btnBack.setOnClickListener {
            viewModel.step.previous?.let { goToStep(it, animate = true) }
        }
        goToStep(viewModel.step, animate = false)

        // Hand-off from Home's media picker: the selection type already
        // decided the branch — image becomes the reference, video enters
        // the extract-frames flow. Consumed once: a process death restores
        // the original Intent, extras and all, but the draft holds the result.
        if (savedInstanceState == null) consumePickerHandOff()
        applyInsets()
        // Gentle entrance: cards cascade in on first show only (not on rotation)
        if (savedInstanceState == null) Motion.enterStaggered(binding.contentColumn)

        restoreUiFromViewModel()
        BatchRunController(this, viewModel, chrome, settings.tvStaticResult, host = outcomes).observe()
        wireButtons()
    }

    /** The warnings, the sliders, the page chrome and the sweep page. */
    private fun buildPages() {
        val frameSizeChip = WarnChip(settings.frameSizeWarnRow.root, ::confirmOpenFaq)
            .apply { setFaq(getString(R.string.url_faq_frame_size)) }
        readyGate = AnalysisReadyGate(viewModel, binding, settings, frameSizeChip)
        // Text is set per-refresh by AnalysisWizardSlots.updateFormatChip: it
        // names the formats actually loaded, so it cannot be fixed here.
        val formatChip = WarnChip(binding.formatWarnRow.root, ::confirmOpenFaq)
            .apply { setFaq(getString(R.string.url_faq_jpeg)) }
        frameOrder = FrameOrderController(this, viewModel, binding, onReordered = { wizardSlots.refreshDefSlot() })
        subsets = SubsetRecommendationController(this, viewModel, binding, settings, host = this)
        settings.rgInterpolator.onButtonChecked { clearRunStatus() }
        params = WizardParamFields(this, viewModel, settings, subsets, host = this).also { it.bind() }

        wizardChrome = AnalysisWizardChrome(this, binding, settingsPage, sweepPage)
        wizardCoach = AnalysisWizardCoach(this, CoachMarkController(this), binding, settings, sweepPage)
        sweepHelper = SweepSetupHelper(activity = this, viewModel = viewModel, callbacks = this)
        // After the wizard views exist: the sweep controls call checkReady().
        sweepHelper.setup()
        wizardSlots = AnalysisWizardSlots(
            viewModel = viewModel,
            binding = binding,
            settings = settings,
            formatChip = formatChip,
            frameOrderAdapter = frameOrder.adapter,
            onLineCutPreview = { sweepHelper.refreshLineCutPreview() },
        )
    }

    private fun buildRuns(): WizardRunOutcomes {
        runs = WizardRunLauncher(this, viewModel, chrome, sweepHelper, ::checkReady)
        return WizardRunOutcomes(this, viewModel, chrome, status, sweepHelper, ::checkReady)
    }

    private fun buildImports() {
        imports = FrameImportController(this, viewModel, chrome, settings.tvStaticResult, ::checkReady)
        reference = ReferenceImportController(this, viewModel, onLoaded = { preview ->
            refPreviewBmp = preview
            onImagesChanged(newReference = true, newFrames = false)
        })
    }

    /**
     * The pickers and the ROI studio, in this order: their result launchers
     * are matched by registration order after a process death.
     */
    private fun wireButtons() {
        val pickers = WizardMediaPickers(this, onReference = reference::load, onDeformed = ::importDeformed)
        val roiStudio = RoiStudioLauncher(this, viewModel, onRoiChanged = {
            wizardSlots.updateRoiSummary()
            checkReady()
            subsets.request()
        })
        binding.refDropzone.setOnClickListener { pickers.pickReference() }
        binding.btnRefChange.setOnClickListener { pickers.pickReference() }
        binding.defDropzone.setOnClickListener { pickers.pickDeformed() }
        binding.btnDefChange.setOnClickListener { pickers.pickDeformed() }
        settings.btnDefineRoi.setOnClickListener { roiStudio.open() }
        binding.btnCalculateFullField.setOnClickListener {
            // A field still holding focus has not committed its typed value yet.
            commitParamFields()
            if (!viewModel.sweepMode) runs.startBatch(params.dicParams(), params.useKeysInterpolator())
        }
    }

    private fun applyInsets() {
        // Edge-to-edge (targetSdk 36): push the app bar below the status bar
        // and keep the wizard nav above the nav-bar gesture area so the top
        // controls aren't in the system swipe-down zone.
        Insets.padTop(binding.toolbar)
        binding.toolbar.apply {
            title = getString(R.string.new_analysis_title)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        val maxFrames = DicSettings.maxFrames(this, AppRemoteConfig.maxFrames(this))
        binding.tvDefDropHint.text =
            resources.getQuantityString(R.plurals.def_formats_hint_fmt, maxFrames, maxFrames)
        Insets.padBottom(binding.bottomNav)

        // Keyboard: the settings/sweep pages hold number fields; pad their scroll
        // viewports by the part of the IME above the nav bar, and scroll the
        // focused field clear of the keyboard.
        Insets.padImeBottom(settingsPage.root)
        Insets.padImeBottom(sweepPage.root)
    }

    override fun onDestroy() {
        // The engine runs on a process-global daemon thread that outlives this
        // Activity. Without this teardown a solve in flight when the screen is
        // destroyed keeps burning CPU and holding frame bytes, the progress
        // ticker reposts against dead views, and KEEP_SCREEN_ON leaks.
        viewModel.cancelRequested = true // also flips the native cancel flag via AnalysisCancelGate
        if (::imports.isInitialized) imports.cancel()
        if (::reference.isInitialized) reference.cancel()
        // VsgStudyRunner observes the same gate — no separate flag.
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::chrome.isInitialized) chrome.overlay.release()
        if (::frameOrder.isInitialized) frameOrder.release()
        // Reclaim the retained reference thumbnail deterministically on close. The
        // thumbnail ImageViews won't be drawn again after onDestroy, so this is
        // safe. (Replace sites intentionally don't recycle: the prior bitmap may
        // still be shown in a slot until its refresh runs, and recycling a live
        // bitmap crashes on the next draw — the superseded ones are GC-eligible.)
        refPreviewBmp?.recycle()
        refPreviewBmp = null
        // Finishing is the one way out that no restore follows.
        if (isFinishing) viewModel.discardDraft()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::params.isInitialized) params.refreshPasteVisibility()
    }

    private fun importDeformed(uris: List<Uri>) {
        imports.importDeformed(uris) { onImagesChanged(newReference = false, newFrames = true) }
    }

    /** Extracts the frames [request] samples, with the progress overlay. */
    private fun extractVideoFrames(request: ExtractionRequest) {
        if (chrome.isBusy) return
        // The video's first frame becomes the reference; an image pick still
        // decoding must not land on top of it.
        reference.cancel()
        imports.extractVideo(request) { applied ->
            applied.refPreview?.let { refPreviewBmp = it }
            onImagesChanged(newReference = true, newFrames = true)
        }
    }

    /**
     * Redraws the slots new images change, then re-checks the frame sizes,
     * drops the last run's status, re-checks the buttons and re-measures the
     * subset for a new reference.
     */
    private fun onImagesChanged(newReference: Boolean, newFrames: Boolean) {
        if (newReference) wizardSlots.refreshRefSlot(refPreviewBmp)
        if (newFrames) wizardSlots.refreshDefSlot()
        // A different-size reference resets the ROI (applyNewReference).
        if (newReference) wizardSlots.updateRoiSummary()
        // Frames may have been loaded before this reference.
        viewModel.checkFrameSizes(resources)
        clearRunStatus()
        checkReady()
        if (newReference) subsets.request()
    }

    override fun clearRunStatus() = status.clear()

    override fun currentSubsetSize(): Int = params.subsetSize()

    /** Largest odd subset the loaded image and ROI can hold. */
    override fun maxSubsetForRoi(): Int = RoiResolveHelper.maxSubsetForRoi(
        hasCustomRoi = viewModel.hasCustomRoi,
        roiX = viewModel.roiX,
        roiY = viewModel.roiY,
        roiW = viewModel.roiW,
        roiH = viewModel.roiH,
        realRefWidth = viewModel.realRefWidth,
        realRefHeight = viewModel.realRefHeight,
    )

    /** Flushes any in-progress typing into the sliders (focus loss commits). */
    override fun commitParamFields() {
        params.commit()
        if (::sweepHelper.isInitialized) sweepHelper.clearSweepFieldFocus()
    }

    override fun onSweepInputsChanged() {
        if (::sweepHelper.isInitialized) sweepHelper.onRecommendationChanged()
    }

    override fun resetSweepInputs() {
        if (!::sweepHelper.isInitialized) return
        viewModel.stepDenominator = VsgStudy.DEFAULT_STEP_DENOM
        viewModel.subsetOverlap = VsgStudy.overlapForDenominator(VsgStudy.DEFAULT_STEP_DENOM)
        sweepHelper.resetUserModified()
        sweepHelper.seedSweepSuggestions()
    }

    override fun startVsgSweep() = runs.startSweep(params.useKeysInterpolator())

    override fun confirmOpenFaq(url: String) {
        FaqRedirect.confirm(this, url)
    }

    // ------------------------------------------------------------------
    // Wizard navigation: page 1 (images) → page 2 (settings) → page 3 (sweep)
    // ------------------------------------------------------------------
    /** The sweep setup's way to the settings page; also how the smoke test moves the wizard. */
    override fun goToStep(step: Int, animate: Boolean) = goToStep(WizardStep.of(step), animate)

    override fun updateWizardChrome() = wizardChrome.updateBottomNav(viewModel.step, viewModel.sweepMode)

    override fun refPreviewBitmap(): Bitmap? = refPreviewBmp

    override fun renderParamField(field: EditText, value: Int) = field.showUnlessEditing(value.toString())

    private fun goToStep(step: WizardStep, animate: Boolean) {
        val target = wizardChrome.applyStep(
            previous = viewModel.step,
            requested = step,
            sweepMode = viewModel.sweepMode,
            animate = animate,
        )
        viewModel.step = target

        // Reaching the settings page counts as reviewing the parameters —
        // they are all visible here — which satisfies the Compute gate.
        if (target >= WizardStep.SETTINGS) viewModel.settingsReviewed = true

        if (target == WizardStep.SETTINGS) {
            wizardSlots.updateRoiSummary()
            // Cheap no-op when the reference/ROI have not changed since the
            // last measurement; covers inputs that arrived before this page.
            subsets.request()
        }
        if (target == WizardStep.SWEEP) {
            sweepHelper.refreshSweepPlan()
        }

        checkReady()
        wizardCoach.maybeShow(target)
    }

    override fun checkReady() {
        readyGate.apply(chrome.isBusy, sweepHelper = if (::sweepHelper.isInitialized) sweepHelper else null)
    }

    private fun consumePickerHandOff() {
        intent.getStringExtra(DicKeys.PICKED_REF_URI)?.let {
            intent.removeExtra(DicKeys.PICKED_REF_URI)
            reference.load(it.toUri())
        }
        intent.getStringExtra(DicKeys.PICKED_VIDEO_URI)?.let {
            intent.removeExtra(DicKeys.PICKED_VIDEO_URI)
            VideoSamplingSheet(this, onExtract = ::extractVideoFrames).open(it.toUri())
        }
        intent.getStringArrayListExtra(DicKeys.PICKED_DEF_URIS)?.let { list ->
            intent.removeExtra(DicKeys.PICKED_DEF_URIS)
            if (list.isNotEmpty()) importDeformed(list.map { it.toUri() })
        }
    }

    /**
     * Redraws the slots from the view model, after first reading back the
     * draft when this is a restore from a process death (ADR-005). Runs
     * straight through, without suspending, when there is nothing to read.
     */
    private fun restoreUiFromViewModel() {
        lifecycleScope.launch {
            val restore = viewModel.restoreDraft()
            val bytes = viewModel.refBytes
            if (bytes != null) {
                // Through the loader, not the native decoder directly: a RAW
                // reference is a headerless RGBA blob, which OpenCV cannot read.
                refPreviewBmp = ReferencePreviewLoader.load(
                    ReferencePreviewLoader.Request(
                        bytes = bytes,
                        intentWidth = viewModel.realRefWidth,
                        intentHeight = viewModel.realRefHeight,
                        previewMaxEdge = BitmapDecode.PREVIEW_MAX_EDGE,
                    ),
                ).bitmap
            }
            wizardSlots.refreshRefSlot(refPreviewBmp)
            wizardSlots.refreshDefSlot()
            when (restore) {
                // Re-enter the page so it re-measures what it shows.
                DraftRestore.RESTORED -> goToStep(viewModel.step, animate = false)
                DraftRestore.LOST -> {
                    goToStep(WizardStep.IMAGES, animate = false)
                    val lost = getString(R.string.wizard_draft_lost)
                    Snackbar.make(findViewById(android.R.id.content), lost, FaqRedirect.durationFor(lost))
                        .setAnchorView(binding.bottomNav)
                        .show()
                }
                DraftRestore.NONE -> Unit
            }
            checkReady()
            subsets.apply()
        }
    }
}
