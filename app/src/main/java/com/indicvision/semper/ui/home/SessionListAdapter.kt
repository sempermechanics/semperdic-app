// List adapter: onBindViewHolder assembles one row's subtitle/badges inline, and
// literal view-type/dimension constants read clearest there too.
@file:Suppress("MagicNumber", "CyclomaticComplexMethod", "TooManyFunctions")

@file:SuppressLint("NotifyDataSetChanged")

package com.indicvision.semper.ui.home

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.ui.analysis.EngineFailure
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Home session list. Selection state lives with the owner ([isSelected]); this
 * adapter only paints rows and forwards clicks.
 */
class SessionListAdapter(
    private val isSelected: (String) -> Boolean,
    private val onClick: (SessionRecord) -> Unit,
    private val onLongClick: (SessionRecord) -> Unit,
    private val onBadgeClick: (SessionRecord) -> Unit = {},
) : RecyclerView.Adapter<SessionListAdapter.Holder>() {

    private var items: List<SessionRecord> = emptyList()
    private val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())

    /** id → live upload progress; empty except for rows currently backing up. */
    private var progress: Map<String, RowProgress> = emptyMap()

    /**
     * Ids whose frames are not on this phone, read off the main thread with
     * the list ([submit]). A SYNCED one is badged "Only in cloud"; selection
     * and open ask [hasLocalData] instead of listing the session directory.
     */
    private var withoutLocalData: Set<String> = emptySet()

    /**
     * Whether rows show their sync badge and upload bar at all. False on a
     * demo account: its analyses are recorded silently and it has no restore,
     * so there is nothing for a badge to say or a tap to do.
     */
    private var syncVisible: Boolean = true

    /** Path → thumbnail; recycles evicted bitmaps. Cap keeps scroll GC mild. */
    private val thumbCache =
        object : LinkedHashMap<String, Bitmap>(THUMB_CACHE_MAX + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
                if (size <= THUMB_CACHE_MAX) return false
                eldest?.value?.takeIf { !it.isRecycled }?.recycle()
                return true
            }
        }

    /** Paths that are not platform-decodable (e.g. TIFF bytes named `.png`). */
    private val thumbMisses: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /**
     * Shows [newItems]. [withoutLocalData] are the ids among them with no frame
     * data on this phone, from [SessionRecord.hasLocalData] read on IO.
     */
    fun submit(newItems: List<SessionRecord>, withoutLocalData: Set<String>) {
        items = newItems
        this.withoutLocalData = withoutLocalData
        notifyDataSetChanged()
    }

    /** Whether [id]'s frames were on this phone when the list was read; no disk access. */
    fun hasLocalData(id: String): Boolean = id !in withoutLocalData

    fun allIds(): List<String> = items.map { it.id }

    fun setSyncVisible(visible: Boolean) {
        if (syncVisible == visible) return
        syncVisible = visible
        notifyDataSetChanged()
    }

    /** Redraws one row by id — selection changes never touch the whole list. */
    fun rebindRow(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index)
    }

    /** Update live backup progress; rebinds only the rows whose progress changed. */
    fun setUploadProgress(new: Map<String, RowProgress>) {
        val old = progress
        if (old == new) return
        progress = new
        (old.keys + new.keys).forEach { id ->
            if (old[id] != new[id]) rebindRow(id)
        }
    }

    /** Rows whose ids are in [ids], in list order. */
    fun recordsFor(ids: Collection<String>): List<SessionRecord> =
        items.filter { it.id in ids }

    /** Drop cached thumbs (e.g. when Home is destroyed). */
    fun clearThumbCache() {
        for (bmp in thumbCache.values) {
            if (!bmp.isRecycled) bmp.recycle()
        }
        thumbCache.clear()
    }

    /** Live backup progress for a row while its upload work is running. */
    data class RowProgress(val phase: String, val percent: Int)

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val card: com.google.android.material.card.MaterialCardView =
            v.findViewById(R.id.sessionCard)
        val thumb: ImageView = v.findViewById(R.id.sessionThumb)
        val check: ImageView = v.findViewById(R.id.sessionCheck)
        val title: TextView = v.findViewById(R.id.sessionTitle)
        val subtitle: TextView = v.findViewById(R.id.sessionSubtitle)
        val badge: TextView = v.findViewById(R.id.sessionBadge)
        val progressBar: com.google.android.material.progressindicator.LinearProgressIndicator =
            v.findViewById(R.id.sessionProgress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
        return Holder(v)
    }

    override fun getItemCount() = items.size

    /**
     * Date · size · headline, plus why it stopped when it did.
     *
     * A run cut short reads as "39 of 50 frames" rather than "39 frames": the
     * count alone cannot distinguish a short run from a shorter test.
     */
    private fun subtitleFor(ctx: android.content.Context, r: SessionRecord): String = buildString {
        append(dateFmt.format(Date(r.createdAt)))
        append(" · ")
        if (r.isSweep) {
            append(ctx.getString(R.string.session_sweep_kind))
        } else if (r.stoppedEarly && r.plannedFrameCount > r.frameCount) {
            append(
                ctx.resources.getQuantityString(
                    R.plurals.session_frames_of_fmt,
                    r.plannedFrameCount,
                    r.frameCount,
                    r.plannedFrameCount,
                ),
            )
        } else {
            append(ctx.resources.getQuantityString(R.plurals.session_frames_fmt, r.frameCount, r.frameCount))
        }
        if (r.headline.isNotBlank()) {
            append(" · ")
            append(r.headline)
        }
        if (r.stoppedEarly) {
            append(" · ")
            append(ctx.getString(EngineFailure.shortReasonRes(r.stopCode)))
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val r = items[position]
        val ctx = holder.itemView.context
        holder.title.text = r.name
        holder.subtitle.text = subtitleFor(ctx, r)

        bindSyncBadge(holder, r)
        bindThumbnail(holder, r)

        val selected = isSelected(r.id)
        holder.check.isVisible = selected
        holder.card.setCardBackgroundColor(
            ctx.getColor(if (selected) R.color.sky_container else R.color.surface_muted),
        )
        holder.card.strokeColor =
            ctx.getColor(if (selected) R.color.sky_primary else R.color.surface_outline)

        // Outside selection mode a tap opens the analysis and a long-press
        // starts selecting; inside it, every tap just toggles a row. That
        // policy lives in the callbacks Home wires.
        holder.itemView.setOnClickListener { onClick(r) }
        holder.itemView.setOnLongClickListener {
            onLongClick(r)
            true
        }
    }

    /**
     * Sync badge and progress bar. While a backup is running, the badge shows
     * live progress and a bar appears under the subtitle; otherwise it is the
     * normal sync-state badge. Hidden entirely when [syncVisible] is false.
     */
    private fun bindSyncBadge(holder: Holder, r: SessionRecord) {
        val ctx = holder.itemView.context
        val prog = progress[r.id]
        holder.badge.isVisible = syncVisible
        if (!syncVisible) {
            holder.progressBar.isIndeterminate = false
            holder.progressBar.isVisible = false
            holder.badge.setOnClickListener(null)
        } else if (prog != null) {
            holder.progressBar.isVisible = true
            // Bundle restore reports 0% for most of the Session.zip download —
            // indeterminate reads as "working" instead of a stuck empty bar.
            val indeterminate = prog.phase == DicKeys.PHASE_DOWNLOAD && prog.percent <= 0
            holder.progressBar.isIndeterminate = indeterminate
            if (!indeterminate) {
                holder.progressBar.setProgressCompat(prog.percent.coerceIn(0, 100), true)
            }
            holder.badge.text = ctx.getString(
                when (prog.phase) {
                    "prepare" -> R.string.badge_preparing_fmt
                    "download" -> R.string.badge_downloading_fmt
                    else -> R.string.badge_uploading_fmt
                },
                prog.percent.coerceAtLeast(0),
            )
            holder.badge.setTextColor(ctx.getColor(R.color.sky_on_container))
        } else {
            holder.progressBar.isIndeterminate = false
            holder.progressBar.isVisible = false
            holder.badge.text = when {
                r.syncState == SessionRecord.SyncState.SYNCED && r.id in withoutLocalData ->
                    ctx.getString(R.string.badge_cloud_only)
                r.syncState == SessionRecord.SyncState.SYNCED -> ctx.getString(R.string.badge_synced)
                r.syncState == SessionRecord.SyncState.PENDING -> ctx.getString(R.string.badge_pending)
                r.syncState == SessionRecord.SyncState.LOCAL_ONLY -> ctx.getString(R.string.badge_local)
                r.syncState == SessionRecord.SyncState.FAILED -> ctx.getString(R.string.badge_not_backed_up)
                else -> ctx.getString(R.string.badge_local)
            }
            holder.badge.setTextColor(
                if (r.syncState == SessionRecord.SyncState.FAILED) {
                    ctx.getColor(R.color.semantic_danger)
                } else {
                    ctx.getColor(R.color.sky_on_container)
                },
            )
        }
        if (syncVisible) holder.badge.setOnClickListener { onBadgeClick(r) }
    }

    /**
     * The view's tag names the reference it is showing (or waiting for), and a
     * decode only lands while it still matches. Every branch sets it: a holder
     * rebound from a row still decoding to a cached row used to keep the old
     * tag, and the old decode then painted over the new row's thumbnail.
     */
    private fun bindThumbnail(holder: Holder, r: SessionRecord) {
        val path = r.refPath
        val cached = thumbCache[path]?.takeIf { !it.isRecycled }
        when {
            path.isBlank() || path in thumbMisses -> {
                holder.thumb.tag = null
                holder.thumb.setImageDrawable(null)
            }
            cached != null -> {
                holder.thumb.tag = path
                holder.thumb.setImageBitmap(cached)
            }
            else -> {
                holder.thumb.setImageDrawable(null)
                holder.thumb.tag = path
                thumbExecutor.execute {
                    // The existence check is file I/O, so it runs here rather
                    // than on every bind. A missing reference is not a miss: a
                    // restore can still bring it back.
                    val exists = File(path).exists()
                    // Sniff-first via BitmapDecode — never hand TIFF/RAW to
                    // BitmapFactory (Skia "invalid input" spam on Home rebind).
                    val bmp = if (exists) {
                        BitmapDecode.decodeFileForView(
                            path,
                            THUMB_EDGE,
                            THUMB_EDGE,
                            THUMB_EDGE,
                            rawWidth = r.imgW,
                            rawHeight = r.imgH,
                        )
                    } else {
                        null
                    }
                    mainHandler.post {
                        if (holder.thumb.tag != path) {
                            bmp?.recycle()
                        } else if (bmp != null) {
                            thumbCache[path] = bmp
                            holder.thumb.setImageBitmap(bmp)
                        } else if (exists) {
                            thumbMisses.add(path)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val THUMB_CACHE_MAX = 24
        private const val THUMB_EDGE = 256
        private val thumbExecutor = Executors.newSingleThreadExecutor()
        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
