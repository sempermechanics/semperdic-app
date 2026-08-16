package com.indicvision.semper.ui.settings

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.indicvision.semper.R
import com.indicvision.semper.data.DeviceKeyManager
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.ui.admin.AdminActivity

/**
 * Account identity row: email, device id, and the admin entry point.
 */
class SettingsAccountSection(
    private val activity: SettingsActivity,
) {
    fun wire() {
        activity.findViewById<TextView>(R.id.tvAccountEmail).text =
            TokenStore.cachedEmail(activity).orEmpty()
        val deviceId = runCatching { DeviceKeyManager(activity).getDeviceId() }.getOrDefault("")
        activity.findViewById<TextView>(R.id.tvAccountDevice).text =
            activity.getString(R.string.account_device_id_fmt, deviceId)

        activity.findViewById<View>(R.id.btnAdmin).apply {
            isVisible = TokenStore.isAdmin(activity)
            setOnClickListener { activity.startActivity(Intent(activity, AdminActivity::class.java)) }
        }
    }
}
