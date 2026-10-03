package com.sempermechanics.semper.data

import android.content.Context
import com.sempermechanics.semper.data.cloud.SessionUploadBundler
import com.sempermechanics.semper.data.cloud.SessionUploadMetadata
import com.sempermechanics.semper.data.cloud.TransferPhase
import com.sempermechanics.semper.data.cloud.UploadProgressSampler
import com.sempermechanics.semper.data.cloud.UploadWorkOutcomes
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.ArtifactRoles
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionZip
import com.sempermechanics.semper.data.session.StagingLayout
import timber.log.Timber
import java.io.File

/** One file the upload declares and sends: its role and name in the backup, and where it is. */
internal data class UploadArtifact(
    val role: String,
    val name: String,
    val file: File,
    /** Precomputed digest when available (e.g. hash-while-zip); else hashed on demand. */
    val sha256Hex: String? = null,
)

/** What staging an analysis for upload concluded. */
internal sealed interface StagingResult {
    /** Upload these: `metadata.json` and the archives (or every file, when there is no payload). */
    data class Ready(val files: List<UploadArtifact>) : StagingResult

    /** The report bundle is not ready yet; end this run with a retry. */
    data class Retry(val reason: String) : StagingResult

    /** The bundle's inputs are gone for good: fail the backup ([UploadFailures.inputsGone]). */
    data object InputsGone : StagingResult
}

/**
 * Stages one analysis for upload in its PERSISTENT staging dir
 * (`<session>/upload_staging/`), not cache.
 *
 * The staged files must be byte-identical across a resumed upload: the cloud
 * session declared each file's size/sha256, and a regenerated zip (new PDF
 * dates, new zip timestamps) would no longer match, so Drive's resumable URI and
 * the completeFile size check would never reconcile. Generating once and reusing
 * also skips the expensive report/zip work on every retry.
 *
 * Archives are split by what a restore actually needs: `Session.zip` holds only
 * raw/ and dat/ — everything needed to rebuild a working session — while the
 * derived deliverables (csv/, reports/, processed/) go to `Extras.zip`. Nothing
 * reads those back after a restore; they are regenerated on export.
 */
internal class UploadStaging(private val context: Context, private val record: SessionRecord) {

    private val session = SessionLayout(File(record.sessionDir))

    val layout: StagingLayout = session.staging

    /** Whether a finished staging is reused as it is; [prepare] decides. */
    var reuse: Boolean = false
        private set

    /**
     * Ready the staging dir for this run, and decide [reuse].
     *
     * Only incomplete staging is wiped. A blank cloudSessionId after Rebuild /
     * provision failure must NOT destroy a finished Session.zip — that was
     * forcing a full prepare loop on every WorkManager retry.
     */
    fun prepare() {
        if (record.cloudSessionId.isBlank() && !UploadWorkOutcomes.stagingReusable(layout.dir)) {
            layout.dir.deleteRecursively()
        }
        layout.dir.mkdirs()
        reuse = UploadWorkOutcomes.stagingReusable(layout.dir)
    }

    /**
     * Stage everything: `metadata.json`, the reference and each frame's original
     * and `.dat`, the CSV and report bundle, then the two archives. [progress]
     * follows the report pass by frame and the archives by source byte.
     */
    suspend fun stage(progress: UploadProgressSampler): StagingResult {
        val sources = sourceArtifacts()
        val incomplete = stageReports(progress)
        if (incomplete != null) return incomplete
        return StagingResult.Ready(archive(sources + derivedArtifacts(), progress))
    }

    /** Session-level metadata (generated once, then reused), the reference, and every frame's files. */
    private fun sourceArtifacts(): List<UploadArtifact> {
        val metaFile = layout.metadataJson
        UploadWorkOutcomes.stageMetadataJson(metaFile) {
            SessionUploadMetadata.buildMetadataJson(record, context)
        }
        val artifacts = mutableListOf(UploadArtifact(ArtifactRoles.METADATA, SessionLayout.METADATA_JSON, metaFile))

        // The reference image is already stable on disk.
        val refFile = File(record.refPath)
        if (refFile.exists() && refFile.length() > 0) {
            artifacts += UploadArtifact(ArtifactRoles.RAW, SessionZip.REFERENCE_NAME, refFile)
        }

        // A sweep repeats the one image it ran on across every frame, so the
        // raw image is bundled once — a second identical `raw/<name>` entry
        // would make Session.zip throw a duplicate-entry exception.
        val addedRaw = HashSet<String>()
        record.defNames.forEachIndexed { index, defName -> artifacts += stageFrame(index, defName, addedRaw) }
        return artifacts
    }

