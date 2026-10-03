package com.sempermechanics.semper.data.net

/**
 * The `role` strings the client puts on every uploaded artifact.
 *
 * Mirrors `Role` in `backend/app/models.py`, which is a `Literal[…]` — the
 * backend rejects anything outside the set, and the restore path routes a
 * downloaded file by the role it was stored under. The two lists are pinned
 * against each other by `backend/tests/test_wire_vocabulary.py`.
 *
 * Without that pin a role rename deploys green on both sides and breaks restore
 * silently: the upload succeeds under the new name, and the restore matcher —
 * which still compares against the old one — routes the file nowhere. The
 * comment on `FileSpecDto.role` had already drifted four values behind the
 * backend before this object existed, which is the same drift starting.
 */
object ArtifactRoles {

    /** Deformed source frames, under the user's own filenames. */
    const val RAW = "raw"

    /** Rendered heatmaps and overlays. */
    const val PROCESSED = "processed"

    /** Generated PDF reports. */
    const val REPORTS = "reports"

    /** Session metadata JSON. */
    const val METADATA = "metadata"

    /** Exported CSV tables. */
    const val CSV = "csv"

    /** Binary `.dat` displacement/strain fields. */
    const val DAT = "dat"

    /** The `Session.zip` bundle carrying the restore-essential artifacts. */
    const val BUNDLE = "bundle"

    /** The `Extras.zip` bundle carrying everything regenerable. */
    const val EXTRAS = "extras"
}
