package com.indicvision.semper.ui.analysis

import android.graphics.Rect
import com.indicvision.semper.DicResult
import com.indicvision.semper.ProgressCallback
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.report.EngineStats
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Correlates a burst of static test frames against the first one, turning each
 * pair into the [NoiseFloorStats.PairSample] the gate reasons about.
 *
 * Nothing is loaded while these frames are taken, so every displacement here is
 * error — this is the measurement that says whether the setup can see the strain
 * about to be applied, before a specimen is spent finding out.
 *
 * It is deliberately coarse. The gate needs a *scatter* over the ROI, not a
 * field: a lattice of a few hundred points estimates σ as precisely as tens of
 * thousands would, and the test shot has to still feel like a test shot. The
 * step is widened until the grid fits [TARGET_GRID_POINTS], never narrowed.
 *
 * Every failure path returns fewer samples rather than throwing. A burst that
 * could not be correlated is the *existing* speckle failure — the caller
 * already has a dialog for it — and must never be a new crash.
 */
object NoiseFloorProbe {

    /**
     * Measure [frameFiles] against [refFile] over [roi].
     *
     * Must run on [SemperNativeLib.nativeDispatcher]: it calls the engine, which
     * is pinned to one thread.
     *
     * @param roi in the coordinates of the burst frames, not of the test shot.
     * @return one sample per frame that correlated, in capture order, and the
     *   per-cell scatter over all of them. Frames that failed are dropped, so
     *   the sample list can be shorter than [frameFiles].
     */
    @Suppress("ReturnCount")
    fun measure(
        refFile: File,
        frameFiles: List<File>,
        roi: Rect,
        subset: Int,
    ): Measurement {
        if (frameFiles.isEmpty()) return Measurement.EMPTY
        val refBytes = runCatching { refFile.readBytes() }.getOrNull() ?: return Measurement.EMPTY
        val bounds = NoiseFloorPixels.boundsOf(refBytes) ?: return Measurement.EMPTY
        val (imgW, imgH) = bounds
        val region = Rect(roi)
        if (!region.intersect(Rect(0, 0, imgW, imgH))) return Measurement.EMPTY
        if (region.width() <= subset || region.height() <= subset) return Measurement.EMPTY

        val step = probeStepFor(region, subset)
        val strainWindow = strainWindowFor(step)
        val buffer = allocateFor(region, step)
        val refWindow = NoiseFloorPixels.grayWindow(refBytes, region)

        runCatching { SemperNativeLib.initializeReference(refBytes, ByteArray(0), imgW, imgH) }
            .onFailure {
                Timber.w(it, "noise probe: reference init failed")
                return Measurement.EMPTY
            }

        val scatter = SigmaAccumulator()
        val samples = frameFiles.mapNotNull { frame ->
            sampleOf(refBytes, frame, region, subset, step, strainWindow, buffer, refWindow, scatter)
        }
        return Measurement(samples, scatter.field(imgW, imgH, step))
    }

    /** What one burst produced: the per-frame samples, and the map of where. */
    data class Measurement(
        val samples: List<NoiseFloorStats.PairSample>,
        val sigmaField: SigmaField?,
    ) {
        internal companion object {
            val EMPTY = Measurement(emptyList(), null)
        }
    }

    /**
     * Per-point displacement scatter over the burst, laid out exactly as
     * [DicResult] lays out a solved field: grid position in [DicResult.IDX_X]
     * and [DicResult.IDX_Y], the scatter in the [DicResult.IDX_U] slot, and an
     * accepted marker in [DicResult.IDX_ZNSSD].
     *
     * That layout is not a convenience, it is the whole reason this can be
     * drawn at all: `VisualizationEngine.generateHeatmap` already consumes
     * precisely this shape, so the map is rendered by calling it with
     * `valIndex = IDX_U` and nothing inside the visualiser changes.
     *
     * A single aggregate sigma cannot show *where* a setup is weak. A glare
     * patch, a defocused corner and a thin band of speckle all reduce to one
     * slightly-worse number, and all three have different answers.
     */
    class SigmaField(
        val points: FloatArray,
        val imgW: Int,
        val imgH: Int,
        val step: Int,
        /** Smallest and largest sigma in the field, in pixels, for the legend. */
        val minSigmaPx: Double,
        val maxSigmaPx: Double,
    )

    /**
     * Running per-cell moments of u and v across the burst.
     *
     * **Keyed on the grid coordinates the engine reports, not on array
     * position.** The engine drops points whose correlation or strain failed,
     * so two frames of one burst come back as arrays of different lengths
     * holding different points; accumulating by index would quietly average one
     * part of the ROI against another and draw a map of nothing. That is the
     * one thing here worth a test of its own, so this is internal rather than
     * private.
     */
    internal class SigmaAccumulator {

        private class Cell(val x: Float, val y: Float) {
            var n = 0
            var sumU = 0.0
            var sumV = 0.0
            var sumUU = 0.0
            var sumVV = 0.0
        }

        private val cells = HashMap<Long, Cell>()

