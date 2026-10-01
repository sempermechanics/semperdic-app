package com.indicvision.semper.ui.viewer

import android.content.Context
import android.content.Intent
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SkippedNode
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.ui.analysis.VsgLatticeActivity
import timber.log.Timber

/**
 * Per-frame settings only a parameter sweep carries.
 *
 * Its presence is also what picks the destination: a sweep opens on the
 * interactive lattice, and a lattice node opens the result viewer with the
 * same arguments plus a [ViewerArgs.startFrame].
 */
data class ViewerSweepArgs(
    val subsets: List<Int>,
    val steps: List<Int>,
    val strainWindows: List<Int>,
    val lineCutHorizontal: Boolean,
    val skippedJson: String,
)

/**
 * Everything a viewer launch puts on its Intent, and everything a viewer reads
 * back (ADR-003).
 *
 * Two callers open the viewer — `SessionOpenHelper` from the Home list and a
 * finished run from `AnalysisNavHelper` — and four places read it:
 * `ResultViewerActivity`, `VsgLatticeActivity`, `ViewerSettingsSheet` and
 * `ViewerReportFactory`. All of them go through this type: writers through
 * [toIntent], readers through [from]. A missing extra used to read as whatever
 * default each reader picked (subset 41 in one place, 0 in another); now it is
 * filled from the session record, else from one default below, and logged.
 *
 * Deliberately **not** a `Parcelable` under a single extra: an Intent already
 * in the back stack across an update must keep opening, so the wire format is
 * the same twenty-odd [DicKeys] it always was.
 */
