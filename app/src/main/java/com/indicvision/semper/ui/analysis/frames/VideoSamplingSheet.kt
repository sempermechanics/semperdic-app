package com.indicvision.semper.ui.analysis.frames

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.indicvision.semper.R
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.prefs.DicSettings
import com.indicvision.semper.databinding.DialogVideoSamplingBinding
import com.indicvision.semper.imaging.video.ExtractionRequest
import com.indicvision.semper.imaging.video.VideoFrameExtractor
import com.indicvision.semper.imaging.video.VideoKeyframeHelper
import com.indicvision.semper.imaging.video.VideoMeta
import com.indicvision.semper.ui.common.dialog.FaqRedirect
import com.indicvision.semper.ui.common.onButtonChecked
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * Video input. Frame 0 of the chosen segment becomes the reference; the rest
 * become the deformed sequence, feeding the exact same refBytes / defFilePaths
 * state as the image flow.
 *
 * Step 1: read metadata, show resolution/fps/length + sampling options.
 * Step 2: [onExtract] the chosen frame rate over the chosen time segment.
 */
class VideoSamplingSheet(
    private val activity: AppCompatActivity,
    private val onExtract: (ExtractionRequest) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Reads [uri]'s metadata on [io], then offers the sampling sheet; a video it cannot read gets a snackbar. */
    fun open(uri: Uri) {
        activity.lifecycleScope.launch {
            val meta = withContext(io) { VideoFrameExtractor.readMeta(activity, uri) }
            // An AVI we could demux but not decode can say which codec it is,
            // which beats "could not read this video" by a mile.
            val unsupported = meta.unsupportedCodec
            when {
                unsupported != null -> FaqRedirect.snackbar(
                    activity,
                    activity.getString(R.string.video_codec_unsupported, unsupported.trim()),
                    R.string.url_faq_video_read,
                )
                meta.durationMs <= 0L ->
                    FaqRedirect.snackbar(activity, R.string.video_read_failed, R.string.url_faq_video_read)
                else -> show(uri, meta)
            }
        }
    }

    /** Sampling by extraction frame rate + time segment, with a metadata summary. */
    internal fun show(uri: Uri, meta: VideoMeta): BottomSheetDialog {
        val form = DialogVideoSamplingBinding.inflate(activity.layoutInflater)
        val sampling = VideoSampling(meta, DicSettings.maxFrames(activity, AppRemoteConfig.maxFrames(activity)))
        form.tvVideoInfo.text = infoLine(meta)

        // --- Frame-rate selector (capped at the source rate when known) ---
        form.sliderFps.valueFrom = 1f
        form.sliderFps.valueTo = sampling.maxFps.toFloat()
        form.sliderFps.value = minOf(DEFAULT_FPS, sampling.maxFps).toFloat()
        form.tvFpsValue.text = fpsLabel(form.sliderFps.value.toInt())

        // --- Time-segment selector (seconds) ---
        val durationSec = (meta.durationMs / MS_PER_SECOND).toFloat().coerceAtLeast(MIN_SEGMENT_SEC)
        form.rangeSegment.valueFrom = 0f
        form.rangeSegment.valueTo = durationSec
        form.rangeSegment.values = listOf(0f, durationSec)
        form.tvSegmentValue.text = segmentLabel(0, meta.durationMs)

        form.toggleExtractMode.onButtonChecked { checkedId ->
            form.layoutFps.isVisible = checkedId != R.id.btnModeKeyframes
            refreshEstimate(form, sampling)
        }
        form.sliderFps.addOnChangeListener { _, v, _ ->
            form.tvFpsValue.text = fpsLabel(v.toInt())
            refreshEstimate(form, sampling)
        }
        form.rangeSegment.addOnChangeListener { s, _, _ ->
            val startMs = (s.values.first() * MS_PER_SECOND_F).toLong()
            val endMs = (s.values.last() * MS_PER_SECOND_F).toLong()
            form.tvSegmentValue.text = segmentLabel(startMs, endMs)
            refreshEstimate(form, sampling)
        }
        refreshEstimate(form, sampling)

        // Bottom sheet (wireframe 05b): the primary button states the outcome.
        val sheet = BottomSheetDialog(activity)
        sheet.setContentView(form.root)
        form.btnExtractFrames.setOnClickListener {
            sheet.dismiss()
            val (startMs, endMs) = sampling.segmentMs(form.rangeSegment.values)
            onExtract(
                ExtractionRequest(
                    uri = uri,
                    fpsExtract = form.sliderFps.value.toDouble().coerceAtLeast(MIN_EXTRACT_FPS),
                    startMs = startMs,
                    endMs = endMs,
                    maxFrames = sampling.maxFrames,
                    preferKeyframes = form.toggleExtractMode.checkedButtonId == R.id.btnModeKeyframes,
                    rotationDegrees = meta.rotationDegrees,
                ),
            )
        }
        sheet.show()
        return sheet
    }

    /** Only the parts the file actually reported. */
    private fun infoLine(meta: VideoMeta): String {
        val info = mutableListOf<String>()
        if (meta.width > 0 && meta.height > 0) {
            info.add(activity.getString(R.string.video_resolution_fmt, meta.width, meta.height))
        }
        if (meta.fpsKnown) info.add(activity.getString(R.string.video_fps_fmt, meta.fps))
        info.add(VideoFrameExtractor.formatClock(meta.durationMs))
        return info.joinToString(activity.getString(R.string.video_info_separator))
    }

    private fun fpsLabel(fps: Int): String = activity.getString(R.string.video_fps_fmt, fps.toDouble())

    private fun segmentLabel(startMs: Long, endMs: Long): String = activity.getString(
        R.string.video_segment_fmt,
        VideoFrameExtractor.formatClock(startMs),
        VideoFrameExtractor.formatClock(endMs),
    )

    private fun refreshEstimate(form: DialogVideoSamplingBinding, sampling: VideoSampling) {
        if (form.toggleExtractMode.checkedButtonId == R.id.btnModeKeyframes) {
            form.tvEstimate.text = activity.getString(R.string.video_keyframes_estimate_note)
            form.btnExtractFrames.setText(R.string.extract_frames_title)
        } else {
            val n = sampling.estimate(form.rangeSegment.values, form.sliderFps.value.toDouble())
            val capped = if (n >= sampling.maxFrames) activity.getString(R.string.video_capped_suffix) else ""
            val deformed = (n - 1).coerceAtLeast(0)
            form.tvEstimate.text =
                activity.resources.getQuantityString(R.plurals.video_estimate_fmt, n, n, deformed, capped)
            form.btnExtractFrames.text = activity.resources.getQuantityString(R.plurals.extract_n_frames_fmt, n, n)
        }
    }

    private companion object {
        const val DEFAULT_FPS = 10
        const val MS_PER_SECOND = 1000.0
        const val MS_PER_SECOND_F = 1000f
        const val MIN_SEGMENT_SEC = 0.1f
        const val MIN_EXTRACT_FPS = 0.1
    }
}

