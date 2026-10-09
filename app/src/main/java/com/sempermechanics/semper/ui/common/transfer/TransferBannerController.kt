package com.sempermechanics.semper.ui.common.transfer

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.annotation.MainThread
import androidx.core.view.isVisible
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.ViewTransferBannerBinding
import com.sempermechanics.semper.ui.common.EtaEstimator
import com.sempermechanics.semper.ui.common.ProgressText
import kotlin.math.roundToInt

/**
 * Non-modal transfer strip: one progress page at a time, with left/right
 * navigation when multiple transfers are active. Does not block the host UI.
 *
 * A page reads like the export dialog: the status on the left ("Frame 12 of
 * 40 · heatmaps", "4.2 of 12.0 MB · 1.1 MB/s"), the percent to one decimal on
 * the right, the bar, and the time left under it once [EtaEstimator] has an
 * answer. Each transfer keeps its own estimate.
 *
 * @param clock monotonic milliseconds for the time-left estimates; a test's to set.
 */
class TransferBannerController(
    private val root: View,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {

    /** One transfer's page. [percent] is 0–100; 0 shows a spinning bar. */
    data class Transfer(
        val id: String,
        val title: String,
        val percent: Double = 0.0,
        val status: String = "",
        val cancellable: Boolean = true,
        val onCancel: (() -> Unit)? = null,
        val eta: EtaEstimator.Eta = EtaEstimator.Eta.Unknown,
    )

    private val views = ViewTransferBannerBinding.bind(root)

    private val transfers = linkedMapOf<String, Transfer>()
    private val estimators = HashMap<String, EtaEstimator>()
    private var pageIndex = 0

    init {
        views.btnTransferPrev.setOnClickListener { moveBy(-1) }
        views.btnTransferNext.setOnClickListener { moveBy(1) }
        views.btnTransferCancel.setOnClickListener {
            current()?.onCancel?.invoke()
        }
        render()
    }

    fun upsert(transfer: Transfer) {
        onMain {
            val isNew = transfer.id !in transfers
            transfers[transfer.id] = transfer
            if (isNew) pageIndex = transfers.size - 1
            render()
        }
    }

    /** Moves transfer [id] to [percent] (0–100), with [status] when given. Safe from any thread. */
    fun updateProgress(id: String, percent: Double, status: String? = null) {
        onMain {
            val existing = transfers[id] ?: return@onMain
            val clamped = percent.coerceIn(0.0, PERCENT)
            val eta = estimators.getOrPut(id) { EtaEstimator() }.sample(clamped / PERCENT, clock())
            transfers[id] = existing.copy(percent = clamped, status = status ?: existing.status, eta = eta)
            render()
        }
    }

    fun remove(id: String) {
        onMain {
            estimators.remove(id)
            if (transfers.remove(id) == null) return@onMain
            if (pageIndex >= transfers.size) pageIndex = (transfers.size - 1).coerceAtLeast(0)
            render()
        }
    }

    /**
     * Whether [id] is on the strip. Main thread only: [transfers] is changed
     * on the main thread (see [onMain]) and read here without a lock.
     */
    @MainThread
    fun contains(id: String): Boolean = id in transfers

    fun size(): Int = transfers.size

    private fun current(): Transfer? = transfers.values.elementAtOrNull(pageIndex)

    private fun moveBy(delta: Int) {
        if (transfers.size <= 1) return
        pageIndex = (pageIndex + delta + transfers.size) % transfers.size
        render()
    }

    private fun render() {
        val item = current()
        if (item == null) {
            root.isVisible = false
            return
        }
        root.isVisible = true
        views.tvTransferTitle.text = item.title
        val multi = transfers.size > 1
        views.btnTransferPrev.isVisible = multi
        views.btnTransferNext.isVisible = multi
        views.tvTransferPage.isVisible = multi
        if (multi) {
            views.tvTransferPage.text = root.context.getString(
                R.string.transfer_banner_page_fmt,
                pageIndex + 1,
                transfers.size,
            )
        }
        views.btnTransferCancel.isVisible = item.cancellable && item.onCancel != null
        renderProgress(item)
    }

    private fun renderProgress(item: Transfer) {
        val res = root.resources
        val started = item.percent > 0.0
        views.transferProgress.isIndeterminate = !started
        if (started) {
            views.transferProgress.setProgressCompat((item.percent * BAR_PER_PERCENT).roundToInt(), true)
        }
        views.tvTransferStatus.text = item.status.ifBlank {
            if (started) "" else res.getString(R.string.transfer_banner_working)
        }
        views.tvTransferPercent.isVisible = started
        views.tvTransferPercent.text = if (started) ProgressText.percent(res, item.percent) else null
        val left = if (started) EtaEstimator.label(res, item.eta) else null
        views.tvTransferEta.isVisible = left != null
        views.tvTransferEta.text = left
    }

    /** Share jobs report progress off the main thread; hop UI writes here. */
    private fun onMain(block: () -> Unit) {
        val activity = root.context as? Activity
        if (activity != null) {
            activity.runOnUiThread(block)
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            root.post(block)
        }
    }

    private companion object {
        const val PERCENT = 100.0

        /** The bar's max is 1000 (layout): it moves in tenths of a percent. */
        const val BAR_PER_PERCENT = 10.0
    }
}
