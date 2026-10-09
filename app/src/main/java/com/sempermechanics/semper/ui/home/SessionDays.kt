package com.sempermechanics.semper.ui.home

import android.content.Context
import android.text.format.DateFormat
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One entry in Home's list: a day header, or an analysis under it. */
internal sealed interface SessionRow {

    /** The day [millis] falls on in the device's timezone; its words come at bind ([SessionDays.label]). */
    data class Header(val millis: Long) : SessionRow

    data class Session(val record: SessionRecord) : SessionRow
}

/**
 * Home's day headers: a newest-first list split by the day each analysis was
 * created, in the device's timezone and locale. `SimpleDateFormat` and
 * `Calendar`, not `java.time`: minSdk is 24 without core library desugaring.
 */
internal object SessionDays {

    /** [records] (newest first, as `SessionStore.list` sorts them) with a [SessionRow.Header] above each day. */
    fun rows(records: List<SessionRecord>, zone: TimeZone = TimeZone.getDefault()): List<SessionRow> {
        val calendar = Calendar.getInstance(zone)
        val out = ArrayList<SessionRow>(records.size + 1)
        var lastDay: Int? = null
        for (record in records) {
            val day = dayKey(calendar, record.createdAt)
            if (day != lastDay) {
                out += SessionRow.Header(record.createdAt)
                lastDay = day
            }
            out += SessionRow.Session(record)
        }
        return out
    }

    /**
     * "Today", "Yesterday", then the locale's short date: "Oct 7", or
     * "Oct 7, 2025" outside [now]'s year.
     */
    fun label(
        context: Context,
        millis: Long,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val calendar = Calendar.getInstance(zone, locale)
        val day = dayKey(calendar, millis)
        val today = dayKey(calendar, now)
        val thisYear = calendar.get(Calendar.YEAR)
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = keyOf(calendar)
        return when (day) {
            today -> context.getString(R.string.home_day_today)
            yesterday -> context.getString(R.string.home_day_yesterday)
            else -> {
                val skeleton = if (day / YEAR_KEY == thisYear) "MMMd" else "yMMMd"
                SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
                    .apply { timeZone = zone }
                    .format(Date(millis))
            }
        }
    }

    /** Year and day of year of [millis] as one number; leaves [calendar] on [millis]. */
    private fun dayKey(calendar: Calendar, millis: Long): Int {
        calendar.timeInMillis = millis
        return keyOf(calendar)
    }

    private fun keyOf(calendar: Calendar): Int =
        calendar.get(Calendar.YEAR) * YEAR_KEY + calendar.get(Calendar.DAY_OF_YEAR)

    /** Above the largest day of year, so a key splits back into year and day. */
    private const val YEAR_KEY = 1000
}
