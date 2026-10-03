package com.sempermechanics.semper.ui.settings

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.databinding.SettingsScrollContentBinding
import com.sempermechanics.semper.ui.common.auth.AuthRoute
import com.sempermechanics.semper.ui.common.auth.ExternalLinks
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import com.sempermechanics.semper.ui.common.auth.confirm

/** The foot of Settings: About (version, privacy, terms) and Sign out. */
internal class SettingsFooterSection(
    private val activity: SettingsActivity,
    private val views: SettingsScrollContentBinding,
) {

    fun wire() {
        views.btnAbout.setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.about_title)
                .setMessage(
                    activity.getString(
                        R.string.about_message,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                )
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.legal_privacy) { _, _ ->
                    ExternalLinks.open(activity, activity.getString(R.string.legal_privacy_url))
                }
                .setNegativeButton(R.string.legal_terms) { _, _ ->
                    ExternalLinks.open(activity, activity.getString(R.string.legal_terms_url))
                }
                .show()
        }
        views.btnSignOut.setOnClickListener {
            // Outside this screen, so a rotation cannot half sign out; the
            // observer below routes once it is done.
            val app = activity.applicationContext
            SignOutRun.confirm(activity) { AuthRepository(app).signOut() }
        }
        SignOutRun.observe(activity) { AuthRoute.toSignIn(activity) }
    }
}
