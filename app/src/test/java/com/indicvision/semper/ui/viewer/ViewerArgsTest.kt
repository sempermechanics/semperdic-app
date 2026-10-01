package com.indicvision.semper.ui.viewer

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SkippedNode
import com.indicvision.semper.fixtures.sessionRecord
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.ui.analysis.VsgLatticeActivity
import com.indicvision.semper.ui.home.SessionOpenHelper
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
}
