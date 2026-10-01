package com.indicvision.semper.data.session

import java.io.File

/**
 * Where everything sits in one session directory, by its established name.
 *
 * The run, upload, restore, export and viewer each spelled these names out as
 * string literals (`"reference.png"`, `"metadata.json"`, `"reports"`, …).
 * The segment constants that already had a home stay in [SessionPaths]; this
 * resolves them and the remaining names against a concrete [dir] and
 * delegates frame `.dat` naming to [SessionPaths].
 *
 * Pure path arithmetic: nothing here touches the disk.
 */
class SessionLayout(val dir: File) {

    /** Display-size PNG copy of the reference (the Home thumbnail; restored from `raw/Reference.png`). */
    val referencePng: File get() = File(dir, REFERENCE_PNG)

    /** The cloud backup's blueprint, as uploaded or as restored. */
    val metadataJson: File get() = File(dir, METADATA_JSON)

    /** Persisted raw deformed originals ([SessionPaths.RAW_DEFORMED_SUBDIR]). */
    val rawDeformedDir: File get() = File(dir, SessionPaths.RAW_DEFORMED_SUBDIR)

    /** One raw deformed original, by the name the run persisted it under. */
    fun rawDeformed(name: String): File = File(rawDeformedDir, name)

    /** Field heatmaps and animations ([SessionPaths.PROCESSED_SUBDIR]); a restore puts `processed/` entries here. */
    val processedDir: File get() = File(dir, SessionPaths.PROCESSED_SUBDIR)

    /** Per-frame PDF reports; a restore puts `reports/` entries here. */
    val reportsDir: File get() = File(dir, REPORTS_SUBDIR)

    /** The upload's persistent staging ([SessionPaths.UPLOAD_STAGING_SUBDIR]). */
    val staging: StagingLayout get() = StagingLayout(File(dir, SessionPaths.UPLOAD_STAGING_SUBDIR))

    /** Frame [index]'s engine result ([SessionPaths.frameDat]). */
    fun frameDat(index: Int): File = SessionPaths.frameDat(dir, index)

    /** The per-field range sidecar the summary animation reads (`report.FieldRangesStore.FILE_NAME`). */
    val fieldRanges: File get() = File(dir, FIELD_RANGES)

    /** Integrity rebuilds since the last good upload (`UploadErrors.INTEGRITY_REBUILDS_MARKER`). */
    val integrityRebuildsMarker: File get() = File(dir, INTEGRITY_REBUILDS_MARKER)

    companion object {
        const val REFERENCE_PNG = "reference.png"
        const val METADATA_JSON = "metadata.json"
        const val REPORTS_SUBDIR = "reports"
        const val FIELD_RANGES = "field_ranges.bin"
        const val INTEGRITY_REBUILDS_MARKER = "upload_integrity_rebuilds"
    }
}

/**
 * The upload's staging directory (`<session>/upload_staging/`): generated once,
 * then reused byte for byte by every retry, so the sizes and hashes the
 * backend was told stay true.
 *
 * Archive names come with their sidecars: `<name>.sha256` (the verified hash a
 * reuse checks), and `<name>.tmp` (`SessionZip.build`'s write-then-move file,
 * which a restage deletes by that name).
 */
class StagingLayout(val dir: File) {

    val metadataJson: File get() = File(dir, SessionLayout.METADATA_JSON)

    /** One combined CSV for every frame. */
    val analysisCsv: File get() = File(dir, ANALYSIS_CSV)

    val reportsDir: File get() = File(dir, SessionLayout.REPORTS_SUBDIR)

    val processedDir: File get() = File(dir, SessionPaths.PROCESSED_SUBDIR)

    /** Per-field GIFs for a single-setting run, under [processedDir]. */
    val animationsDir: File get() = File(processedDir, ANIMATIONS_SUBDIR)

    /** Written only after a complete report pass; without it the staging is redone. */
    val bundlesDone: File get() = File(dir, BUNDLES_DONE)

    /** The restore-essential archive. */
    val sessionZip: File get() = archive(SESSION_ZIP)

    /** The derived deliverables a restore never reads. */
    val extrasZip: File get() = archive(EXTRAS_ZIP)

    fun archive(name: String): File = File(dir, name)

    /** `<name>.sha256`: the hex digest of a verified archive. */
    fun sha256Sidecar(name: String): File = File(dir, name + SHA256_SUFFIX)

    /** `<name>.tmp`: the archive while it is written. */
    fun tmpOf(name: String): File = File(dir, name + TMP_SUFFIX)

    /**
     * What a rebuild of archive [name] deletes first: the archive, its `.sha256`
     * and its `.tmp`. The restage drops [SESSION_ZIP]'s before regenerating the
     * bundles; `stageArchive` drops either zip's ([EXTRAS_ZIP] too) when its
     * digest does not verify.
     */
    fun staleFiles(name: String): List<File> = listOf(archive(name), sha256Sidecar(name), tmpOf(name))

    companion object {
        const val ANALYSIS_CSV = "analysis_data.csv"
        const val ANIMATIONS_SUBDIR = "animations"
        const val BUNDLES_DONE = ".bundles_done"
        const val SESSION_ZIP = "Session.zip"
        const val EXTRAS_ZIP = "Extras.zip"
        const val SHA256_SUFFIX = ".sha256"
        const val TMP_SUFFIX = ".tmp"
    }
}
