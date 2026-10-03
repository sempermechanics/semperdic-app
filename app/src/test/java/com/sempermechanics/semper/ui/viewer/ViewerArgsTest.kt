package com.sempermechanics.semper.ui.viewer

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SkippedNode
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.navigation.DicKeys
import com.sempermechanics.semper.ui.analysis.VsgLatticeActivity
import com.sempermechanics.semper.ui.home.SessionOpenHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The viewer's Intent contract, which two entry points write and two
 * Activities read.
 *
 * The failure this guards against is silent by construction: a key added on
 * the post-run path and missed on the Home path gives a viewer that works
 * after an analysis and quietly renders defaults after a reopen, because a
 * missing extra *is* a default. Asserting the two key sets against each other
 * is the only thing that fails when they drift.
 */
@RunWith(RobolectricTestRunner::class)
class ViewerArgsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun args(sweep: ViewerSweepArgs? = null) = ViewerArgs(
        imgW = 1920,
        imgH = 1080,
        step = 5,
        refName = "ref.png",
        refPath = "/sessions/s1/ref.png",
        batchDirPath = "/sessions/s1",
        frameNames = listOf("f1.png", "f2.png"),
        stopCode = 0,
        plannedFrames = 2,
        sessionId = "cloud-1",
        sessionLocalId = "local-1",
        subsetSize = 41,
        strainWindow = 15,
        engineStats = listOf(1f, 2f),
        roiX = 10,
        roiY = 20,
        roiW = 300,
        roiH = 400,
        sweep = sweep,
    )

    private fun record(sweepSteps: List<Int> = emptyList()) = sessionRecord(
        id = "local-1",
        name = "Session 1",
        createdAt = 0L,
        frameCount = 2,
        imgW = 1920,
        imgH = 1080,
        roiX = 10,
        roiY = 20,
        roiW = 300,
        roiH = 400,
        refPath = "/sessions/s1/ref.png",
        sessionDir = "/sessions/s1",
        defNames = listOf("f1.png", "f2.png"),
    ).copy(
        engineStats = listOf(1f, 2f),
        sweepSubsets = if (sweepSteps.isEmpty()) emptyList() else listOf(41, 51),
        sweepSteps = sweepSteps,
        sweepStrainWindows = if (sweepSteps.isEmpty()) emptyList() else listOf(15, 21),
        sweepLabels = if (sweepSteps.isEmpty()) emptyList() else listOf("41/5", "51/7"),
    )

    @Test
    fun `both entry points write the same extras, bar the two only a fresh run has`() {
        val fromHome = SessionOpenHelper.intentFor(context, record()).extras!!.keySet()
        val fromRun = args()
            .copy(defPath = "/tmp/def.png", defFilePaths = listOf("/tmp/f1.png"))
            .toIntent(context)
            .extras!!
            .keySet()

        // The post-run launch adds the just-analysed run's temp paths. Home has
        // no use for them: its frames are the copies persisted under the
        // session dir, which the viewer prefers over these anyway.
        assertEquals(setOf(DicKeys.DEF_PATH, DicKeys.DEF_FILE_PATHS), fromRun - fromHome)
        assertEquals(emptySet<String>(), fromHome - fromRun)
    }

    @Test
    fun `a sweep opens the lattice and carries its per-frame settings`() {
        val sweep = ViewerSweepArgs(
            subsets = listOf(41, 51),
            steps = listOf(5, 7),
            strainWindows = listOf(15, 21),
            lineCutHorizontal = false,
            skippedJson = "[]",
        )
        val intent = args(sweep).toIntent(context)

        assertEquals(VsgLatticeActivity::class.java.name, intent.component!!.className)
        assertTrue(intArrayOf(41, 51).contentEquals(intent.getIntArrayExtra(DicKeys.SWEEP_SUBSETS)))
        assertTrue(intArrayOf(5, 7).contentEquals(intent.getIntArrayExtra(DicKeys.SWEEP_STEPS)))
        assertTrue(
            intArrayOf(15, 21)
                .contentEquals(intent.getIntArrayExtra(DicKeys.SWEEP_STRAIN_WINS)),
        )
        assertFalse(intent.getBooleanExtra(DicKeys.LINE_CUT_HORIZONTAL, true))
        assertEquals("[]", intent.getStringExtra(DicKeys.SWEEP_SKIPPED))
    }

    @Test
    fun `an ordinary analysis opens the viewer and writes no sweep keys`() {
        val intent = args().toIntent(context)

        assertEquals(ResultViewerActivity::class.java.name, intent.component!!.className)
        assertNull(intent.getIntArrayExtra(DicKeys.SWEEP_SUBSETS))
        assertNull(intent.getStringExtra(DicKeys.SWEEP_SKIPPED))
        // Geometry and identity survive the trip unchanged — the viewer's math
        // and its cloud lookups both read these.
        assertEquals(1920, intent.getIntExtra(DicKeys.IMG_W, 0))
        assertEquals(400, intent.getIntExtra(DicKeys.ROI_H, 0))
        assertEquals("cloud-1", intent.getStringExtra(DicKeys.SESSION_ID))
        assertEquals("local-1", intent.getStringExtra(DicKeys.SESSION_LOCAL_ID))
        assertEquals(listOf("f1.png", "f2.png"), intent.getStringArrayListExtra(DicKeys.DEF_FILE_NAMES))
    }

    @Test
    fun `a sweep session reopened from Home is labelled by combination, not filename`() {
        val intent = SessionOpenHelper.intentFor(context, record(sweepSteps = listOf(5, 7)))

        assertEquals(VsgLatticeActivity::class.java.name, intent.component!!.className)
        assertEquals(listOf("41/5", "51/7"), intent.getStringArrayListExtra(DicKeys.DEF_FILE_NAMES))
    }

    // ------------------------------------------------------ read side (ADR-003)

    private val sweepArgs = ViewerSweepArgs(
        subsets = listOf(41, 51),
        steps = listOf(5, 7),
        strainWindows = listOf(15, 21),
        lineCutHorizontal = false,
        skippedJson = SkippedNode.encodeJson(listOf(SkippedNode(61, 9, 27, -3))),
    )

    private fun noRecord(): SessionRecord? = throw AssertionError("a complete Intent must not read the index")

    @Test
    fun `a single run survives the Intent unchanged`() {
        val sent = args().copy(defPath = "/tmp/def.png", defFilePaths = listOf("/tmp/f1.png"))

        assertEquals(sent, ViewerArgs.from(sent.toIntent(context), ::noRecord))
    }

    @Test
    fun `a sweep and its picked frame survive the Intent unchanged`() {
        val sent = args(sweepArgs).copy(startFrame = 1)

        assertEquals(sent, ViewerArgs.from(sent.toIntent(context), ::noRecord))
    }

    @Test
    fun `Home's Intent reads back as the arguments Home built`() {
        val session = record(sweepSteps = listOf(5, 7))

        assertEquals(
            SessionOpenHelper.argsFor(session),
            ViewerArgs.from(SessionOpenHelper.intentFor(context, session), ::noRecord),
        )
    }

    @Test
    fun `a lattice node opens the viewer on its frame with the sweep's arguments`() {
        val lattice = args(sweepArgs).toIntent(context)

        val hop = ViewerArgs.from(lattice, ::noRecord).copy(startFrame = 1).toIntent(context)

        assertEquals(ResultViewerActivity::class.java.name, hop.component!!.className)
        assertEquals(1, hop.getIntExtra(DicKeys.START_FRAME, -1))
        assertEquals(args(sweepArgs).copy(startFrame = 1), ViewerArgs.from(hop, ::noRecord))
    }

    @Test
    fun `an older build's skip arrays fold into the skipped list`() {
        val legacy = Intent(context, VsgLatticeActivity::class.java)
            .putExtra(DicKeys.SWEEP_SUBSETS, intArrayOf(41))
            .putExtra(DicKeys.SWEEP_STEPS, intArrayOf(5))
            .putExtra(DicKeys.SWEEP_STRAIN_WINS, intArrayOf(15))
            .putExtra(DicKeys.SWEEP_SKIP_SUBSETS, intArrayOf(61))
            .putExtra(DicKeys.SWEEP_SKIP_STEPS, intArrayOf(9))
            .putExtra(DicKeys.SWEEP_SKIP_STRAIN_WINS, intArrayOf(27))
            .putExtra(DicKeys.SWEEP_SKIP_CODES, intArrayOf(-3))

        val sweep = ViewerArgs.from(legacy).sweep!!

        assertEquals(listOf(SkippedNode(61, 9, 27, -3)), SkippedNode.decodeJson(sweep.skippedJson))
    }

    @Test
    fun `a missing key comes from the session record`() {
        val bare = Intent(context, ResultViewerActivity::class.java)
            .putExtra(DicKeys.SESSION_LOCAL_ID, "local-1")

        val read = ViewerArgs.from(bare) { record() }

        assertEquals(10, read.roiX)
        assertEquals(300, read.roiW)
        assertEquals(41, read.subsetSize)
        assertEquals(15, read.strainWindow)
        assertEquals("local-1", read.sessionId)
        assertEquals(listOf("f1.png", "f2.png"), read.frameNames)
        assertEquals(listOf(1f, 2f), read.engineStats)
        assertNull("a record never makes a single run a sweep", read.sweep)
    }

    @Test
    fun `with no record each missing key takes its one default`() {
        val bare = Intent(context, ResultViewerActivity::class.java)
            .putExtra(DicKeys.IMG_W, 800)
            .putExtra(DicKeys.IMG_H, 600)

        val read = ViewerArgs.from(bare)

        assertEquals(ViewerArgs.DEFAULT_STEP, read.step)
        assertEquals(ViewerArgs.DEFAULT_SUBSET, read.subsetSize)
        assertEquals(ViewerArgs.DEFAULT_STRAIN_WINDOW, read.strainWindow)
        // No ROI recorded means the whole image, for every reader alike.
        assertEquals(listOf(0, 0, 800, 600), listOf(read.roiX, read.roiY, read.roiW, read.roiH))
        assertNull(read.startFrame)
    }

    // ------------------------------------------------------ wire format

    /** Every extra on [intent], arrays and lists as lists, so the map compares by value. */
    private fun extrasOf(intent: Intent): Map<String, Any?> {
        val extras = intent.extras!!
        return extras.keySet().associateWith { key ->
            @Suppress("DEPRECATION") // a plain read of whatever was put, for comparison only
            when (val value = extras.get(key)) {
                is IntArray -> value.toList()
                is FloatArray -> value.toList()
                else -> value
            }
        }
    }

    @Test
    fun `the Intent carries the keys and values the base build wrote`() {
        // Written out by hand: an Intent already in a back stack must keep
        // opening, so neither a key's spelling nor its value's type may move.
        val expected = mapOf(
            "IMG_W" to 1920,
            "IMG_H" to 1080,
            "STEP" to 5,
            "REF_NAME" to "ref.png",
            "REF_PATH" to "/sessions/s1/ref.png",
            "DEF_PATH" to "/tmp/def.png",
            "BATCH_DIR_PATH" to "/sessions/s1",
            "DEF_FILE_NAMES" to arrayListOf("f1.png", "f2.png"),
            "DEF_FILE_PATHS" to arrayListOf("/tmp/f1.png"),
            "SWEEP_SUBSETS" to listOf(41, 51),
            "SWEEP_STEPS" to listOf(5, 7),
            "SWEEP_STRAIN_WINS" to listOf(15, 21),
            "LINE_CUT_HORIZONTAL" to false,
            "SWEEP_SKIPPED" to """[{"subset":61,"step":9,"strainWindow":27,"code":-3}]""",
            "STOP_CODE" to 0,
            "PLANNED_FRAMES" to 2,
            "SESSION_ID" to "cloud-1",
            "SESSION_LOCAL_ID" to "local-1",
            "SUBSET_SIZE" to 41,
            "STRAIN_WINDOW" to 15,
            "STRAIN_METHOD" to "VSG",
            "ENGINE_STATS" to listOf(1f, 2f),
            "ROI_X" to 10,
            "ROI_Y" to 20,
            "ROI_W" to 300,
            "ROI_H" to 400,
            "START_FRAME" to 1,
        )
        val sent = args(sweepArgs).copy(defPath = "/tmp/def.png", defFilePaths = listOf("/tmp/f1.png"), startFrame = 1)

        assertEquals(expected, extrasOf(sent.toIntent(context)))
    }

    @Test
    fun `an Intent the base build wrote reads back field by field`() {
        // Built by hand with the base build's keys and value types, not through
        // toIntent: what an Intent already in a back stack carries must still read.
        val sent = Intent(context, ResultViewerActivity::class.java)
            .putExtra("IMG_W", 1920)
            .putExtra("IMG_H", 1080)
            .putExtra("STEP", 7)
            .putExtra("REF_NAME", "ref.png")
            .putExtra("REF_PATH", "/sessions/s1/ref.png")
            .putExtra("DEF_PATH", "/tmp/def.png")
            .putExtra("BATCH_DIR_PATH", "/sessions/s1")
            .putStringArrayListExtra("DEF_FILE_NAMES", arrayListOf("f1.png", "f2.png"))
            .putStringArrayListExtra("DEF_FILE_PATHS", arrayListOf("/tmp/f1.png"))
            .putExtra("SWEEP_SUBSETS", intArrayOf(41, 51))
            .putExtra("SWEEP_STEPS", intArrayOf(5, 7))
            .putExtra("SWEEP_STRAIN_WINS", intArrayOf(15, 21))
            .putExtra("LINE_CUT_HORIZONTAL", false)
            .putExtra("SWEEP_SKIPPED", """[{"subset":61,"step":9,"strainWindow":27,"code":-3}]""")
            .putExtra("STOP_CODE", 3)
            .putExtra("PLANNED_FRAMES", 4)
            .putExtra("SESSION_ID", "cloud-1")
            .putExtra("SESSION_LOCAL_ID", "local-1")
            .putExtra("SUBSET_SIZE", 31)
            .putExtra("STRAIN_WINDOW", 21)
            .putExtra("STRAIN_METHOD", "VSG")
            .putExtra("ENGINE_STATS", floatArrayOf(1f, 2f))
            .putExtra("ROI_X", 10)
            .putExtra("ROI_Y", 20)
            .putExtra("ROI_W", 300)
            .putExtra("ROI_H", 400)
            .putExtra("START_FRAME", 1)

        val read = ViewerArgs.from(sent, ::noRecord)

        assertEquals(ImageSize(1920, 1080), read.imageSize)
        assertEquals(7, read.step)
        assertEquals("ref.png", read.refName)
        assertEquals("/sessions/s1/ref.png", read.refPath)
        assertEquals("/tmp/def.png", read.defPath)
        assertEquals("/sessions/s1", read.batchDirPath)
        assertEquals(listOf("f1.png", "f2.png"), read.frameNames)
        assertEquals(listOf("/tmp/f1.png"), read.defFilePaths)
        assertEquals(listOf(41, 51), read.sweep!!.subsets)
        assertEquals(listOf(5, 7), read.sweep!!.steps)
        assertEquals(listOf(15, 21), read.sweep!!.strainWindows)
        assertFalse(read.sweep!!.lineCutHorizontal)
        assertEquals(listOf(SkippedNode(61, 9, 27, -3)), SkippedNode.decodeJson(read.sweep!!.skippedJson))
        assertEquals(3, read.stopCode)
        assertEquals(4, read.plannedFrames)
        assertEquals("cloud-1", read.sessionId)
        assertEquals("local-1", read.sessionLocalId)
        assertEquals(31, read.subsetSize)
        assertEquals(21, read.strainWindow)
        assertEquals("VSG", read.strainMethod)
        assertEquals(listOf(1f, 2f), read.engineStats)
        assertEquals(Roi(10, 20, 300, 400), read.roi)
        assertEquals(1, read.startFrame)
    }

    @Test
    fun `the typed views read the same fields`() {
        val read = args(sweepArgs)

        assertEquals(ImageSize(1920, 1080), read.imageSize)
        assertEquals(Roi(10, 20, 300, 400), read.roi)
        assertEquals(DicParams(subset = 41, step = 5, strainWindow = 15), read.frameParams.base)
        assertEquals(DicParams(subset = 51, step = 7, strainWindow = 21), read.frameParams.at(1))
        assertEquals("past a sweep's lists, the run's own", read.frameParams.base, read.frameParams.at(2))
        assertEquals(DicParams(41, 5, 15), args().frameParams.at(1))
    }
}
