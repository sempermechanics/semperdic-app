package com.sempermechanics.semper.ui.recyclebin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.SessionDeletes
import com.sempermechanics.semper.data.net.RecycleBinItemDto
import com.sempermechanics.semper.databinding.ActivityRecycleBinBinding
import com.sempermechanics.semper.databinding.ItemRecycleBinBinding
import com.sempermechanics.semper.ui.common.ByteSize
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.dialog.ReopenAfterRecreate
import com.sempermechanics.semper.ui.common.setBusy
import com.sempermechanics.semper.ui.common.transfer.DeleteFeedback
import com.sempermechanics.semper.ui.limit.SessionLimitActivity
import kotlinx.coroutines.launch

/**
 * The account's recycle bin (ADR-035): each backup deleted in the last 30
 * days, when it is erased for good, Restore and Delete permanently on each,
 * and Empty recycle bin.
 *
 * Restore runs in [RecycleBinViewModel]. Delete permanently and Empty ask
 * first, then queue in [SessionDeletes] with [SessionDeletes.Mode.PURGE]: the
 * same 5-second undo, pacing and [DeleteFeedback] as every cloud delete.
 */
@MainThread
class RecycleBinActivity : AppCompatActivity() {

    private val viewModel: RecycleBinViewModel by viewModels()

    private lateinit var binding: ActivityRecycleBinBinding
    private lateinit var deleteFeedback: DeleteFeedback
    private val adapter = BinAdapter()

    /** The Delete permanently and Empty questions, asked again after a rotation (readiness AE1-111). */
    private val reopenDelete = ReopenAfterRecreate(this, "recycleBin.delete")
    private val reopenEmpty = ReopenAfterRecreate(this, "recycleBin.empty")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecycleBinBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.padVertical(binding.recycleBinRoot)

