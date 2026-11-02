package com.sempermechanics.semper.settings

import android.view.View
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.recyclebin.RecycleBinActivity
import com.sempermechanics.semper.ui.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Settings → Your data → Recycle bin (ADR-035): the section has no licence
 * lock, so every account, Demo too, reaches the bin a Home delete fills.
 */
@RunWith(RobolectricTestRunner::class)
class RecycleBinEntryTest {

    private val built = mutableListOf<ActivityController<SettingsActivity>>()

    @After
    fun destroySettings() = built.forEach { runCatching { it.pause().stop().destroy() } }

    @Test
    fun `Your data's Recycle bin opens the bin`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().also { built += it }.get()
        activity.findViewById<View>(R.id.headerYourData).performClick()

        val button = activity.findViewById<View>(R.id.btnRecycleBin)
        assertTrue(button.isShown)
        button.performClick()

        assertEquals(
            RecycleBinActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
    }
}
