package com.sempermechanics.semper.ui.viewer

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.field.DicResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The ViewModel's field metrics are keyed by frame index, so they hold only for the listing they were read from. */
@RunWith(RobolectricTestRunner::class)
class FieldMetricsCacheTest {

    private val vm = ResultViewerViewModel(ApplicationProvider.getApplicationContext<Application>(), SavedStateHandle())

    /** One accepted point whose U is [u]. */
    private fun frame(u: Float) = FloatArray(DicResult.STRIDE).also {
        it[DicResult.IDX_U] = u
        it[DicResult.IDX_ZNSSD] = 0.01f
    }

    @Test
    fun `the same listing keeps the cached metrics`() {
        val listing = listOf(File("a/frame_000.dat"))
        vm.useFrameListing(listing)
        val first = vm.fieldMetricsFor(0, DicResult.IDX_U, frame(1f))

        // A rotation lists the same files again.
        vm.useFrameListing(listOf(File("a/frame_000.dat")))

        assertSame(first, vm.fieldMetricsFor(0, DicResult.IDX_U, frame(2f)))
    }

    @Test
    fun `a different listing drops them`() {
        vm.useFrameListing(listOf(File("a/frame_000.dat")))
        val first = vm.fieldMetricsFor(0, DicResult.IDX_U, frame(1f))

        vm.useFrameListing(listOf(File("b/frame_000.dat")))
        val second = vm.fieldMetricsFor(0, DicResult.IDX_U, frame(2f))

        assertNotSame(first, second)
        assertEquals(2f, second.stats!!.max, 0f)
    }
}
