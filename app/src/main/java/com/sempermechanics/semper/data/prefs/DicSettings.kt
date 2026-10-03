package com.sempermechanics.semper.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.sempermechanics.semper.data.prefs.PrefFiles.Settings

/**
 * Behavioral settings surfaced in the Home settings drawer. Plain
 * SharedPreferences — read at the point of use, no caching layer.
 *
 * The deformed-frame ceiling comes from cloud config, but this data-layer object
 * does not reach into `data.net` for it: callers pass the remote ceiling in (from
 * `AppRemoteConfig.maxFrames`), keeping the dependency pointing UI/net → data.
 */
@Suppress("TooManyFunctions") // one getter/setter pair per setting; splitting would scatter them
object DicSettings {

    const val DEFAULT_MAX_FRAMES = 50
    const val MIN_MAX_FRAMES = 10

    /**
     * Compile-time stand-in for the deformed-frame ceiling until cloud config
     * is known. The live ceiling is the caller-supplied `remoteMaxFrames`.
     */
    const val MAX_MAX_FRAMES = 500

    /** Bump when a key is retired, and drop it in [migrate]. */
    private const val SCHEMA_VERSION = 1

    /** [autoFreeBudgetGb] value meaning "never free space automatically". */
    const val AUTO_FREE_OFF = 0
    const val MIN_AUTO_FREE_GB = 1
    const val MAX_AUTO_FREE_GB = 64

    private fun prefs(context: Context): SharedPreferences = privatePrefs(context, Settings.NAME)

    /**
     * Drops preferences whose setting no longer exists, so an upgraded device
     * does not carry values nothing reads. Runs once per schema bump.
     */
    fun migrate(context: Context) {
        val prefs = prefs(context)
        if (prefs[Settings.SCHEMA] >= SCHEMA_VERSION) return
        prefs.edit {
            // "Keep every re-run" — the toggle is gone; one row per set of inputs.
            remove(Settings.KEEP_EVERY_RERUN)
            put(Settings.SCHEMA, SCHEMA_VERSION)
        }
    }

    /**
     * Whether the user has agreed to send crash reports and diagnostics.
     *
     * Defaults to **off**, and stays off until [setDiagnosticsEnabled] is called
     * — Crashlytics and Analytics used to collect from first launch with no
     * notice and no way to decline, which is not a defensible position for EU
     * users. [diagnosticsAsked] records that the first-run notice was shown, so
     * it is not shown again after a considered "no".
     */
    fun diagnosticsEnabled(context: Context): Boolean =
        prefs(context)[Settings.DIAGNOSTICS_ENABLED]

    fun setDiagnosticsEnabled(context: Context, value: Boolean) =
        prefs(context).edit {
            put(Settings.DIAGNOSTICS_ENABLED, value)
            put(Settings.DIAGNOSTICS_ASKED, true)
        }

    /** True once the first-run diagnostics choice has been made either way. */
    fun diagnosticsAsked(context: Context): Boolean =
        prefs(context)[Settings.DIAGNOSTICS_ASKED]

    /** Master switch for the upload worker; off = sessions stay "local only". */
    fun saveToCloud(context: Context): Boolean = prefs(context)[Settings.SAVE_TO_CLOUD]

    fun setSaveToCloud(context: Context, value: Boolean) =
        prefs(context).edit { put(Settings.SAVE_TO_CLOUD, value) }

    /**
     * When true, uploads (post-analysis and reconcile repair) wait for unmetered
     * Wi‑Fi. Default false = any connected network.
     */
    fun uploadWifiOnly(context: Context): Boolean = prefs(context)[Settings.UPLOAD_WIFI_ONLY]

    fun setUploadWifiOnly(context: Context, value: Boolean) =
        prefs(context).edit { put(Settings.UPLOAD_WIFI_ONLY, value) }

    /**
     * Hard ceiling for deformed frames: the cloud [remoteMaxFrames] when > 0,
     * else the [MAX_MAX_FRAMES] fallback. Pass `AppRemoteConfig.maxFrames(context)`.
     */
    fun frameCeiling(remoteMaxFrames: Int): Int =
        if (remoteMaxFrames > 0) remoteMaxFrames else MAX_MAX_FRAMES

    /** Cap on deformed frames per analysis (picker + video extraction). */
    fun maxFrames(context: Context, remoteMaxFrames: Int): Int =
        clampMaxFrames(prefs(context)[Settings.MAX_FRAMES], remoteMaxFrames)

    fun setMaxFrames(context: Context, value: Int, remoteMaxFrames: Int) = prefs(context).edit {
        put(Settings.MAX_FRAMES, clampMaxFrames(value, remoteMaxFrames))
    }

    /**
     * Gigabyte ceiling for local analysis storage, past which backed-up sessions
     * give up their local files (they re-download on open). [AUTO_FREE_OFF] means
     * nothing is ever removed without the user asking — the default, since a
     * session that vanishes on its own is a worse surprise than a full disk.
     */
    fun autoFreeBudgetGb(context: Context): Int = clampAutoFree(prefs(context)[Settings.AUTO_FREE_GB])

    fun setAutoFreeBudgetGb(context: Context, value: Int) = prefs(context).edit {
        put(Settings.AUTO_FREE_GB, clampAutoFree(value))
    }

    private fun clampMaxFrames(value: Int, remoteMaxFrames: Int): Int =
        value.coerceIn(MIN_MAX_FRAMES, frameCeiling(remoteMaxFrames))

    /** [AUTO_FREE_OFF] for zero or less, else a budget in range. */
    private fun clampAutoFree(gb: Int): Int =
        if (gb <= AUTO_FREE_OFF) AUTO_FREE_OFF else gb.coerceIn(MIN_AUTO_FREE_GB, MAX_AUTO_FREE_GB)
}
