package com.indicvision.semper.ui.analysis.frames

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The deformed-frame strip on wizard step 1: dropping a dragged tile renumbers
 * every badge and returns the tile to its resting size. The badge refresh is a
 * payload change, and the item animator ends a holder's running animation on a
 * change, which once froze the drop's shrink at the dragged 1.06×.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FrameOrderAdapterTest {

    private val context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(),
        R.style.Theme_Semper,
    )
    private val orders = mutableListOf<List<String>>()
    private val adapter = FrameOrderAdapter { orders += it }
    private val recycler = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        adapter = this@FrameOrderAdapterTest.adapter
    }

    @Before
    fun setUp() {
        adapter.submit(listOf("a.png", "b.png", "c.png")) // missing files draw the placeholder
        layout()
    }

    private fun layout() {
        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY),
        )
        recycler.layout(0, 0, W, H)
    }

    private fun tile(position: Int): View = recycler.findViewHolderForAdapterPosition(position)!!.itemView

    private fun badge(position: Int): String =
        tile(position).findViewById<android.widget.TextView>(R.id.tvFrameBadge).text.toString()

    @Test
    fun `a dropped tile shrinks back although the badges refresh at once`() {
        val dragged = tile(0)
        FrameOrderAdapter.applyDragging(dragged, dragging = true, animate = false)
        adapter.moveItem(0, 2)
        layout()

        // What ItemTouchHelper's clearView does on the drop.
        FrameOrderAdapter.applyDragging(dragged, dragging = false)
        adapter.refreshBadges()
        layout()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertEquals(1f, dragged.scaleX)
        assertEquals(1f, dragged.scaleY)
        assertEquals(0f, dragged.elevation)
    }

    @Test
    fun `a move renumbers the badges in the new order`() {
        adapter.moveItem(0, 2)
        adapter.refreshBadges()
        layout()

        assertEquals(listOf("b.png", "c.png", "a.png"), adapter.currentPaths())
        assertEquals(listOf("1", "2", "3"), (0..2).map(::badge))
    }

    private companion object {
        const val W = 1080
        const val H = 240
    }
}
