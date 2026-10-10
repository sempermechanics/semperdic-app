package com.sempermechanics.semper.ui.analysis.frames

import android.app.Application
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sempermechanics.semper.R
import com.sempermechanics.semper.imaging.video.ExtractionRequest
import com.sempermechanics.semper.imaging.video.VideoFrameExtractor
import com.sempermechanics.semper.imaging.video.VideoMeta
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The sampling sheet says what the clip reported and what a sampling will
 * deliver, and Extract hands over that sampling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VideoSamplingSheetTest {

    private val beds = mutableListOf<WizardTestBed>()

    @After
    fun closeBeds() = beds.forEach { it.close() }

    private val meta = VideoMeta(
        durationMs = 4_000L,
        fps = 29.97,
        fpsKnown = true,
        width = 1920,
        height = 1080,
        rotationDegrees = 90,
    )

    @Test
    fun `the slider tops out at the source rate, and an unknown rate assumes 30`() {
        assertEquals(30, VideoSampling(meta, maxFrames = 100).maxFps)
        assertEquals(30, VideoSampling(meta.copy(fpsKnown = false, fps = 0.0), maxFrames = 100).maxFps)
        assertEquals(2, VideoSampling(meta.copy(fps = 1.0), maxFrames = 100).maxFps)
        assertEquals(60, VideoSampling(meta.copy(fps = 240.0), maxFrames = 100).maxFps)
    }

    @Test
    fun `the segment stops at the last frame's start`() {
        val sampling = VideoSampling(meta, maxFrames = 100)
        val (start, end) = sampling.segmentMs(listOf(1f, 4f))
        assertEquals(1_000L, start)
        assertTrue(end < 4_000L)
    }

    @Test
    fun `the estimate never passes the cap`() {
        assertEquals(5, VideoSampling(meta, maxFrames = 5).estimate(listOf(0f, 4f), fps = 30.0))
    }

    @Test
    fun `the sheet shows the clip and the sampling, and Extract hands it over`() {
        val bed = WizardTestBed().also { beds += it }
        var request: ExtractionRequest? = null
        val sheet = VideoSamplingSheet(bed.activity, onExtract = { request = it })
            .show(Uri.parse("content://v/1"), meta)
        bed.idle()

        val info = sheet.findViewById<TextView>(R.id.tvVideoInfo)!!.text.toString()
        assertEquals("1920×1080   ·   30 fps   ·   ${VideoFrameExtractor.formatClock(4_000L)}", info)
        assertEquals("10 fps", sheet.findViewById<TextView>(R.id.tvFpsValue)!!.text.toString())
        val segment = "${VideoFrameExtractor.formatClock(0)} – ${VideoFrameExtractor.formatClock(4_000L)}"
        assertEquals(segment, sheet.findViewById<TextView>(R.id.tvSegmentValue)!!.text.toString())

        // Keyframes give no count up front, so no estimate line until Interval.
        assertFalse(sheet.findViewById<TextView>(R.id.tvEstimate)!!.isVisible)
        sheet.findViewById<MaterialButtonToggleGroup>(R.id.rgExtractMode)!!.check(R.id.btnModeUniform)
        assertTrue(sheet.findViewById<TextView>(R.id.tvEstimate)!!.isVisible)
        val estimate = sheet.findViewById<TextView>(R.id.tvEstimate)!!.text.toString()
        assertTrue(estimate, estimate.startsWith("≈ 40 frames: 1 reference + 39 deformed"))

        sheet.findViewById<android.view.View>(R.id.btnExtractFrames)!!.performClick()
        val sent = request!!
        assertEquals(10.0, sent.fpsExtract, 0.0)
        assertEquals(0L, sent.startMs)
        assertEquals(90, sent.rotationDegrees)
        assertEquals(false, sent.preferKeyframes)
        assertTrue(sent.maxFrames > 0)
    }

    @Test
    fun `the mode toggle's info button explains both modes and the reference`() {
        val bed = WizardTestBed().also { beds += it }
        val sheet = VideoSamplingSheet(bed.activity, onExtract = {}).show(Uri.parse("content://v/1"), meta)
        bed.idle()

        sheet.findViewById<View>(R.id.btnVideoModeInfo)!!.performClick()
        bed.idle()
        val info = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(
            bed.activity.getString(R.string.video_mode_info),
            info.findViewById<TextView>(android.R.id.message)!!.text.toString(),
        )
    }
}
