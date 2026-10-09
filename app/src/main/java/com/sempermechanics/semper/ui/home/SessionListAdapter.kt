@file:Suppress("TooManyFunctions")

@file:SuppressLint("NotifyDataSetChanged")

package com.sempermechanics.semper.ui.home

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.TransferPhase
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.databinding.ItemSessionBinding
import com.sempermechanics.semper.databinding.ItemSessionHeaderBinding
import com.sempermechanics.semper.ui.common.transfer.TransferWorkObserver

/**
 * Home session list: cards under day headers ([SessionDays]). Selection
 * state lives with the owner ([isSelected]); this adapter only paints rows
 * and forwards clicks. Everything the owner asks is by session id, so the
 * headers never count as rows.
 */
class SessionListAdapter(
    private val isSelected: (String) -> Boolean,
    private val onClick: (SessionRecord) -> Unit,
    private val onLongClick: (SessionRecord) -> Unit,
    private val onBadgeClick: (SessionRecord) -> Unit = {},
    /** The time "Today" and "Yesterday" are counted from when a header binds. */
    private val clock: () -> Long = System::currentTimeMillis,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var records: List<SessionRecord> = emptyList()

    /** [records] with a header above each day: what the list shows, position for position. */
    private var rows: List<SessionRow> = emptyList()

    /** Session id → its adapter position in [rows]. */
    private var positions: Map<String, Int> = emptyMap()

    /** id → live transfer progress; empty except for rows currently backing up or restoring. */
    private var progress: Map<String, TransferWorkObserver.RowProgress> = emptyMap()

    /**
     * Ids whose frames are not on this phone, read off the main thread with
     * the list ([submit]). A SYNCED one shows "Only in cloud" and keeps its
     * reference thumbnail; selection and open ask [hasLocalData] instead of
     * listing the session directory.
     */
    private var withoutLocalData: Set<String> = emptySet()

    /**
     * Whether rows show their state icon and transfer bar at all. False on a
     * demo account: its analyses are recorded silently and it has no restore,
     * so there is nothing for an icon to say or a tap to do.
     */
    private var syncVisible: Boolean = true

    /** Result and reference thumbnails; made with the first row, which knows the screen density. */
    private var thumbs: SessionThumbs? = null

    /**
     * Shows [newItems], newest first, under their days. [withoutLocalData] are
     * the ids among them with no frame data on this phone, from
     * [SessionRecord.hasLocalData] read on IO.
     */
    fun submit(newItems: List<SessionRecord>, withoutLocalData: Set<String>) {
        records = newItems
        rows = SessionDays.rows(newItems)
        positions = buildMap {
            rows.forEachIndexed { position, row -> if (row is SessionRow.Session) put(row.record.id, position) }
        }
        this.withoutLocalData = withoutLocalData
        notifyDataSetChanged()
    }

    /** Whether [id]'s frames were on this phone when the list was read; no disk access. */
    fun hasLocalData(id: String): Boolean = id !in withoutLocalData

    /** Every session's id in list order; never a header. */
    fun allIds(): List<String> = records.map { it.id }

    fun setSyncVisible(visible: Boolean) {
        if (syncVisible == visible) return
        syncVisible = visible
        notifyDataSetChanged()
    }

    /** [id]'s adapter position, counting the headers above it; -1 when it is not listed. */
    internal fun positionOf(id: String): Int = positions[id] ?: RecyclerView.NO_POSITION

    /** Redraws one row by id — selection changes never touch the whole list. */
    fun rebindRow(id: String) {
        val position = positionOf(id)
        if (position >= 0) notifyItemChanged(position)
    }

    /** Update live transfer progress; rebinds only the rows whose progress changed. */
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
        records.filter { it.id in ids }

    /** Drop cached thumbs (e.g. when Home is destroyed). */
    fun clearThumbCache() {
        thumbs?.clear()
    }

    class Holder(val row: ItemSessionBinding) : RecyclerView.ViewHolder(row.root)

    class DayHolder(val day: ItemSessionHeaderBinding) : RecyclerView.ViewHolder(day.root)

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is SessionRow.Header -> TYPE_DAY
        is SessionRow.Session -> TYPE_SESSION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        if (viewType == TYPE_DAY) {
            val day = ItemSessionHeaderBinding.inflate(inflater, parent, false)
            ViewCompat.setAccessibilityHeading(day.root, true)
            return DayHolder(day)
        }
        if (thumbs == null) {
            val edge = parent.resources.getDimensionPixelSize(R.dimen.session_thumb_size) * THUMB_OVERSAMPLE
            thumbs = SessionThumbs(edge)
        }
        return Holder(ItemSessionBinding.inflate(inflater, parent, false))
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is SessionRow.Header -> (holder as DayHolder).day.sessionDay.text =
                SessionDays.label(holder.itemView.context, row.millis, now = clock())
            is SessionRow.Session -> bindSession(holder as Holder, row.record)
        }
    }

    private fun bindSession(holder: Holder, r: SessionRecord) {
        val row = holder.row
        val context = holder.itemView.context
        val prog = progress[r.id]?.takeIf { syncVisible }
        row.sessionTitle.text = r.name
        if (prog != null) {
            row.sessionSubtitle.text = SessionRowText.transfer(context, prog)
            row.sessionSubtitle.contentDescription = null
        } else {
            row.sessionSubtitle.text = SessionRowText.subtitle(context, r)
            row.sessionSubtitle.contentDescription = SessionRowText.spokenSubtitle(context, r)
        }

        bindState(holder, r, prog)
        thumbs?.bind(row, r, framesOnPhone = hasLocalData(r.id))

        val selected = isSelected(r.id)
        row.sessionCheck.isVisible = selected
        row.sessionCard.setCardBackgroundColor(
            context.getColor(if (selected) R.color.sky_container else R.color.surface_muted),
        )
        row.sessionCard.strokeColor = context.getColor(if (selected) R.color.sky_primary else R.color.surface_outline)

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
     * State icon and transfer bar. While a transfer runs ([prog]) the icon
     * shows its phase and a bar appears under the subtitle; otherwise the icon
     * says where the backup stands. Hidden entirely when [syncVisible] is false.
     */
    private fun bindState(holder: Holder, r: SessionRecord, prog: TransferWorkObserver.RowProgress?) {
        val context = holder.itemView.context
        val stateView = holder.row.sessionState
        val progressBar = holder.row.sessionProgress
        stateView.isVisible = syncVisible
        progressBar.isVisible = prog != null
        if (!syncVisible) {
            progressBar.isIndeterminate = false
            stateView.setOnClickListener(null)
            return
        }
        val icon = if (prog != null) {
            bindBar(holder, prog)
            SessionStateIcon.forTransfer(prog.phase)
        } else {
            progressBar.isIndeterminate = false
            SessionStateIcon.forState(r.syncState, framesOnPhone = hasLocalData(r.id))
        }
        stateView.setImageResource(icon.icon)
        stateView.imageTintList = ColorStateList.valueOf(context.getColor(icon.tint))
        stateView.contentDescription = context.getString(icon.label)
        stateView.setOnClickListener { onBadgeClick(r) }
    }

    private fun bindBar(holder: Holder, prog: TransferWorkObserver.RowProgress) {
        val progressBar = holder.row.sessionProgress
        // Bundle restore reports 0% for most of the Session.zip download —
        // indeterminate reads as "working" instead of a stuck empty bar.
        val indeterminate = prog.phase == TransferPhase.DOWNLOAD && prog.percent <= 0
        progressBar.isIndeterminate = indeterminate
        if (!indeterminate) {
            progressBar.setProgressCompat(prog.percent.coerceIn(0, PERCENT), true)
        }
    }

    internal companion object {
        /** View types: a day header ([DayHolder]) and an analysis card ([Holder]). */
        const val TYPE_DAY = 0
        const val TYPE_SESSION = 1

        /** The cached result render is twice the thumb view's edge in px. */
        private const val THUMB_OVERSAMPLE = 2
        private const val PERCENT = 100
    }
}
