package com.indicvision.semper.cloud

import com.indicvision.semper.data.cloud.restore.CloudRestore
import com.indicvision.semper.data.net.CloudSessionDto
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.ui.analysis.frames.DeformedFrame
import com.indicvision.semper.ui.analysis.frames.FrameImportHelper
import com.indicvision.semper.ui.analysis.frames.ImportedBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RestoreAndImportSafetyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `restore target uses cloud local id when present`() {
        val cloud = CloudSessionDto(sessionId = "cloud-123", localSessionId = "local-456")

        assertEquals("local-456", CloudRestore.targetLocalId(cloud))
    }

    @Test
    fun `restore target has deterministic fallback`() {
        val cloud = CloudSessionDto(sessionId = "abcdefghijklmnop", localSessionId = "")

        assertEquals("restored-abcdefghijkl", CloudRestore.targetLocalId(cloud))
    }

    @Test
    fun `bundle download work name is stable per cloud session`() {
        assertEquals("download-bundle-cloud-123", CloudRestore.bundleDownloadWorkName("cloud-123"))
        assertEquals("download-bundle", CloudRestore.TAG_BUNDLE_DOWNLOAD)
    }

    @Test
    fun `suggested bundle file name is sanitized`() {
        assertEquals("My_Sample_Session.zip", CloudRestore.suggestedBundleFileName("My Sample"))
        assertEquals("analysis_Session.zip", CloudRestore.suggestedBundleFileName("!!!"))
    }

    @Test
    fun `staged import replaces committed batch only at commit`() {
        val cache = tmp.newFolder("cache")
        val committed = File(cache, "temp_deformed").apply { mkdirs() }
        File(committed, "old.png").writeText("old")

        val staging = FrameImportHelper.createStagingDir(cache)
        val stagedFrame = File(staging, "0000_new.png").apply { writeText("new") }
        assertTrue(File(committed, "old.png").exists())

        val result = FrameImportHelper.commitStagedBatch(
            cache,
            staging,
            ImportedBatch(
                frames = listOf(DeformedFrame(stagedFrame.absolutePath, "new.png", size = ImageSize(10, 20))),
            ),
        )

        assertFalse(File(committed, "old.png").exists())
        val committedFrame = File(committed, "0000_new.png")
        assertTrue(committedFrame.exists())
        assertEquals(listOf(committedFrame.absolutePath), result?.filePaths)
        assertEquals(
            DeformedFrame(committedFrame.absolutePath, "new.png", size = ImageSize(10, 20)),
            result?.frames?.single(),
        )
    }

    @Test
    fun `discarding staging preserves committed batch`() {
        val cache = tmp.newFolder("cancel-cache")
        val committed = File(cache, "temp_deformed").apply { mkdirs() }
        val old = File(committed, "old.png").apply { writeText("old") }
        val staging = FrameImportHelper.createStagingDir(cache)
        File(staging, "partial.png").writeText("partial")

        staging.deleteRecursively()

        assertTrue(old.exists())
        assertFalse(staging.exists())
    }
}