        binding.rvRecycleBin.layoutManager = LinearLayoutManager(this)
        binding.rvRecycleBin.adapter = adapter
        binding.btnRecycleBinRetry.setOnClickListener { viewModel.load() }
        binding.btnRecycleBinEmpty.setOnClickListener { askEmpty() }
        deleteFeedback = DeleteFeedback(this, binding.recycleBinRoot) {
            viewModel.clearQueued()
            viewModel.load()
        }
        deleteFeedback.observe()
        observeViewModel()
        // Opened from a Settings row's Restore backup: restore it here, once,
        // so the outcome lands on this screen beside the list.
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_RESTORE)?.let { viewModel.restore(it) }
        reopenDelete.restored()?.let { state ->
            askDelete(
                state.getString(CLOUD_ID).orEmpty(),
                state.getString(LOCAL_ID).orEmpty(),
                state.getString(NAME).orEmpty(),
            )
        }
        if (reopenEmpty.restored() != null) askEmpty()
    }

    override fun onResume() {
        super.onResume()
        viewModel.load()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.screen.collect(::show) }
                launch { viewModel.busy.collect(::setBusy) }
                viewModel.notices.collect(::say)
            }
        }
    }

    private fun say(notice: Notice) {
        Feedback.toast(this, notice.note.text(this), long = true)
        // No room under the account's or this phone's cap: the screen that
        // says how to make room (delete an analysis, or refresh the licence).
        if (notice.openLimit) startActivity(Intent(this, SessionLimitActivity::class.java))
    }

    private fun show(screen: RecycleBinViewModel.Screen) {
        val items = (screen as? RecycleBinViewModel.Screen.Loaded)?.items
        binding.progressRecycleBinList.isVisible = screen is RecycleBinViewModel.Screen.Loading
        binding.recycleBinFailed.isVisible = screen is RecycleBinViewModel.Screen.Failed
        binding.rvRecycleBin.isVisible = !items.isNullOrEmpty()
        binding.tvRecycleBinEmpty.isVisible = items != null && items.isEmpty()
        binding.btnRecycleBinEmpty.isVisible = !items.isNullOrEmpty()
        if (screen is RecycleBinViewModel.Screen.Failed) binding.tvRecycleBinFailed.text = screen.note.text(this)
        adapter.submitList(items.orEmpty())
    }

    private fun setBusy(busy: Boolean) {
        binding.progressRecycleBin.setBusy(busy, binding.btnRecycleBinEmpty, idleVisibility = View.GONE)
        adapter.busy = busy
    }

    private fun nameOf(item: RecycleBinItemDto): String =
        RecycleBinView.name(item).ifBlank { getString(R.string.cloud_backups_unnamed) }

    /** "Delete steel_00 permanently?", then the queued erase of that one backup. */
    private fun askDelete(cloudId: String, localId: String, name: String) {
        if (cloudId.isEmpty()) return
        val dialog = Dialogs.confirm(
            this,
            getString(R.string.recycle_bin_delete_title_fmt, name),
            getText(R.string.recycle_bin_delete_body),
            R.string.recycle_bin_delete_forever,
        ) { queuePurge(listOf(SessionDeletes.Item(localId, cloudId, SessionDeletes.Mode.PURGE))) }
        reopenDelete.keep(dialog) { bundleOf(CLOUD_ID to cloudId, LOCAL_ID to localId, NAME to name) }
    }

    /** "Erase 3 backups for good?", then one queued erase of everything listed. */
    private fun askEmpty() {
        val items = (viewModel.screen.value as? RecycleBinViewModel.Screen.Loaded)?.items.orEmpty()
        if (items.isEmpty()) return
        val dialog = Dialogs.confirm(
            this,
            resources.getQuantityString(R.plurals.recycle_bin_empty_title_fmt, items.size, items.size),
            getText(R.string.recycle_bin_empty_body),
            R.string.recycle_bin_delete_forever,
        ) {
            // What is listed when the user confirms, not when the question opened.
            val now = (viewModel.screen.value as? RecycleBinViewModel.Screen.Loaded)?.items.orEmpty()
            queuePurge(now.map { SessionDeletes.Item(it.localSessionId, it.sessionId, SessionDeletes.Mode.PURGE) })
        }
        reopenEmpty.keep(dialog)
    }

    /** One queued job: it waits out the undo window, then erases one backup at a time. */
    private fun queuePurge(items: List<SessionDeletes.Item>) {
        if (items.isEmpty()) return
        val workId = SessionDeletes.enqueue(this, items)
        viewModel.hideQueued(items.map { it.cloudId })
        deleteFeedback.queued(workId, items.size)
    }

    private inner class BinAdapter : ListAdapter<RecycleBinItemDto, BinAdapter.Holder>(BY_SESSION) {

        /** A Restore is out: the row buttons wait. */
        var busy = false
            set(value) {
                if (field == value) return
                field = value
                @Suppress("NotifyDataSetChanged") // a short list, each row with two buttons
                notifyDataSetChanged()
            }

        inner class Holder(val row: ItemRecycleBinBinding) : RecyclerView.ViewHolder(row.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemRecycleBinBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val row = holder.row
            val context = row.root.context
            val now = System.currentTimeMillis()
            val name = nameOf(item)
            row.tvBinName.text = name
            row.tvBinSize.isVisible = item.totalBytes > 0
            row.tvBinSize.text = ByteSize.format(item.totalBytes)
            val dates = RecycleBinView.datesLine(context, item, now)
            row.tvBinDates.isVisible = dates != null
            row.tvBinDates.text = dates.orEmpty()
            val left = RecycleBinView.daysLeftNote(item.purgeAtMs, now)
            row.tvBinDaysLeft.isVisible = left != null
            row.tvBinDaysLeft.text = left?.text(context).orEmpty()
            row.btnBinRestore.apply {
                // Past its time the backend refuses a restore; the daily job erases it.
                isEnabled = !busy && RecycleBinView.daysLeft(item.purgeAtMs, now) != 0
                contentDescription = getString(R.string.recycle_bin_restore_desc_fmt, name)
                setOnClickListener { viewModel.restore(item.sessionId, name) }
            }
            row.btnBinDelete.apply {
                isEnabled = !busy
                contentDescription = getString(R.string.recycle_bin_delete_desc_fmt, name)
                setOnClickListener { askDelete(item.sessionId, item.localSessionId, name) }
            }
        }
    }

    companion object {
        private const val CLOUD_ID = "cloudId"
        private const val LOCAL_ID = "localId"
        private const val NAME = "name"
        private const val EXTRA_RESTORE = "com.sempermechanics.semper.extra.RESTORE_FROM_BIN"

        private val BY_SESSION = object : DiffUtil.ItemCallback<RecycleBinItemDto>() {
            override fun areItemsTheSame(old: RecycleBinItemDto, new: RecycleBinItemDto) =
                old.sessionId == new.sessionId

            override fun areContentsTheSame(old: RecycleBinItemDto, new: RecycleBinItemDto) = old == new
        }

        /**
         * Opens the recycle bin; with [restore] (a binned backup's cloud id),
         * restores it there at once, so the tap runs on the screen's
         * ViewModel and its outcome shows beside the list.
         */
        fun open(context: Context, restore: String? = null) {
            context.startActivity(
                Intent(context, RecycleBinActivity::class.java).apply {
                    if (!restore.isNullOrBlank()) putExtra(EXTRA_RESTORE, restore)
                },
            )
        }
    }
}
