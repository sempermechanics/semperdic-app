package com.indicvision.semper.results

import com.indicvision.semper.DicResult
import com.indicvision.semper.ui.viewer.SummaryAnimation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The viewer's summary slot and the share sheet build the same GIF on
 * background threads (H8). Whoever comes second must get the file the first
 * one wrote, byte for byte, not a `.part` both of them were writing into.
 *
 * Robolectric for the jet palette, which is built from `Color.rgb`.
 */
@RunWith(RobolectricTestRunner::class)
class SummaryAnimationBuildTest {

    @get:Rule
    val temp = TemporaryFolder()

    private companion object {
        const val GRID = 10
        const val STEP = 4
        const val FRAMES = 4
        val BOUNDS = -1f to 5f

        /** Long enough for the second build to finish if nothing holds it back. */
        const val OVERLAP_WAIT_MS = 1_500L
    }

    private fun writeFrames(dir: File): List<File> = (0 until FRAMES).map { f ->
        val points = GRID * GRID
        val buffer = ByteBuffer.allocate(points * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
        for (i in 0 until points) {
            buffer.putFloat((i % GRID) * STEP.toFloat())
            buffer.putFloat((i / GRID) * STEP.toFloat())
            buffer.putFloat(f + i * 0.01f) // u — differs per frame and point
            buffer.putFloat(0f)
            buffer.putFloat(0f).putFloat(0f).putFloat(0f)
            buffer.putFloat(0.01f)
        }
        File(dir, "frame_%04d.dat".format(f)).apply { writeBytes(buffer.array()) }
    }

    private fun animation(frames: List<File>, out: File) = SummaryAnimation(
        SummaryAnimation.Spec(
            batchFiles = frames,
            imgW = GRID * STEP,
            imgH = GRID * STEP,
            stepAt = { STEP },
            outputDir = out,
            backgroundColor = 0xFF101518.toInt(),
        ),
    )

    @Test
    fun `two builds at once leave one valid file identical to a single build`() {
        // The reference: one build, alone, in its own folder.
        val alone = temp.newFolder("alone")
        val expected = runBlocking {
            animation(writeFrames(temp.newFolder("aloneFrames")), alone).build(DicResult.IDX_U, "U", BOUNDS)
        }?.readBytes()
        assertNotNull(expected)

        val frames = writeFrames(temp.newFolder("frames"))
        val shared = temp.newFolder("shared")
        // One instance, shared by the viewer and Share, as the viewer does.
        val anim = animation(frames, shared)
        val firstStarted = CountDownLatch(1)
        val secondDone = CountDownLatch(1)

        val (first, second) = runBlocking(Dispatchers.Default) {
            val first = async {
                var paused = false
                anim.build(DicResult.IDX_U, "U", BOUNDS) { _, _ ->
                    if (!paused) {
                        paused = true
                        firstStarted.countDown()
                        // Hold the first encode open mid-file while the second runs.
                        secondDone.await(OVERLAP_WAIT_MS, TimeUnit.MILLISECONDS)
                    }
                }
            }
            firstStarted.await()
            val second = async {
                try {
                    anim.build(DicResult.IDX_U, "U", BOUNDS)
                } finally {
                    secondDone.countDown()
                }
            }
            first.await() to second.await()
        }

        assertNotNull("the first build lost its file", first)
        assertNotNull("the second build lost its file", second)
        assertEquals(first, second)
        assertArrayEquals(expected, first!!.readBytes())
        assertFalse("no .part left behind", File(shared, "${first.name}.part").exists())
    }

    @Test
    fun `a second instance over the same frames reuses the file the first built`() {
        val frames = writeFrames(temp.newFolder("frames"))
        val out = temp.newFolder("out")
        val built = runBlocking { animation(frames, out).build(DicResult.IDX_U, "U", BOUNDS) }!!
        val stamp = built.lastModified() - 10_000
        built.setLastModified(stamp)

        // The viewer after a rotation: a new instance, the same session.
        val again = animation(frames, out)

        assertEquals(true, again.isBuilt(DicResult.IDX_U, "U", BOUNDS))
        assertEquals(built, runBlocking { again.build(DicResult.IDX_U, "U", BOUNDS) })
        assertEquals("not re-encoded", stamp, built.lastModified())
    }

    @Test
    fun `another session's file under the same name is not taken for this one's`() {
        val out = temp.newFolder("out")
        runBlocking { animation(writeFrames(temp.newFolder("a")), out).build(DicResult.IDX_U, "U", BOUNDS) }

        val other = animation(writeFrames(temp.newFolder("b")), out)

        assertFalse(other.isBuilt(DicResult.IDX_U, "U", BOUNDS))
    }

    @Test
    fun `frames re-solved in place are not taken for the ones the file was built from`() {
        val dir = temp.newFolder("frames")
        val out = temp.newFolder("out")
        val frames = writeFrames(dir)
        runBlocking { animation(frames, out).build(DicResult.IDX_U, "U", BOUNDS) }

        // Same folder, same frame count, same size: one frame solved again later.
        // (Only its time moves; the build mapped the file, which Windows will
        // not let a test rewrite.)
        val last = frames.last()
        last.setLastModified(last.lastModified() + 60_000)

        assertFalse(animation(frames, out).isBuilt(DicResult.IDX_U, "U", BOUNDS))
    }
}
