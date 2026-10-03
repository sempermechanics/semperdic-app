package com.sempermechanics.semper.diagnostics

import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.SemperNativeLib
import java.io.File

/**
 * Gate for the engine's debug export.
 *
 * Given a non-empty output directory the native pipeline writes roughly ten
 * full-ROI diagnostic images plus two CSVs *per frame* (see
 * `engine/src/pipeline/full_field_debug_export.cpp`, whose own header states
 * that production leaves the directory empty). Release builds therefore pass
 * null so the engine skips the export entirely — it is a dev tool, and on a
 * long batch it was the single largest consumer of cache storage.
 */
object EngineDebug {

    /** Cache subdirectory the debug suite is written into (debug builds only). */
    const val DIR_NAME = "dic_debug"

    /** The debug output directory in debug builds, null in release. */
    fun dirFor(cacheDir: File): File? =
        if (BuildConfig.DEBUG) File(cacheDir, DIR_NAME).apply { mkdirs() } else null

    /** Points the engine at [dir], or turns the export off when it is null. */
    fun attach(dir: File?) {
        if (dir == null) {
            SemperNativeLib.setDebugOutputDir(null)
            return
        }
        if (!dir.exists()) dir.mkdirs()
        SemperNativeLib.setDebugOutputDir(dir.absolutePath)
    }
}
