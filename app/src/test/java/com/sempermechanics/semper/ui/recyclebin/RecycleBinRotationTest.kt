package com.sempermechanics.semper.ui.recyclebin

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.bin.BinOutcome
import com.sempermechanics.semper.data.net.RecycleBinRestoreResponse
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast

/**
 * A Restore runs in [RecycleBinViewModel] (ADR-016): a rotation while the
 * backend answers does not cancel it, the rebuilt screen waits on it, and
 * what it came to is said once, there, and not again after another rotation.
 */
@RunWith(RobolectricTestRunner::class)
class RecycleBinRotationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screens = mutableListOf<ActivityController<RecycleBinActivity>>()
    private val fake = FakeBin()

    @Before
    fun standIn() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        RecycleBinViewModel.factory = { fake }
    }

    @After
    fun tearDown() {
        screens.forEach { runCatching { it.pause().stop().destroy() } }
        RecycleBinViewModel.factory = { RecycleBinViewModel.Bin.Live(it) }
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun screen() =
        Robolectric.buildActivity(RecycleBinActivity::class.java, Intent(context, RecycleBinActivity::class.java))
            .setup()
            .also {
                screens += it
                idle()
            }

    private fun RecycleBinActivity.restoreButton(index: Int): View {
        val list = findViewById<RecyclerView>(R.id.rvRecycleBin)
        list.measure(0, 0)
        list.layout(0, 0, 1000, 4000)
        return list.getChildAt(index).findViewById(R.id.btnBinRestore)
    }

    private fun viewModelOf(activity: RecycleBinActivity) = ViewModelProvider(activity)[RecycleBinViewModel::class.java]

    @Test
    fun `a restore answered after a rotation is said once, on the new screen`() {
        val answer = CompletableDeferred<BinOutcome<RecycleBinRestoreResponse>>()
        fake.restoreAnswer = { answer.await() }
        val controller = screen()
        val vm = viewModelOf(controller.get())
        controller.get().restoreButton(0).performClick()
        idle()

        val rebuilt = controller.recreate().get()
        idle()
        assertSame("the restore's view model outlives the screen", vm, viewModelOf(rebuilt))
        assertFalse("the new screen waits on it", rebuilt.restoreButton(0).isEnabled)

        ShadowToast.reset()
        answer.complete(BinOutcome.Ok(RecycleBinRestoreResponse(sessionId = "S2", restored = true)))
        idle()

        assertEquals(listOf("restore(S2)"), fake.calls.filter { it.startsWith("restore") })
        assertEquals("pmma_00 is backed up again", ShadowToast.getTextOfLatestToast())
        assertEquals(1, ShadowToast.shownToastCount())

        ShadowToast.reset()
        controller.recreate()
        idle()
        assertEquals("not said again after a rotation", 0, ShadowToast.shownToastCount())
    }
}
