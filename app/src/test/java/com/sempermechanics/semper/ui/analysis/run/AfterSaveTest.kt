package com.sempermechanics.semper.ui.analysis.run

import com.sempermechanics.semper.data.session.SessionStore.UpsertOutcome
import com.sempermechanics.semper.field.RunStop
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A run's record save decides how the run ends: saved keeps its stop, a full
 * quota stops it at the session limit, and an unavailable index is reported
 * as such without passing for the quota.
 */
class AfterSaveTest {

    private val stops = listOf(RunStop.Finished, RunStop.Cancelled, RunStop.LowConvergence)

    @Test
    fun `a saved record keeps the run's stop`() {
        stops.forEach { stop ->
            assertEquals(
                AfterSave(stop, recordSaved = true, indexUnavailable = false),
                afterSave(stop, UpsertOutcome.SAVED),
            )
        }
    }

    @Test
    fun `a full quota stops the run at the session limit, a cancelled one included`() {
        stops.forEach { stop ->
            assertEquals(
                "from $stop",
                AfterSave(RunStop.SessionLimit, recordSaved = false, indexUnavailable = false),
                afterSave(stop, UpsertOutcome.QUOTA_FULL),
            )
        }
    }

    @Test
    fun `an unavailable index is not the quota, and keeps the run's stop`() {
        stops.forEach { stop ->
            assertEquals(
                AfterSave(stop, recordSaved = false, indexUnavailable = true),
                afterSave(stop, UpsertOutcome.INDEX_UNAVAILABLE),
            )
        }
    }
}
