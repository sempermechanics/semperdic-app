package com.indicvision.semper.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.fixtures.CleanAppState
import com.indicvision.semper.report.EngineStats
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [SessionRepository.buildSessionRecord]: each run value lands in its row field,
 * through the grouped inputs and through the one-value-per-parameter form.
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
    fun `the one-value-per-parameter form fills the same fields`() {
        val record = repository.buildSessionRecord(
            appContext = context,
            localSessionId = "s1",
            batchDir = File("sessions/s1"),
            refPngPath = "/ref.png",
            refName = "plate.tif",
            realRefWidth = 4000,
            realRefHeight = 3000,
            settings = settings,
            cloudEnabled = false,
            pointsConverged = 1180,
            avgIterations = 2.3f,
            executionTimeMs = 812,
            frameCount = 2,
            defNames = listOf("a.tif", "b.tif", "c.tif"),
            engineStatsArray = stats.toFloatArray(),
            stopCode = -3,
            plannedFrameCount = 3,
        )

        assertEquals(expectedRow(record, SessionRecord.SyncState.LOCAL_ONLY), record)
    }

    @Test
    fun `the flat form without engine stats saves none`() {
        val record = repository.buildSessionRecord(
            context, "s1", File("sessions/s1"), "/ref.png", "plate.tif", 4000, 3000, settings,
            cloudEnabled = false, pointsConverged = 0, avgIterations = 0f, executionTimeMs = 0,
            frameCount = 1, defNames = listOf("a.tif"), engineStatsArray = null,
        )

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
