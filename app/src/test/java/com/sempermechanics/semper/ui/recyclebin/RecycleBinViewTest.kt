package com.sempermechanics.semper.ui.recyclebin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.bin.BinOutcome
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.RecycleBinItemDto
import com.sempermechanics.semper.data.net.RecycleBinRestoreResponse
import com.sempermechanics.semper.ui.common.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * What the recycle bin says (ADR-035): the dates on a row, the days left,
 * the lines Home and Settings use for a row in the bin, and what a Restore
 * came to.
 */
@RunWith(RobolectricTestRunner::class)
class RecycleBinViewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val utc = ZoneId.of("UTC")
    private val uk = Locale.UK
    private val day = TimeUnit.DAYS.toMillis(1)

    private fun at(year: Int, month: Int, dayOfMonth: Int): Long =
        ZonedDateTime.of(year, month, dayOfMonth, 12, 0, 0, 0, utc).toInstant().toEpochMilli()

    private val now = at(2026, 11, 3)

    private fun item(deletedAt: String? = "2026-11-03T12:00:00+00:00", purgeAt: String? = "2026-12-03T12:00:00+00:00") =
        RecycleBinItemDto(sessionId = "S1", name = "steel_00", deletedAt = deletedAt, purgeAt = purgeAt)

    @Test
    fun `a row says when it was deleted and when it is erased for good`() {
        assertEquals(
            "Deleted 3 Nov · erased for good on 3 Dec",
            RecycleBinView.datesLine(context, item(), now, utc, uk),
        )
    }

    @Test
    fun `without a deletion time it says only when it is erased, and without either nothing`() {
        assertEquals(
            "Erased for good on 3 Dec",
            RecycleBinView.datesLine(context, item(deletedAt = null), now, utc, uk),
        )
        assertNull(RecycleBinView.datesLine(context, item(purgeAt = null), now, utc, uk))
    }

    @Test
    fun `a date outside this year carries the year`() {
        assertEquals("3 Jan 2027", RecycleBinView.date(at(2027, 1, 3), now, utc, uk))
        assertEquals("3 Dec", RecycleBinView.date(at(2026, 12, 3), now, utc, uk))
    }

    @Test
    fun `the days left count a part day as one, and none once due`() {
        assertEquals(30, RecycleBinView.daysLeft(now + 30 * day, now))
        assertEquals(1, RecycleBinView.daysLeft(now + 1, now))
        assertEquals(2, RecycleBinView.daysLeft(now + day + 1, now))
        assertEquals(0, RecycleBinView.daysLeft(now, now))
        assertEquals(0, RecycleBinView.daysLeft(now - day, now))
        assertEquals(-1, RecycleBinView.daysLeft(0L, now))
    }

    @Test
    fun `the days left read as plurals, and past due as being erased`() {
        assertEquals("30 days left", RecycleBinView.daysLeftNote(now + 30 * day, now)!!.text(context))
        assertEquals("1 day left", RecycleBinView.daysLeftNote(now + day, now)!!.text(context))
        assertEquals("Being erased for good", RecycleBinView.daysLeftNote(now - 1, now)!!.text(context))
        assertNull(RecycleBinView.daysLeftNote(0L, now))
    }

    @Test
    fun `a phone row in the bin says until when, and so does deleting it`() {
        assertEquals("In the recycle bin until 3 Dec", RecycleBinView.inBinLine(context, at(2026, 12, 3), now, utc, uk))
        assertEquals("In the recycle bin", RecycleBinView.inBinLine(context, 0L, now, utc, uk))
        assertEquals(
            "It's deleted from this phone. Its backup stays in the recycle bin until 3 Dec.",
            RecycleBinView.deleteBinnedBody(context, at(2026, 12, 3), now, utc, uk),
        )
        assertEquals(
            context.getString(R.string.delete_binned_body),
            RecycleBinView.deleteBinnedBody(context, 0L, now, utc, uk),
        )
    }

    @Test
    fun `the row is named as the backups list names it`() {
        assertEquals("steel_00", RecycleBinView.name(item()))
        assertEquals("ref_01", RecycleBinView.name(RecycleBinItemDto("S2", name = "", specimen = "ref_01")))
        assertEquals("", RecycleBinView.name(RecycleBinItemDto("S3")))
    }

    @Test
    fun `a restore says what it came to, and no room opens the limit screen`() {
        val ok = BinOutcome.Ok(RecycleBinRestoreResponse(sessionId = "S1", restored = true))
        assertEquals(
            Notice(Note(R.string.recycle_bin_restored_fmt, listOf("steel_00"))),
            RecycleBinView.restored(ok, "steel_00"),
        )
        assertEquals(Notice(Note(R.string.recycle_bin_restored)), RecycleBinView.restored(ok, ""))
        val noRoom = RecycleBinView.restored(BinOutcome.NoRoom, "steel_00")
        assertTrue(noRoom.openLimit)
        assertEquals(Note(R.string.recycle_bin_no_room), noRoom.note)
        assertEquals(Notice(Note(R.string.recycle_bin_gone)), RecycleBinView.restored(BinOutcome.Gone, "x"))
        assertEquals(Notice(Note(R.string.recycle_bin_offline)), RecycleBinView.restored(BinOutcome.Offline, "x"))
        val refused = RecycleBinView.restored(BinOutcome.Failed(HttpFailure.classify(ApiException(400, "{}"))), "x")
        assertEquals(Notice(Note(R.string.recycle_bin_restore_failed)), refused)
        assertFalse(refused.openLimit)
    }

    @Test
    fun `a list that failed says why`() {
        assertEquals(Note(R.string.recycle_bin_offline), RecycleBinView.listFailed(BinOutcome.Offline))
        assertEquals(
            Note(R.string.error_offline),
            RecycleBinView.listFailed(BinOutcome.Failed(HttpFailure.classify(IOException("x")))),
        )
        assertEquals(
            Note(R.string.recycle_bin_load_failed),
            RecycleBinView.listFailed(BinOutcome.Failed(HttpFailure.classify(ApiException(400, "{}")))),
        )
    }
}
