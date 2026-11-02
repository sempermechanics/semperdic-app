package com.sempermechanics.semper.ui.common.transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The pill a finished delete shows (ADR-035): a cloud delete moved its
 * backups to the recycle bin, and an erase from the bin says it was for good.
 */
@RunWith(RobolectricTestRunner::class)
class DeleteDoneTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a cloud delete says its backups are in the recycle bin for 30 days`() {
        assertEquals("10 deleted · in the recycle bin for 30 days", deleteDoneText(context.resources, 10, 0))
        assertEquals("1 deleted · in the recycle bin for 30 days", deleteDoneText(context.resources, 1, 0))
    }

    @Test
    fun `an erase from the bin says it was for good`() {
        assertEquals("1 backup erased for good", deleteDoneText(context.resources, 1, 1))
        assertEquals("3 backups erased for good", deleteDoneText(context.resources, 3, 3))
    }

    @Test
    fun `an outcome from a build before the bin, with no purged count, reads as moved to the bin`() {
        assertEquals("2 deleted · in the recycle bin for 30 days", deleteDoneText(context.resources, 2, 0))
    }
}
