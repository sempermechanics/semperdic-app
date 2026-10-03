// Frame cache: one early return per kind of miss reads clearest.
@file:Suppress("ReturnCount")

package com.sempermechanics.semper.ui.viewer

import android.graphics.Bitmap
import com.sempermechanics.semper.field.ValueRange
import com.sempermechanics.semper.report.BakedHeatmap

/**
 * Small LRU for result-viewer scrubbing: recent decoded `.dat` frames plus
 * optional display-scale heatmaps keyed by frame/field/scale bounds.
 */
class ScrubFrameCache(
    private val maxFrames: Int = DEFAULT_MAX_FRAMES,
    private val maxHeatmaps: Int = DEFAULT_MAX_HEATMAPS,
    /**
     * Hard ceiling on the bytes of decoded frame data held at once. The frame *count*
     * alone is not a memory bound — one PLC frame can be several MB — so the look-ahead
     * window is capped by bytes as well, and heavy frames simply hold fewer slots.
     */
    private val maxDataBytes: Long = DEFAULT_MAX_DATA_BYTES,
) {

    data class HeatKey(
        val frame: Int,
        val field: Int,
        val step: Int,
        /** The fixed colour scale it was drawn against; null for the frame's own. */
        val custom: ValueRange?,
        /** Drawn at the displaced positions, over the frame's own photo. */
        val displaced: Boolean = false,
    )

    // accessOrder = true, so iteration runs least-recently-used first — which is the
    // order evictData() drops from. Eviction is manual rather than via
    // removeEldestEntry so it can honour the byte ceiling as well as the count.
    private val dataByFrame = LinkedHashMap<Int, FloatArray>(maxFrames + 1, LOAD_FACTOR, true)

    /** Bytes currently held in [dataByFrame]; kept in step with every insert/evict. */
    private var dataBytes = 0L

    private val heatByKey =
        object : LinkedHashMap<HeatKey, BakedHeatmap>(maxHeatmaps + 1, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HeatKey, BakedHeatmap>?): Boolean {
                // Drop the entry only — do not recycle here. The ImageView may still
                // be displaying this bitmap; clear() recycles when the viewer exits.
                return size > maxHeatmaps
            }
        }

    @Synchronized
    fun getData(frame: Int): FloatArray? = dataByFrame[frame]

    @Synchronized
    fun putData(frame: Int, data: FloatArray) {
        val previous = dataByFrame.put(frame, data)
        if (previous != null) dataBytes -= previous.size.toLong() * Float.SIZE_BYTES
        dataBytes += data.size.toLong() * Float.SIZE_BYTES
        evictData()
    }

    /**
     * How many more frames fit under both ceilings, given a typical [frameBytes].
     * The prefetcher asks this so it stops filling instead of decoding frames that
     * would be evicted on arrival.
     */
    @Synchronized
    fun freeSlots(frameBytes: Long): Int {
        val byCount = maxFrames - dataByFrame.size
        if (frameBytes <= 0L) return byCount.coerceAtLeast(0)
        val byBytes = ((maxDataBytes - dataBytes) / frameBytes).toInt()
        return minOf(byCount, byBytes).coerceAtLeast(0)
    }

    /** Drops least-recently-used frames until both the count and byte caps hold. */
    private fun evictData() {
        val iterator = dataByFrame.entries.iterator()
        while (iterator.hasNext() && (dataByFrame.size > maxFrames || dataBytes > maxDataBytes)) {
            val entry = iterator.next()
            dataBytes -= entry.value.size.toLong() * Float.SIZE_BYTES
            iterator.remove()
        }
    }

    @Synchronized
    fun getHeat(key: HeatKey): BakedHeatmap? {
        val entry = heatByKey[key] ?: return null
        if (entry.bitmap.isRecycled) {
            heatByKey.remove(key)
            return null
        }
        return entry
    }

    @Synchronized
    fun putHeat(key: HeatKey, entry: BakedHeatmap) {
        // Overwrite without recycling — the previous bitmap may still be on screen.
        heatByKey[key] = entry
    }

    @Synchronized
    fun clear(except: Bitmap? = null) {
        dataByFrame.clear()
        dataBytes = 0L
        for (entry in heatByKey.values) {
            val bmp = entry.bitmap
            if (bmp !== except && !bmp.isRecycled) bmp.recycle()
        }
        heatByKey.clear()
    }

    companion object {
        // Look-ahead depth: enough decoded frames queued that a scrub step is
        // usually a cache hit, while DEFAULT_MAX_DATA_BYTES stops the window
        // growing with frame size - heavy frames hold fewer slots.
        const val DEFAULT_MAX_FRAMES = 6
        const val DEFAULT_MAX_HEATMAPS = 3

        /** [LinkedHashMap]'s default, spelled out because access order is not. */
        private const val LOAD_FACTOR = 0.75f

        /**
         * Byte ceiling for cached frame data — an eighth of the heap (64 MB of a 512 MB
         * heap), leaving room for the foreground frame, heatmap bitmaps and the
         * report/summary passes that share it.
         */
        val DEFAULT_MAX_DATA_BYTES: Long = Runtime.getRuntime().maxMemory() / 8
    }
}
