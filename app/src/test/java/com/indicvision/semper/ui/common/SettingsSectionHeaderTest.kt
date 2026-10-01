package com.indicvision.semper.ui.common

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.R
import com.indicvision.semper.databinding.SettingsSectionHeaderBinding
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shared section header inflates to the same views, attributes and text
 * as the hand-copied headers in `settings_scroll_content.xml`: each copy is
 * compared with the include-able one bound to that copy's title.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsSectionHeaderTest {

    private lateinit var activity: AppCompatActivity
    private lateinit var settings: LinearLayout

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        activity = built.setup().get()
        settings = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        LayoutInflater.from(activity).inflate(R.layout.settings_scroll_content, settings, true)
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

    /** The shared header, inflated through its generated binding (the route wave 4 takes) and bound. */
    private fun shared(title: Int): View {
        val binding = SettingsSectionHeaderBinding.inflate(LayoutInflater.from(activity), settings, false)
        SettingsSectionHeader.bind(binding, title)
        return binding.root
    }

    @Test
    fun `matches every 14dp copy`() {
        val copies = mapOf(
            R.id.headerCloud to R.string.cloud_section,
            R.id.headerAnalysesData to R.string.analyses_data_management,
            R.id.headerStorage to R.string.storage_section,
            R.id.headerYourData to R.string.your_data_section,
            R.id.headerAnalysisPrefs to R.string.analysis_preferences,
            R.id.headerHelpSupport to R.string.help_support_section,
        )
        for ((id, title) in copies) {
            assertEquals(activity.getString(title), look(settings.findViewById(id)), look(shared(title)))
        }
    }

    @Test
    fun `the Account copy differs only in its top margin`() {
        val account = look(settings.findViewById(R.id.headerAccount))
        val shared = look(shared(R.string.account_section))
        val density = activity.resources.displayMetrics.density

        assertEquals((8 * density).toInt(), account.margins[1])
        assertEquals((14 * density).toInt(), shared.margins[1])
        assertEquals(account.copy(margins = shared.margins), shared)
    }

    @Test
    fun `bind titles a header and describes its chevron`() {
        val header = SettingsSectionHeaderBinding.inflate(LayoutInflater.from(activity), settings, false)
        SettingsSectionHeader.bind(header, R.string.storage_section)

        val title = activity.getString(R.string.storage_section)
        assertEquals(title, header.tvSectionTitle.text.toString())
        assertEquals(title, header.ivSectionChevron.contentDescription)
    }
}
