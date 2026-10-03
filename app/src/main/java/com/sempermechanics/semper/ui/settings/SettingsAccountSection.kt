package com.sempermechanics.semper.ui.settings

import android.content.Intent
import androidx.core.view.isVisible
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.DeviceKeyManager
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.databinding.ViewSettingsScrollContentBinding
import com.sempermechanics.semper.ui.admin.AdminActivity

/**
 * Account identity row: email, device id, licence prefix, and the admin
 * entry point.
 */
class SettingsAccountSection(
    private val activity: SettingsActivity,
    private val views: ViewSettingsScrollContentBinding,
) {
    fun wire() {
        views.tvAccountEmail.text = TokenStore.cachedEmail(activity).orEmpty()
        val deviceId = DeviceKeyManager.deviceId(activity)
        views.tvAccountDevice.text = activity.getString(R.string.account_device_id_fmt, deviceId)

        // The prefix is the only part of a key the app is ever told, and it is
        // what support asks for. Empty on demo, and on any backend that
        // predates the field, so the row is hidden rather than showing
        // "Licensed as" with nothing after it.
        val prefix = LicenseEntitlements.licensePrefix(activity)
        views.tvAccountLicense.apply {
            isVisible = prefix.isNotEmpty()
            text = activity.getString(R.string.account_license_prefix_fmt, prefix)
        }

        views.btnAdmin.apply {
            isVisible = TokenStore.isAdmin(activity)
            setOnClickListener { activity.startActivity(Intent(activity, AdminActivity::class.java)) }
        }
    }
}
