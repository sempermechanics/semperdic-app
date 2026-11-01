package com.sempermechanics.semper.ui.analysis

import android.content.Context
import android.content.Intent
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.ui.analysis.run.DicFieldIo
import com.sempermechanics.semper.ui.viewer.ViewerArgs
import com.sempermechanics.semper.ui.viewer.ViewerSweepArgs
import java.io.File

/**
 * A three-combination sweep for the lattice screen: its Intent, and optionally
 * one `.dat` per combination under [batchDir] so the line-cut plot has curves.
 */
internal class VsgLatticeFixture(private val batchDir: File) {

    val subsets = listOf(31, 41, 51)
    val steps = listOf(STEP, STEP, STEP)
    val windows = listOf(21, 21, 21)

    fun intent(context: Context): Intent = ViewerArgs(
        imgW = ROI,
        imgH = ROI,
        step = STEP,
        refName = "ref.png",
        refPath = File(batchDir, "ref.png").path,
        batchDirPath = batchDir.path,
        frameNames = subsets.map { "$it/$STEP" },
        stopCode = 0,
        plannedFrames = subsets.size,
        sessionId = null,
        sessionLocalId = null,
        subsetSize = subsets.first(),
        strainWindow = windows.first(),
        engineStats = null,
        roiX = 0,
        roiY = 0,
        roiW = ROI,
        roiH = ROI,
        sweep = ViewerSweepArgs(subsets, steps, windows, lineCutHorizontal = true, skippedJson = "[]"),
    ).toIntent(context)

    /** One solved grid per combination, strain growing with the frame so each curve differs. */
    fun writeFrames() {
        batchDir.mkdirs()
        val side = ROI / STEP + 1
        subsets.indices.forEach { frame ->
            val buffer = DicFieldIo.allocateDirect(side.toLong() * side)
            val floats = buffer.asFloatBuffer()
            for (row in 0 until side) {
                for (col in 0 until side) {
                    val point = FloatArray(DicResult.STRIDE)
                    point[DicResult.IDX_X] = (col * STEP).toFloat()
                    point[DicResult.IDX_Y] = (row * STEP).toFloat()
                    point[DicResult.IDX_EXX] = 1e-4f * (frame + 1) * col
                    point[DicResult.IDX_EYY] = 2e-4f * (frame + 1)
                    point[DicResult.IDX_EXY] = 5e-5f
                    point[DicResult.IDX_ZNSSD] = 0.01f
                    floats.put(point)
                }
            }
            DicFieldIo.write(buffer, side * side, File(batchDir, "frame_%04d.dat".format(frame)))
        }
    }

    companion object {
        const val STEP = 5
        const val ROI = 100
    }
}