    /**
     * Frame [index]'s original image (once per name, see [addedRaw]) and its
     * `.dat`, each when it is on disk. The `.dat` is bundled so a restored
     * session is fully viewable in the app (the heatmap viewer reads it); it
     * also feeds the CSV and reports.
     */
    private fun stageFrame(index: Int, defName: String, addedRaw: MutableSet<String>): List<UploadArtifact> {
        val frameName = "Frame_${index + 1}"
        val original = session.rawDeformed(defName)
        val raw = if (original.exists() && original.length() > 0) {
            UploadArtifact(ArtifactRoles.RAW, defName, original).takeIf { addedRaw.add(defName) }
        } else {
            Timber.w("Deformed image missing for %s", frameName)
            null
        }
        val datFile = session.frameDat(index)
        val dat = if (datFile.exists()) {
            UploadArtifact(ArtifactRoles.DAT, datFile.name, datFile)
        } else {
            Timber.w("No .dat for %s (%s)", frameName, datFile.name)
            null
        }
        return listOfNotNull(raw, dat)
    }

    /**
     * The combined CSV and per-frame reports/heatmaps, in ONE `.dat` decode pass
     * (the same writer the share/export uses for CSV; Session.zip compresses the
     * staged plain files, so no nested archives). Null when they are ready.
     *
     * `.bundles_done` is written only after a COMPLETE pass: a dir half-filled
     * by a killed run, or a pass that skipped every PDF/heatmap, must not be
     * mistaken for done.
     */
    private suspend fun stageReports(progress: UploadProgressSampler): StagingResult? {
        val needCsv = !layout.analysisCsv.exists() || layout.analysisCsv.length() == 0L
        val needBundles = record.defNames.isNotEmpty() && !UploadWorkOutcomes.bundleArtifactsReady(layout.dir)
        if (!needCsv && !needBundles) return null

        // Restaging invalidates any prior Session.zip — it was built without the
        // artifacts we are about to (re)generate.
        layout.staleFiles(StagingLayout.SESSION_ZIP).forEach { it.delete() }
        if (needBundles) layout.bundlesDone.delete()
        SessionUploadBundler.stageCsvAndBundles(
            context,
            record,
            session.dir,
            File(record.refPath),
            session.rawDeformedDir,
            layout.dir,
            csvFile = if (needCsv) layout.analysisCsv else null,
            writeReports = needBundles,
            onFrame = { d, t ->
                progress.done.set(d.toLong())
                progress.total.set(t.toLong())
            },
        )
        return when {
            record.defNames.isEmpty() -> null
            UploadWorkOutcomes.bundleArtifactsReady(layout.dir) -> {
                layout.bundlesDone.createNewFile()
                null
            }
            needBundles -> incompleteBundles()
            else -> null
        }
    }

    /**
     * Do not upload a raw+dat-only zip as "synced". Sweeps hit this when report
     * bake skips (bad dims / undecodable base image / every .dat missing). Retry
     * while a later pass can still succeed; once the inputs are gone for good,
     * fail so Home shows why instead of "pending" forever.
     */
    private fun incompleteBundles(): StagingResult {
        Timber.e("Bundle staging incomplete (csv=%dB, reports/processed missing)", layout.analysisCsv.length())
        return if (inputsGoneForGood()) {
            StagingResult.InputsGone
        } else {
            StagingResult.Retry("bundle staging incomplete — reports/csv/processed not ready")
        }
    }

    /**
     * After a prepare pass left the report bundle incomplete: whether the
     * record's inputs are gone for good
     * ([UploadWorkOutcomes.classifyIncompleteStaging]); false means retry.
     */
    private fun inputsGoneForGood(): Boolean {
        val sessionDir = session.dir
        val now = System.currentTimeMillis()
        val onDisk = UploadWorkOutcomes.stagingInputsOnDisk(sessionDir, record.defNames.size, File(record.refPath))
        val verdict = UploadWorkOutcomes.classifyIncompleteStaging(
            inputsOnDisk = onDisk,
            sessionAgeMs = now - record.updatedAt,
            missingForMs = UploadWorkOutcomes.inputsMissingForMs(sessionDir, onDisk, record.updatedAt, now),
        )
        if (verdict == UploadWorkOutcomes.IncompleteStaging.RETRY) return false
        Timber.e("Session files missing on disk — failing backup (no retry loop)")
        return true
    }

    /** The CSV, the frame reports and the heatmaps the report pass left in the staging. */
    private fun derivedArtifacts(): List<UploadArtifact> {
        val artifacts = mutableListOf<UploadArtifact>()
        val csv = layout.analysisCsv
        if (csv.length() > 0) artifacts += UploadArtifact(ArtifactRoles.CSV, StagingLayout.ANALYSIS_CSV, csv)
        if (record.defNames.isEmpty()) {
            Timber.e("Skipping reports — no frames in the record")
            return artifacts
        }
        val pdfs = layout.reportsDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            .orEmpty()
        pdfs.forEach { artifacts += UploadArtifact(ArtifactRoles.REPORTS, it.name, it) }
        // Heatmaps sit in per-frame subfolders; keep the "<frame>/<field>.png"
        // relative path as the artifact name, so the bundle entry becomes
        // processed/<frame>/<field>.png.
        val processedDir = layout.processedDir
        val pngs = processedDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
        pngs.forEach {
            val name = it.relativeTo(processedDir).invariantSeparatorsPath
            artifacts += UploadArtifact(ArtifactRoles.PROCESSED, name, it)
        }
        if (pdfs.isEmpty()) Timber.e("No frame reports generated during bundle staging")
        if (pngs.isEmpty()) Timber.e("No processed heatmaps generated during bundle staging")
        return artifacts
    }

