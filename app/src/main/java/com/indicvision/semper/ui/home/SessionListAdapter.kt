@file:Suppress("TooManyFunctions")

@file:SuppressLint("NotifyDataSetChanged")

package com.indicvision.semper.ui.home

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.indicvision.semper.R
import com.indicvision.semper.data.cloud.TransferPhase
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.databinding.ItemSessionBinding
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.ui.analysis.run.EngineFailure
import com.indicvision.semper.ui.common.media.ThumbnailLoader
import com.indicvision.semper.ui.common.transfer.TransferWorkObserver
import java.io.File
import java.text.SimpleDateFormat
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
    private var progress: Map<String, TransferWorkObserver.RowProgress> = emptyMap()

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

    /** Reference thumbnails; an evicted one is dropped, never recycled under a live row. */
    private val thumbs = ThumbnailLoader(THUMB_CACHE_MAX, thumbExecutor, ::decodeThumb)

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
    fun setUploadProgress(new: Map<String, TransferWorkObserver.RowProgress>) {
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
    fun clearThumbCache() = thumbs.clear()

    class Holder(val row: ItemSessionBinding) : RecyclerView.ViewHolder(row.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

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
            append(EngineFailure.shortReason(ctx, r.stopCode))
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val r = items[position]
        val row = holder.row
        val ctx = holder.itemView.context
        row.sessionTitle.text = r.name
        row.sessionSubtitle.text = subtitleFor(ctx, r)

        bindSyncBadge(holder, r)
        thumbs.bind(row.sessionThumb, r.refPath.takeIf { it.isNotBlank() }?.let { Thumb(it, r.imgW, r.imgH) })

        val selected = isSelected(r.id)
        row.sessionCheck.isVisible = selected
        row.sessionCard.setCardBackgroundColor(
            ctx.getColor(if (selected) R.color.sky_container else R.color.surface_muted),
        )
        row.sessionCard.strokeColor =
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
        val badge = holder.row.sessionBadge
        val progressBar = holder.row.sessionProgress
        val prog = progress[r.id]
        badge.isVisible = syncVisible
        if (!syncVisible) {
            progressBar.isIndeterminate = false
            progressBar.isVisible = false
            badge.setOnClickListener(null)
        } else if (prog != null) {
            progressBar.isVisible = true
            // Bundle restore reports 0% for most of the Session.zip download —
            // indeterminate reads as "working" instead of a stuck empty bar.
            val indeterminate = prog.phase == TransferPhase.DOWNLOAD && prog.percent <= 0
            progressBar.isIndeterminate = indeterminate
            if (!indeterminate) {
                progressBar.setProgressCompat(prog.percent.coerceIn(0, PERCENT_MAX), true)
            }
            badge.text = ctx.getString(
                when (prog.phase) {
                    TransferPhase.PREPARE -> R.string.badge_preparing_fmt
                    TransferPhase.DOWNLOAD -> R.string.badge_downloading_fmt
                    TransferPhase.UPLOAD -> R.string.badge_uploading_fmt
                },
                prog.percent.coerceAtLeast(0),
            )
            badge.setTextColor(ctx.getColor(R.color.sky_on_container))
        } else {
            progressBar.isIndeterminate = false
            progressBar.isVisible = false
            badge.setText(syncStateLabel(r))
            badge.setTextColor(
                if (r.syncState == SessionRecord.SyncState.FAILED) {
                    ctx.getColor(R.color.semantic_danger)
                } else {
                    ctx.getColor(R.color.sky_on_container)
                },
            )
        }
        if (syncVisible) badge.setOnClickListener { onBadgeClick(r) }
    }

    /** The idle badge: where [r]'s backup stands, and "Only in cloud" for a synced one off this phone. */
    @StringRes
    private fun syncStateLabel(r: SessionRecord): Int =
        syncStateLabel(r.syncState, framesOnPhone = r.id !in withoutLocalData)

    /** A row's reference image, with the raw dimensions a TIFF/RAW sniff needs. */
    private data class Thumb(val path: String, val rawWidth: Int, val rawHeight: Int)

    companion object {
        /**
         * The words for a backup in [state], on Home's badge and Settings'
         * analysis rows alike; a synced one whose frames are not on this
         * phone ([framesOnPhone] false) is "Only in cloud".
         */
        @StringRes
        internal fun syncStateLabel(state: SessionRecord.SyncState, framesOnPhone: Boolean = true): Int =
            when (state) {
                SessionRecord.SyncState.SYNCED ->
                    if (framesOnPhone) R.string.badge_synced else R.string.badge_cloud_only
                SessionRecord.SyncState.PENDING -> R.string.badge_pending
                SessionRecord.SyncState.LOCAL_ONLY -> R.string.badge_local
                SessionRecord.SyncState.FAILED -> R.string.badge_not_backed_up
            }

        private const val THUMB_CACHE_MAX = 24
        private const val THUMB_EDGE = 256
        private const val PERCENT_MAX = 100
        private val thumbExecutor = Executors.newSingleThreadExecutor()

        /**
         * Runs on the decode thread. The existence check is file I/O, so it
         * runs here rather than on every bind. A missing reference is not a
         * miss: a restore can still bring it back. Sniff-first via
         * [BitmapDecode] — never hand TIFF/RAW to BitmapFactory (Skia
         * "invalid input" spam on Home rebind).
         */
        private fun decodeThumb(thumb: Thumb): ThumbnailLoader.Decoded {
            if (!File(thumb.path).exists()) return ThumbnailLoader.Decoded.Missing
            val bitmap = BitmapDecode.decodeFileForView(
                thumb.path,
                THUMB_EDGE,
                THUMB_EDGE,
                THUMB_EDGE,
                rawWidth = thumb.rawWidth,
                rawHeight = thumb.rawHeight,
            )
            return if (bitmap != null) ThumbnailLoader.Decoded.Loaded(bitmap) else ThumbnailLoader.Decoded.Undecodable
        }
    }
}
