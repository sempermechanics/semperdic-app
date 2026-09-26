package com.indicvision.semper.ui.analysis

import android.media.MediaFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which decoded frames are stretched to full range (TD-134): video unless the
 * stream says otherwise, and the codec's output format before the container's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageLumaRangeTest {

    private fun format(range: Int? = null) = MediaFormat().apply {
        range?.let { setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
    }

    @Test
    fun `a stream that names no range is video, so limited`() {
        assertTrue(ImageLuma.isLimitedRange())
        assertTrue(ImageLuma.isLimitedRange(null))
        assertTrue(ImageLuma.isLimitedRange(format(), format()))
    }

    @Test
    fun `a full-range stream is kept as stored`() {
        assertFalse(ImageLuma.isLimitedRange(format(MediaFormat.COLOR_RANGE_FULL)))
        assertTrue(ImageLuma.isLimitedRange(format(MediaFormat.COLOR_RANGE_LIMITED)))
    }

    @Test
    fun `the codec's output format decides before the container's`() {
        val full = format(MediaFormat.COLOR_RANGE_FULL)
        val limited = format(MediaFormat.COLOR_RANGE_LIMITED)
        assertTrue(ImageLuma.isLimitedRange(limited, full))
        assertFalse(ImageLuma.isLimitedRange(full, limited))
        // An output format without the key leaves the container's to decide.
        assertFalse(ImageLuma.isLimitedRange(format(), full))
        assertFalse(ImageLuma.isLimitedRange(null, full))
    }
}
