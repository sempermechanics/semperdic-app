@file:Suppress("TooGenericExceptionCaught") // corrupt/OOM decode must never abort session save

package com.indicvision.semper.data.session

import android.content.Context
import android.graphics.Bitmap
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.imaging.ImageEncode
import com.indicvision.semper.imaging.RawRgba
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.report.VisualizationEngine
import com.indicvision.semper.util.AtomicFiles
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Session on-disk persistence helpers extracted from [com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel].
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
        val refPngFile = File(sessionDir, "reference.png")
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
        val name = (defOriginalNames.getOrNull(frameIndex) ?: source.name)
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

    fun defaultSessionName(refFileName: String, now: Long): String {
        val base = refFileName.substringBeforeLast('.').ifBlank { "Analysis" }
        val stamp = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(now))
        return "$base · $stamp"
    }

    @Suppress("LongParameterList")
    fun buildSessionRecord(
        appContext: Context,
        localSessionId: String,
        batchDir: File,
        refPngPath: String,
        refName: String,
        realRefWidth: Int,
        realRefHeight: Int,
        settings: SessionRecordSettings,
        cloudEnabled: Boolean,
        pointsConverged: Int,
        avgIterations: Float,
        executionTimeMs: Int,
        frameCount: Int,
        defNames: List<String>,
        engineStatsArray: FloatArray?,
        stopCode: Int = 0,
        plannedFrameCount: Int = 0,
    ): SessionRecord {
        val now = System.currentTimeMillis()
        val existing = SessionStore.get(appContext, localSessionId)
        val createdAt = existing?.createdAt ?: now
        val convergence = engineStatsArray?.getOrNull(EngineStats.SLOT_CONVERGENCE) ?: 0f
        // Regenerate the auto-name for THIS run's kind (single here), keyed to the
        // original createdAt so re-runs don't churn the timestamp — but never
        // override a name the user set themselves.
        val autoName = if (existing?.renamedByUser == true) {
            existing.name
        } else {
            defaultSessionName(refName, createdAt)
        }
        return SessionRecord(
            id = localSessionId,
            name = autoName,
            createdAt = createdAt,
            renamedByUser = existing?.renamedByUser ?: false,
            updatedAt = now,
            frameCount = frameCount,
            subset = settings.subset,
            step = settings.step,
            strainWindow = settings.strainWin,
            use6x6 = settings.use6x6,
            imgW = realRefWidth,
            imgH = realRefHeight,
            roiX = settings.roiX,
            roiY = settings.roiY,
            roiW = settings.roiW,
            roiH = settings.roiH,
            refPath = refPngPath,
            refName = refName,
            sessionDir = batchDir.absolutePath,
            defNames = defNames,
            headline = SessionHeadline.firstFrameConvergence(convergence, defNames.size),
            engineStats = engineStatsArray?.toList() ?: emptyList(),
            stopCode = stopCode,
            plannedFrameCount = plannedFrameCount,
            strainMethod = "VSG",
            pointsConverged = pointsConverged,
            avgIterations = avgIterations,
            executionTimeMs = executionTimeMs,
            syncState = if (cloudEnabled) SessionRecord.SyncState.PENDING else SessionRecord.SyncState.LOCAL_ONLY,
        )
    }

    /**
     * Persist [record] via [SessionStore.upsert] and optionally enqueue a cloud
     * upload. Returns the upsert result (false = quota refuse / corrupt index).
     */
    fun saveSession(
        context: Context,
        record: SessionRecord,
        enqueueCloudIfSaved: Boolean = false,
    ): Boolean {
        val saved = SessionStore.upsert(context, record)
        if (saved && enqueueCloudIfSaved) {
            CloudSync.enqueueUpload(context, record.id)
        }
        return saved
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
