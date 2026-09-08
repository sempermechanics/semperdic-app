package com.indicvision.semper.capture

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The noise-floor dialog inflates under the app's own theme.
 *
 * Nothing else in the app inflates this layout before the burst finishes, so a
 * theme attribute the layout names but `Theme.Semper` never defines — a
 * Material 3 attribute on a Material 2 theme, say — throws only on a real
 * device, at the one moment the user is being told whether their recording is
 * usable. Inflating it here moves that failure into the build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoiseFloorDialogLayoutTest {

    @Test
    fun `the noise floor dialog inflates under the app theme`() {
        val themed = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(),
            R.style.Theme_Semper,
        )
        val view = LayoutInflater.from(themed)
            .inflate(R.layout.dialog_noise_floor_content, null)
        assertNotNull(view.findViewById(R.id.tvNoiseFloorValue))
        assertNotNull(view.findViewById(R.id.tvNoiseFloorLabel))
        assertNotNull(view.findViewById(R.id.tvNoiseFloorBody))
        assertNotNull(view.findViewById(R.id.tvNoiseFloorHeadline))
    }

    @Test
    fun `the sigma map starts hidden so the text-only dialog stays valid`() {
        // A burst that produced no field is a normal outcome, not a degraded
        // one: INSUFFICIENT bursts and short bursts both reach this dialog with
        // nothing to draw, and the layout has to be correct with the block
        // never made visible.
        val themed = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Context>(),
            R.style.Theme_Semper,
        )
        val view = LayoutInflater.from(themed)
            .inflate(R.layout.dialog_noise_floor_content, null)
        assertNotNull(view.findViewById(R.id.imgNoiseFloorMap))
        assertNotNull(view.findViewById(R.id.tvNoiseFloorMapLegend))
        assertEquals(View.GONE, view.findViewById<View>(R.id.groupNoiseFloorMap).visibility)
    }
}