data class ViewerArgs(
    val imgW: Int,
    val imgH: Int,
    val step: Int,
    val refName: String,
    val refPath: String,
    val batchDirPath: String?,
    val frameNames: List<String>,
    val stopCode: Int,
    val plannedFrames: Int,
    val sessionId: String?,
    val sessionLocalId: String?,
    val subsetSize: Int,
    val strainWindow: Int,
    val engineStats: List<Float>?,
    val roiX: Int,
    val roiY: Int,
    val roiW: Int,
    val roiH: Int,
    val sweep: ViewerSweepArgs? = null,
    // The just-analysed run's paths. Home omits both: its frames are the
    // originals persisted under the session dir, which the viewer prefers
    // anyway, and an absent extra and an empty one read the same there.
    val defPath: String? = null,
    val defFilePaths: List<String> = emptyList(),
    /** The frame to open on; null opens a run on its summary. A lattice node sets it. */
    val startFrame: Int? = null,
    val strainMethod: String = STRAIN_METHOD_VSG,
) {

    /**
     * A sweep opens on its lattice unless a node has already been picked
     * ([startFrame]); everything else opens the result viewer.
     */
    fun toIntent(context: Context): Intent {
        val target = if (sweep != null && startFrame == null) {
            VsgLatticeActivity::class.java
        } else {
            ResultViewerActivity::class.java
        }
        return Intent(context, target).apply {
            putExtra(DicKeys.IMG_W, imgW)
            putExtra(DicKeys.IMG_H, imgH)
            putExtra(DicKeys.STEP, step)
            putExtra(DicKeys.REF_NAME, refName)
            putExtra(DicKeys.REF_PATH, refPath)
            defPath?.let { putExtra(DicKeys.DEF_PATH, it) }
            putExtra(DicKeys.BATCH_DIR_PATH, batchDirPath)
            putStringArrayListExtra(DicKeys.DEF_FILE_NAMES, ArrayList(frameNames))
            if (defFilePaths.isNotEmpty()) {
                putStringArrayListExtra(DicKeys.DEF_FILE_PATHS, ArrayList(defFilePaths))
            }
            if (sweep != null) {
                putExtra(DicKeys.SWEEP_SUBSETS, sweep.subsets.toIntArray())
                putExtra(DicKeys.SWEEP_STEPS, sweep.steps.toIntArray())
                putExtra(DicKeys.SWEEP_STRAIN_WINS, sweep.strainWindows.toIntArray())
                putExtra(DicKeys.LINE_CUT_HORIZONTAL, sweep.lineCutHorizontal)
                putExtra(DicKeys.SWEEP_SKIPPED, sweep.skippedJson)
            }
            putExtra(DicKeys.STOP_CODE, stopCode)
            putExtra(DicKeys.PLANNED_FRAMES, plannedFrames)
            putExtra(DicKeys.SESSION_ID, sessionId)
            putExtra(DicKeys.SESSION_LOCAL_ID, sessionLocalId)
            putExtra(DicKeys.SUBSET_SIZE, subsetSize)
            putExtra(DicKeys.STRAIN_WINDOW, strainWindow)
            putExtra(DicKeys.STRAIN_METHOD, strainMethod)
            putExtra(DicKeys.ENGINE_STATS, engineStats?.toFloatArray())
            putExtra(DicKeys.ROI_X, roiX)
            putExtra(DicKeys.ROI_Y, roiY)
            putExtra(DicKeys.ROI_W, roiW)
            putExtra(DicKeys.ROI_H, roiH)
            startFrame?.let { putExtra(DicKeys.START_FRAME, it) }
        }
    }

    /** [engineStats] in the array form `EngineStats.fromArray` reads, or null. */
    fun engineStatsArray(): FloatArray? = engineStats?.toFloatArray()

    companion object {
        /** Step when neither the Intent nor a record says; the wizard's default. */
        const val DEFAULT_STEP = 5

        /** Subset when neither the Intent nor a record says; the engine's default. */
        const val DEFAULT_SUBSET = 41

        /** Strain window when neither the Intent nor a record says; the wizard's default. */
        const val DEFAULT_STRAIN_WINDOW = 15

        /** The only strain method the engine has. */
        const val STRAIN_METHOD_VSG = "VSG"

        /**
         * A viewer over a bare directory of `.dat` frames with no session behind
         * it, the whole image as ROI: the debug and benchmark seed activities
         * and the Robolectric viewer tests.
         */
        @Suppress("LongParameterList") // each is a real knob of a seeded viewer
        fun ofFrames(
            batchDir: String,
            imgW: Int,
            imgH: Int,
            step: Int,
            frameNames: List<String> = emptyList(),
            startFrame: Int? = null,
        ) = ViewerArgs(
            imgW = imgW,
            imgH = imgH,
            step = step,
            refName = "",
            refPath = "",
            batchDirPath = batchDir,
            frameNames = frameNames,
            stopCode = 0,
            plannedFrames = frameNames.size,
            sessionId = null,
            sessionLocalId = null,
            subsetSize = DEFAULT_SUBSET,
            strainWindow = DEFAULT_STRAIN_WINDOW,
            engineStats = null,
            roiX = 0,
            roiY = 0,
            roiW = imgW,
            roiH = imgH,
            startFrame = startFrame,
        )

        /**
         * Parses a viewer Intent from any build.
         *
         * A missing key is filled from [record], else from its one default
         * (image size for the ROI's width and height, [DEFAULT_STEP],
         * [DEFAULT_SUBSET], [DEFAULT_STRAIN_WINDOW], zero or empty for the
         * rest), and every filled key is logged. A key that is present wins
         * even when its value is null: the writer meant "none". [record] runs
         * only when a key is absent, which no current writer allows, so an
         * ordinary open costs no disk read. Sweep keys never fall back: their absence is what makes
         * an Intent a single run. The legacy `SWEEP_SKIP_*` arrays of older
         * builds fold into [ViewerSweepArgs.skippedJson].
         */
        fun from(intent: Intent, record: () -> SessionRecord? = { null }): ViewerArgs =
            Reader(intent, record).read()
    }

    /** One parse; collects which keys it had to fill, for the log line. */
    private class Reader(private val intent: Intent, record: () -> SessionRecord?) {
        private val record by lazy(record)
        private val fromRecord = mutableListOf<String>()
        private val defaulted = mutableListOf<String>()

        /**
         * The Intent's value when the key is there — a writer that put null
         * meant "none" — else the record's, else [default].
         */
        private fun <T> fill(key: String, ofRecord: (SessionRecord) -> T?, default: T, read: (Intent) -> T?): T {
            if (intent.hasExtra(key)) return read(intent) ?: default
            val filled = record?.let(ofRecord)
            (if (filled != null) fromRecord else defaulted).add(key)
            return filled ?: default
        }

        private fun int(key: String, ofRecord: (SessionRecord) -> Int?, default: Int): Int =
            fill(key, ofRecord, default) { it.getIntExtra(key, default) }

        private fun string(key: String, ofRecord: (SessionRecord) -> String?, default: String?): String? =
            fill(key, ofRecord, default) { it.getStringExtra(key) }

        @Suppress("LongMethod") // one line per key, in wire order
        fun read(): ViewerArgs {
            val imgW = int(DicKeys.IMG_W, { it.imgW }, 0)
            val imgH = int(DicKeys.IMG_H, { it.imgH }, 0)
            val sweep = readSweep()
            val args = ViewerArgs(
                imgW = imgW,
                imgH = imgH,
                step = int(DicKeys.STEP, { it.step }, DEFAULT_STEP),
                refName = string(DicKeys.REF_NAME, { it.refName }, "").orEmpty(),
                refPath = string(DicKeys.REF_PATH, { it.refPath }, "").orEmpty(),
                batchDirPath = string(DicKeys.BATCH_DIR_PATH, { it.sessionDir }, null),
                frameNames = fill(
                    DicKeys.DEF_FILE_NAMES,
                    { if (it.isSweep) it.sweepLabels else it.defNames },
                    emptyList(),
                ) { it.getStringArrayListExtra(DicKeys.DEF_FILE_NAMES) },
                stopCode = int(DicKeys.STOP_CODE, { it.stopCode }, 0),
                plannedFrames = int(DicKeys.PLANNED_FRAMES, { it.plannedFrameCount }, 0),
                sessionId = string(DicKeys.SESSION_ID, { it.id }, null),
                sessionLocalId = intent.getStringExtra(DicKeys.SESSION_LOCAL_ID),
                subsetSize = int(DicKeys.SUBSET_SIZE, { it.subset }, DEFAULT_SUBSET),
                strainWindow = int(DicKeys.STRAIN_WINDOW, { it.strainWindow }, DEFAULT_STRAIN_WINDOW),
                engineStats = fill(
                    DicKeys.ENGINE_STATS,
                    { it.engineStats.ifEmpty { null } },
                    null,
                ) { it.getFloatArrayExtra(DicKeys.ENGINE_STATS)?.toList() },
                roiX = int(DicKeys.ROI_X, { it.roiX }, 0),
                roiY = int(DicKeys.ROI_Y, { it.roiY }, 0),
                roiW = int(DicKeys.ROI_W, { it.roiW }, imgW),
                roiH = int(DicKeys.ROI_H, { it.roiH }, imgH),
                sweep = sweep,
                defPath = intent.getStringExtra(DicKeys.DEF_PATH),
                defFilePaths = intent.getStringArrayListExtra(DicKeys.DEF_FILE_PATHS).orEmpty(),
                startFrame = intent.takeIf { it.hasExtra(DicKeys.START_FRAME) }?.getIntExtra(DicKeys.START_FRAME, 0),
                strainMethod = intent.getStringExtra(DicKeys.STRAIN_METHOD) ?: STRAIN_METHOD_VSG,
            )
            if (fromRecord.isNotEmpty() || defaulted.isNotEmpty()) {
                Timber.w("Viewer Intent missing keys; from record: %s; defaulted: %s", fromRecord, defaulted)
            }
            return args
        }

        private fun readSweep(): ViewerSweepArgs? {
            val subsets = intent.getIntArrayExtra(DicKeys.SWEEP_SUBSETS) ?: return null
            val skipped = SkippedNode.decodeFromExtras(
                intent.getStringExtra(DicKeys.SWEEP_SKIPPED),
                intent.getIntArrayExtra(DicKeys.SWEEP_SKIP_SUBSETS),
                intent.getIntArrayExtra(DicKeys.SWEEP_SKIP_STEPS),
                intent.getIntArrayExtra(DicKeys.SWEEP_SKIP_STRAIN_WINS),
                intent.getIntArrayExtra(DicKeys.SWEEP_SKIP_CODES),
            )
            return ViewerSweepArgs(
                subsets = subsets.toList(),
                steps = intent.getIntArrayExtra(DicKeys.SWEEP_STEPS)?.toList().orEmpty(),
                strainWindows = intent.getIntArrayExtra(DicKeys.SWEEP_STRAIN_WINS)?.toList().orEmpty(),
                lineCutHorizontal = fill(DicKeys.LINE_CUT_HORIZONTAL, { it.lineCutHorizontal }, true) {
                    it.getBooleanExtra(DicKeys.LINE_CUT_HORIZONTAL, true)
                },
                skippedJson = SkippedNode.encodeJson(skipped),
            )
        }
    }
}
