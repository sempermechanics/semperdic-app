@file:Suppress("TooGenericExceptionCaught") // corrupt/OOM decode must never abort session save

package com.sempermechanics.semper.data.session

import android.content.Context
import android.graphics.Bitmap
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.imaging.BitmapDecode
import com.sempermechanics.semper.imaging.ImageEncode
import com.sempermechanics.semper.imaging.RawRgba
import com.sempermechanics.semper.report.EngineStats
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.util.AtomicFiles
import timber.log.Timber
import java.io.File

/**
 * Session on-disk persistence helpers extracted from [com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel].
 * Keeps file/index bookkeeping off the ViewModel surface.
 */
class SessionRepository {

    /** Writes a display-sized PNG of the reference into the session dir. */
    fun writeReferenceCopy(
        sessionDir: File,
        refBytes: ByteArray,
        width: Int = 0,
        height: Int = 0,
    ): String {
        val refPngFile = SessionLayout(sessionDir).referencePng
        var refBmp: Bitmap? = null
        try {
            // DNG/RAW imports are stored as headerless RGBA. OpenCV and
            // BitmapFactory cannot read them — sample into a real PNG so the
            // viewer / Home thumb / share path can decode normally.
            refBmp = if (width > 0 &&
                height > 0 &&
                RawRgba.matches(refBytes.size.toLong(), width, height)
            ) {
                RawRgba.preview(refBytes, width, height, VisualizationEngine.DISPLAY_MAX_EDGE)
            } else {
                SemperNativeLib.getPreviewFromBytes(
                    refBytes,
                    VisualizationEngine.DISPLAY_MAX_EDGE,
                ) ?: BitmapDecode.decodeByteArrayCapped(refBytes)
            }
            val bmp = refBmp
            if (bmp != null) {
                refPngFile.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, out)
                }
            } else {
                // Keep original bytes for restore/upload, but log when they are not
                // a real PNG — Home thumbs sniff headers and skip BitmapFactory.
                if (!BitmapDecode.looksLikePlatformRaster(refBytes)) {
                    Timber.w(
                        "Reference preview unavailable; storing non-PNG source bytes as %s",
                        refPngFile.name,
                    )
                }
                refPngFile.writeBytes(refBytes)
            }
        } catch (e: Exception) {
            Timber.w(e, "Reference preview failed; storing the raw reference bytes")
            runCatching { refPngFile.writeBytes(refBytes) }
        } finally {
            refBmp?.recycle()
        }
        return refPngFile.absolutePath
    }

    /**
     * Moves one deformed original into the session dir; returns its file name.
     *
     * A sweep varies settings, not images, so it keeps exactly one — anything
     * else under `raw_deformed/` is left over from an earlier run. The source
     * itself may already be in there (a re-run, since runs move their images in
     * rather than copying), so it is resolved before the directory is pruned.
     */
    fun persistRawDeformed(
        batchDir: File,
        frameIndex: Int,
        defFilePaths: List<String>,
        defOriginalNames: List<String>,
    ): String {
        val rawDir = File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).apply { mkdirs() }
        val source = File(defFilePaths[frameIndex])
        if (source.parentFile?.absolutePath == rawDir.absolutePath) {
            rawDir.listFiles()?.forEach { if (it.name != source.name) it.delete() }
            return source.name
        }
        val name = defOriginalNames.originalNameOr(frameIndex, source.name)
            .substringAfterLast('/')
            .substringAfterLast('\\')
        return runCatching {
            rawDir.listFiles()?.forEach { it.delete() }
            val target = File(rawDir, name)
            // Both directories are app-private storage, so this is a rename
            // rather than a second multi-megabyte write; the copy is the
            // fallback for the rare cross-volume case.
            AtomicFiles.promote(source, target)
            target.name
        }.onFailure { Timber.w(it, "Could not persist the sweep's deformed frame") }.getOrDefault("")
    }

    /**
     * The index row for a run saved into [input]'s session: [outcome]'s frames
     * and metrics under [input]'s settings, PENDING upload when [cloudEnabled].
     * A re-run keeps the row's creation time, and the user's name if they gave
     * one; otherwise the auto-name is regenerated for this run.
     */
    fun buildSessionRecord(
        appContext: Context,
        input: RunInput,
        outcome: RunOutcome,
        cloudEnabled: Boolean,
    ): SessionRecord {
        val now = System.currentTimeMillis()
        val existing = SessionStore.get(appContext, input.localSessionId)
        val createdAt = existing?.createdAt ?: now
        val settings = input.settings
        val metrics = outcome.metrics
        val convergence = metrics.engineStats.getOrNull(EngineStats.SLOT_CONVERGENCE) ?: 0f
        // Regenerate the auto-name for THIS run's kind (single here), keyed to the
        // original createdAt so re-runs don't churn the timestamp — but never
        // override a name the user set themselves.
        val autoName = if (existing?.renamedByUser == true) {
            existing.name
        } else {
            SessionNaming.defaultSessionName(input.reference.name, createdAt)
        }
        return SessionRecord(
            id = input.localSessionId,
            name = autoName,
            createdAt = createdAt,
            renamedByUser = existing?.renamedByUser ?: false,
            updatedAt = now,
            frameCount = outcome.frameCount,
            subset = settings.subset,
            step = settings.step,
            strainWindow = settings.strainWin,
            use6x6 = settings.use6x6,
            imgW = input.reference.size.width,
            imgH = input.reference.size.height,
            roiX = settings.roiX,
            roiY = settings.roiY,
            roiW = settings.roiW,
            roiH = settings.roiH,
            refPath = input.reference.pngPath,
            refName = input.reference.name,
            sessionDir = input.dir.absolutePath,
            defNames = outcome.defNames,
            headline = SessionHeadline.firstFrameConvergence(convergence, outcome.defNames.size),
            engineStats = metrics.engineStats,
            stopCode = outcome.stopCode,
            plannedFrameCount = outcome.plannedFrameCount,
            strainMethod = "VSG",
            pointsConverged = metrics.pointsConverged,
            avgIterations = metrics.avgIterations,
            executionTimeMs = metrics.executionTimeMs,
            syncState = if (cloudEnabled) SessionRecord.SyncState.PENDING else SessionRecord.SyncState.LOCAL_ONLY,
        )
    }
}

/** Geometry + subset settings captured into a [SessionRecord]. */
data class SessionRecordSettings(
    val subset: Int,
    val step: Int,
    val strainWin: Int,
    val roiX: Int,
    val roiY: Int,
    val roiW: Int,
    val roiH: Int,
    val use6x6: Boolean,
)

/** The reference a run is saved with: its display copy, its original file name and its real size. */
data class RunReference(val pngPath: String, val name: String, val size: ImageSize)

/** Where a run is saved and what it ran on: the session's id and directory, its reference, its settings. */
data class RunInput(
    val localSessionId: String,
    val dir: File,
    val reference: RunReference,
    val settings: SessionRecordSettings,
)

/** The first frame's engine metrics and the run's time, as the session row keeps them. */
data class RunMetrics(
    val pointsConverged: Int,
    val avgIterations: Float,
    val executionTimeMs: Int,
    /** The first frame's telemetry slots ([EngineStats]). */
    val engineStats: List<Float>,
)

/** What a run produced: how many frames it solved, of which deformed images, and why it stopped. */
data class RunOutcome(
    val frameCount: Int,
    val defNames: List<String>,
    val metrics: RunMetrics,
    /** The run's stop code; 0 when it ran to completion. */
    val stopCode: Int = 0,
    val plannedFrameCount: Int = 0,
)
