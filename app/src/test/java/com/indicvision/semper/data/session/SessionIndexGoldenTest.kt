package com.indicvision.semper.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.fixtures.CleanAppState
import com.indicvision.semper.fixtures.sessionRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * `sessions/index.json` byte for byte: what an insert, a re-run, two field
 * updates and a forget leave on disk. Phones keep this file across updates, so
 * a change to how the index is encoded, ordered or rewritten shows here first.
 *
 * The expected file was captured from wave 4's index code (the wave-4 review
 * compared that code's output with wave 3's, 74980e5b, and found it identical).
 * Regenerate it only for a deliberate format change.
 */
@RunWith(RobolectricTestRunner::class)
class SessionIndexGoldenTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val batch = sessionRecord(
        id = "batch-1",
        name = "Tensile A · Sep 30, 14:02:11",
        createdAt = 1_700_000_000_000L,
        frameCount = 3,
        imgW = 4000,
        imgH = 3000,
        roiX = 10,
        roiY = 20,
        roiW = 300,
        roiH = 400,
        refPath = "sessions/batch-1/reference.png",
        refName = "ref.tif",
        sessionDir = "sessions/batch-1",
        defNames = listOf("def_001.tif", "def_002.tif", "def_003.tif"),
    ).copy(
        engineStats = listOf(1200f, 1180f, 20f, 2.3f, 97.5f),
        headline = "97.5% converged on frame 1",
        use6x6 = true,
        pointsConverged = 1180,
        avgIterations = 2.3f,
        executionTimeMs = 812,
        stopCode = -7,
        plannedFrameCount = 5,
    )

    private val sweep = sessionRecord(
        id = "sweep-1",
        name = "Sweep \"B\" — ü",
        createdAt = 1_700_000_100_000L,
        frameCount = 2,
        sessionDir = "sessions/sweep-1",
        defNames = listOf("d.png", "d.png"),
    ).copy(
        sweepSubsets = listOf(21, 31),
        sweepSteps = listOf(5, 7),
        sweepStrainWindows = listOf(41, 85),
        sweepLabels = listOf("S21·s5", "S31·s7"),
        lineCutHorizontal = false,
        sweepSkippedNodes = listOf(SkippedNode(41, 9, 121, -12), SkippedNode(51, 9, 121, -3)),
    )

    /** A sweep row saved before FI-3: the legacy skip lists, no codes. */
    private val legacySweep = sessionRecord(
        id = "legacy-1",
        createdAt = 1_700_000_200_000L,
        sessionDir = "sessions/legacy-1",
    ).copy(
        sweepSubsets = listOf(21),
        sweepSkipSubsets = listOf(41),
        sweepSkipSteps = listOf(9),
        sweepSkipStrainWindows = listOf(121),
    )

    private val doomed = sessionRecord(id = "gone-1", createdAt = 1_700_000_300_000L, sessionDir = "sessions/gone-1")

    @Test
    fun `the index is written byte for byte as before`() {
        listOf(batch, sweep, legacySweep, doomed).forEach { SessionStore.upsert(context, it, allowOverLimit = true) }
        SessionStore.upsert(context, batch.copy(frameCount = 4), allowOverLimit = true)
        SessionStore.setCloudSessionId(context, "sweep-1", "cloud-9")
        SessionStore.setSyncState(context, "sweep-1", SessionRecord.SyncState.SYNCED)
        SessionStore.forget(context, "gone-1")

        val actual = File(context.filesDir, "sessions/index.json").readText(Charsets.UTF_8)

        assertEquals(expected(), actual)
    }

    private fun expected(): String = checkNotNull(javaClass.classLoader)
        .getResource("session/index_golden.json")
        .readText(Charsets.UTF_8)
        .trimEnd('\n')
}
