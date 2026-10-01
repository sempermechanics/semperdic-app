package com.indicvision.semper.analysis

import com.indicvision.semper.ui.analysis.wizard.LossyFormatCheck
import org.junit.Assert.assertEquals
import org.junit.Test

class LossyFormatCheckTest {

    @Test
    fun `says nothing about a fully lossless set`() {
        assertEquals(
            emptyList<String>(),
            LossyFormatCheck.lossyLabels(
                listOf("ref.png", "/a/frame_0001.PNG", "b.tiff", "c.bmp", "shot.dng", "x.RAW"),
            ),
        )
    }

    @Test
    fun `treats DNG and RAW as lossless`() {
        assertEquals(
            emptyList<String>(),
            LossyFormatCheck.lossyLabels(listOf("specimen.dng", "0000_shot.RAW", "frame.png")),
        )
    }

    @Test
    fun `names the reference format when only the reference is lossy`() {
        // The locked capture writes PNG frames but the test shot comes from the
        // camera app as JPEG. Blaming "PNG" there sent the user looking for a
        // setting that was already correct.
        val labels = LossyFormatCheck.lossyLabels(
            listOf("IMG_2043.jpg", "/data/frame_0000.png", "/data/frame_0001.png"),
        )

        assertEquals(listOf("JPEG"), labels)
    }

    @Test
    fun `catches lossy formats that are not JPEG`() {
        assertEquals(listOf("WEBP"), LossyFormatCheck.lossyLabels(listOf("shot.webp", "r.png")))
        assertEquals(listOf("HEIC"), LossyFormatCheck.lossyLabels(listOf("shot.HEIC")))
    }

    @Test
    fun `collapses jpg and jpeg into one name`() {
        val labels = LossyFormatCheck.lossyLabels(listOf("a.jpg", "b.jpeg", "c.jfif"))

        assertEquals(listOf("JPEG"), labels)
    }

    @Test
    fun `lists several distinct formats in the order first seen`() {
        val labels = LossyFormatCheck.lossyLabels(listOf("a.webp", "b.jpg", "c.webp", "d.png"))

        assertEquals(listOf("WEBP", "JPEG"), labels)
    }

    @Test
    fun `skips names with no extension rather than showing a blank`() {
        assertEquals(emptyList<String>(), LossyFormatCheck.lossyLabels(listOf("", "frame", "a.")))
    }

    @Test
    fun `reads the extension past directories and dotted names`() {
        assertEquals("png", LossyFormatCheck.extensionOf("/data/run.1/frame.png"))
        assertEquals("jpg", LossyFormatCheck.extensionOf("C:\\shots\\my.photo.JPG"))
        assertEquals("", LossyFormatCheck.extensionOf("/data/run.1/frame"))
        assertEquals("", LossyFormatCheck.extensionOf(".hidden"))
    }
}
