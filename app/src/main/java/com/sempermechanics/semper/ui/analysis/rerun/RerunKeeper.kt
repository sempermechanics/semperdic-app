package com.sempermechanics.semper.ui.analysis.rerun

import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.originalNameOr
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.run.RunInputs
import com.sempermechanics.semper.ui.analysis.run.RunSpec
import com.sempermechanics.semper.ui.analysis.run.baseName
import com.sempermechanics.semper.util.AtomicFiles
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Locale

/*
 * A run that saves keeps what Home's Re-run needs to start a new analysis
 * from it ([RerunInputs]), in its session's `rerun/` folder. Best-effort: a
 * failure here costs that analysis its Re-run, never the run or its save, and
 * leaves no partial `rerun/` behind. None of it reaches the `.dat` files.
 */

/**
 * Keeps a batch run's Re-run inputs in session folder [dir]. [resolved] is
 * where each of [inputs]' frames is now, index-aligned: the run moved the
 * frames it reached into `raw_deformed/`, and the ones it stopped before (or
 * could not keep) are still where it found them. Those move into
 * `rerun/frames/`, copied when they belong to another session, and [resolved]
 * follows them, so the wizard does too. [inputs] without a
 * [RunInputs.setup] keeps nothing.
 */
@WorkerThread
internal fun keepBatchRerun(dir: File, inputs: RunInputs, spec: RunSpec, resolved: MutableList<String>) {
    val setup = inputs.setup ?: return
    val layout = SessionLayout(dir)
    try {
        val kept = layout.rerunFramesDir.apply { mkdirs() }
        val frames = resolved.indices.map { i ->
            val name = inputs.frames.originalNames.originalNameOr(i, File(resolved[i]).name).baseName()
            val placed = placeFrame(File(resolved[i]), dir, kept, name, i)
            resolved[i] = placed.absolutePath
            frameEntry(dir, placed, setup.frames.getOrNull(i), name)
        }
        val listed = frames.mapTo(HashSet()) { File(dir, it.path).canonicalFile }
        kept.listFiles()?.forEach { if (it.canonicalFile !in listed) it.delete() }
        writeRerun(layout, inputs, spec, setup, frames, sweep = null)
    } catch (e: IOException) {
        dropRerun(layout, e)
    }
}

/**
 * Keeps a sweep's Re-run inputs in session folder [dir]. A sweep keeps one
 * image, the frame it solved against, as `raw_deformed/`[rawName]; that is
 * the Re-run's one frame, and its sweep frame.
 */
@WorkerThread
internal fun keepSweepRerun(dir: File, inputs: RunInputs, spec: RunSpec, rawName: String) {
    val setup = inputs.setup ?: return
    val sweep = spec.sweep ?: return
    val layout = SessionLayout(dir)
    try {
        val image = layout.rawDeformed(rawName)
        if (rawName.isBlank() || !image.isFile) throw IOException("the sweep's frame was not kept")
        val picked = setup.frames.getOrNull(sweep.frameIndex)
        val frame = frameEntry(dir, image, picked, picked?.name ?: rawName)
        val ranges = RerunInputs.Sweep(
            ranges = setup.sweep.ranges.toIntArray().toList(),
            overlap = setup.sweep.subsetOverlap,
            lineCutHorizontal = sweep.lineCutHorizontal,
            frameIndex = 0,
        )
        layout.rerunFramesDir.deleteRecursively()
        writeRerun(layout, inputs, spec, setup, listOf(frame), ranges)
    } catch (e: IOException) {
        dropRerun(layout, e)
    }
}

/**
 * Where frame [index]'s image [source] stays for a Re-run of session [dir]:
 * where it is when the session already keeps it (`raw_deformed/`, or
 * [kept] from an earlier run in this folder that saved nothing), else in
 * [kept] under [name], index-prefixed if that is taken. One in another
 * session is copied, never moved: it is that session's image.
 */
private fun placeFrame(source: File, dir: File, kept: File, name: String, index: Int): File {
    if (!source.isFile) throw IOException("frame $index is gone")
    val parent = source.canonicalFile.parentFile
    if (parent == SessionLayout(dir).rawDeformedDir.canonicalFile || parent == kept.canonicalFile) return source
    val target = File(kept, name).let {
        if (it.exists()) File(kept, String.format(Locale.US, "%04d_%s", index, name)) else it
    }
    if (SessionPaths.isInOtherSession(source, dir)) {
        source.copyTo(target, overwrite = true)
    } else {
        AtomicFiles.promote(source, target)
    }
    return target
}

/** [image]'s entry, under session folder [dir], with [picked]'s date, size and time. */
private fun frameEntry(dir: File, image: File, picked: DeformedFrame?, name: String) = RerunInputs.Frame(
    path = image.relativeTo(dir).invariantSeparatorsPath,
    name = picked?.name?.ifEmpty { null } ?: name,
    date = picked?.date ?: DeformedFrame.UNKNOWN_DATE,
    width = picked?.size?.width ?: -1,
    height = picked?.size?.height ?: -1,
    timeMs = picked?.timeMs,
)

/** Writes the reference, the mask and, last, the inputs that name them all. */
@Suppress("LongParameterList") // the run's three parts and what is kept of each
private fun writeRerun(
    layout: SessionLayout,
    inputs: RunInputs,
    spec: RunSpec,
    setup: RerunSetup,
    frames: List<RerunInputs.Frame>,
    sweep: RerunInputs.Sweep?,
) {
    val reference = inputs.reference
    val bytes = reference.bytes ?: throw IOException("no reference")
    layout.rerunDir.mkdirs()
    writeAtomically(layout.rerunReference, bytes)
    val hasMask = spec.mask.isNotEmpty()
    if (hasMask) writeAtomically(layout.rerunMask, spec.mask) else layout.rerunMask.delete()
    val kept = RerunInputs(
        reference = RerunInputs.Reference(reference.name, reference.size.width, reference.size.height),
        hasMask = hasMask,
        hasCustomRoi = setup.hasCustomRoi,
        roi = setup.roi.toXywh().toList(),
        frames = frames,
        fromVideo = setup.fromVideo,
        videoName = inputs.frames.videoName,
        orderMode = setup.order.mode.name,
        orderDirection = setup.order.direction.name,
        params = RerunInputs.Params(
            spec.params.subset,
            spec.params.step,
            spec.params.strainWindow,
            spec.use6x6,
            if (spec.incremental) spec.refUpdateInterval else 0,
        ),
        sweep = sweep,
    )
    writeAtomically(layout.rerunInputs, RerunInputs.encode(kept).toByteArray(Charsets.UTF_8))
}

private fun writeAtomically(dest: File, bytes: ByteArray) {
    val part = AtomicFiles.partOf(dest)
    part.writeBytes(bytes)
    AtomicFiles.promote(part, dest)
}

/**
 * No Re-run for this analysis: its inputs, reference and mask go. A frame
 * already moved into `rerun/frames/` stays: the wizard reads it there.
 */
private fun dropRerun(layout: SessionLayout, cause: IOException) {
    Timber.w(cause, "Could not keep this analysis's Re-run inputs")
    layout.rerunInputs.delete()
    layout.rerunReference.delete()
    layout.rerunMask.delete()
}
