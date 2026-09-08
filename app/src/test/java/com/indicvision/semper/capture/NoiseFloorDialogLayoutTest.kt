package com.indicvision.semper.capture

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        val view = inflate()
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
        val view = inflate()
        assertNotNull(view.findViewById(R.id.imgNoiseFloorMap))
        assertNotNull(view.findViewById(R.id.tvNoiseFloorMapLegend))
        assertEquals(View.GONE, view.findViewById<View>(R.id.groupNoiseFloorMap).visibility)
    }

    @Test
    fun `the dialog scrolls, so nothing on it can be unreachable`() {
        // The map made this dialog taller than a phone screen, and with no
        // scroller the body text explaining the floor value was simply clipped
        // away with no way to reach it.
        assertTrue("root scrolls", inflate() is ScrollView)
    }

    @Test
    fun `the floor value and its explanation come before the map`() {
        // Order is the other half of the same fix. The verdict is what the user
        // opened the dialog for; a picture above it pushes the number and its
        // sentence off the bottom, and having to scroll back for them is a
        // worse answer than not putting them there.
        val column = (inflate() as ScrollView).getChildAt(0) as ViewGroup
        val at = (0 until column.childCount).associateBy { column.getChildAt(it).id }
        assertTrue("value before body", at.getValue(R.id.tvNoiseFloorValue) < at.getValue(R.id.tvNoiseFloorBody))
        assertTrue("body before map", at.getValue(R.id.tvNoiseFloorBody) < at.getValue(R.id.groupNoiseFloorMap))
    }

    @Test
    fun `the map cannot grow tall enough to crowd out the text`() {
        val image = inflate().findViewById<ImageView>(R.id.imgNoiseFloorMap)
        assertTrue("a ceiling is set", image.maxHeight in 1..MAX_SENSIBLE_MAP_PX)
        assertTrue("and it can still shrink to fit", image.adjustViewBounds)
    }

    /** The layout under the app's own theme, which is the only way it ships. */
    private fun inflate(): View = LayoutInflater
        .from(
            ContextThemeWrapper(
                ApplicationProvider.getApplicationContext<Context>(),
                R.style.Theme_Semper,
            ),
        )
        .inflate(R.layout.dialog_noise_floor_content, null)

    private companion object {
        /**
         * A map taller than this has stopped being a figure beside the number
         * and become the dialog. Generous on purpose: the test guards against
         * there being no ceiling at all, it does not tune the one there is.
         */
        const val MAX_SENSIBLE_MAP_PX = 900
    }
}