/**
 * What a sampling of [meta] delivers, capped at [maxFrames]. The segment
 * slider reaches the clip's end, where no frame starts; sampling stops at the
 * last frame's start so the estimate is what extraction delivers (see
 * [VideoKeyframeHelper.lastFrameStartMs]).
 */
internal class VideoSampling(meta: VideoMeta, val maxFrames: Int) {
    /** The frame-rate slider's top: the source rate when known. */
    val maxFps = (if (meta.fpsKnown) ceil(meta.fps).toInt() else ASSUMED_FPS).coerceIn(MIN_TOP_FPS, MAX_TOP_FPS)

    private val lastFrameMs = VideoKeyframeHelper.lastFrameStartMs(meta.durationMs, meta.fps, meta.fpsKnown)

    /** The segment slider's [values], in seconds, as ms clamped to the last frame's start. */
    fun segmentMs(values: List<Float>): Pair<Long, Long> =
        (values.first() * MS_PER_SECOND).toLong().coerceAtMost(lastFrameMs) to
            (values.last() * MS_PER_SECOND).toLong().coerceAtMost(lastFrameMs)

    /** Frames a uniform sampling at [fps] over [values] extracts. */
    fun estimate(values: List<Float>, fps: Double): Int {
        val (startMs, endMs) = segmentMs(values)
        return VideoKeyframeHelper.uniformTimestampsUs(startMs, endMs, fps, maxFrames).size
    }

    private companion object {
        const val ASSUMED_FPS = 30
        const val MIN_TOP_FPS = 2
        const val MAX_TOP_FPS = 60
        const val MS_PER_SECOND = 1000f
    }
}
