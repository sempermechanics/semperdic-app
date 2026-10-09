package com.sempermechanics.semper.ui.settings

import android.content.Intent
import androidx.core.view.isVisible
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.DeviceKeys
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.AccountCache
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
        views.tvAccountEmail.text = AccountCache.cachedEmail(activity).orEmpty()
        val deviceId = DeviceKeys.deviceId(activity)
        views.tvAccountDevice.text = activity.getString(R.string.account_device_id_fmt, deviceId)

        // The prefix is the only part of a key the app is ever told, and it is
        // what support asks for. Shown only while the account runs licensed:
        // older backends send the prefix of any key the account holds, a
        // revoked, lapsed or unseated one included, and "Licensed as" beside
        // demo's limits is wrong. Empty on a backend that predates the field.
        val prefix = LicenseEntitlements.licensePrefix(activity)
        views.tvAccountLicense.apply {
            isVisible = prefix.isNotEmpty() && LicenseEntitlements.isLicensed(activity)
            text = activity.getString(R.string.account_license_prefix_fmt, prefix)
        }

        views.btnAdmin.apply {
            isVisible = AccountCache.isAdmin(activity)
            setOnClickListener { activity.startActivity(Intent(activity, AdminActivity::class.java)) }
        }
    }
}
