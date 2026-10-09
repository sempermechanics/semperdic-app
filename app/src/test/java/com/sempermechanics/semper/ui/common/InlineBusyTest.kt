package com.sempermechanics.semper.ui.common

import android.app.Application
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * A busy slot shows only for a wait past 300 ms, goes when the wait ends, keeps
 * still with animations off, and never outlives its screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class InlineBusyTest {

    private lateinit var controller: ActivityController<AppCompatActivity>
    private lateinit var activity: AppCompatActivity
    private lateinit var slot: View
    private lateinit var skeleton: View
    private lateinit var spinner: View
    private lateinit var busy: InlineBusy

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
        activity = controller.get()
        slot = View(activity).apply { visibility = View.GONE }
        skeleton = View(activity)
        spinner = View(activity).apply { visibility = View.GONE }
        busy = InlineBusy(activity, slot, pulse = skeleton, spinner = spinner)
    }

    private fun idleFor(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `nothing shows before 300 ms, the slot shows after, and goes when the wait ends`() {
        var filled = 0
        val token = busy.start { filled++ }

        idleFor(InlineBusy.SHOW_AFTER_MS - 1)
        assertEquals(View.GONE, slot.visibility)
        assertEquals(0, filled)

        idleFor(2)
        assertEquals(View.VISIBLE, slot.visibility)
        assertEquals(View.VISIBLE, spinner.visibility)
        assertTrue(busy.isShown)
        assertTrue(busy.isPulsing)
        assertEquals(1, filled)

        busy.stop(token)
        assertEquals(View.GONE, slot.visibility)
        assertEquals(View.GONE, spinner.visibility)
        assertFalse(busy.isPulsing)
        assertEquals(1f, skeleton.alpha)
    }

    @Test
    fun `a wait that ends before 300 ms never shows`() {
        var filled = 0
        val token = busy.start { filled++ }
        idleFor(SHORT_MS)
        busy.stop(token)

        idleFor(LONG_MS)
        assertEquals(View.GONE, slot.visibility)
        assertEquals(0, filled)
    }

    @Test
    fun `an older wait ending late does not hide the newer one`() {
        val first = busy.start()
        idleFor(LONG_MS)
        var refilled = 0
        val second = busy.start { refilled++ }
        assertEquals("a slot already up takes the new wait's content at once", 1, refilled)

        busy.stop(first)
        assertEquals(View.VISIBLE, slot.visibility)

        busy.stop(second)
        assertEquals(View.GONE, slot.visibility)
    }

    @Test
    fun `around ends the wait when its block fails`() {
        val gate = CompletableDeferred<Unit>()
        val job = activity.lifecycleScope.launch {
            runCatching { busy.around { gate.await() } }
        }
        idleFor(LONG_MS)
        assertEquals(View.VISIBLE, slot.visibility)

        gate.completeExceptionally(IllegalStateException("decode failed"))
        idleFor(SHORT_MS)
        assertEquals(View.GONE, slot.visibility)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `with animations off the slot shows still, without pulse or spinner`() {
        Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(Motion.reduced(activity))

        busy.start()
        idleFor(LONG_MS)

        assertEquals(View.VISIBLE, slot.visibility)
        assertEquals(View.GONE, spinner.visibility)
        assertFalse(busy.isPulsing)
        assertEquals(1f, skeleton.alpha)
    }

    @Test
    fun `nothing shows once the screen is gone`() {
        busy.start()
        controller.pause().stop().destroy()

        idleFor(LONG_MS)
        assertEquals(View.GONE, slot.visibility)
        busy.start()
        idleFor(LONG_MS)
        assertEquals(View.GONE, slot.visibility)
    }

    private companion object {
        const val SHORT_MS = 100L
        const val LONG_MS = 400L
    }
}
