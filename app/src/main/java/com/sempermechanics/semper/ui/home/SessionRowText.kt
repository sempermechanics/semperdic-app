package com.sempermechanics.semper.ui.home

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.annotation.StringRes
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.imaging.video.VideoFrameExtractor
import com.sempermechanics.semper.report.EngineStats
import com.sempermechanics.semper.ui.analysis.run.EngineFailure
import com.sempermechanics.semper.ui.common.transfer.TransferWorkObserver
import java.util.Locale

/**
 * The words under a Home row's title: what the run produced, or how far a
 * running transfer has got.
 */
internal object SessionRowText {

    /** A convergence under this percent is painted amber. */
    const val CONVERGENCE_WARN_BELOW = 85f

    private const val SEP = " · "
    private const val KB = 1024L
    private const val MB = KB * 1024L
    private const val GB = MB * 1024L

    /** Below this a byte figure keeps one decimal ("4.2"); from it on, none ("12"). */
    private const val WHOLE_FROM = 10.0

    /**
     * Size · result · why it stopped: "40 frames · 96.3%".
     *
     * A video analysis says so, with the span of its frames in the clip:
     * "Video · 40 frames, 0:00–0:12 · 91.2%".
     * A run cut short reads "39 of 50 frames" and ends with its stop reason:
     * the count alone cannot tell a short run from a shorter test. A sweep
     * reads "9 of 9 solved": its title already names it a sweep and its image.
     * A convergence under [CONVERGENCE_WARN_BELOW] is amber. The day is the
     * list's header above the row, not part of it.
     */
    fun subtitle(context: Context, r: SessionRecord): CharSequence =
        build(context, r, R.string.session_convergence_fmt)

    /** [subtitle] as TalkBack reads it, where the bare figure says what it is: "40 frames · 96.3% converged". */
    fun spokenSubtitle(context: Context, r: SessionRecord): String =
        build(context, r, R.string.session_converged_fmt).toString()

    private fun build(context: Context, r: SessionRecord, @StringRes convergenceFmt: Int): SpannableStringBuilder {
        val out = SpannableStringBuilder(size(context, r))
        appendResult(context, r, convergenceFmt, out)
        if (r.stoppedEarly) out.append(SEP).append(EngineFailure.shortReason(context, r.stopCode))
        return out
    }

    /**
     * A running transfer: "Backing up · 4.2 of 12 MB" when [progress] carries
     * byte counts, else "Backing up · 35.0%", and the phase alone before any
     * progress (the bar spins or sits empty then).
     */
    fun transfer(context: Context, progress: TransferWorkObserver.RowProgress): String {
        val label = context.getString(SessionStateIcon.forTransfer(progress.phase).label)
        val done = progress.bytesDone
        val total = progress.bytesTotal
        return when {
            done != null && total != null && total > 0 ->
                context.getString(R.string.transfer_row_bytes_fmt, label, figure(done, unitOf(total)), withUnit(total))
            progress.percent > 0 ->
                context.getString(R.string.transfer_row_percent_fmt, label, oneDecimal(progress.percent.toFloat()))
            else -> label
        }
    }

    /**
     * "40 frames", "39 of 50 frames" for a run cut short, or a sweep's
     * "9 of 9 solved": solved of planned combinations, from its own sweep lists.
     * A video's reads "Video · 40 frames, 0:00–0:12": its first and last
     * frame's times in the clip, when it kept them.
     */
    private fun size(context: Context, r: SessionRecord): String {
        val frames = when {
            r.isSweep -> (r.sweepSteps.size + r.sweepSkipSubsets.size).let { planned ->
                val solved = r.sweepSteps.size
                context.resources.getQuantityString(R.plurals.session_sweep_row_fmt, planned, solved, planned)
            }
            r.stoppedEarly && r.plannedFrameCount > r.frameCount -> context.resources.getQuantityString(
                R.plurals.session_frames_of_fmt,
                r.plannedFrameCount,
                r.frameCount,
                r.plannedFrameCount,
            )
            else -> context.resources.getQuantityString(R.plurals.session_frames_fmt, r.frameCount, r.frameCount)
        }
        if (r.isSweep || !r.isVideo) return frames
        val span = r.frameTimesMs?.takeIf { it.isNotEmpty() }?.let { times ->
            context.getString(
                R.string.session_video_span_fmt,
                frames,
                VideoFrameExtractor.formatClock(times.first()),
                VideoFrameExtractor.formatClock(times.last()),
            )
        }
        return context.getString(R.string.session_video_row_fmt, span ?: frames)
    }

    /**
     * The run's convergence, from its engine stats; an older row without stats
     * keeps its stored headline instead. A sweep's result is its [size].
     */
    private fun appendResult(
        context: Context,
        r: SessionRecord,
        @StringRes convergenceFmt: Int,
        out: SpannableStringBuilder,
    ) {
        if (r.isSweep) return
        val convergence = r.engineStats.getOrNull(EngineStats.SLOT_CONVERGENCE)
        when {
            convergence != null -> {
                val percent = oneDecimal(convergence)
                val text = context.getString(convergenceFmt, percent)
                out.append(SEP)
                val start = out.length + text.indexOf("$percent%").coerceAtLeast(0)
                out.append(text)
                if (convergence < CONVERGENCE_WARN_BELOW) {
                    out.setSpan(
                        ForegroundColorSpan(context.getColor(R.color.semantic_warning)),
                        start,
                        start + percent.length + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            r.headline.isNotBlank() -> out.append(SEP).append(r.headline)
        }
    }

    private fun oneDecimal(value: Float): String = String.format(Locale.getDefault(), "%.1f", value)

    /** "12 MB": [bytes] in its own [unitOf]. */
    internal fun withUnit(bytes: Long): String {
        val unit = unitOf(bytes)
        return "${figure(bytes, unit)} ${unit.second}"
    }

    /** [bytes] counted in [unit]: one decimal under ten, whole from ten. */
    internal fun figure(bytes: Long, unit: Pair<Long, String>): String {
        val value = bytes.toDouble() / unit.first
        return if (value < WHOLE_FROM) {
            String.format(Locale.getDefault(), "%.1f", value)
        } else {
            String.format(Locale.getDefault(), "%.0f", value)
        }
    }

    /** The binary unit Settings' sizes use for [bytes] (1 KB = 1024 bytes, as `ByteSize`). */
    private fun unitOf(bytes: Long): Pair<Long, String> = when {
        bytes >= GB -> GB to "GB"
        bytes >= MB -> MB to "MB"
        else -> KB to "KB"
    }
}
