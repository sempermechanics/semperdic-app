package com.indicvision.semper.ui.settings

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.indicvision.semper.R
import com.indicvision.semper.data.DeviceKeyManager
import com.indicvision.semper.data.LicenseEntitlements
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.ui.admin.AdminActivity

/**
 * Account identity row: email, device id, licence prefix, and the admin
 * entry point.
 */
class SettingsAccountSection(
    private val activity: SettingsActivity,
) {
    fun wire() {
        activity.findViewById<TextView>(R.id.tvAccountEmail).text =
            TokenStore.cachedEmail(activity).orEmpty()
        val deviceId = DeviceKeyManager.deviceId(activity)
        activity.findViewById<TextView>(R.id.tvAccountDevice).text =
            activity.getString(R.string.account_device_id_fmt, deviceId)

        // The prefix is the only part of a key the app is ever told, and it is
        // what support asks for. It is *not* empty on demo: the backend sends
        // the prefix of whatever key the account points at, including its own
        // Demo key and a licence that is revoked, lapsed, or waiting on a
        // floating seat. So "Licensed as" is gated on the entitlement, not on
        // the prefix; the prefix check only covers a backend that predates the
        // field, where the row would otherwise end at "Licensed as".
        val prefix = LicenseEntitlements.licensePrefix(activity)
        activity.findViewById<TextView>(R.id.tvAccountLicense).apply {
            isVisible = LicenseEntitlements.isLicensed(activity) && prefix.isNotEmpty()
            text = activity.getString(R.string.account_license_prefix_fmt, prefix)
        }

        activity.findViewById<View>(R.id.btnAdmin).apply {
            isVisible = TokenStore.isAdmin(activity)
            setOnClickListener { activity.startActivity(Intent(activity, AdminActivity::class.java)) }
        }
    }
}