        fun add(points: FloatArray) {
            var i = 0
            while (i < points.size) {
                if (DicResult.isAcceptedPoint(points[i + DicResult.IDX_ZNSSD])) {
                    val x = points[i + DicResult.IDX_X]
                    val y = points[i + DicResult.IDX_Y]
                    val cell = cells.getOrPut(keyOf(x, y)) { Cell(x, y) }
                    val u = points[i + DicResult.IDX_U].toDouble()
                    val v = points[i + DicResult.IDX_V].toDouble()
                    cell.n++
                    cell.sumU += u
                    cell.sumV += v
                    cell.sumUU += u * u
                    cell.sumVV += v * v
                }
                i += DicResult.STRIDE
            }
        }

        /**
         * The field, or null when too little of the ROI was seen often enough
         * to draw.
         *
         * Cells below [MIN_CELL_FRAMES] contributing frames are left out: two
         * samples give a scatter that is one difference, which as a colour on a
         * map would read as a finding rather than as an artefact of having
         * looked only twice.
         */
        fun field(imgW: Int, imgH: Int, step: Int): SigmaField? {
            val usable = cells.values.filter { it.n >= MIN_CELL_FRAMES }
            if (usable.size < MIN_FIELD_CELLS) {
                Timber.i("noise probe: %d cells seen %d+ times; no sigma map", usable.size, MIN_CELL_FRAMES)
                return null
            }
            val points = FloatArray(usable.size * DicResult.STRIDE)
            var low = Double.MAX_VALUE
            var high = 0.0
            usable.forEachIndexed { index, cell ->
                val meanU = cell.sumU / cell.n
                val meanV = cell.sumV / cell.n
                val varU = max(0.0, cell.sumUU / cell.n - meanU * meanU)
                val varV = max(0.0, cell.sumVV / cell.n - meanV * meanV)
                val sigma = sqrt(varU + varV)
                val at = index * DicResult.STRIDE
                points[at + DicResult.IDX_X] = cell.x
                points[at + DicResult.IDX_Y] = cell.y
                points[at + DicResult.IDX_U] = sigma.toFloat()
                points[at + DicResult.IDX_ZNSSD] = ACCEPTED_MARKER
                if (sigma < low) low = sigma
                if (sigma > high) high = sigma
            }
            return SigmaField(points, imgW, imgH, step, low, high)
        }

        /** The two grid coordinates packed into one key; both are lattice integers. */
        private fun keyOf(x: Float, y: Float): Long =
            (x.toInt().toLong() shl Int.SIZE_BITS) or (y.toInt().toLong() and UNSIGNED_INT_MASK)
    }

    /**
     * Grid step for the probe: the ROI's own long edge divided into
     * [TARGET_GRID_POINTS], floored at the subset's half-width.
     *
     * Points closer together than that overlap almost entirely, so they are not
     * independent estimates — they cost solve time and add nothing to σ.
     */
    internal fun probeStepFor(region: Rect, subset: Int): Int {
        val longEdge = max(region.width(), region.height())
        val forGrid = longEdge / TARGET_GRID_POINTS
        return maxOf(forGrid, subset / 2, MIN_STEP)
    }

    /**
     * Strain window to ask the engine for, in **pixels**, sized to this probe's
     * own grid rather than to the analysis the run will later do.
     *
     * The probe wants displacement and nothing else — the floor is derived from
     * σ analytically, and the engine's strain is never read. But it cannot have
     * one without the other: a point whose strain could not be computed is
     * dropped from the output entirely, and its perfectly good u,v go with it.
     *
     * The window is a diameter in pixels, walked at the grid's own spacing. On
     * this deliberately coarse lattice the analysis default of 15 px therefore
     * lands inside a single cell: the centre point is alone in its own window,
     * fails the engine's three-point minimum, and every point in the ROI is
     * discarded. A whole burst then measures as zero — which is exactly what
     * both test devices reported.
     *
     * `2 * step + 1` is the smallest window that reaches the four orthogonal
     * neighbours at `step` and stops short of the diagonals at `step√2`. Five
     * points, one clear of the minimum, and the most permissive fill the
     * engine's 90% support rule allows. The grid's outer ring has no neighbours
     * and drops; the interior is what σ is measured on.
     */
    internal fun strainWindowFor(step: Int): Int = 2 * step + 1