    /**
     * The files to declare: `metadata.json` plus `Session.zip` and `Extras.zip`
     * packed from the rest ([SessionZip.isRestoreEssential] splits them). With no
     * payload at all, [artifacts] as they are.
     *
     * Firestore prices the whole flow per file (a doc, a signed complete call, a
     * challenge/nonce cycle each), so 3F+4 files per analysis was burning the
     * daily read quota in a single upload. Two zips + the metadata blueprint keeps
     * that at 3 files, and Drive resumable uploads resume a single large file
     * mid-byte, so recovery still works.
     */
    private fun archive(
        artifacts: List<UploadArtifact>,
        progress: UploadProgressSampler,
    ): List<UploadArtifact> {
        val payload = artifacts.filter { it.role != ArtifactRoles.METADATA }
        if (payload.isEmpty()) return artifacts.toList()
        // Zip dominates prepare on heavy PLC; drive the badge by source bytes
        // across BOTH archives so it does not restart at 0%.
        progress.begin(TransferPhase.PREPARE, payload.sumOf { it.file.length().coerceAtLeast(1L) }.coerceAtLeast(1L))
        val onZipBytes: (Long) -> Unit = { n -> progress.done.addAndGet(n) }
        val (essential, derived) = payload.partition { SessionZip.isRestoreEssential(it.role) }
        val restoreZip = stageArchive(StagingLayout.SESSION_ZIP, essential, onZipBytes)
        val extrasZip = stageArchive(StagingLayout.EXTRAS_ZIP, derived, onZipBytes)
        return artifacts.filter { it.role == ArtifactRoles.METADATA } +
            listOfNotNull(
                restoreZip?.let { UploadArtifact(ArtifactRoles.BUNDLE, StagingLayout.SESSION_ZIP, it.file, it.sha256) },
                extrasZip?.let { UploadArtifact(ArtifactRoles.EXTRAS, StagingLayout.EXTRAS_ZIP, it.file, it.sha256) },
            )
    }

    /** A staged archive and the sha256 declared for it. */
    private data class StagedArchive(val file: File, val sha256: String)

    /**
     * Build (or reuse) one archive named [zipName] from [members], entries named
     * `role/name` (`raw/Reference.png`, `dat/frame_0000.dat`, …) so restore can
     * rebuild the exact per-role layout. Built once into the persistent staging
     * dir and reused byte-identically on retries — zip entry timestamps differ
     * across rebuilds, which would break the declared sha256/size of a resumed
     * upload. The SHA-256 comes from [SessionZip], which tees a digest while
     * writing and round-trip-verifies every entry before promote.
     *
     * Returns null when [members] is empty — a session with no derived artifacts
     * must not declare an empty Extras.zip, both because [SessionZip.build] rejects
     * an empty payload and because an empty object would cost a Firestore doc and a
     * signed upload for nothing.
     *
     * Reuses only a sidecar-verified archive (see `UploadWorkOutcomes.stagingReusable`).
     * A sidecar is never invented from a leftover truncated zip — doing so uploaded
     * bit-identical corrupt Drive objects.
     */
    private fun stageArchive(
        zipName: String,
        members: List<UploadArtifact>,
        onBytes: (Long) -> Unit,
    ): StagedArchive? {
        if (members.isEmpty()) return null
        val zip = layout.archive(zipName)
        val sidecar = layout.sha256Sidecar(zipName)
        val sha = UploadWorkOutcomes.verifiedBundleSha256(zip, sidecar)
            ?.takeIf { reuse }
            ?: run {
                layout.staleFiles(zipName).forEach { it.delete() }
                val hex = SessionZip.build(
                    members.map { SessionZip.Member(it.role, it.name, it.file) },
                    zip,
                    onBytes = onBytes,
                    // Cloud-controlled version gate (Phase 1.3's .dat codec) — see
                    // AppRemoteConfig.datCodecEncodingEnabled's doc and SessionZip's
                    // isDatEntry doc for why this cannot just default to on.
                    encodeDatEntries = AppRemoteConfig.datCodecEncodingEnabled(context),
                )
                sidecar.writeText(hex)
                hex
            }
        return StagedArchive(zip, sha)
    }
}
