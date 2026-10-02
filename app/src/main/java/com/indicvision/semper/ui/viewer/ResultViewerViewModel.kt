package com.indicvision.semper.ui.viewer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.field.FieldStats
import com.indicvision.semper.field.ValueRange
import com.indicvision.semper.ui.viewer.share.ShareExportJobs
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Survives configuration changes for the results browser: frame scrubber index,
 * active field type, the user's fixed colour scales, running exports and each
 * visited field's stats. Heavy bitmaps stay in the Activity (they are bound to
 * view lifetime).
 */
class ResultViewerViewModel(
    app: Application,
    private val state: SavedStateHandle,
) : AndroidViewModel(app) {
    var currentFrameIndex: Int = 0
    var currentDataIndex: Int = 2
    var currentTypeString: String = "U"

    /**
     * Fixed colour-scale bounds per field, set in the custom-scale dialog.
     * Concurrent: an export reads them off the main thread (the GIF bounds)
     * while the dialog may change them.
     */
    val customBounds: MutableMap<Int, ValueRange> = ConcurrentHashMap()

    /** Share/export jobs; in [viewModelScope], so a rotation does not cancel them. */
    internal val exports = ShareExportJobs(viewModelScope, app.contentResolver)

    /**
     * A picked save-as document (export kind and destination) whose export has
     * not started yet: the picker's answer can reach a viewer whose frames are
     * not listed yet. Held here, and in the saved state for process death, so
     * whichever viewer is current when the frames are known starts it — once.
     */
    val hasPendingSave: Boolean get() = state.contains(KEY_SAVE_KIND)

    fun setPendingSave(kind: String, uri: Uri) {
        state[KEY_SAVE_URI] = uri
        state[KEY_SAVE_KIND] = kind
    }

    /** The pending save-as, cleared as it is handed out so its export starts once. */
    fun takePendingSave(): Pair<String, Uri>? {
        val kind = state.remove<String>(KEY_SAVE_KIND)
        val uri = state.remove<Uri>(KEY_SAVE_URI)
        return if (kind != null && uri != null) kind to uri else null
    }

    // ── Field metrics cache (caption + peek-sheet extrema) ───────────────────

    /** Immutable per-(frame,field) result: its stats and the indices of its extrema. */
    internal class FieldMetrics(val stats: FieldStats?, val maxIdx: Int, val minIdx: Int)

    /**
     * Memoised [FieldMetrics] keyed by (frameIndex, dataIndex). A decoded frame is
     * immutable, so these never need invalidation — only an LRU size bound. Computing
     * them is one walk of accepted points; caching means a field toggle or a
     * revisited frame costs nothing, and the viewer warms the entry on its
     * background thread so a scrub settle never does the work on the main thread.
     * Guarded by its own monitor (read on Main, written on Dispatchers.Default).
     */
    private val fieldMetricsCache = LinkedHashMap<Long, FieldMetrics>()

    /** The frame listing [fieldMetricsCache]'s keys index into; see [useFrameListing]. */
    private var metricsListing: List<File>? = null

    /**
     * Tells the cache which `.dat` files its frame indices name. A viewer
     * rebuilt over the same listing (a rotation) keeps the cached metrics; a
     * different listing drops them, since index N is then another frame.
     */
    internal fun useFrameListing(files: List<File>) {
        synchronized(fieldMetricsCache) {
            if (files != metricsListing) {
                fieldMetricsCache.clear()
                metricsListing = files
            }
        }
    }

    internal fun fieldMetricsFor(frameIndex: Int, dataIndex: Int, data: FloatArray): FieldMetrics {
        val key = (frameIndex.toLong() shl Int.SIZE_BITS) or (dataIndex.toLong() and LOW_32_BITS)
        synchronized(fieldMetricsCache) { fieldMetricsCache[key]?.let { return it } }
        val stats = FieldStats.fromArray(DicResult.fieldStats(data, dataIndex))
        val (maxIdx, minIdx) = trueExtremaIndices(data, dataIndex)
        val metrics = FieldMetrics(stats, maxIdx, minIdx)
        synchronized(fieldMetricsCache) {
            fieldMetricsCache[key] = metrics
            if (fieldMetricsCache.size > FIELD_METRICS_CACHE_MAX) {
                val eldest = fieldMetricsCache.keys.iterator()
                eldest.next()
                eldest.remove()
            }
        }
        return metrics
    }

    /**
     * Indices of the accepted points that carry this field's true min and max —
     * the same values [DicResult.fieldStats] reports — so the ⓘ coordinates
     * match the printed numbers (not the colour-bar percentile clamp).
     */
    private fun trueExtremaIndices(data: FloatArray, dataIndex: Int): Pair<Int, Int> {
        var maxIdx = -1
        var minIdx = -1
        var maxV = Float.NEGATIVE_INFINITY
        var minV = Float.POSITIVE_INFINITY
        var i = 0
        while (i < data.size) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                val v = data[i + dataIndex]
                if (v > maxV) {
                    maxV = v
                    maxIdx = i
                }
                if (v < minV) {
                    minV = v
                    minIdx = i
                }
            }
            i += DicResult.STRIDE
        }
        return maxIdx to minIdx
    }

    private companion object {
        const val KEY_SAVE_KIND = "pending_save_kind"
        const val KEY_SAVE_URI = "pending_save_uri"

        /** Field metrics are tiny (5 floats + 2 ints); keep plenty across frames/fields. */
        const val FIELD_METRICS_CACHE_MAX = 64

        /** The low half of a cache key, where the field index goes. */
        const val LOW_32_BITS = 0xFFFF_FFFFL
    }
}