    @Suppress("LongParameterList", "ReturnCount")
    private fun sampleOf(
        refBytes: ByteArray,
        frame: File,
        region: Rect,
        subset: Int,
        step: Int,
        strainWindow: Int,
        buffer: ByteBuffer,
        refWindow: FloatArray?,
        scatter: SigmaAccumulator,
    ): NoiseFloorStats.PairSample? {
        val defBytes = runCatching { frame.readBytes() }.getOrNull() ?: return null
        val solved = solve(refBytes, defBytes, region, subset, step, strainWindow, buffer)
        if (solved <= 0 || DicFieldIo.wouldOverrun(solved, buffer)) {
            Timber.w("noise probe: %s solved %d points", frame.name, solved)
            return null
        }
        val points = FloatArray(solved * DicResult.STRIDE)
        buffer.position(0)
        buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().get(points)

        val stats = displacementStats(points) ?: return null
        // After the aggregate and only for frames that produced one, so the map
        // and the verdict are drawn from exactly the same evidence.
        scatter.add(points)
        val defWindow = NoiseFloorPixels.grayWindow(defBytes, region)
        return NoiseFloorStats.PairSample(
            sigmaU = stats.sigmaU,
            sigmaV = stats.sigmaV,
            meanU = stats.meanU,
            meanV = stats.meanV,
            noiseVariance = NoiseFloorPixels.noiseVarianceOf(refWindow, defWindow),
            noiseCorrelation = NoiseFloorPixels.noiseCorrelationOf(refWindow, defWindow),
            meanIntensity = defWindow?.average() ?: Double.NaN,
        )
    }

    @Suppress("LongParameterList")
    private fun solve(
        refBytes: ByteArray,
        defBytes: ByteArray,
        region: Rect,
        subset: Int,
        step: Int,
        strainWindow: Int,
        buffer: ByteBuffer,
    ): Int {
        buffer.clear()
        val silent = object : ProgressCallback {
            override fun onProgressUpdate(percentage: Int) = Unit
        }
        val metrics = FloatArray(EngineStats.SLOT_COUNT)
        return runCatching {
            SemperNativeLib.computeFullFieldDirect(
                refBytes, defBytes, ByteArray(0),
                region.left, region.top, region.width(), region.height(),
                step, subset, strainWindow,
                false,
                buffer, silent, metrics,
            )
        }.getOrElse {
            Timber.w(it, "noise probe: solve threw")
            -1
        }
    }

    private class Displacement(
        val sigmaU: Double,
        val sigmaV: Double,
        val meanU: Double,
        val meanV: Double,
    )

    /**
     * Mean and standard deviation of u and v over the accepted points.
     *
     * The mean is kept rather than discarded: it is the rigid-body part, which
     * has a different answer from the scatter — a whole-frame shift means the
     * rig or the stabiliser moved, while scatter means the image is noisy.
     */
    private fun displacementStats(points: FloatArray): Displacement? {
        var n = 0
        var sumU = 0.0
        var sumV = 0.0
        var sumUU = 0.0
        var sumVV = 0.0
        var i = 0
        while (i < points.size) {
            if (DicResult.isAcceptedPoint(points[i + DicResult.IDX_ZNSSD])) {
                val u = points[i + DicResult.IDX_U].toDouble()
                val v = points[i + DicResult.IDX_V].toDouble()
                sumU += u
                sumV += v
                sumUU += u * u
                sumVV += v * v
                n++
            }
            i += DicResult.STRIDE
        }
        // Too few points is not a floor of zero — it is no measurement at all,
        // and reporting it as a pass would be the worst possible failure here.
        if (n < MIN_ACCEPTED_POINTS) {
            // Logged rather than dropped quietly: on a device this is the
            // difference between "the setup is bad" and "the probe measured
            // nothing", and the two have opposite answers.
            Timber.w("noise probe: %d accepted points of %d", n, points.size / DicResult.STRIDE)
            return null
        }
        val meanU = sumU / n
        val meanV = sumV / n
        return Displacement(
            sigmaU = sqrt(max(0.0, sumUU / n - meanU * meanU)),
            sigmaV = sqrt(max(0.0, sumVV / n - meanV * meanV)),
            meanU = meanU,
            meanV = meanV,
        )
    }

    private fun allocateFor(region: Rect, step: Int): ByteBuffer {
        // One extra row and column of slack: the engine's grid rounding is its
        // own, and reading past the buffer would crash a test shot.
        val gridW = region.width() / step + 1
        val gridH = region.height() / step + 1
        return DicFieldIo.allocateDirect(gridW * gridH)
    }

    /** Enough points that σ is well determined; far fewer than a real field. */
    private const val TARGET_GRID_POINTS = 24
    private const val MIN_STEP = 4
    private const val MIN_ACCEPTED_POINTS = 20

    /**
     * Frames a cell must appear in before its scatter is drawn. Two frames give
     * one difference, which is not a scatter.
     */
    private const val MIN_CELL_FRAMES = 3

    /** Below this the map is a scatter of dots, which misleads more than it shows. */
    private const val MIN_FIELD_CELLS = 12

    /**
     * The ZNSSD the synthetic field carries, so every cell reads as accepted.
     *
     * Zero, not one: [DicResult.isAcceptedPoint] tests `corr <= MAX_ZNSSD` and
     * MAX_ZNSSD is 0.15, so 1f would mark every point of the map rejected and
     * the heat map would come back empty. Zero is a perfect correlation, which
     * is honest here — these cells did correlate; the number being drawn is
     * their scatter, not their correlation.
     */
    private const val ACCEPTED_MARKER = 0f

    private const val UNSIGNED_INT_MASK = 0xFFFFFFFFL
}
