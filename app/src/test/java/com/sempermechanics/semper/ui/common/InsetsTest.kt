package com.sempermechanics.semper.ui.common

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.core.graphics.Insets as GraphicsInsets

/**
 * Wizard step 1 on a Pixel 6 (Android 17): the keyboard hid the focused field
 * and the page would not scroll it out. The scroll view was padded by the whole
 * IME inset, which also counts the nav bar under it, and nothing scrolled the
 * field back into view once the padding landed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class InsetsTest {

    @Test
    fun `only the keyboard above the chrome under the view counts`() {
        assertEquals(647, ImeReveal.overlap(imeBottom = 900, spaceBelow = 253))
        assertEquals(0, ImeReveal.overlap(imeBottom = 0, spaceBelow = 253))
        assertEquals(0, ImeReveal.overlap(imeBottom = 200, spaceBelow = 253))
    }

    @Test
    fun `a field is scrolled just far enough to show it`() {
        // Visible 0..1000: a field at 1100..1200 scrolls up by 200.
        assertEquals(200, ImeReveal.revealDelta(1100, 1200, 0, 1000))
        // Already visible: no scroll.
        assertEquals(0, ImeReveal.revealDelta(400, 500, 0, 1000))
        // Above the window: scrolls back down to its top.
        assertEquals(-100, ImeReveal.revealDelta(-100, 0, 0, 1000))
        // Taller than the window: its top wins.
        assertEquals(1100, ImeReveal.revealDelta(1100, 2500, 0, 1000))
    }

    @Test
    fun `the scroll view is padded by the overlap, not the whole keyboard`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val root = FrameLayout(context)
        val scroll = ScrollView(context).apply { setPadding(0, 0, 0, 16) }
        root.addView(scroll)
        root.layout(0, 0, 1080, 2400)
        // A 253 px nav bar under the scroll view.
        scroll.layout(0, 0, 1080, 2147)

        Insets.padImeBottom(scroll)
        dispatchIme(scroll, 900)
        assertEquals(16 + 647, scroll.paddingBottom)

        dispatchIme(scroll, 0)
        assertEquals(16, scroll.paddingBottom)
    }

    private fun dispatchIme(view: View, bottom: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), GraphicsInsets.of(0, 0, 0, bottom))
            .build()
        ViewCompat.dispatchApplyWindowInsets(view, insets)
    }
}
