package com.sempermechanics.semper.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.report.EngineStats
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [SessionRepository.buildSessionRecord]: the grouped inputs land in the row's
 * fields.
 */
@RunWith(RobolectricTestRunner::class)
class SessionRecordBuildTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = SessionRepository()
    private val settings = SessionRecordSettings(41, 5, 21, 1, 2, 300, 400, use6x6 = true)
    private val stats = List(EngineStats.SLOT_COUNT) { 0f }.toMutableList()
        .also { it[EngineStats.SLOT_CONVERGENCE] = 97.5f }

    private val input = RunInput(
        localSessionId = "s1",
        dir = File("sessions/s1"),
        reference = RunReference("/ref.png", "plate.tif", ImageSize(4000, 3000)),
        settings = settings,
    )
    private val outcome = RunOutcome(
        frameCount = 2,
        defNames = listOf("a.tif", "b.tif", "c.tif"),
        metrics = RunMetrics(pointsConverged = 1180, avgIterations = 2.3f, executionTimeMs = 812, engineStats = stats),
        stopCode = -3,
        plannedFrameCount = 3,
    )

    /**
     * Every field the run fills, as literals: [input] and [outcome] carry a
     * different value per field, so a swapped or dropped one shows. The clock
     * and the auto-name derived from it are taken from [built].
     */
    private fun expectedRow(built: SessionRecord, syncState: SessionRecord.SyncState) = SessionRecord(
        id = "s1",
        name = SessionNaming.defaultSessionName("plate.tif", built.createdAt),
        createdAt = built.createdAt,
        updatedAt = built.updatedAt,
        frameCount = 2,
        subset = 41,
        step = 5,
        strainWindow = 21,
        use6x6 = true,
        imgW = 4000,
        imgH = 3000,
        roiX = 1,
        roiY = 2,
        roiW = 300,
        roiH = 400,
        refPath = "/ref.png",
        refName = "plate.tif",
        sessionDir = File("sessions/s1").absolutePath,
        defNames = listOf("a.tif", "b.tif", "c.tif"),
        headline = "97.5% converged on frame 1",
        engineStats = stats,
        stopCode = -3,
        plannedFrameCount = 3,
        strainMethod = "VSG",
        pointsConverged = 1180,
        avgIterations = 2.3f,
        executionTimeMs = 812,
        syncState = syncState,
        renamedByUser = false,
    )

    @Test
    fun `the grouped inputs fill the row`() {
        val record = repository.buildSessionRecord(context, input, outcome, cloudEnabled = true)

        assertEquals(expectedRow(record, SessionRecord.SyncState.PENDING), record)
        assertEquals(record.createdAt, record.updatedAt)
    }

    @Test
    fun `a run without engine stats saves none`() {
        val bare = RunOutcome(
            frameCount = 1,
            defNames = listOf("a.tif"),
            metrics = RunMetrics(
                pointsConverged = 0,
                avgIterations = 0f,
                executionTimeMs = 0,
                engineStats = emptyList(),
            ),
        )
        val record = repository.buildSessionRecord(context, input, bare, cloudEnabled = false)

        assertEquals(emptyList<Float>(), record.engineStats)
        assertEquals(listOf(0, 0), listOf(record.stopCode, record.plannedFrameCount))
        assertEquals("0.0% converged", record.headline)
    }

    @Test
    fun `a re-run keeps the user's own name`() {
        val first = repository.buildSessionRecord(context, input, outcome, cloudEnabled = false)
        SessionStore.upsert(context, first.copy(name = "Mine", renamedByUser = true))

        val rerun = repository.buildSessionRecord(context, input, outcome, cloudEnabled = false)

        assertEquals("Mine", rerun.name)
        assertEquals(first.createdAt, rerun.createdAt)
    }
}
