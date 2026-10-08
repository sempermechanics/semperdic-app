package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.cloud.CorruptTransferException
import com.sempermechanics.semper.data.cloud.restore.RestoreBundleFetcher
import com.sempermechanics.semper.data.net.ArtifactRoles
import com.sempermechanics.semper.data.session.SessionLayout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * How a restore fetches its payload — everything but `metadata.json` — for
 * each era of backup, and the plan that lets a legacy bundle be fetched as its
 * raw/ + dat/ prefix instead of whole. [CloudRestorePipelineTest] drives the
 * whole restore; this pins the fetcher's own choices.
 */
class RestoreBundleFetcherTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val api = FakeRestoreApi()
    private val reference = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2)
    private val dat = FakeRestoreApi.onePointDat()

    private lateinit var cacheDir: File
    private lateinit var layout: SessionLayout

    private fun fetch(): RestoreBundleFetcher.PayloadFetch {
        cacheDir = temp.newFolder("cache")
        layout = SessionLayout(temp.newFolder("session"))
        return RestoreBundleFetcher.PayloadFetch(api, "token", "cloud-1", cacheDir, layout)
    }

    private val progress = mutableListOf<Pair<Long, Long>>()

    private fun payload(f: RestoreBundleFetcher.PayloadFetch, split: Boolean) = runBlocking {
        RestoreBundleFetcher.fetchPayload(f, api.files, split) { done, total -> progress += done to total }
    }

    @Test
    fun `a split backup's Session zip is fetched whole, checked, and unpacked into the layout`() {
        val f = fetch()
        val zip = FakeRestoreApi.zipOf(listOf("raw/Reference.png" to reference, "dat/frame_0000.dat" to dat))
        api.files = listOf(api.file("bundle-1", ArtifactRoles.BUNDLE, zip))

        val outcome = payload(f, split = true)

        assertEquals("whole-bundle", outcome.mode)
        assertEquals(layout.referencePng.absolutePath, outcome.refPath)
        assertArrayEquals(reference, layout.referencePng.readBytes())
        assertArrayEquals(dat, File(layout.dir, "frame_0000.dat").readBytes())
        assertEquals(zip.size.toLong(), outcome.bytesDownloaded)
        assertEquals("progress ends complete", progress.last().first, progress.last().second)
        assertTrue("no scratch file left behind", cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a bundle that fails its sha256 is refused and leaves no scratch file`() {
        val f = fetch()
        val zip = FakeRestoreApi.zipOf(listOf("raw/Reference.png" to reference))
        val wrongHash = FakeRestoreApi.sha256Of(byteArrayOf(1))
        api.files = listOf(api.file("bundle-1", ArtifactRoles.BUNDLE, zip, sha256 = wrongHash))

        val error = assertThrows(CorruptTransferException::class.java) { payload(f, split = true) }

        assertEquals("session_zip_sha256_mismatch", error.message)
        assertTrue(cacheDir.listFiles().orEmpty().isEmpty())
        assertFalse(layout.referencePng.exists())
    }

    @Test
    fun `a pre-bundle backup downloads each artifact into place, but never the metadata`() {
        val f = fetch()
        api.files = listOf(
            api.file("meta-1", ArtifactRoles.METADATA, FakeRestoreApi.metadataJson()),
            api.file("ref-1", ArtifactRoles.RAW, reference).copy(name = "Reference.png"),
            api.file("dat-1", ArtifactRoles.DAT, dat).copy(name = "frame_0000.dat"),
        )

        val outcome = payload(f, split = false)

        assertEquals("legacy-per-file", outcome.mode)
        assertEquals(layout.referencePng.absolutePath, outcome.refPath)
        assertArrayEquals(dat, File(layout.dir, "frame_0000.dat").readBytes())
        assertFalse("metadata.json is the caller's", "downloadFile:meta-1" in api.calls)
        assertEquals(2L to 2L, progress.last())
    }

    @Test
    fun `an artifact named to escape the session folder is refused`() {
        val f = fetch()
        api.files = listOf(api.file("dat-1", ArtifactRoles.DAT, dat).copy(name = "../../evil.dat"))

        val error = assertThrows(CorruptTransferException::class.java) { payload(f, split = false) }

        assertEquals("artifact_path_escapes_session", error.message)
        assertFalse(File(temp.root, "evil.dat").exists())
    }

    // ── planFromTail: the legacy bundle's prefix ─────────────────────────

    private fun noise(size: Int) = Random(size).nextBytes(size)

    private fun planFor(zip: ByteArray) = RestoreBundleFetcher.planFromTail(zip, 0L, zip.size.toLong())

    @Test
    fun `raw and dat first with the bulky rest after them is fetched as a prefix`() {
        val zip = FakeRestoreApi.zipOf(
            listOf(
                "raw/Reference.png" to reference,
                "dat/frame_0000.dat" to dat,
                "reports/frame_0000.pdf" to noise(200_000),
            ),
        )
        val plan = planFor(zip)

        assertNotNull(plan)
        assertTrue("the cut skips the reports", plan!!.cut < zip.size / 2)
        assertEquals(setOf("raw/Reference.png", "dat/frame_0000.dat"), plan.crcByName.keys)
    }

    @Test
    fun `a prefix that saves almost nothing is not worth the extra round trip`() {
        val zip = FakeRestoreApi.zipOf(
            listOf("raw/Reference.png" to noise(100_000), "dat/frame_0000.dat" to dat, "csv/a.csv" to byteArrayOf(1)),
        )
        assertNull(planFor(zip))
    }

    @Test
    fun `a layout with the payload interleaved, or an unreadable directory, falls back to the whole archive`() {
        val interleaved = FakeRestoreApi.zipOf(
            listOf(
                "raw/Reference.png" to reference,
                "reports/frame_0000.pdf" to noise(200_000),
                "dat/frame_0000.dat" to dat,
            ),
        )
        assertNull(planFor(interleaved))
        assertNull(planFor(noise(4_096)))
    }
}
