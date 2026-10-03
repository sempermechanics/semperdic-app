package com.sempermechanics.semper.results

import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.FieldRangesStore
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.ui.viewer.summary.SummaryAnimation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The two decisions the animation rests on: how long a frame is shown, and what
 * colour scale every frame is drawn against.
 */
class SummaryAnimationTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** One frame of correlated points whose Exx spans [low, high]. */
    private fun frame(name: String, low: Float, high: Float, points: Int = 100): File {
        val buffer = ByteBuffer.allocate(points * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
        for (i in 0 until points) {
            val t = i.toFloat() / (points - 1)
            buffer.putFloat((i % 10) * 4f) // x
            buffer.putFloat((i / 10) * 4f) // y
            buffer.putFloat(0f) // u
            buffer.putFloat(0f) // v
            buffer.putFloat(low + t * (high - low)) // exx
            buffer.putFloat(0f) // eyy
            buffer.putFloat(0f) // exy
            buffer.putFloat(0.01f) // znssd — accepted
        }
        return temp.newFile(name).apply { writeBytes(buffer.array()) }
    }

    // ── delayCentis ──────────────────────────────────────────────────────

    @Test
    fun `a short sequence gets the preferred 300ms a frame`() {
        for (frames in 1..33) {
            assertEquals("$frames frames", SummaryAnimation.PREFERRED_CENTIS, SummaryAnimation.delayCentis(frames))
        }
    }

    @Test
    fun `a long sequence keeps every frame and stays inside the ceiling`() {
        // Max frames per analysis is 500; nothing may be dropped to fit 10 s.
        for (frames in 34..500) {
            val delay = SummaryAnimation.delayCentis(frames)
            val total = frames * delay
            assertTrue("$frames frames ran to $total cs", total <= SummaryAnimation.MAX_TOTAL_CENTIS)
            assertTrue("$frames frames used $delay cs", delay >= SummaryAnimation.MIN_CENTIS)
            assertTrue("$frames frames should not exceed the preference", delay <= SummaryAnimation.PREFERRED_CENTIS)
        }
    }

    @Test
    fun `150 frames runs for about nine seconds at 60ms each`() {
        assertEquals(6, SummaryAnimation.delayCentis(150))
    }

    @Test
    fun `an absurd frame count still yields a delay a viewer will honour`() {
        assertEquals(SummaryAnimation.MIN_CENTIS, SummaryAnimation.delayCentis(100_000))
    }

    @Test
    fun `no frames is not a division by zero`() {
        assertEquals(SummaryAnimation.PREFERRED_CENTIS, SummaryAnimation.delayCentis(0))
    }

    @Test
    fun `gif cache filename includes the canvas colour`() {
        val anim = SummaryAnimation(
            SummaryAnimation.Spec(
                batchFiles = emptyList(),
                imgW = 1,
                imgH = 1,
                stepAt = { 1 },
                outputDir = temp.root,
                backgroundColor = 0xFFF4F9FC.toInt(),
            ),
        )
        assertEquals("U_animation_FFF4F9FC_auto.gif", anim.fileFor("U").name)
    }

    @Test
    fun `gif cache filename includes the fit box when set`() {
        val anim = SummaryAnimation(
            SummaryAnimation.Spec(
                batchFiles = emptyList(),
                imgW = 100,
                imgH = 100,
                stepAt = { 1 },
                outputDir = temp.root,
                backgroundColor = 0xFF101518.toInt(),
                fitBounds = floatArrayOf(10f, 20f, 40f, 80f),
            ),
        )
        assertEquals("Exx_animation_FF101518_10-20-40-80.gif", anim.fileFor("Exx").name)
    }

    // ── globalRanges ─────────────────────────────────────────────────────

    @Test
    fun `the range spans every frame, not just the last`() {
        // Frame 2 holds the lowest value, frame 3 the highest: neither alone
        // describes the sequence.
        val files = listOf(
            frame("a.dat", 0f, 1f),
            frame("b.dat", -5f, 0.5f),
            frame("c.dat", 0f, 9f),
        )

        val exx = runBlocking { SummaryAnimation.globalRanges(files) }.getValue(DicResult.IDX_EXX)

        assertTrue("low ${exx.first} should come from the second frame", exx.first < -4f)
        assertTrue("high ${exx.second} should come from the third frame", exx.second > 8f)
    }

    @Test
    fun `a later frame's wider colour bar still sets the sequence scale`() {
        // GIF max = max of each frame's scale-max; GIF min = min of each
        // frame's scale-min. Those two ends need not come from the same frame.
        val files = listOf(
            frame("early.dat", -200f, 50f),
            frame("late.dat", -50f, 400f),
        )
        val fields = intArrayOf(DicResult.IDX_EXX)
        val early = requireNotNull(
            VisualizationEngine.valueRanges(
                requireNotNull(DicResult.decodeDatFile(files[0])),
                fields,
            )[DicResult.IDX_EXX],
        )
        val late = requireNotNull(
            VisualizationEngine.valueRanges(
                requireNotNull(DicResult.decodeDatFile(files[1])),
                fields,
            )[DicResult.IDX_EXX],
        )

        val exx = runBlocking { SummaryAnimation.globalRanges(files) }.getValue(DicResult.IDX_EXX)

        assertEquals(minOf(early.first, late.first), exx.first, 0f)
        assertEquals(maxOf(early.second, late.second), exx.second, 0f)
        assertTrue("later frame should raise the sequence max", exx.second > early.second)
        assertTrue("earlier frame should keep the sequence min", exx.first < late.first)
    }

    @Test
    fun `all five fields come back from one pass`() {
        val ranges = runBlocking { SummaryAnimation.globalRanges(listOf(frame("a.dat", 0f, 1f))) }

        for ((label, index) in SummaryAnimation.FIELDS) {
            assertTrue("$label missing", ranges.containsKey(index))
        }
    }

    @Test
    fun `unreadable frames are skipped rather than aborting the pass`() {
        val broken = temp.newFile("broken.dat").apply { writeBytes(ByteArray(7)) }
        val files = listOf(broken, frame("good.dat", 0f, 4f))

        val exx = runBlocking { SummaryAnimation.globalRanges(files) }.getValue(DicResult.IDX_EXX)

        assertTrue("high ${exx.second} should still reflect the good frame", exx.second > 3f)
    }

    @Test
    fun `no frames yields no ranges`() {
        assertTrue(runBlocking { SummaryAnimation.globalRanges(emptyList()) }.isEmpty())
    }

    @Test
    fun `globalRanges reports progress for every frame including unreadable`() = runBlocking {
        val broken = temp.newFile("broken.dat").apply { writeBytes(ByteArray(7)) }
        val files = listOf(broken, frame("good.dat", 0f, 4f))
        val ticks = mutableListOf<Pair<Int, Int>>()

        SummaryAnimation.globalRanges(files) { done, total -> ticks += done to total }

        assertEquals(listOf(1 to 2, 2 to 2), ticks)
    }

    // ── globalRanges: cached (FieldRangesStore) vs decoded must agree exactly ──

    private val summaryFieldIndices = intArrayOf(
        DicResult.IDX_U,
        DicResult.IDX_V,
        DicResult.IDX_EXX,
        DicResult.IDX_EYY,
        DicResult.IDX_EXY,
    )

    /** [FieldRangesStore.write]'s input, computed the same way AnalysisViewModel does. */
    private fun rangesFileFor(files: List<File>): File {
        val perFrame = files.map { f ->
            DicResult.decodeDatFile(f)?.let { VisualizationEngine.valueRanges(it, summaryFieldIndices) }
                ?: emptyMap()
        }
        val out = temp.newFile("field_ranges_${files.hashCode()}.bin")
        FieldRangesStore.write(out, summaryFieldIndices, perFrame)
        return out
    }

    @Test
    fun `a valid cache produces exactly the same ranges as decoding every frame`() = runBlocking {
        val files = listOf(
            frame("a.dat", 0f, 1f),
            frame("b.dat", -5f, 0.5f),
            frame("c.dat", 0f, 9f),
        )
        val rangesFile = rangesFileFor(files)

        val decoded = SummaryAnimation.globalRanges(files)
        val cached = SummaryAnimation.globalRanges(files, rangesFile)

        assertEquals(decoded, cached)
    }

    @Test
    fun `a missing cache file falls back to decoding, same result`() = runBlocking {
        val files = listOf(frame("a.dat", 0f, 1f), frame("b.dat", -5f, 0.5f))
        val missingCache = temp.root.resolve("does_not_exist.bin")

        val decoded = SummaryAnimation.globalRanges(files)
        val fallback = SummaryAnimation.globalRanges(files, missingCache)

        assertEquals(decoded, fallback)
    }

    @Test
    fun `a cache whose frame count no longer matches the batch falls back to decoding`() = runBlocking {
        // Simulates a batch edited after the cache was written (e.g. a re-run that
        // added a frame) — the stale cache must never be trusted over the real files.
        val originalFiles = listOf(frame("a.dat", 0f, 1f), frame("b.dat", -5f, 0.5f))
        val staleCache = rangesFileFor(originalFiles)
        val grownFiles = originalFiles + frame("c.dat", 0f, 9f)

        val decoded = SummaryAnimation.globalRanges(grownFiles)
        val withStaleCache = SummaryAnimation.globalRanges(grownFiles, staleCache)

        assertEquals(decoded, withStaleCache)
        // Not the vacuous case — the stale cache really did omit the third frame's range.
        assertTrue(decoded.getValue(DicResult.IDX_EXX).second > 8f)
    }

    @Test
    fun `frames of different sizes give the ranges each frame gives on its own`() = runBlocking {
        // The pass reuses one frame buffer and one set of columns, growing them for a
        // larger frame (TD-87): small after large and large after small must both
        // match decoding every frame separately.
        val files = listOf(
            frame("big.dat", -3f, 2f, points = 300),
            frame("small.dat", 0f, 9f, points = 40),
            frame("bigger.dat", -8f, 1f, points = 500),
        )
        val expected = mutableMapOf<Int, Pair<Float, Float>>()
        for (file in files) {
            val data = requireNotNull(DicResult.decodeDatFile(file))
            VisualizationEngine.valueRanges(data, summaryFieldIndices).forEach { (valIndex, range) ->
                if (range != null) {
                    val seen = expected[valIndex]
                    expected[valIndex] = if (seen == null) {
                        range
                    } else {
                        minOf(seen.first, range.first) to maxOf(seen.second, range.second)
                    }
                }
            }
        }

        assertEquals(expected, SummaryAnimation.globalRanges(files))
    }

    @Test
    fun `a decode with no sidecar writes one the next call reads`() = runBlocking {
        // A session restored from the cloud has no sidecar, and the viewer runs this
        // pass on every open: without the write-back each open decoded every frame.
        val files = listOf(frame("a.dat", 0f, 1f), frame("b.dat", -5f, 0.5f), frame("c.dat", 0f, 9f))
        val rangesFile = temp.root.resolve(FieldRangesStore.FILE_NAME)

        val decoded = SummaryAnimation.globalRanges(files, rangesFile)

        val saved = FieldRangesStore.read(rangesFile, summaryFieldIndices)
        assertEquals(3, saved?.size)
        assertEquals(decoded, SummaryAnimation.globalRanges(files, rangesFile))
        val part = rangesFile.resolveSibling("${rangesFile.name}.part")
        assertFalse("the .part sidecar is moved into place", part.exists())
    }

    @Test
    fun `a stale sidecar is replaced by the decode it forced`() = runBlocking {
        val originalFiles = listOf(frame("a.dat", 0f, 1f), frame("b.dat", -5f, 0.5f))
        val rangesFile = rangesFileFor(originalFiles)
        val grownFiles = originalFiles + frame("c.dat", 0f, 9f)

        SummaryAnimation.globalRanges(grownFiles, rangesFile)

        assertEquals(3, FieldRangesStore.read(rangesFile, summaryFieldIndices)?.size)
    }

    @Test
    fun `an unreadable frame leaves no sidecar behind`() = runBlocking {
        // Saving it would record the frame as having no points, for good.
        val broken = temp.newFile("broken.dat").apply { writeBytes(ByteArray(7)) }
        val rangesFile = temp.root.resolve(FieldRangesStore.FILE_NAME)

        SummaryAnimation.globalRanges(listOf(broken, frame("good.dat", 0f, 4f)), rangesFile)

        assertFalse(rangesFile.exists())
    }

    @Test
    fun `cached progress reporting still covers every frame`() = runBlocking {
        val files = listOf(frame("a.dat", 0f, 1f), frame("b.dat", -5f, 0.5f))
        val rangesFile = rangesFileFor(files)
        val ticks = mutableListOf<Pair<Int, Int>>()

        SummaryAnimation.globalRanges(files, rangesFile) { done, total -> ticks += done to total }

        assertEquals(listOf(1 to 2, 2 to 2), ticks)
    }
}
