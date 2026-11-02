package com.sempermechanics.semper.ui.recyclebin

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.bin.BinOutcome
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.RecycleBinRestoreResponse
import com.sempermechanics.semper.ui.common.Note
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [RecycleBinViewModel] over a stand-in bin: the list (soonest erased first,
 * without what a permanent delete is about to erase), Restore and what it
 * says, once each.
 */
@RunWith(RobolectricTestRunner::class)
class RecycleBinViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val fake = FakeBin()
    private lateinit var vm: RecycleBinViewModel
    private val notices = mutableListOf<Notice>()

    @Before
    fun standIn() {
        RecycleBinViewModel.factory = { fake }
        vm = RecycleBinViewModel(app)
    }

    @After
    fun tearDown() {
        RecycleBinViewModel.factory = { RecycleBinViewModel.Bin.Live(it) }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun loaded(): List<String> =
        (vm.screen.value as RecycleBinViewModel.Screen.Loaded).items.map { it.sessionId }

    /** Takes every notice handed out so far, as the screen would. */
    private fun drain() {
        val job = MainScope().launch { vm.notices.collect { notices += it } }
        idle()
        job.cancel()
    }

    @Test
    fun `the list is soonest erased first`() {
        vm.load()
        idle()
        assertEquals(listOf("S2", "S1"), loaded())
    }

    @Test
    fun `a backup a permanent delete is queued for is left out, and comes back when it is undone`() {
        vm.load()
        idle()
        vm.hideQueued(listOf("S2"))
        assertEquals("hidden at once, before WorkManager lists the job", listOf("S1"), loaded())

        fake.pending = { setOf("S2") }
        vm.clearQueued()
        vm.load()
        idle()
        assertEquals("hidden while the job is queued", listOf("S1"), loaded())

        fake.pending = { emptySet() }
        vm.load()
        idle()
        assertEquals("back once the job is undone", listOf("S2", "S1"), loaded())
    }

    @Test
    fun `an empty bin is an empty list`() {
        fake.items.clear()
        vm.load()
        idle()
        assertEquals(emptyList<String>(), loaded())
    }

    @Test
    fun `a bin that cannot be read says why`() {
        fake.listAnswer = BinOutcome.Offline
        vm.load()
        idle()
        assertEquals(RecycleBinViewModel.Screen.Failed(Note(R.string.recycle_bin_offline)), vm.screen.value)

        fake.listAnswer = BinOutcome.Failed(HttpFailure.classify(ApiException(400, "{}")))
        vm.load()
        idle()
        assertEquals(RecycleBinViewModel.Screen.Failed(Note(R.string.recycle_bin_load_failed)), vm.screen.value)
    }

    @Test
    fun `a restore says so once, reads the bin again, and checks the backups`() {
        vm.load()
        idle()
        vm.restore("S1", "steel_00")
        idle()
        drain()

        assertEquals(listOf(Notice(Note(R.string.recycle_bin_restored_fmt, listOf("steel_00")))), notices)
        assertEquals(listOf("S2"), loaded())
        assertEquals(listOf("list", "restore(S1)", "list", "reconcile"), fake.calls)
        assertFalse(vm.busy.value)

        drain()
        assertEquals("said once", 1, notices.size)
    }

    @Test
    fun `no room says so and opens the limit screen, and leaves the list`() {
        fake.restoreAnswer = { BinOutcome.NoRoom }
        vm.load()
        idle()
        vm.restore("S1", "steel_00")
        idle()
        drain()

        assertEquals(listOf(Notice(Note(R.string.recycle_bin_no_room), openLimit = true)), notices)
        assertEquals(listOf("S2", "S1"), loaded())
        assertFalse("nothing came back to check", "reconcile" in fake.calls)
    }

    @Test
    fun `a backup already erased says so and the list is read again`() {
        fake.restoreAnswer = {
            fake.items.removeAll { item -> item.sessionId == it }
            BinOutcome.Gone
        }
        vm.load()
        idle()
        vm.restore("S1")
        idle()
        drain()

        assertEquals(listOf(Notice(Note(R.string.recycle_bin_gone))), notices)
        assertEquals(listOf("S2"), loaded())
        assertFalse("reconcile" in fake.calls)
    }

    @Test
    fun `one restore at a time`() {
        val answer = CompletableDeferred<BinOutcome<RecycleBinRestoreResponse>>()
        fake.restoreAnswer = { answer.await() }
        vm.restore("S1")
        idle()
        assertTrue(vm.busy.value)
        vm.restore("S2")
        idle()
        answer.complete(BinOutcome.Offline)
        idle()

        assertEquals(listOf("restore(S1)"), fake.calls.filter { it.startsWith("restore") })
        assertFalse(vm.busy.value)
    }

    @Test
    fun `a blank id restores nothing`() {
        vm.restore("")
        idle()
        assertTrue(fake.calls.none { it.startsWith("restore") })
    }
}
