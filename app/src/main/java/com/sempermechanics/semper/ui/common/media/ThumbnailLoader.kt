package com.sempermechanics.semper.ui.common.media

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.util.concurrent.Executor

/**
 * Loads list thumbnails off the main thread into recycled [ImageView]s.
 *
 * Shared shape of Home's session list, the media picker grid and the wizard's
 * frame strip: a small LRU of decoded bitmaps keyed by [K], a decode on
 * [executor], and the result posted back only while the view still wants it.
 *
 * **The tag check.** The view's tag names the key it is showing (or waiting
 * for), and *every* bind sets it — cached, missing and loading alike. A decode
 * lands only while the tag still matches, so a recycled view rebound to a
 * cached row is never painted over by the older row's late decode (the bug
 * wave 1 fixed in `SessionListAdapter`). A decode that lands too late is
 * recycled.
 *
 * **Eviction does not recycle.** An evicted bitmap may still be on screen: a
 * grid can hold more live views than [maxCached], and RecyclerView reattaches
 * cached views without rebinding them, so recycling on eviction would make the
 * next draw throw "trying to use a recycled bitmap". An evicted entry is only
 * dropped and left to the GC; [clear] is the one place that recycles.
 *
 * Main thread only for [bind] and [clear].
 *
 * @param maxCached LRU size; an evicted bitmap is dropped, not recycled. Home uses 24.
 * @param decode runs on [executor]: a [Decoded.Loaded] bitmap, or why there is none.
 *   The bitmap must be a fresh one the loader then owns — not shared, not
 *   cached elsewhere — because a result that lands for a view that has moved
 *   on is recycled. It is not wrapped: a decode that can throw catches for
 *   itself (the media grid's `loadThumbnail` does), as each adapter's did.
 * @param placeholder what a view shows while it waits and when nothing loads
 *   (null drawable for Home and the media grid).
 * @param onFailed what a view shows when [decode] gave no bitmap; defaults to [placeholder].
 */
class ThumbnailLoader<K : Any>(
    private val maxCached: Int,
    private val executor: Executor,
    private val decode: (K) -> Decoded,
    private val placeholder: (ImageView) -> Unit = { it.setImageDrawable(null) },
    private val onFailed: (ImageView) -> Unit = placeholder,
    private val mainThread: Executor = MainThreadExecutor,
) {
    /** What [decode] made of a key. */
    sealed interface Decoded {
        data class Loaded(val bitmap: Bitmap) : Decoded

        /** Present but not decodable (TIFF bytes behind a `.png` name): never tried again. */
        data object Undecodable : Decoded

        /** Not there right now (a restore may still bring it back): tried again on the next bind. */
        data object Missing : Decoded
    }

    private val cache = object : LinkedHashMap<K, Bitmap>(maxCached + 1, LOAD_FACTOR, true) {
        // Dropped, never recycled: the bitmap may still be drawn by a live view.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Bitmap>?): Boolean = size > maxCached
    }

    /**
     * Keys [decode] called [Decoded.Undecodable]. Recorded when the result
     * lands for a view still showing that key, as Home did.
     */
    private val misses = HashSet<K>()

    /** The cached bitmap for [key], if it is still usable. */
    fun cached(key: K): Bitmap? = cache[key]?.takeIf { !it.isRecycled }

    /**
     * Shows [key]'s thumbnail in [view]: from the cache at once, else the
     * [placeholder] until [decode] finishes. A null [key] (no reference) and a
     * known-undecodable one show the placeholder and load nothing.
     */
    fun bind(view: ImageView, key: K?) {
        val hit = key?.let { cached(it) }
        when {
            key == null || key in misses -> {
                view.tag = null
                placeholder(view)
            }
            hit != null -> {
                view.tag = key
                view.setImageBitmap(hit)
            }
            else -> {
                placeholder(view)
                view.tag = key
                executor.execute { load(view, key) }
            }
        }
    }

    private fun load(view: ImageView, key: K) {
        val result = decode(key)
        mainThread.execute { land(view, key, result) }
    }

    private fun land(view: ImageView, key: K, result: Decoded) {
        val bitmap = (result as? Decoded.Loaded)?.bitmap
        if (view.tag != key) {
            bitmap?.recycle()
            return
        }
        if (bitmap != null) {
            cache[key] = bitmap
            view.setImageBitmap(bitmap)
        } else {
            if (result == Decoded.Undecodable) misses.add(key)
            onFailed(view)
        }
    }

    /**
     * Recycles and drops every cached bitmap. Only from the screen's
     * `onDestroy` (or a sheet's dismiss), once no view can draw them again.
     */
    fun clear() {
        for (bitmap in cache.values) {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        cache.clear()
    }

    private object MainThreadExecutor : Executor {
        private val handler = Handler(Looper.getMainLooper())

        override fun execute(command: Runnable) {
            handler.post(command)
        }
    }

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}
