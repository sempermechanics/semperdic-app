package com.sempermechanics.semper.ui.common

import androidx.annotation.StringRes
import com.sempermechanics.semper.databinding.ViewSettingsSectionHeaderBinding

/**
 * The collapsible Settings section header (`view_settings_section_header.xml`).
 *
 * Settings repeats the header block seven times in `view_settings_scroll_content.xml`,
 * each with its own title and its chevron described by that same title. An
 * `<include>` cannot set a child's text, so [bind] does: include the layout
 * with the header's id (`headerAccount`, …) and bind its generated binding
 * once. The chevron is the binding's `imgSectionChevron` — the same id in every
 * include, so reach it through its header's binding, never by an
 * Activity-wide `findViewById`.
 *
 * The Account header sits 8dp below the top, not 14dp: its `<include>` sets
 * `android:layout_marginTop`, which an include applies only when it also sets
 * `android:layout_width` and `android:layout_height`.
 */
object SettingsSectionHeader {

    /** Titles [header] [title] and describes its chevron with the same text. */
    fun bind(header: ViewSettingsSectionHeaderBinding, @StringRes title: Int) {
        val text = header.root.context.getText(title)
        header.tvSectionTitle.text = text
        header.imgSectionChevron.contentDescription = text
    }
}
