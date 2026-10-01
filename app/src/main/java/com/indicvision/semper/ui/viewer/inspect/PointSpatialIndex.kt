// Spatial-index grid math: literal bucket sizes and the tight nearest-neighbour
// scan read clearest inline, so MagicNumber / NestedBlockDepth are suppressed.
@file:Suppress("MagicNumber", "NestedBlockDepth")

package com.indicvision.semper.ui.viewer.inspect

import com.indicvision.semper.field.DicResult
import kotlin.math.floor

/**
 * Grid-bucket nearest-point lookup for inspect mode. Built once per frame load
 * so ACTION_MOVE is O(nearby cells) instead of O(n) over every DIC point.
 */
class PointSpatialIndex private constructor(
    private val data: FloatArray,
    private val cellSize: Float,
    private val buckets: Map<Long, IntArray>,
) {

    fun nearest(physX: Float, physY: Float, searchRadius: Float): Int {
        if (cellSize <= 0f || buckets.isEmpty()) return -1

        val searchRadiusSq = searchRadius * searchRadius
        val minCx = floor((physX - searchRadius) / cellSize).toInt()
        val maxCx = floor((physX + searchRadius) / cellSize).toInt()
        val minCy = floor((physY - searchRadius) / cellSize).toInt()
        val maxCy = floor((physY + searchRadius) / cellSize).toInt()

        var closestIdx = -1
        var minDistSq = Float.MAX_VALUE

        for (cy in minCy..maxCy) {
            for (cx in minCx..maxCx) {
                val cell = buckets[pack(cx, cy)] ?: continue
                for (i in cell) {
                    val dx = data[i] - physX
                    val dy = data[i + 1] - physY
                    val distSq = dx * dx + dy * dy
                    if (distSq < minDistSq && distSq <= searchRadiusSq) {
                        minDistSq = distSq
                        closestIdx = i
                    }
                }
            }
        }
        return closestIdx
    }

    companion object {
        fun build(data: FloatArray, step: Int): PointSpatialIndex {
            val cellSize = step.coerceAtLeast(1).toFloat()
            // Single pass into one bucket map, then freeze each cell to an IntArray.
            // A round-2 rewrite tried a two-pass count-then-fill into primitive
            // IntArrays to drop the boxed ArrayList<Int>, but at ~one point per
            // step-sized cell the two extra HashMap<Long,Int> (counts + cursors) box
            // more Long keys / Int values than they save — the micro-benchmark measured
            // it ~48% slower and allocating ~46% more, so this reverts to the original.
            val scratch = HashMap<Long, MutableList<Int>>()
            for (i in data.indices step DicResult.STRIDE) {
                val corr = data[i + DicResult.IDX_ZNSSD]
                if (!DicResult.isAcceptedPoint(corr)) continue
                val cx = floor(data[i] / cellSize).toInt()
                val cy = floor(data[i + 1] / cellSize).toInt()
                scratch.getOrPut(pack(cx, cy)) { ArrayList(4) }.add(i)
            }
            val buckets = HashMap<Long, IntArray>(scratch.size)
            for ((k, list) in scratch) {
                buckets[k] = list.toIntArray()
            }
            return PointSpatialIndex(data, cellSize, buckets)
        }

        private fun pack(cx: Int, cy: Int): Long =
            (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
    }
}
