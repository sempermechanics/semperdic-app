package com.indicvision.semper.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

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

    /**
     * Frames per analysis out of the box.
     *
     * Sized against the capture side rather than the picker: the rate ladder
     * stops at 1 fps, where a run needs one frame per second, so this default
     * is what decides the longest run the app can offer at its slowest rate.
     * At 50 anything over 50 s had no offerable rate at all, which read as the
     * app refusing a perfectly ordinary plan.
     */
    const val DEFAULT_MAX_FRAMES = 150
    const val MIN_MAX_FRAMES = 10

    /**
     * Compile-time stand-in for the deformed-frame ceiling until cloud config
     * is known. The live ceiling is the caller-supplied `remoteMaxFrames`.
     */
    const val MAX_MAX_FRAMES = 500

    private const val PREFS = "dic_settings"
    private const val KEY_SCHEMA = "schema"

    /** Bump when a key is retired, and drop it in [migrate]. */
    private const val SCHEMA_VERSION = 1
    private const val KEY_SAVE_TO_CLOUD = "save_to_cloud"
    private const val KEY_UPLOAD_WIFI_ONLY = "upload_wifi_only"
    private const val KEY_MAX_FRAMES = "max_frames"
    private const val KEY_AUTO_FREE_GB = "auto_free_gb"
    private const val KEY_DIAGNOSTICS = "diagnostics_enabled"
    private const val KEY_DIAGNOSTICS_ASKED = "diagnostics_asked"

    /** [autoFreeBudgetGb] value meaning "never free space automatically". */
    const val AUTO_FREE_OFF = 0
    const val MIN_AUTO_FREE_GB = 1
    const val MAX_AUTO_FREE_GB = 64

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Drops preferences whose setting no longer exists, so an upgraded device
     * does not carry values nothing reads. Runs once per schema bump.
     */
    fun migrate(context: Context) {
        val prefs = prefs(context)
        if (prefs.getInt(KEY_SCHEMA, 0) >= SCHEMA_VERSION) return
        prefs.edit {
            // "Keep every re-run" — the toggle is gone; one row per set of inputs.
            remove("keep_every_rerun")
            putInt(KEY_SCHEMA, SCHEMA_VERSION)
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
        prefs(context).getBoolean(KEY_DIAGNOSTICS, false)

    fun setDiagnosticsEnabled(context: Context, value: Boolean) =
        prefs(context).edit {
            putBoolean(KEY_DIAGNOSTICS, value)
            putBoolean(KEY_DIAGNOSTICS_ASKED, true)
        }

    /** True once the first-run diagnostics choice has been made either way. */
    fun diagnosticsAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DIAGNOSTICS_ASKED, false)

    /** Master switch for the upload worker; off = sessions stay "local only". */
    fun saveToCloud(context: Context): Boolean = prefs(context).getBoolean(KEY_SAVE_TO_CLOUD, true)

    fun setSaveToCloud(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean(KEY_SAVE_TO_CLOUD, value) }

    /**
     * When true, uploads (post-analysis and reconcile repair) wait for unmetered
     * Wi‑Fi. Default false = any connected network.
     */
    fun uploadWifiOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_UPLOAD_WIFI_ONLY, false)

    fun setUploadWifiOnly(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean(KEY_UPLOAD_WIFI_ONLY, value) }

    /**
     * Hard ceiling for deformed frames: the cloud [remoteMaxFrames] when > 0,
     * else the [MAX_MAX_FRAMES] fallback. Pass `AppRemoteConfig.maxFrames(context)`.
     */
    fun frameCeiling(remoteMaxFrames: Int): Int =
        if (remoteMaxFrames > 0) remoteMaxFrames else MAX_MAX_FRAMES

    /** Cap on deformed frames per analysis (picker + video extraction). */
    fun maxFrames(context: Context, remoteMaxFrames: Int): Int =
        prefs(context).getInt(KEY_MAX_FRAMES, DEFAULT_MAX_FRAMES)
            .coerceIn(MIN_MAX_FRAMES, frameCeiling(remoteMaxFrames))

    fun setMaxFrames(context: Context, value: Int, remoteMaxFrames: Int) = prefs(context).edit {
        putInt(KEY_MAX_FRAMES, value.coerceIn(MIN_MAX_FRAMES, frameCeiling(remoteMaxFrames)))
    }

    /**
     * Gigabyte ceiling for local analysis storage, past which backed-up sessions
     * give up their local files (they re-download on open). [AUTO_FREE_OFF] means
     * nothing is ever removed without the user asking — the default, since a
     * session that vanishes on its own is a worse surprise than a full disk.
     */
    fun autoFreeBudgetGb(context: Context): Int =
        prefs(context).getInt(KEY_AUTO_FREE_GB, AUTO_FREE_OFF)
            .let { if (it <= AUTO_FREE_OFF) AUTO_FREE_OFF else it.coerceIn(MIN_AUTO_FREE_GB, MAX_AUTO_FREE_GB) }

    fun setAutoFreeBudgetGb(context: Context, value: Int) = prefs(context).edit {
        putInt(
            KEY_AUTO_FREE_GB,
            if (value <= AUTO_FREE_OFF) AUTO_FREE_OFF else value.coerceIn(MIN_AUTO_FREE_GB, MAX_AUTO_FREE_GB),
        )
    }
}
