package com.sempermechanics.semper.fixtures

import com.sempermechanics.semper.field.DicResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Packs points of [DicResult.STRIDE] floats (`x y u v exx eyy exy znssd`) as `.dat` bytes, native order. */
fun packDat(points: List<FloatArray>): ByteArray {
    val out = ByteBuffer.allocate(points.size * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
    for (p in points) for (v in p) out.putFloat(v)
    return out.array()
}

/**
 * Frame [frame] of the viewer tests' batch: a [grid] × [grid] lattice [step] px
 * apart, every point accepted (ZNSSD 0.01), with u = [frame] and
 * exx = [frame] × 0.001 so each frame reads differently.
 */
fun gridFrame(frame: Int, grid: Int, step: Int): ByteArray = packDat(
    List(grid * grid) { i ->
        floatArrayOf(
            ((i % grid) * step).toFloat(),
            ((i / grid) * step).toFloat(),
            frame.toFloat(),
            0f,
            frame * 0.001f,
            0f,
            0f,
            0.01f,
        )
    },
)

/** Writes [gridFrame] 0 until [frames] into [dir] as `frame_000.dat`, `frame_001.dat`, … */
fun writeGridBatch(dir: File, frames: Int, grid: Int, step: Int) {
    for (f in 0 until frames) {
        File(dir, "frame_%03d.dat".format(f)).writeBytes(gridFrame(f, grid, step))
    }
}
