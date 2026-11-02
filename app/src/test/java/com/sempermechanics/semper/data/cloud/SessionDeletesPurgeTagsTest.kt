package com.sempermechanics.semper.data.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A permanent delete from the recycle bin ([SessionDeletes.Mode.PURGE],
 * ADR-035) names each backup it will erase, so the bin hides them while it
 * waits, and Home hides no row for it; its report counts what it erased.
 */
@RunWith(RobolectricTestRunner::class)
class SessionDeletesPurgeTagsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun startWorkManager() = WorkManagerTestInitHelper.initializeTestWorkManager(context)

    @After
    fun stopWorkManager() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private val purges = listOf(
        SessionDeletes.Item("L1", "S1", SessionDeletes.Mode.PURGE),
        SessionDeletes.Item("L2", "S2", SessionDeletes.Mode.PURGE),
    )

    @Test
    fun `a queued purge names its backups, not its rows, until it is undone`() {
        val id = SessionDeletes.enqueue(context, purges)

        assertEquals(setOf("S1", "S2"), SessionDeletes.pendingPurgeIds(context))
        assertTrue("Home keeps the rows", SessionDeletes.pendingRowIds(context).isEmpty())
        val tags = WorkManager.getInstance(context).getWorkInfoById(id).get()!!.tags
        assertTrue(tags.containsAll(setOf(WorkTags.deletePurgeTag("S1"), WorkTags.deletePurgeTag("S2"))))

        SessionDeletes.cancel(context, id)
        assertTrue(SessionDeletes.pendingPurgeIds(context).isEmpty())
    }

    @Test
    fun `a delete that bins names no purge`() {
        SessionDeletes.enqueue(context, listOf(SessionDeletes.Item("L1", "S1", SessionDeletes.Mode.EVERYWHERE)))

        assertTrue(SessionDeletes.pendingPurgeIds(context).isEmpty())
        assertEquals(setOf("L1"), SessionDeletes.pendingRowIds(context))
    }

    @Test
    fun `the report counts the purges done, not those still in the cloud or the binned`() {
        val items = purges + SessionDeletes.Item("L3", "S3", SessionDeletes.Mode.CLOUD)
        val report = SessionDeletes.Report(done = 2, stillInCloud = listOf(purges[1]), nothingSent = false)

        assertEquals(1, report.purged(items))
        assertEquals(0, SessionDeletes.Report(1, emptyList(), false).purged(items.takeLast(1)))
    }
}
