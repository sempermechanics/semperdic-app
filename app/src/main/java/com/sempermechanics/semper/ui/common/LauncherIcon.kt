package com.sempermechanics.semper.ui.common

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import timber.log.Timber

/**
 * Keeps the launcher icon in step with the system's dark theme: the light icon
 * by day, the inverted S on jet black at night.
 *
 * Launchers never swap an icon's `-night` resources when the theme changes, so
 * the manifest carries two aliases of SplashActivity, [LIGHT] (enabled by
 * default) and [DARK], and this enables the one that fits. It runs at process
 * start and on a configuration change, so a theme change made while Semper is
 * not running shows at its next start.
 */
object LauncherIcon {

    const val LIGHT = "com.sempermechanics.semper.LauncherLight"
    const val DARK = "com.sempermechanics.semper.LauncherDark"

    /** Enables the entry for [night] and disables the other; a no-op when they already match. */
    fun sync(context: Context, night: Boolean = isNight(Resources.getSystem().configuration)) {
        val pm = context.packageManager
        val wanted = if (night) DARK else LIGHT
        val other = if (night) LIGHT else DARK
        try {
            // Enable first, so the app is never without a launcher entry.
            setEnabled(pm, ComponentName(context, wanted), enabled = true)
            setEnabled(pm, ComponentName(context, other), enabled = false)
        } catch (e: SecurityException) {
            Timber.w(e, "Launcher icon not switched")
        } catch (e: IllegalArgumentException) {
            // A manifest without the aliases (a test or a stripped variant).
            Timber.w(e, "Launcher icon not switched")
        }
    }

    fun isNight(config: Configuration): Boolean =
        config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun setEnabled(pm: PackageManager, component: ComponentName, enabled: Boolean) {
        val now = when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> component.className == LIGHT
            else -> false
        }
        if (now == enabled) return
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
}
