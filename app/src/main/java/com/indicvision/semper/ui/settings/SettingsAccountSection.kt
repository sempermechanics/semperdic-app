package com.indicvision.semper.ui.settings

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.indicvision.semper.R
import com.indicvision.semper.data.account.DeviceKeyManager
import com.indicvision.semper.data.account.LicenseEntitlements
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
        // what support asks for. Empty on demo, and on any backend that
        // predates the field, so the row is hidden rather than showing
        // "Licensed as" with nothing after it.
        val prefix = LicenseEntitlements.licensePrefix(activity)
        activity.findViewById<TextView>(R.id.tvAccountLicense).apply {
            isVisible = prefix.isNotEmpty()
            text = activity.getString(R.string.account_license_prefix_fmt, prefix)
        }

        activity.findViewById<View>(R.id.btnAdmin).apply {
            isVisible = TokenStore.isAdmin(activity)
            setOnClickListener { activity.startActivity(Intent(activity, AdminActivity::class.java)) }
        }
    }
}
