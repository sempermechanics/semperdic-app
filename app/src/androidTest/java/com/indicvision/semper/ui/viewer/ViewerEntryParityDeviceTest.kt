package com.indicvision.semper.ui.viewer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indicvision.semper.R
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.ui.analysis.VsgLatticeActivity
import com.indicvision.semper.ui.analysis.run.RunSpec
import com.indicvision.semper.ui.analysis.wizard.AnalysisNavHelper
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.home.SessionOpenHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One session, opened the two ways a user reaches it (TD-61, ADR-003/004):
 * straight from the run that made it, and later from Home. The viewer must
 * parse the same arguments and the ⓘ sheet must show the same rows. The
 * lattice → viewer hop must carry the sweep's arguments to the node picked.
 */
@RunWith(AndroidJUnit4::class)
class ViewerEntryParityDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var dir: File
    private lateinit var record: SessionRecord

    @Before
    fun seed() {
        dir = SessionStore.dirFor(context, ID)
        val ref = File(dir, "reference.png")
        Bitmap.createBitmap(IMG_W, IMG_H, Bitmap.Config.ARGB_8888).apply {
            ref.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val floats = FloatArray(COLS * ROWS * DicResult.STRIDE)
        for (p in floats.indices step DicResult.STRIDE) {
            floats[p + DicResult.IDX_X] = (ROI_X + p / DicResult.STRIDE % COLS * STEP).toFloat()
            floats[p + DicResult.IDX_Y] = (ROI_Y + p / DicResult.STRIDE / COLS * STEP).toFloat()
            floats[p + DicResult.IDX_ZNSSD] = 0.01f
        }
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(floats)
        repeat(FRAMES) { SessionPaths.frameDat(dir, it).writeBytes(buf.array()) }

        val now = System.currentTimeMillis()
        record = SessionRecord(
            id = ID,
            name = "Entry parity",
            createdAt = now,
            updatedAt = now,
            frameCount = FRAMES,
            subset = SUBSET,
            step = STEP,
            strainWindow = STRAIN_WINDOW,
            imgW = IMG_W,
            imgH = IMG_H,
            roiX = ROI_X,
            roiY = ROI_Y,
            roiW = ROI_W,
            roiH = ROI_H,
            refPath = ref.absolutePath,
            refName = ref.name,
            sessionDir = dir.absolutePath,
            defNames = listOf("f1.png", "f2.png"),
            plannedFrameCount = FRAMES,
        )
        assertTrue(SessionStore.upsert(context, record, allowOverLimit = true))
    }

    @After
    fun remove() {
        SessionStore.delete(context, ID)
    }

    /** The view model as the run that saved [record] leaves it. */
    private fun finishedRun(): AnalysisViewModel = AnalysisViewModel().apply {
        realRefWidth = IMG_W
        realRefHeight = IMG_H
        refName = record.refName
        workingLocalId = ID
        // The ROI the user drew; the run solved the inset one.
        roiX = 0
        roiY = 0
        roiW = IMG_W
        roiH = IMG_H
        val spec = RunSpec.of(
            SUBSET,
            STEP,
            STRAIN_WINDOW,
            intArrayOf(ROI_X, ROI_Y, ROI_W, ROI_H),
            mask = null,
            use6x6 = false,
            debugDir = null,
        )
        resetRunResult(dir.absolutePath, spec)
        recordRunSettings(spec.recordSettings())
        lastRefPath = record.refPath
        lastPlannedFrames = FRAMES
    }

    private class Seen(val args: ViewerArgs, val rows: List<Pair<String, String>>, val roi: List<Int>)

    private fun open(intent: Intent): Seen {
        var seen: Seen? = null
        ActivityScenario.launch<ResultViewerActivity>(intent).use { scenario ->
            scenario.onActivity { viewer ->
                seen = Seen(
                    viewer.args,
                    ViewerSettingsSheet.entriesFor(viewer),
                    viewer.roi.let { listOf(it.x, it.y, it.w, it.h) },
                )
            }
        }
        return checkNotNull(seen)
    }

    @Test
    fun theRunAndHomeOpenTheSameViewer() {
        val fromRun = open(
            AnalysisNavHelper.resultArgs(finishedRun(), sweep = false, frameNames = record.defNames)
                .toIntent(context),
        )
        val fromHome = open(SessionOpenHelper.intentFor(context, record))

        // defPath / defFilePaths are the run's own image paths; Home has the
        // persisted originals instead, which the viewer prefers either way.
        assertEquals(fromHome.args, fromRun.args.copy(defPath = null, defFilePaths = emptyList()))
        assertEquals(fromHome.rows, fromRun.rows)
        assertEquals(listOf(ROI_X, ROI_Y, ROI_W, ROI_H), fromRun.roi)
        assertEquals(fromHome.roi, fromRun.roi)
    }

    @Test
    fun aLatticeNodeOpensTheViewerOnThatCombination() {
        val sweep = ViewerSweepArgs(
            subsets = listOf(21, 31),
            steps = listOf(STEP, STEP),
            strainWindows = listOf(15, 21),
            lineCutHorizontal = false,
            skippedJson = "[]",
        )
        val lattice = SessionOpenHelper.argsFor(record).copy(sweep = sweep, frameNames = listOf("a", "b"))
        val monitor = instrumentation.addMonitor(ResultViewerActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch<VsgLatticeActivity>(lattice.toIntent(context)).use { scenario ->
                scenario.onActivity { activity ->
                    // Focus opens on the first solved node (frame 0); step to frame 1.
                    activity.findViewById<View>(R.id.btnNextNode).performClick()
                    activity.findViewById<View>(R.id.btnView).performClick()
                }
                val viewer = instrumentation.waitForMonitorWithTimeout(monitor, TIMEOUT_MS)
                assertNotNull("the View button opened no viewer", viewer)
                val args = (viewer as ResultViewerActivity).args
                assertEquals(lattice.copy(startFrame = 1), args)
                (viewer as Activity).finish()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private companion object {
        const val ID = "test_entry_parity"
        const val IMG_W = 200
        const val IMG_H = 120
        const val ROI_X = 20
        const val ROI_Y = 20
        const val ROI_W = 160
        const val ROI_H = 80
        const val STEP = 4
        const val COLS = ROI_W / STEP
        const val ROWS = ROI_H / STEP
        const val SUBSET = 21
        const val STRAIN_WINDOW = 15
        const val FRAMES = 2
        const val TIMEOUT_MS = 10_000L
    }
}
