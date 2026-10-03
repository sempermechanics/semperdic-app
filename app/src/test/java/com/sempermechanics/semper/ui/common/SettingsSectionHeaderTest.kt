package com.sempermechanics.semper.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.ViewSettingsSectionHeaderBinding
import com.sempermechanics.semper.ui.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * Settings' seven section headers are `<include>`s of
 * `view_settings_section_header.xml`, titled by [SettingsSectionHeader.bind]. Each
 * one on the real screen must look exactly like the hand-copied header it
 * replaced — the same views, attributes and text, with the chevron described
 * by the title — and open its own section from its own chevron.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsSectionHeaderTest {

    private lateinit var controller: ActivityController<SettingsActivity>
    private lateinit var activity: SettingsActivity

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        runCatching { controller.pause().stop().destroy() }
    }

    private data class Look(
        val width: Int,
        val height: Int,
        val margins: List<Int>,
        val padding: List<Int>,
        val gravity: Int,
        val orientation: Int,
        val clickableBackground: Boolean,
        val title: String,
        val titleSize: Float,
        val titleColor: Int,
        val titleTypefaceStyle: Int?,
        val titleWeight: Float,
        val chevronSize: Pair<Int, Int>,
        val chevronDescription: String,
        val chevronTint: Int?,
        val chevronHasImage: Boolean,
    )

    private fun look(header: View): Look {
        header as LinearLayout
        val lp = header.layoutParams as ViewGroup.MarginLayoutParams
        val title = header.getChildAt(0) as TextView
        val chevron = header.getChildAt(1) as ImageView
        return Look(
            width = lp.width,
            height = lp.height,
            margins = listOf(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin),
            padding = listOf(header.paddingLeft, header.paddingTop, header.paddingRight, header.paddingBottom),
            gravity = header.gravity,
            orientation = header.orientation,
            clickableBackground = header.background != null,
            title = title.text.toString(),
            titleSize = title.textSize,
            titleColor = title.currentTextColor,
            titleTypefaceStyle = title.typeface?.style,
            titleWeight = (title.layoutParams as LinearLayout.LayoutParams).weight,
            chevronSize = chevron.layoutParams.width to chevron.layoutParams.height,
            chevronDescription = chevron.contentDescription.toString(),
            chevronTint = chevron.imageTintList?.defaultColor,
            chevronHasImage = chevron.drawable != null,
        )
    }

    /** The shared header, inflated through its generated binding into a vertical list, and bound. */
    private fun shared(title: Int): View {
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val binding = ViewSettingsSectionHeaderBinding.inflate(LayoutInflater.from(activity), list, false)
        SettingsSectionHeader.bind(binding, title)
        return binding.root
    }

    private val sections = mapOf(
        R.id.headerCloud to (R.string.cloud_section to R.id.bodyCloud),
        R.id.headerAnalysesData to (R.string.analyses_data_management to R.id.bodyAnalysesData),
        R.id.headerStorage to (R.string.storage_section to R.id.bodyStorage),
        R.id.headerYourData to (R.string.your_data_section to R.id.bodyYourData),
        R.id.headerAnalysisPrefs to (R.string.analysis_preferences to R.id.bodyAnalysisPrefs),
        R.id.headerHelpSupport to (R.string.help_support_section to R.id.bodyHelpSupport),
    )

    @Test
    fun `every 14dp header on the screen is the shared one, titled`() {
        for ((id, section) in sections) {
            val title = section.first
            assertEquals(activity.getString(title), look(shared(title)), look(activity.findViewById(id)))
        }
    }

    @Test
    fun `the Account header differs only in its 8dp top margin`() {
        val account = look(activity.findViewById(R.id.headerAccount))
        val shared = look(shared(R.string.account_section))
        val density = activity.resources.displayMetrics.density

        assertEquals((8 * density).toInt(), account.margins[1])
        assertEquals((14 * density).toInt(), shared.margins[1])
        assertEquals(shared.copy(margins = account.margins), account)
    }

    @Test
    fun `each header opens and closes its own section, turning its own chevron`() {
        for ((id, section) in sections + (R.id.headerAccount to (R.string.account_section to R.id.bodyAccount))) {
            val header = activity.findViewById<View>(id)
            val chevron = header.findViewById<ImageView>(R.id.imgSectionChevron)
            val body = activity.findViewById<View>(section.second)
            val name = activity.getString(section.first)
            assertFalse("$name starts closed", body.isShown)
            assertEquals(name, 0f, chevron.rotation)

            header.performClick()
            assertEquals(name, View.VISIBLE, body.visibility)
            assertEquals(name, 180f, chevron.rotation)

            header.performClick()
            assertEquals(name, View.GONE, body.visibility)
            assertEquals(name, 0f, chevron.rotation)
        }
    }

    @Test
    fun `bind titles a header and describes its chevron`() {
        val header = ViewSettingsSectionHeaderBinding.inflate(LayoutInflater.from(activity), null, false)
        SettingsSectionHeader.bind(header, R.string.storage_section)

        val title = activity.getString(R.string.storage_section)
        assertEquals(title, header.tvSectionTitle.text.toString())
        assertEquals(title, header.imgSectionChevron.contentDescription)
    }
}
