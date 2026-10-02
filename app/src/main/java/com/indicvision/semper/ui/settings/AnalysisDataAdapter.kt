package com.indicvision.semper.ui.settings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.indicvision.semper.R
import com.indicvision.semper.databinding.ItemAnalysisDataBinding

/**
 * Rows for the settings page's per-analysis list. Which buttons a row shows is
 * decided by [AnalysisEntry]; what they do is the host's business, so every
 * action is a callback.
 */
@Suppress("LongParameterList") // one adapter, one callback per row action
class AnalysisDataAdapter(
    private val stateLine: (AnalysisEntry) -> String,
    private val backupLabel: (AnalysisEntry) -> Int?,
    private val onOpen: (AnalysisEntry) -> Unit,
    private val onBackup: (AnalysisEntry) -> Unit,
    private val onLocalDownload: (AnalysisEntry) -> Unit,
    private val onCloudRestore: (AnalysisEntry) -> Unit,
    private val onDelete: (AnalysisEntry) -> Unit,
) : ListAdapter<AnalysisEntry, AnalysisDataAdapter.Row>(BY_KEY) {

    private var downloadingKeys: Set<String> = emptySet()

    /**
     * The list last handed to [submitList]. [getCurrentList] only catches up
     * once its diff has run off the main thread, so a change made before
     * then must start from this one, or it drops the change before it.
     */
    private var submitted: List<AnalysisEntry> = emptyList()

    /**
     * Shows [items]. Every row is rebound afterwards, as a whole-list refresh
     * did: a row's wording and backup action also read settings that are not
     * part of its entry (Save to cloud), so an unchanged entry is not an
     * unchanged row.
     */
    fun submit(items: List<AnalysisEntry>) {
        submitted = items
        submitList(items) { notifyItemRangeChanged(0, itemCount) }
    }

    /** Keys from [AnalysisEntry.downloadKey] with an in-flight Download / restore. */
    fun setDownloadingKeys(keys: Set<String>) {
        if (keys == downloadingKeys) return
        downloadingKeys = keys
        notifyItemRangeChanged(0, itemCount)
    }

    /**
     * Drops the row for [key] ([AnalysisEntry.downloadKey]) immediately,
     * before its deletion is actually sent.
     */
    fun remove(key: String) {
        val kept = submitted.filterNot { it.downloadKey() == key }
        if (kept.size == submitted.size) return
        submitted = kept
        submitList(kept)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
        Row(ItemAnalysisDataBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Row, position: Int) = holder.bind(getItem(position))

    inner class Row(private val row: ItemAnalysisDataBinding) : RecyclerView.ViewHolder(row.root) {

        fun bind(entry: AnalysisEntry) {
            row.tvAnalysisName.text = entry.name
            val busy = entry.downloadKey() in downloadingKeys
            row.tvAnalysisState.text = if (busy) {
                itemView.context.getString(R.string.download_analysis_working)
            } else {
                stateLine(entry)
            }

            val hasCloud = entry.offersCloudActions()
            val localDownload = row.btnAnalysisLocalDownload
            val restore = row.btnAnalysisRestore
            // Download when cloud is listed; Restore only when local frames are missing.
            localDownload.isVisible = entry.offersDownload()
            restore.isVisible = entry.offersRestore()
            row.btnAnalysisDelete.isVisible = hasCloud
            localDownload.isEnabled = !busy
            restore.isEnabled = !busy
            localDownload.alpha = if (busy) BUSY_ICON_ALPHA else 1f
            restore.alpha = if (busy) BUSY_ICON_ALPHA else 1f
            localDownload.setOnClickListener {
                if (entry.downloadKey() in downloadingKeys) return@setOnClickListener
                onLocalDownload(entry)
            }
            restore.setOnClickListener {
                if (entry.downloadKey() in downloadingKeys) return@setOnClickListener
                onCloudRestore(entry)
            }
            row.btnAnalysisDelete.setOnClickListener { onDelete(entry) }

            // A backup action only applies to a row with no cloud copy listed.
            val label = if (hasCloud) null else backupLabel(entry)
            row.btnAnalysisBackup.isVisible = label != null && !busy
            label?.let {
                row.btnAnalysisBackup.setText(it)
                row.btnAnalysisBackup.setOnClickListener { onBackup(entry) }
            }

            itemView.isClickable = entry.record != null
            itemView.setOnClickListener(if (entry.record != null) View.OnClickListener { onOpen(entry) } else null)
        }
    }

    private companion object {
        /** Dim action icons while a transfer for this row is running. */
        const val BUSY_ICON_ALPHA = 0.4f

        /** A row is its analysis ([AnalysisEntry.downloadKey]); a changed entry rebinds it. */
        val BY_KEY = object : DiffUtil.ItemCallback<AnalysisEntry>() {
            override fun areItemsTheSame(oldItem: AnalysisEntry, newItem: AnalysisEntry) =
                oldItem.downloadKey() == newItem.downloadKey()

            override fun areContentsTheSame(oldItem: AnalysisEntry, newItem: AnalysisEntry) = oldItem == newItem
        }
    }
}
