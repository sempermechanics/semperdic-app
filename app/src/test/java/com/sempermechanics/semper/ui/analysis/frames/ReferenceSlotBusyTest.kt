package com.sempermechanics.semper.ui.analysis.frames

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** The reference slot says what it is waiting on: a decode (with the RAW's size) or a video's metadata. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReferenceSlotBusyTest {

    private lateinit var bed: WizardTestBed
    private lateinit var slot: ReferenceSlotBusy

    @Before
    fun setUp() {
        bed = WizardTestBed()
        slot = ReferenceSlotBusy(bed.activity, bed.binding)
    }

    @After
    fun closeBed() = bed.close()

    private fun idleFor(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    }

    private fun meta(isRaw: Boolean, size: ImageSize?) = ReferenceSlotBusy.decodingMeta(bed.activity, isRaw, size)

    @Test
    fun `the decode line names RAW and its megapixels when the size is known`() {
        assertEquals("Decoding RAW · 24 MP", meta(isRaw = true, ImageSize(6000, 4000)))
        assertEquals("Decoding RAW · 1 MP", meta(isRaw = true, ImageSize(640, 480)))
        assertEquals("Decoding RAW", meta(isRaw = true, size = null))
        assertEquals("Decoding RAW", meta(isRaw = true, ImageSize.UNKNOWN))
        assertEquals("Decoding image", meta(isRaw = false, ImageSize(6000, 4000)))
    }

    @Test
    fun `a slow RAW decode shows the skeleton and its size, then the slot goes`() {
        val gate = CompletableDeferred<Unit>()
        bed.activity.lifecycleScope.launch {
            slot.decoding(isRaw = true) {
                slot.decodingSize(ImageSize(6000, 4000))
                gate.await()
            }
        }
        idleFor(SHORT_MS)
        assertEquals("nothing for a wait under 300 ms", View.GONE, bed.binding.refBusy.visibility)

        idleFor(LONG_MS)
        assertEquals(View.VISIBLE, bed.binding.refBusy.visibility)
        assertEquals(View.VISIBLE, bed.binding.refBusySkeleton.visibility)
        assertEquals(View.GONE, bed.binding.refBusySpinner.visibility)
        assertEquals("Decoding RAW · 24 MP", bed.binding.tvRefBusy.text.toString())

        gate.complete(Unit)
        idleFor(SHORT_MS)
        assertEquals(View.GONE, bed.binding.refBusy.visibility)
    }

    @Test
    fun `a slow video read shows the spinner and says so`() {
        val gate = CompletableDeferred<Unit>()
        bed.activity.lifecycleScope.launch { slot.readingVideo { gate.await() } }

        idleFor(LONG_MS)
        assertEquals(View.VISIBLE, bed.binding.refBusy.visibility)
        assertEquals(View.GONE, bed.binding.refBusySkeleton.visibility)
        assertEquals(View.VISIBLE, bed.binding.refBusySpinner.visibility)
        assertEquals("Reading video…", bed.binding.tvRefBusy.text.toString())

        gate.complete(Unit)
        idleFor(SHORT_MS)
        assertEquals(View.GONE, bed.binding.refBusy.visibility)
    }

    private companion object {
        const val SHORT_MS = 100L
        const val LONG_MS = 300L
    }
}
