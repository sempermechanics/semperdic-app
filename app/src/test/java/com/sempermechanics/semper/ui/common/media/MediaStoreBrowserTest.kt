package com.sempermechanics.semper.ui.common.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreBrowserTest {

    @Test
    fun `pre-13 uses storage permission`() {
        val perms = MediaStoreBrowser.permissions(sdk = 32, includeVideo = true)
        assertArrayEquals(arrayOf("android.permission.READ_EXTERNAL_STORAGE"), perms)
    }

    @Test
    fun `13+ asks for images and optional video`() {
        val images = MediaStoreBrowser.permissions(sdk = 33, includeVideo = false)
        assertArrayEquals(arrayOf("android.permission.READ_MEDIA_IMAGES"), images)
        val both = MediaStoreBrowser.permissions(sdk = 34, includeVideo = true)
        assertTrue(both.contains("android.permission.READ_MEDIA_IMAGES"))
        assertTrue(both.contains("android.permission.READ_MEDIA_VIDEO"))
        assertFalse(both.contains("android.permission.READ_EXTERNAL_STORAGE"))
    }
}
