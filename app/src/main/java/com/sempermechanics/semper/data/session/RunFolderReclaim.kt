package com.sempermechanics.semper.data.session

import android.content.Context
import androidx.annotation.WorkerThread
import java.util.concurrent.TimeUnit

/*
 * Which unindexed session folders the startup sweep deletes
 * (`CacheJanitor.sweepOnStartup`): a killed run's and an unsaved run's. The
 * delete itself, under the index lock, is [SessionStore.reclaimUnindexed].
 */

/**
 * Deletes the folders runs the process died in left behind (readiness
 * TE1-101): a folder under the sessions root that no index row names,
 * that holds a `run_staging/` and no record manifest, and that nothing has
 * written since [startedAtMs] (a run started in this process is live).
 * Nothing is deleted while the index does not parse. Returns the bytes freed.
 */
@WorkerThread
fun SessionStore.reclaimDeadRuns(context: Context, startedAtMs: Long): Long =
    reclaimUnindexed(context, "a killed run") { dir ->
        SessionPaths.runStaging(dir).isDirectory &&
            !SessionLayout(dir).recordManifest.exists() &&
            dir.walkTopDown().all { it.lastModified() < startedAtMs }
    }

/**
 * Deletes the folders of runs that ended but saved nothing (a first frame
 * that kept no points): the wizard reuses one only while it names it as
 * its working session, and once it drops it (new inputs, or the wizard
 * closed) nothing deletes it, frames and all. A folder goes when no index
 * row names it, when it holds nothing only a saving run writes (the Home
 * thumbnail `reference.png`, Re-run's `rerun/`, a backup's
 * `metadata.json`: so an analysis whose row an index restored from its
 * `.bak` lost is never one of them), and when nothing has written to it
 * for [UNSAVED_RUN_MIN_AGE_MS] before [startedAtMs]. None goes while
 * [draftLive]: a wizard restored from its draft may still be reading
 * frames from one. Nothing is deleted while the index does not parse.
 * Returns the bytes freed.
 */
@WorkerThread
fun SessionStore.reclaimUnsavedRuns(context: Context, startedAtMs: Long, draftLive: Boolean): Long {
    if (draftLive) return 0L
    val before = startedAtMs - UNSAVED_RUN_MIN_AGE_MS
    return reclaimUnindexed(context, "an unsaved run") { dir ->
        val layout = SessionLayout(dir)
        !layout.referencePng.exists() &&
            !layout.rerunDir.exists() &&
            !layout.metadataJson.exists() &&
            dir.walkTopDown().all { it.lastModified() < before }
    }
}

/** How long an unsaved run's folder is left alone before [reclaimUnsavedRuns] takes it. */
internal val UNSAVED_RUN_MIN_AGE_MS: Long = TimeUnit.HOURS.toMillis(UNSAVED_RUN_MIN_AGE_HOURS)
private const val UNSAVED_RUN_MIN_AGE_HOURS = 24L
