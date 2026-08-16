@file:Suppress("MagicNumber")

package com.indicvision.semper.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.indicvision.semper.DicKeys
import com.indicvision.semper.DicResult
import com.indicvision.semper.data.SessionPaths
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Debug-only launcher that fabricates a short synthetic DIC session and opens
 * [com.indicvision.semper.ui.viewer.ResultViewerActivity]. Used for emulator
 * screenshots; never compiled into release.
 */
class DebugViewerSeedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frameCount = intent.getIntExtra(EXTRA_FRAME_COUNT, DEFAULT_FRAMES)
        val skipSummary = intent.getBooleanExtra(EXTRA_SKIP_SUMMARY, false)
        val dir = seedSession(frameCount)

        val names = ArrayList<String>(frameCount)
        for (i in 0 until frameCount) names.add("Frame_${i + 1}")

        val viewer = Intent().apply {
            setClassName(this@DebugViewerSeedActivity, VIEWER)
            putExtra(DicKeys.BATCH_DIR_PATH, dir.absolutePath)
            putExtra(DicKeys.IMG_W, IMG_W)
            putExtra(DicKeys.IMG_H, IMG_H)
            putExtra(DicKeys.STEP, STEP)
            putExtra(DicKeys.ROI_X, 0)
            putExtra(DicKeys.ROI_Y, 0)
            putExtra(DicKeys.ROI_W, IMG_W)
            putExtra(DicKeys.ROI_H, IMG_H)
            putStringArrayListExtra(DicKeys.DEF_FILE_NAMES, names)
            if (skipSummary) putExtra(DicKeys.START_FRAME, 0)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(viewer)
        finish()
    }

    private fun seedSession(frameCount: Int): File {
        val dir = File(File(filesDir, "sessions"), "debug_seed_$frameCount")
        dir.mkdirs()
        val expected = SessionPaths.frameDat(dir, frameCount - 1)
        if (expected.exists()) return dir
        val floats = FloatArray(COLS * ROWS * DicResult.STRIDE)
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frameCount) {
            fillFrame(floats, seed = i)
            buf.clear()
            buf.asFloatBuffer().put(floats)
            SessionPaths.frameDat(dir, i).writeBytes(buf.array())
        }
        return dir
    }

    private fun fillFrame(floats: FloatArray, seed: Int) {
        var p = 0
        var k = 0
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                floats[p + DicResult.IDX_X] = (c * STEP).toFloat()
                floats[p + DicResult.IDX_Y] = (r * STEP).toFloat()
                floats[p + DicResult.IDX_U] = (k - COLS * ROWS / 2) * 0.031f + seed
                floats[p + DicResult.IDX_V] = (COLS * ROWS / 2 - k) * 0.017f
                floats[p + DicResult.IDX_EXX] = (k % 9 - 4) * 0.00042f + seed * 0.00005f
                floats[p + DicResult.IDX_EYY] = (k % 6 - 3) * 0.00071f
                floats[p + DicResult.IDX_EXY] = (k % 4 - 2) * 0.00023f
                floats[p + DicResult.IDX_ZNSSD] = 0.01f
                p += DicResult.STRIDE
                k++
            }
        }
    }

    private companion object {
        const val EXTRA_FRAME_COUNT = "frameCount"
        const val EXTRA_SKIP_SUMMARY = "skipSummary"
        const val DEFAULT_FRAMES = 8
        const val COLS = 80
        const val ROWS = 60
        const val STEP = 4
        const val IMG_W = COLS * STEP
        const val IMG_H = ROWS * STEP
        const val VIEWER = "com.indicvision.semper.ui.viewer.ResultViewerActivity"
    }
}
