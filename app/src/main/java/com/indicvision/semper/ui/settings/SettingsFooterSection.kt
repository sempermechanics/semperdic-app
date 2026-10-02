package com.indicvision.semper.ui.settings

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.R
import com.indicvision.semper.data.account.AuthRepository
import com.indicvision.semper.databinding.SettingsScrollContentBinding
import com.indicvision.semper.ui.common.AuthRoute
import com.indicvision.semper.ui.common.ExternalLinks
import com.indicvision.semper.ui.common.SignOutRun
import com.indicvision.semper.ui.common.confirm

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
