package com.sempermechanics.semper.ui.common

import android.app.Application
import android.content.res.ColorStateList
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.sempermechanics.semper.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A disabled filled button must not look pressable. Both the theme's default
 * button and the Cta style (the ROI studio's Save, the wizard's Next and Run)
 * once tinted every state sky blue, so a disabled Cta read as enabled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PrimaryButtonStyleTest {

    private val themed = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(),
        R.style.Theme_Semper,
    )

    private fun ColorStateList.enabled() = getColorForState(intArrayOf(android.R.attr.state_enabled), 0)
    private fun ColorStateList.disabled() = getColorForState(intArrayOf(-android.R.attr.state_enabled), 0)

    private fun assertDisabledLooksDisabled(button: MaterialButton) {
        val fill = requireNotNull(button.backgroundTintList)
        assertEquals(ContextCompat.getColor(themed, R.color.sky_primary), fill.enabled())
        assertEquals(ContextCompat.getColor(themed, R.color.surface_outline), fill.disabled())
        assertEquals(ContextCompat.getColor(themed, R.color.text_on_primary), button.textColors.enabled())
        assertEquals(ContextCompat.getColor(themed, R.color.text_secondary), button.textColors.disabled())
    }

    @Test
    fun `the theme's default button greys out while disabled`() {
        assertDisabledLooksDisabled(MaterialButton(themed))
    }

    @Test
    fun `a Cta button greys out while disabled`() {
        val root = LayoutInflater.from(themed).inflate(R.layout.activity_roi_draw, null)
        val save = root.findViewById<MaterialButton>(R.id.btnSaveRoi).apply { isEnabled = false }
        assertDisabledLooksDisabled(save)
    }
}
