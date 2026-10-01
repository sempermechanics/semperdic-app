// Builds a master ZIP of per-session Everything archives for Settings export.

@file:Suppress("TooGenericExceptionCaught")

package com.indicvision.semper.data.session

import android.content.Context
import com.indicvision.semper.data.cloud.SessionUploadBundler
import com.indicvision.semper.util.AtomicFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * GDPR-style local export: one Everything-style ZIP per local session, nested in
 * a single master ZIP the user can share or save.
 */
object SessionEverythingExporter {

    data class Result(val file: File, val sessionCount: Int)

    /**
     * Packages every local session that still has on-device frame data.
     * Returns null when nothing can be exported or writing fails.
     *
     * [onProgress] gets how many sessions are finished: 0 up front, then once
     * after each session is in the archive, so the last tick is the finished
     * archive rather than the start of its last session.
     */
    suspend fun exportMasterZip(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val sessions = SessionStore.list(app).filter { it.hasLocalData() }
        if (sessions.isEmpty()) return@withContext null

        val outDir = CacheJanitor.shareDir(app.cacheDir)
        outDir.listFiles()
            ?.filter { it.name.startsWith(MASTER_PREFIX) || it.name.startsWith(SESSION_PREFIX) }
            ?.forEach { it.delete() }

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val master = File(outDir, "${MASTER_PREFIX}$ts.zip")
        val stagingRoot = File(outDir, "export_staging_$ts").apply { mkdirs() }

        try {
            ZipOutputStream(master.outputStream().buffered()).use { masterZip ->
                onProgress(0, sessions.size)
                sessions.forEachIndexed { index, record ->
                    // The copy below is blocking IO, so cancellation (the
                    // banner's Cancel) is only seen here, between sessions.
                    ensureActive()
                    buildSessionEverythingZip(app, record, stagingRoot, index, ts)?.let { sessionZip ->
                        val entryName = sanitizeZipName(record.name, record.id) + ".zip"
                        masterZip.putNextEntry(ZipEntry(entryName))
                        sessionZip.inputStream().use { it.copyTo(masterZip) }
                        masterZip.closeEntry()
                        sessionZip.delete()
                    }
                    onProgress(index + 1, sessions.size)
                }
            }
            if (master.length() == 0L) {
                master.delete()
                return@withContext null
            }
            Result(master, sessions.size)
        } catch (e: CancellationException) {
            master.delete()
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Master session export failed")
            master.delete()
            null
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    /**
     * Packages one on-device session into a shareable ZIP without changing the
     * session directory. Used when Settings Download should save to Files and
     * the cloud backup has no Session.zip (legacy per-file uploads).
     */
    suspend fun exportSessionZip(
        context: Context,
        record: SessionRecord,
    ): File? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val outDir = CacheJanitor.shareDir(app.cacheDir)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val stagingRoot = File(outDir, "export_one_$ts").apply { mkdirs() }
        try {
            buildSessionEverythingZip(app, record, stagingRoot, 0, ts)?.also { built ->
                val named = File(outDir, sanitizeZipName(record.name, record.id) + ".zip")
                named.delete()
                AtomicFiles.promote(built, named)
                return@withContext named.takeIf { it.exists() && it.length() > 0L }
            }
            null
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    /**
     * One session archive: raw photos, analysis CSV, per-frame reports / field
     * maps (via [SessionUploadBundler]), plus metadata.
     */
    private suspend fun buildSessionEverythingZip(
        context: Context,
        record: SessionRecord,
        stagingRoot: File,
        index: Int,
        ts: String,
    ): File? {
        val sessionDir = File(record.sessionDir)
        if (!sessionDir.isDirectory) return null
        val work = File(stagingRoot, "session_$index").apply {
            deleteRecursively()
            mkdirs()
        }
        val refFile = File(record.refPath).takeIf { it.exists() }
            ?: File(sessionDir, "reference.png")
        val rawDeformedDir = SessionStore.rawDeformedDir(sessionDir)
        val csvFile = File(work, "analysis_data.csv")
        stageReports(context, record, refFile, work, csvFile)

        val zip = File(stagingRoot, "${SESSION_PREFIX}${record.id}.zip")
        return try {
            ZipOutputStream(zip.outputStream().buffered()).use { zipOut ->
                writeSessionEntries(zipOut, sessionDir, work, refFile, rawDeformedDir, csvFile, ts)
            }
            zip.takeIf { it.length() > 0L }
        } catch (e: CancellationException) {
            zip.delete()
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Session zip failed for %s", record.id)
            zip.delete()
            null
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Renders the CSV and per-frame reports into [work]. Failure is not fatal:
     * the archive still gets the raw photos and .dat frames, so the caller does
     * not check the outcome.
     */
    private suspend fun stageReports(
        context: Context,
        record: SessionRecord,
        refFile: File,
        work: File,
        csvFile: File,
    ) {
        val sessionDir = File(record.sessionDir)
        try {
            SessionUploadBundler.stageCsvAndBundles(
                context = context,
                record = record,
                sessionDir = sessionDir,
                refFile = refFile,
                rawDeformedDir = SessionStore.rawDeformedDir(sessionDir),
                stagingDir = work,
                csvFile = csvFile,
                writeReports = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Report staging failed for %s — packing raw session files", record.id)
        }
    }

    @Suppress("LongParameterList") // one archive layout, described by its pieces
    private fun writeSessionEntries(
        zipOut: ZipOutputStream,
        sessionDir: File,
        work: File,
        refFile: File,
        rawDeformedDir: File,
        csvFile: File,
        ts: String,
    ) {
        putFile(zipOut, "metadata.json", File(sessionDir, "metadata.json"))
        val rawPrefix = "photos_$ts/raw photos"
        putFile(zipOut, "$rawPrefix/reference_${refFile.name}", refFile)
        rawDeformedDir.listFiles()?.forEach { f -> putFile(zipOut, "$rawPrefix/${f.name}", f) }
        putFile(zipOut, "analysis_data.csv", csvFile)
        File(work, "reports").listFiles()?.forEach { f -> putFile(zipOut, "reports/${f.name}", f) }
        val processed = File(work, "processed")
        processed.walkTopDown().filter { it.isFile }.forEach { f ->
            putFile(zipOut, "photos_$ts/results/${f.relativeTo(processed).invariantSeparatorsPath}", f)
        }
        // Include .dat frames so the export is self-contained for re-analysis tooling.
        sessionDir.listFiles { f -> f.extension == "dat" }?.forEach { f ->
            putFile(zipOut, "dat/${f.name}", f)
        }
    }

    private fun putFile(zip: ZipOutputStream, entryName: String, file: File) {
        if (!file.exists() || !file.isFile) return
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /** Internal for test: entry names must stay filesystem- and archive-safe. */
    internal fun sanitizeZipName(name: String, id: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        return cleaned.ifBlank { "session" }.take(MAX_NAME_CHARS) + "_" + id.take(ID_CHARS)
    }

    private const val MASTER_PREFIX = "Semper_sessions_export_"
    private const val SESSION_PREFIX = "Semper_session_"

    /** Entry names stay readable and well clear of any archive path limit. */
    private const val MAX_NAME_CHARS = 40
    private const val ID_CHARS = 8
}
