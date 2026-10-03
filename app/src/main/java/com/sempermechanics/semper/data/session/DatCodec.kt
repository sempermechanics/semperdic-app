// Binary record layout. TooManyFunctions: encode/decode, lattice detection,
// SoA+shuffle+deflate and reassembly all belong together as one codec, same
// rationale as SessionZip's own suppression.
@file:Suppress("TooManyFunctions")

package com.sempermechanics.semper.data.session

import com.sempermechanics.semper.field.DicResult
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Lossless **archive-only** codec for `.dat` frame payloads (see [DicResult] for the
 * on-disk float layout). Applied when building `Session.zip` and reversed on restore
 * unpack — the local `.dat` file on device is never touched by this, so
 * [DicResult.decodeDatFile]'s `mmap` + `asFloatBuffer` fast path (the viewer scrub hot
 * path) stays exactly as it was.
 *
 * Two transforms, chosen from what a real captured `.dat` actually contains — not a
 * generic "compress everything" pass:
 *
 * 1. **x/y are frequently a perfect dense grid** (the analysis-grid geometry from
 *    `step`/ROI). When they are, [Mode.DENSE] regenerates them on decode instead of
 *    storing them — verified at encode time by [detectDenseLattice], **never
 *    assumed**: a sparse subset (some candidate cells didn't converge), a
 *    non-uniform step, or any other shape falls back to [Mode.EXPLICIT], which stores
 *    x/y like every other field. Decode is exact either way.
 * 2. **Every remaining field is deinterleaved into columns** (structure-of-arrays)
 *    and **byte-plane shuffled** before deflate — grouping each float's near-constant
 *    sign/exponent byte together so deflate can actually model it. Measured on a real
 *    65,025-point capture with realistic (non-degenerate) displacement noise: blind
 *    deflate alone recovers 19% off raw; SoA + shuffle + deflate recovers 41% — see
 *    `docs/perf/on-device-characterization.md`.
 */
internal object DatCodec {

    private val MAGIC = byteArrayOf('S'.code.toByte(), 'D'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
    private const val FORMAT_VERSION = 1

    private enum class Mode(val id: Int) {
        DENSE(0), // x/y regenerated from grid geometry
        EXPLICIT(1), // x/y stored like any other field — always correct
    }

    /** Value fields when x/y are regenerated: u, v, exx, eyy, exy, znssd. */
    private const val FIELD_COUNT_DENSE = DicResult.STRIDE - 2

    /** All eight fields, x/y included. */
    private const val FIELD_COUNT_EXPLICIT = DicResult.STRIDE

    private const val COMPRESSION_BUFFER = 64 * 1024

    /**
     * Deflate's ceiling on expansion: one 258-byte match per 2 bits of stream. A
     * header claiming more output than its payload could ever inflate to is
     * corrupt, and is refused before anything that size is allocated.
     */
    private const val MAX_DEFLATE_RATIO = 1032L

    /** Slack over [MAX_DEFLATE_RATIO] for the stream's own header and final block. */
    private const val DEFLATE_RATIO_SLACK_BYTES = 1024L

    /**
     * Decode holds roughly four copies of the inflated size at once (inflated,
     * unshuffled, the field columns, the reassembled `.dat`), so a field larger than
     * this fraction of the heap could not decode anyway. Refusing it as corrupt keeps
     * a damaged header from ending in an [OutOfMemoryError], which nothing catches.
     */
    private const val MAX_HEAP_FRACTION_DIVISOR = 4L

    /**
     * Encode raw `.dat` bytes — exactly what [DicResult.decodeDatFile] reads, native
     * byte order, [DicResult.BYTES_PER_POINT]-aligned — into the compact archive form.
     */
    fun encode(rawBytes: ByteArray): ByteArray {
        require(rawBytes.size % DicResult.BYTES_PER_POINT == 0) {
            "not a whole number of DIC points: ${rawBytes.size} bytes"
        }
        val pointCount = rawBytes.size / DicResult.BYTES_PER_POINT
        val floats = FloatArray(pointCount * DicResult.STRIDE)
        ByteBuffer.wrap(rawBytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(floats)

        val lattice = detectDenseLattice(floats, pointCount)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.write(MAGIC)
            d.writeShort(FORMAT_VERSION)
            d.writeInt(pointCount)
            if (lattice != null) {
                d.writeByte(Mode.DENSE.id)
                d.writeFloat(lattice.originX)
                d.writeFloat(lattice.originY)
                d.writeInt(lattice.step)
                d.writeInt(lattice.gridW)
                d.writeInt(lattice.gridH)
                writePayload(d, shuffleDeflate(planes(floats, pointCount, skipXy = true)))
            } else {
                d.writeByte(Mode.EXPLICIT.id)
                writePayload(d, shuffleDeflate(planes(floats, pointCount, skipXy = false)))
            }
        }
        return out.toByteArray()
    }

    private fun writePayload(d: DataOutputStream, payload: ByteArray) {
        d.writeInt(payload.size)
        d.write(payload)
    }

    /**
     * [decode] [bytes] if they carry this codec's magic header; otherwise returns
     * [bytes] unchanged — a Session.zip built before this codec existed has raw
     * (uncompressed) `.dat` entries, and this lets restore/merge handle both without
     * a schema branch. A collision (real DIC output whose first bytes coincidentally
     * spell "SDC1") is not realistically possible: those bytes are always a `.dat`
     * x-coordinate, and `.dat` x is always a small non-negative pixel value — nowhere
     * near the bit pattern that ASCII "SDC1" forms as a float.
     */
    fun decodeIfEncoded(bytes: ByteArray): ByteArray =
        if (bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            decode(bytes)
        } else {
            bytes
        }

    /**
     * Inverse of [encode] — reproduces [DicResult]-layout bytes, bit-for-bit.
     *
     * [encoded] comes off the network, so every size in its header is checked before
     * it is used. A malformed archive fails with [IllegalArgumentException] (or
     * [IllegalStateException] / [java.io.EOFException] for a short read), which
     * `SessionZip` reports as a corrupt transfer, never as a checked zlib exception or
     * a decode that does not terminate. The inflated size must be reachable from the
     * payload at deflate's ratio ceiling **and** fit in a quarter of the heap, so a
     * damaged header is refused before anything that size is allocated. A decode can
     * still run out of memory when the heap is already mostly in use; that is not a
     * property of the archive.
     */
    fun decode(encoded: ByteArray): ByteArray {
        val d = DataInputStream(encoded.inputStream())
        val magic = ByteArray(MAGIC.size).also { d.readFully(it) }
        require(magic.contentEquals(MAGIC)) { "not a DatCodec archive (bad magic)" }
        val version = d.readUnsignedShort()
        require(version == FORMAT_VERSION) { "unsupported DatCodec version $version" }
        val pointCount = d.readInt()
        require(pointCount in 0..Int.MAX_VALUE / DicResult.BYTES_PER_POINT) {
            "DatCodec point count $pointCount out of range"
        }
        val modeId = d.readUnsignedByte()

        return when (modeId) {
            Mode.DENSE.id -> {
                val lattice = Lattice(
                    originX = d.readFloat(),
                    originY = d.readFloat(),
                    step = d.readInt(),
                    gridW = d.readInt(),
                    gridH = d.readInt(),
                )
                val payload = readPayload(d)
                val fields = inflateUnshuffle(payload, pointCount, FIELD_COUNT_DENSE)
                reassembleDense(fields, pointCount, lattice)
            }
            Mode.EXPLICIT.id -> {
                val payload = readPayload(d)
                val fields = inflateUnshuffle(payload, pointCount, FIELD_COUNT_EXPLICIT)
                reassembleExplicit(fields, pointCount)
            }
            else -> error("unknown DatCodec mode $modeId")
        }
    }

    private fun readPayload(d: DataInputStream): ByteArray {
        val len = d.readInt()
        // The stream wraps an in-memory array, so available() is exactly what is left.
        require(len in 0..d.available()) { "DatCodec payload length $len exceeds the archive" }
        return ByteArray(len).also { d.readFully(it) }
    }

    // ── lattice detection ────────────────────────────────────────────────────

    private data class Lattice(
        val originX: Float,
        val originY: Float,
        val step: Int,
        val gridW: Int,
        val gridH: Int,
    )

    /**
     * Verifies the point set is *exactly* the full dense row-major lattice implied by
     * its own x/y values, **in that exact order** — not merely the same set. DENSE
     * reconstruction emits points in row-major scan order, so requiring the input to
     * already be in that order (rather than allowing DENSE to silently reorder a
     * matching-but-shuffled set) means choosing DENSE can never change point order
     * relative to what the engine produced. Nothing in the app depends on `.dat` point
     * order today (every consumer reads points by x/y, never by array index — heatmap
     * grid placement, field stats, CSV, `PointSpatialIndex`), but this keeps the codec
     * from being the first thing that could.
     *
     * Returns null (→ [Mode.EXPLICIT]) on any deviation: too few or too many points
     * for the grid the x/y values imply, a non-uniform step, or an out-of-order /
     * duplicate / missing cell. Never assumes — every point is checked.
     */
    @Suppress("ReturnCount") // each early return is a distinct disqualifying condition — clearer as guards
    private fun detectDenseLattice(floats: FloatArray, pointCount: Int): Lattice? {
        if (pointCount == 0) return null
        val xs = sortedSetOf<Float>()
        val ys = sortedSetOf<Float>()
        for (i in 0 until pointCount) {
            xs += floats[i * DicResult.STRIDE + DicResult.IDX_X]
            ys += floats[i * DicResult.STRIDE + DicResult.IDX_Y]
        }
        val gridW = xs.size
        val gridH = ys.size
        if (gridW.toLong() * gridH.toLong() != pointCount.toLong()) return null

        val xList = xs.toFloatArray()
        val yList = ys.toFloatArray()
        val step = inferUniformStep(xList) ?: return null
        if (inferUniformStep(yList) != step) return null

        for (i in 0 until pointCount) {
            val x = floats[i * DicResult.STRIDE + DicResult.IDX_X]
            val y = floats[i * DicResult.STRIDE + DicResult.IDX_Y]
            if (xList[i % gridW] != x || yList[i / gridW] != y) return null
        }
        return Lattice(xList[0], yList[0], step, gridW, gridH)
    }

    /** Smallest positive gap between consecutive sorted-distinct values, verified uniform throughout. */
    @Suppress("ReturnCount") // each early return is a distinct disqualifying condition — clearer as guards
    private fun inferUniformStep(sortedDistinct: FloatArray): Int? {
        if (sortedDistinct.size < 2) return null
        val step = sortedDistinct[1] - sortedDistinct[0]
        if (step <= 0f || step != kotlin.math.floor(step)) return null
        for (i in 2 until sortedDistinct.size) {
            if (sortedDistinct[i] - sortedDistinct[i - 1] != step) return null
        }
        return step.toInt()
    }

    // ── structure-of-arrays + byte-shuffle + deflate ─────────────────────────

    /** [floats] (point-major, stride [DicResult.STRIDE]) as [fieldCount] value columns. */
    private fun planes(floats: FloatArray, pointCount: Int, skipXy: Boolean): Array<FloatArray> {
        val firstField = if (skipXy) 2 else 0 // skip IDX_X, IDX_Y
        val fieldCount = DicResult.STRIDE - firstField
        return Array(fieldCount) { f ->
            val srcIdx = firstField + f
            FloatArray(pointCount) { p -> floats[p * DicResult.STRIDE + srcIdx] }
        }
    }

    /** Columns -> concatenated little-endian bytes -> byte-plane shuffle -> deflate. */
    private fun shuffleDeflate(fieldPlanes: Array<FloatArray>): ByteArray {
        val pointCount = fieldPlanes.firstOrNull()?.size ?: 0
        val fieldCount = fieldPlanes.size
        val soaBytes = ByteBuffer.allocate(fieldCount * pointCount * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (plane in fieldPlanes) for (v in plane) soaBytes.putFloat(v)
        return deflate(byteShuffle(soaBytes.array()))
    }

    private fun inflateUnshuffle(payload: ByteArray, pointCount: Int, fieldCount: Int): Array<FloatArray> {
        val expectedLong = fieldCount.toLong() * pointCount * Float.SIZE_BYTES
        require(expectedLong <= payload.size * MAX_DEFLATE_RATIO + DEFLATE_RATIO_SLACK_BYTES) {
            "DatCodec claims $expectedLong bytes from a ${payload.size}-byte payload"
        }
        require(expectedLong <= Runtime.getRuntime().maxMemory() / MAX_HEAP_FRACTION_DIVISOR) {
            "DatCodec claims $expectedLong bytes, more than this heap can decode"
        }
        // Fits: decode bounds pointCount so that fieldCount (<= STRIDE) points do.
        val expected = expectedLong.toInt()
        val shuffled = inflate(payload, expected)
        val soaBytes = byteUnshuffle(shuffled)
        val buf = ByteBuffer.wrap(soaBytes).order(ByteOrder.LITTLE_ENDIAN)
        return Array(fieldCount) { FloatArray(pointCount) { buf.float } }
    }

    /**
     * Groups byte 0 of every float, then byte 1, … — the HDF5/Blosc "shuffle" filter.
     * [bytes].size must be a multiple of [Float.SIZE_BYTES].
     */
    private fun byteShuffle(bytes: ByteArray): ByteArray {
        val floatCount = bytes.size / Float.SIZE_BYTES
        val out = ByteArray(bytes.size)
        for (b in 0 until Float.SIZE_BYTES) {
            val planeOffset = b * floatCount
            for (f in 0 until floatCount) {
                out[planeOffset + f] = bytes[f * Float.SIZE_BYTES + b]
            }
        }
        return out
    }

    private fun byteUnshuffle(bytes: ByteArray): ByteArray {
        val floatCount = bytes.size / Float.SIZE_BYTES
        val out = ByteArray(bytes.size)
        for (b in 0 until Float.SIZE_BYTES) {
            val planeOffset = b * floatCount
            for (f in 0 until floatCount) {
                out[f * Float.SIZE_BYTES + b] = bytes[planeOffset + f]
            }
        }
        return out
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream(data.size / 2 + COMPRESSION_BUFFER)
            val buf = ByteArray(COMPRESSION_BUFFER)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end() // frees native zlib memory now, not whenever a cleaner runs
        }
    }

    private fun inflate(data: ByteArray, expectedSize: Int): ByteArray {
        if (expectedSize == 0) return ByteArray(0)
        val inflater = Inflater(false)
        try {
            inflater.setInput(data)
            val out = ByteArray(expectedSize)
            var written = 0
            while (!inflater.finished() && written < expectedSize) {
                val n = inflater.inflate(out, written, expectedSize - written)
                // No progress and none possible: out of input, or the stream wants a
                // preset dictionary (encode never sets one), which would otherwise spin
                // here forever. Either way the stream is malformed; checked below.
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                written += n
            }
            check(written == expectedSize) { "DatCodec inflate size mismatch: $written != $expectedSize" }
            return out
        } catch (e: DataFormatException) {
            throw IllegalArgumentException("DatCodec payload is not a valid deflate stream", e)
        } finally {
            inflater.end()
        }
    }

    // ── reassembly ────────────────────────────────────────────────────────

    private fun reassembleDense(fields: Array<FloatArray>, pointCount: Int, lattice: Lattice): ByteArray {
        check(lattice.gridW.toLong() * lattice.gridH.toLong() == pointCount.toLong()) {
            "DatCodec DENSE grid ${lattice.gridW} x ${lattice.gridH} does not match point count $pointCount"
        }
        // A (-2) x (-2) grid passes the product check above and would emit no points.
        require(lattice.gridW >= 0 && lattice.gridH >= 0 && lattice.step > 0) {
            "DatCodec DENSE grid ${lattice.gridW} x ${lattice.gridH} step ${lattice.step} is malformed"
        }
        val out = ByteBuffer.allocate(pointCount * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
        var p = 0
        for (row in 0 until lattice.gridH) {
            val y = lattice.originY + row * lattice.step
            for (col in 0 until lattice.gridW) {
                val x = lattice.originX + col * lattice.step
                out.putFloat(x)
                out.putFloat(y)
                for (f in 0 until FIELD_COUNT_DENSE) out.putFloat(fields[f][p])
                p++
            }
        }
        return out.array()
    }

    private fun reassembleExplicit(fields: Array<FloatArray>, pointCount: Int): ByteArray {
        val out = ByteBuffer.allocate(pointCount * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
        for (p in 0 until pointCount) {
            for (f in 0 until FIELD_COUNT_EXPLICIT) out.putFloat(fields[f][p])
        }
        return out.array()
    }
}
