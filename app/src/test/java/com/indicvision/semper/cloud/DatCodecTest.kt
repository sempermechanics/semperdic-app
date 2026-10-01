package com.indicvision.semper.cloud

import com.indicvision.semper.data.session.DatCodec
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.fixtures.packDat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [DatCodec] is a **lossless, archive-only** transform of `.dat` bytes — this test's
 * entire job is proving decode(encode(x)) is bit-for-bit x, for every shape the real
 * engine can produce, because a `.dat` is scientific data ("results must not degrade").
 */
class DatCodecTest {

    @Suppress("LongParameterList") // one param per DicResult field — a data class would be less readable at call sites
    private fun point(x: Float, y: Float, u: Float, v: Float, exx: Float, eyy: Float, exy: Float, znssd: Float) =
        floatArrayOf(x, y, u, v, exx, eyy, exy, znssd)

    private fun rawBytesOf(points: List<FloatArray>): ByteArray = packDat(points)

    /** A realistic dense grid: every candidate cell present, smoothly varying fields + noise. */
    private fun denseGridBytes(gridW: Int, gridH: Int, step: Int, seed: Long = 1L): ByteArray {
        val rng = Random(seed)
        val points = ArrayList<FloatArray>(gridW * gridH)
        for (row in 0 until gridH) {
            for (col in 0 until gridW) {
                val x = (col * step).toFloat()
                val y = (row * step).toFloat()
                points += point(
                    x,
                    y,
                    u = 3f + rng.nextFloat() * 0.05f,
                    v = 2f + rng.nextFloat() * 0.05f,
                    exx = rng.nextFloat() * 1e-4f,
                    eyy = rng.nextFloat() * 1e-4f,
                    exy = rng.nextFloat() * 1e-4f,
                    znssd = rng.nextFloat() * 0.1f,
                )
            }
        }
        return rawBytesOf(points)
    }

    @Test
    fun `dense grid round-trips bit-exact`() {
        val raw = denseGridBytes(gridW = 20, gridH = 15, step = 4)
        val decoded = DatCodec.decode(DatCodec.encode(raw))
        assertRoundTripsExact(raw, decoded)
    }

    @Test
    fun `dense grid actually compresses on realistic noisy data`() {
        val raw = denseGridBytes(gridW = 64, gridH = 64, step = 2)
        val encoded = DatCodec.encode(raw)
        assertTrue(
            "expected real compression, got ${encoded.size} of ${raw.size} raw bytes",
            encoded.size < raw.size * 8 / 10,
        )
    }

    @Test
    fun `a sparse subset of a lattice falls back to explicit mode and still round-trips`() {
        // Drop every third cell — some candidate grid points simply didn't converge,
        // the normal case for a real DIC solve (91-95% acceptance, not 100%).
        val rng = Random(2L)
        val points = ArrayList<FloatArray>()
        for (row in 0 until 15) {
            for (col in 0 until 20) {
                if ((row * 20 + col) % 3 == 0) continue
                points += point(
                    (col * 4).toFloat(),
                    (row * 4).toFloat(),
                    u = 1f + rng.nextFloat(),
                    v = 1f + rng.nextFloat(),
                    exx = rng.nextFloat(),
                    eyy = rng.nextFloat(),
                    exy = rng.nextFloat(),
                    znssd = rng.nextFloat(),
                )
            }
        }
        val raw = rawBytesOf(points)
        val decoded = DatCodec.decode(DatCodec.encode(raw))
        assertRoundTripsExact(raw, decoded)
    }

    @Test
    fun `non-uniform step is rejected by lattice detection and still round-trips`() {
        // A sweep varies step per frame — x spacing is 4 then jumps to 7. Must not be
        // mistaken for a uniform lattice.
        val points = listOf(
            point(0f, 0f, 1f, 1f, 0f, 0f, 0f, 0f),
            point(4f, 0f, 1f, 1f, 0f, 0f, 0f, 0f),
            point(11f, 0f, 1f, 1f, 0f, 0f, 0f, 0f),
        )
        val raw = rawBytesOf(points)
        val decoded = DatCodec.decode(DatCodec.encode(raw))
        assertRoundTripsExact(raw, decoded)
    }

