package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import androidx.annotation.StringRes
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.ui.analysis.wizard.BatchAnalysisOutcome
import com.sempermechanics.semper.ui.viewer.ViewerArgs

/**
 * How a batch run or a sweep ended, as the wizard shows it (ADR-018): one
 * case per thing the screen does, read once from the run's outcome ([of]) or
 * from what it threw ([unexpected]), and shown by `BatchRunController` in one
 * `when`. A result opens from its saved row ([SessionRecord], ADR-022); the
 * one with no row, a sweep that solved nothing, opens its lattice.
 */
internal sealed interface RunEnd {

    /** The user cancelled: nothing was kept, so nothing opens and nothing is said. */
    data object Cancelled : RunEnd

    /** The account is at its analysis limit: its screen opens. */
    data object SessionLimit : RunEnd

    /** The run solved, but the session index could not be read or written, so nothing was saved. */
    data object NotSaved : RunEnd

    /**
     * The region's grid at its step did not fit this phone's memory
     * ([RunStop.TooLarge]): refused at the start, or out of memory mid-run.
     * Nothing was kept.
     */
    data object TooLarge : RunEnd

    /**
     * The run threw an exception of class [kind]. What it said stays in the log the
     * catch writes (RunChannels): it can name a path, a file or a specimen, and it is
     * not the user's language, so the screen says the app's own words (ADR-018).
     */
    data class Unexpected(val kind: String) : RunEnd

    /**
     * Nothing was kept: the engine stopped the run with [code] on frame
     * [frameIndex] ([frameName]), or its first frame kept no points
     * ([emptyFirstFrame]), which is why nothing was saved however many frames
     * solved after it.
     */
    data class Failed(
        val code: Int,
        val frameIndex: Int,
        val frameName: String?,
        val emptyFirstFrame: EmptyFirstFrame? = null,
    ) : RunEnd

    /**
     * A first frame that kept no points: the [correlatedPoints] ICGN accepted
     * there tell why (`EngineFailure.zeroPoints`); [spec] is the run's, for
     * the VSG and step the message names.
     */
    data class EmptyFirstFrame(val correlatedPoints: Int, val spec: RunSpec?)

    /**
     * The run stopped itself partway with [code], at frame [stoppedAtFrame]
     * (1-based; [frameIndex] and [frameName] are the frame it failed on, if
     * any); the frames before it are saved as [record].
     */
    data class Partial(
        val record: SessionRecord,
        val code: Int,
        val stoppedAtFrame: Int,
        val frameIndex: Int,
        val frameName: String?,
    ) : RunEnd

    /** Every frame was solved, and the run saved [record] in [executionTimeMs]. */
    data class Solved(val record: SessionRecord, val executionTimeMs: Int) : RunEnd

    /** A sweep saved as [record]; the combinations the engine could not solve are dropped. */
    data class SweepSaved(val record: SessionRecord) : RunEnd

    /** A sweep that solved no combination: its lattice of failed nodes, [args], with no row behind it. */
    data class SweepFailed(val args: ViewerArgs) : RunEnd

    companion object {
        /**
         * What [outcome] means to the wizard. Only a saved row opens: a first
         * frame that kept no points saves nothing, however many frames solved
         * after it, so that run failed.
         */
        fun of(outcome: BatchAnalysisOutcome): RunEnd {
            val record = outcome.record
            return when {
                outcome.indexUnavailable -> NotSaved
                outcome.stop == RunStop.Cancelled -> Cancelled
                outcome.stop == RunStop.SessionLimit -> SessionLimit
                outcome.stop == RunStop.TooLarge -> TooLarge
                outcome.failedSweep != null -> SweepFailed(outcome.failedSweep)
                record != null && record.isSweep -> SweepSaved(record)
                record == null || outcome.firstFrameValidPoints <= 0 -> failed(outcome)
                outcome.engineErrorCode < 0 -> Partial(
                    record = record,
                    code = outcome.engineErrorCode,
                    stoppedAtFrame = outcome.stoppedAtFrame,
                    frameIndex = outcome.failedFrameIndex,
                    frameName = outcome.failedFrameName,
                )
                else -> Solved(record, outcome.executionTimeMs)
            }
        }

        /** A run that threw [e]: what it was, never what it said. */
        fun unexpected(e: Exception): Unexpected = Unexpected(e::class.java.simpleName)

        /** [outcome] kept nothing: the engine's code on its frame, or a first frame that kept no points. */
        private fun failed(outcome: BatchAnalysisOutcome): Failed {
            // Code 0 is "the first frame kept no points", which is not always the
            // strain window's fault. A run that went on past such a frame and
            // stopped later saved nothing because of that first frame, so it is
            // the one to explain.
            val emptyFirstFrame = outcome.engineErrorCode == 0 ||
                (outcome.firstFrameValidPoints <= 0 && outcome.failedFrameIndex > 0)
            return Failed(
                code = outcome.engineErrorCode,
                frameIndex = outcome.failedFrameIndex,
                frameName = outcome.failedFrameName,
                emptyFirstFrame = if (emptyFirstFrame) {
                    EmptyFirstFrame(outcome.firstFrameCorrelatedPoints, outcome.spec)
                } else {
                    null
                },
            )
        }
    }
}

/** What the wizard tells of a run that kept nothing: the dialog's [message], its FAQ, and the longer **Why?** text. */
internal data class FailureNotice(val message: String, @StringRes val faqUrlRes: Int, val why: String?)

/** This failure as the wizard tells it ([FailureNotice]), with [context]'s strings. */
internal fun RunEnd.Failed.notice(context: Context): FailureNotice {
    val empty = emptyFirstFrame
    return if (empty != null) {
        FailureNotice(
            message = EngineFailure.zeroPointsMessage(context, empty.correlatedPoints, empty.spec),
            faqUrlRes = EngineFailure.zeroPointsFaqUrlRes(empty.correlatedPoints),
            why = null,
        )
    } else {
        FailureNotice(
            message = engineFailureMessage(context, code, frameIndex, frameName),
            faqUrlRes = EngineFailure.faqUrlRes(code),
            why = EngineFailure.whyRes(code)?.let(context::getString),
        )
    }
}

/**
 * Why a run produced nothing, naming frame [frameIndex] ([frameName]) it
 * stopped on, if any. The engine's codes are the same for a single analysis
 * and a sweep; the messages reuse the sweep's string resources.
 */
internal fun engineFailureMessage(context: Context, code: Int, frameIndex: Int, frameName: String?): String {
    val frameInfo = when {
        frameIndex < 0 -> ""
        frameName != null -> context.getString(R.string.failure_frame_fmt, frameIndex + 1, frameName)
        else -> context.getString(R.string.failure_frame_no_name_fmt, frameIndex + 1)
    }
    return frameInfo + context.getString(EngineFailure.reasonRes(code), code)
}
