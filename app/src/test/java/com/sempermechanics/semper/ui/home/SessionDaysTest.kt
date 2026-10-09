package com.sempermechanics.semper.ui.home

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Home's day headers: which day an analysis falls on in the phone's own
 * timezone, and the words for it — "Today", "Yesterday", then a short date
 * that gains its year outside this one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SessionDaysTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** Five hours ahead of UTC, so a UTC midnight is not the local one. */
    private val zone = TimeZone.getTimeZone("GMT+05:00")

    /** [year]-[month]-[day] [hour]:[minute] in [zone]. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    private val now = at(2026, 10, 9, hour = 9)

    private fun label(millis: Long) = SessionDays.label(context, millis, now, zone, Locale.US)

    @Test
    fun `today is today from local midnight on`() {
        assertEquals("Today", label(now))
        assertEquals("Today", label(at(2026, 10, 9, hour = 0)))
    }

    @Test
    fun `yesterday runs to the last minute before local midnight`() {
        assertEquals("Yesterday", label(at(2026, 10, 8, hour = 23, minute = 59)))
        assertEquals("Yesterday", label(at(2026, 10, 8, hour = 0)))
    }

    @Test
    fun `an older day this year is a short date`() {
        assertEquals("Oct 7", label(at(2026, 10, 7)))
        assertEquals("Jan 1", label(at(2026, 1, 1)))
    }

    @Test
    fun `a day in another year carries the year`() {
        assertEquals("Dec 31, 2025", label(at(2025, 12, 31)))
    }

    @Test
    fun `yesterday crosses a new year`() {
        val newYear = at(2027, 1, 1, hour = 8)
        assertEquals("Yesterday", SessionDays.label(context, at(2026, 12, 31), newYear, zone, Locale.US))
        assertEquals("Dec 30, 2026", SessionDays.label(context, at(2026, 12, 30), newYear, zone, Locale.US))
    }

    @Test
    fun `the date follows the locale`() {
        assertEquals("7 oct.", SessionDays.label(context, at(2026, 10, 7), now, zone, Locale.FRANCE))
    }

    @Test
    fun `a newest-first list gets one header above each local day`() {
        val records = listOf(
            sessionRecord(id = "late", createdAt = at(2026, 10, 9, hour = 0, minute = 30)),
            // 23:30 the evening before in this zone, though the same UTC day as "late".
            sessionRecord(id = "eve", createdAt = at(2026, 10, 8, hour = 23, minute = 30)),
            sessionRecord(id = "morning", createdAt = at(2026, 10, 8, hour = 6)),
            sessionRecord(id = "old", createdAt = at(2025, 3, 2)),
        )

        val rows = SessionDays.rows(records, zone)

        assertEquals(
            listOf("header", "late", "header", "eve", "morning", "header", "old"),
            rows.map { if (it is SessionRow.Session) it.record.id else "header" },
        )
        assertEquals(
            listOf("Today", "Yesterday", "Mar 2, 2025"),
            rows.filterIsInstance<SessionRow.Header>().map { label(it.millis) },
        )
    }

    @Test
    fun `an empty list has no headers`() {
        assertEquals(emptyList<SessionRow>(), SessionDays.rows(emptyList(), zone))
    }
}
