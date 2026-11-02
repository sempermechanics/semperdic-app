package com.sempermechanics.semper.ui.home

import android.content.Context
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.OtherAppBackups
import com.sempermechanics.semper.data.session.SessionRecord

/**
 * The delete prompt's warning for [records] when one was backed up by the other
 * app (ADR-014, [OtherAppBackups]): this app cannot restore it, so "From this
 * phone" is not the safe choice it is for the rest. Null when none was.
 */
internal fun otherAppDeleteWarning(context: Context, records: List<SessionRecord>): String? {
    val otherApp = OtherAppBackups.ids(context)
    return context.getString(R.string.delete_confirm_body_other_app).takeIf { records.any { it.id in otherApp } }
}