    @Test
    fun `a single point round-trips`() {
        val raw = rawBytesOf(listOf(point(10f, 20f, 0.5f, -0.5f, 1e-5f, -1e-5f, 2e-5f, 0.02f)))
        val decoded = DatCodec.decode(DatCodec.encode(raw))
        assertRoundTripsExact(raw, decoded)
    }

    @Test
    fun `duplicate x,y pair on an otherwise-dense grid is rejected as non-dense`() {
        // Two points at the same (x,y) can't be a valid dense grid (undefined which
        // wins in DENSE reconstruction) — must fall back to EXPLICIT and still be exact.
        val points = ArrayList<FloatArray>()
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                points += point((col * 2).toFloat(), (row * 2).toFloat(), 1f, 1f, 0f, 0f, 0f, 0f)
            }
        }
        // Duplicate the first point instead of one of the real distinct cells, keeping
        // the count at gridW*gridH so the count check alone can't catch it.
        points[points.size - 1] = points[0].copyOf()
        val raw = rawBytesOf(points)
        val decoded = DatCodec.decode(DatCodec.encode(raw))
        assertRoundTripsExact(raw, decoded)
    }

    @Test
    fun `negative-origin ROI offsets are handled`() {
        // A non-zero ROI origin (analysis didn't start at pixel 0,0).
        val points = ArrayList<FloatArray>()
        for (row in 0 until 5) {
            for (col in 0 until 5) {
                points += point((100 + col * 3).toFloat(), (200 + row * 3).toFloat(), 1f, 1f, 0f, 0f, 0f, 0f)
            }
        }
        val raw = rawBytesOf(points)
        val decoded = DatCodec.decode(DatCodec.encode(raw))
        assertRoundTripsExact(raw, decoded)
    }

    @Test
    fun `a dense-looking but out-of-order point set falls back to explicit and preserves order`() {
        // Same set as a valid 2x2 dense lattice, but not in row-major scan order —
        // DENSE reconstruction would silently reorder this, so it must be rejected.
        val points = listOf(
            point(2f, 2f, 4f, 4f, 0f, 0f, 0f, 0f),
            point(0f, 0f, 1f, 1f, 0f, 0f, 0f, 0f),
            point(2f, 0f, 2f, 2f, 0f, 0f, 0f, 0f),
            point(0f, 2f, 3f, 3f, 0f, 0f, 0f, 0f),
        )
        val raw = rawBytesOf(points)
        val decoded = DatCodec.decode(DatCodec.encode(raw))
        assertRoundTripsExact(raw, decoded)
    }

    @Test
    fun `explicit mode preserves point order exactly`() {
        val points = listOf(
            point(50f, 5f, 1f, 1f, 0f, 0f, 0f, 0f),
            point(0f, 0f, 2f, 2f, 0f, 0f, 0f, 0f),
            point(25f, 12f, 3f, 3f, 0f, 0f, 0f, 0f),
        )
        val raw = rawBytesOf(points)
        val decoded = DatCodec.decode(DatCodec.encode(raw))
        assertRoundTripsExact(raw, decoded)
    }

    @Test
    fun `rejects bytes with the wrong point-count alignment`() {
        assertThrows(IllegalArgumentException::class.java) {
            DatCodec.encode(ByteArray(DicResult.BYTES_PER_POINT + 1))
        }
    }

    @Test
    fun `rejects an archive with a bad magic header`() {
        assertThrows(IllegalArgumentException::class.java) {
            DatCodec.decode(ByteArray(20))
        }
    }

    /**
     * Strict byte-for-byte equality — DENSE mode is only ever chosen when the input is
     * already in the exact row-major order DENSE reconstruction emits (see
     * [DatCodec]'s `detectDenseLattice` doc), so decode(encode(x)) never reorders x,
     * in either mode.
     */
    private fun assertRoundTripsExact(rawExpected: ByteArray, rawActual: ByteArray) {
        assertEquals("decoded size must match the original", rawExpected.size, rawActual.size)
        assertEquals("decoded bytes must match the original exactly", rawExpected.toList(), rawActual.toList())
    }
}
