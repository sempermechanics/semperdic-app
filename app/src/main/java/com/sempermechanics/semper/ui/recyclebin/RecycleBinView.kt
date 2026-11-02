package com.sempermechanics.semper.ui.recyclebin

import android.content.Context
import android.text.format.DateFormat
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.bin.BinOutcome
import com.sempermechanics.semper.data.net.RecycleBinItemDto
import com.sempermechanics.semper.data.net.isoToEpochMs
import com.sempermechanics.semper.data.session.CloudNaming
import com.sempermechanics.semper.ui.common.Note
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * What a Restore tap came to, said once: a [note], and with [openLimit] the
 * session limit screen opens after it (no room for the backup).
 */
data class Notice(val note: Note, val openLimit: Boolean = false)

/**
 * What the recycle bin screen, Home and Settings say about a backup in the
 * bin (ADR-035), kept apart from the views so each rule is a test.
 */
object RecycleBinView {

    private val DAY_MS = TimeUnit.DAYS.toMillis(1)

    /** The row's name: the analysis's, else its reference's, as the backups list names it; blank with neither. */
    fun name(item: RecycleBinItemDto): String = CloudNaming.listedName(item.name, item.specimen).trim()

    /**
     * Whole days left before [purgeAtMs] erases it, counting a part day as
     * one ("1 day left" until it is due); 0 once it is due, -1 when unknown.
     */
    fun daysLeft(purgeAtMs: Long, now: Long): Int = when {
        purgeAtMs <= 0L -> -1
        purgeAtMs <= now -> 0
        else -> ((purgeAtMs - now + DAY_MS - 1) / DAY_MS).toInt()
    }

    /** "12 days left", "1 day left", or "Being erased for good" once due; null when unknown. */
    fun daysLeftNote(purgeAtMs: Long, now: Long): Note? = when (val days = daysLeft(purgeAtMs, now)) {
        -1 -> null
        0 -> Note(R.string.recycle_bin_last_day)
        else -> Note(R.plurals.recycle_bin_days_left_fmt, listOf(days), quantity = days)
    }

    /**
     * "Deleted 3 Nov · erased for good on 3 Dec"; without a deletion time
     * "Erased for good on 3 Dec"; null with no purge time either.
     */
    fun datesLine(
        context: Context,
        item: RecycleBinItemDto,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String? {
        val purgeAt = item.purgeAtMs
        if (purgeAt <= 0L) return null
        val erased = date(purgeAt, now, zone, locale)
        val deletedAt = isoToEpochMs(item.deletedAt)
        return if (deletedAt > 0L) {
            context.getString(R.string.recycle_bin_dates_fmt, date(deletedAt, now, zone, locale), erased)
        } else {
            context.getString(R.string.recycle_bin_purge_date_fmt, erased)
        }
    }

    /**
     * A phone row whose backup is in the bin, on Settings' analysis list:
     * "In the recycle bin until 3 Dec", or "In the recycle bin" with no date.
     */
    fun inBinLine(
        context: Context,
        purgeAtMs: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = if (purgeAtMs > 0L) {
        context.getString(R.string.analysis_state_in_recycle_bin_fmt, date(purgeAtMs, now, zone, locale))
    } else {
        context.getString(R.string.analysis_state_in_recycle_bin)
    }

    /**
     * Deleting a phone row whose backup is in the bin removes the phone copy
     * only: "… Its backup stays in the recycle bin until 3 Dec."
     */
    fun deleteBinnedBody(
        context: Context,
        purgeAtMs: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = if (purgeAtMs > 0L) {
        context.getString(R.string.delete_binned_body_fmt, date(purgeAtMs, now, zone, locale))
    } else {
        context.getString(R.string.delete_binned_body)
    }

    /** The locale's short date of [millis]: "3 Nov" (or "Nov 3"), with the year outside [now]'s. */
    fun date(
        millis: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val at = Instant.ofEpochMilli(millis).atZone(zone)
        val skeleton = if (at.year == Instant.ofEpochMilli(now).atZone(zone).year) "MMMd" else "yMMMd"
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(at)
    }

    /** What a Restore tap says; [name] is the backup's, blank when not known. */
    fun restored(outcome: BinOutcome<*>, name: String): Notice = when (outcome) {
        is BinOutcome.Ok -> Notice(
            if (name.isBlank()) {
                Note(R.string.recycle_bin_restored)
            } else {
                Note(R.string.recycle_bin_restored_fmt, listOf(name))
            },
        )
        BinOutcome.NoRoom -> Notice(Note(R.string.recycle_bin_no_room), openLimit = true)
        BinOutcome.Gone -> Notice(Note(R.string.recycle_bin_gone))
        BinOutcome.Offline -> Notice(Note(R.string.recycle_bin_offline))
        is BinOutcome.Failed -> Notice(Note(outcome.failure.userMessage(R.string.recycle_bin_restore_failed)))
    }

    /** Why the bin could not be listed. */
    fun listFailed(outcome: BinOutcome<*>): Note = when (outcome) {
        BinOutcome.Offline -> Note(R.string.recycle_bin_offline)
        is BinOutcome.Failed -> Note(outcome.failure.userMessage(R.string.recycle_bin_load_failed))
        else -> Note(R.string.recycle_bin_load_failed)
    }
}
