@file:Suppress("TooManyFunctions")

package com.indicvision.semper.ui.settings

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.indicvision.semper.R
import com.indicvision.semper.data.CacheJanitor
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.data.SessionStore
import com.indicvision.semper.data.StorageBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On-device storage: sizes, auto-free budget, free-up, and cache clear.
 * Session restore / download / delete stay on [SettingsActivity].
 */
class SettingsStorageSection(
    private val activity: SettingsActivity,
) {
    fun wire() {
        activity.findViewById<View>(R.id.btnStorageFreeUp).setOnClickListener { confirmFreeUpSpace() }
        activity.findViewById<View>(R.id.btnStorageClearCache).setOnClickListener { clearTemporaryFiles() }
        activity.findViewById<ImageButton>(R.id.btnAutoFreeInfo).setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.storage_auto_free)
                .setMessage(R.string.storage_auto_free_info)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        val valueLabel = activity.findViewById<TextView>(R.id.tvAutoFreeValue)
        activity.findViewById<Slider>(R.id.sliderAutoFree).apply {
            valueTo = DicSettings.MAX_AUTO_FREE_GB.toFloat()
            value = DicSettings.autoFreeBudgetGb(activity)
                .toFloat().coerceIn(valueFrom, valueTo)
            valueLabel.text = autoFreeText(value.toInt())
            addOnChangeListener { _, v, fromUser ->
                valueLabel.text = autoFreeText(v.toInt())
                if (!fromUser) return@addOnChangeListener
                DicSettings.setAutoFreeBudgetGb(activity, v.toInt())
                // Applying on release rather than on every tick: dragging past a
                // low value would otherwise start dropping sessions mid-gesture.
            }
            addOnSliderTouchListener(
                object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) = Unit
                    override fun onStopTrackingTouch(slider: Slider) = applyStorageBudget()
                },
            )
        }

        refreshStorageTotals()
    }

    private fun autoFreeText(gb: Int): String =
        if (gb <= DicSettings.AUTO_FREE_OFF) {
            activity.getString(R.string.storage_auto_free_off)
        } else {
            activity.getString(R.string.storage_auto_free_on_fmt, gb)
        }

    /** Measures off the main thread — a full sessions tree is a lot of stat calls. */
    private fun refreshStorageTotals() {
        activity.lifecycleScope.launch {
            val sizes = withContext(Dispatchers.IO) {
                Triple(
                    SessionStore.totalSize(activity),
                    // Show what Clear will free — not raw cacheDir size (which
                    // includes a live import the button must not delete).
                    CacheJanitor.clearableUserBytes(activity),
                    StorageBudget.reclaimableBytes(activity),
                )
            }
            val (analyses, cache, reclaimable) = sizes
            activity.findViewById<TextView>(R.id.tvStorageAnalysesSize).text = activity.humanSize(analyses)
            activity.findViewById<TextView>(R.id.tvStorageCacheSize).text = activity.humanSize(cache)
            activity.findViewById<View>(R.id.btnStorageClearCache).isEnabled = cache > 0

            val freeUpSub = activity.findViewById<TextView>(R.id.tvStorageFreeUpSub)
            activity.findViewById<View>(R.id.btnStorageFreeUp).isEnabled = reclaimable > 0
            freeUpSub.text = if (reclaimable > 0) {
                activity.getString(R.string.storage_free_up_sub_fmt, activity.humanSize(reclaimable))
            } else {
                activity.getString(R.string.storage_free_up_none)
            }
        }
    }

    private fun confirmFreeUpSpace() {
        activity.lifecycleScope.launch {
            val reclaimable = withContext(Dispatchers.IO) {
                StorageBudget.reclaimableBytes(activity)
            }
            if (reclaimable <= 0) {
                activity.toast(activity.getString(R.string.storage_freed_none))
                return@launch
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.storage_free_up_title)
                .setMessage(activity.getString(R.string.storage_free_up_body, activity.humanSize(reclaimable)))
                .setPositiveButton(R.string.storage_free_up_confirm) { _, _ -> freeUpSpace() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun freeUpSpace() {
        activity.lifecycleScope.launch {
            val outcome = StorageBudget.freeAllBackedUpAsync(activity)
            if (outcome.didAnything) {
                activity.toast(
                    activity.getString(
                        R.string.storage_freed_fmt,
                        activity.humanSize(outcome.freedBytes),
                        outcome.sessionsDropped,
                    ),
                )
            } else {
                activity.toast(activity.getString(R.string.storage_freed_none))
            }
            refreshStorageTotals()
            activity.wireAnalysesDataSection()
        }
    }

    private fun clearTemporaryFiles() {
        activity.lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) {
                CacheJanitor.sweepUserRequested(activity)
            }
            if (freed > 0) {
                activity.toast(activity.getString(R.string.storage_cache_cleared_fmt, activity.humanSize(freed)))
            } else {
                activity.toast(activity.getString(R.string.storage_cache_cleared_none))
            }
            refreshStorageTotals()
        }
    }

    private fun applyStorageBudget() {
        activity.lifecycleScope.launch {
            val outcome = StorageBudget.enforceAsync(activity)
            if (outcome.didAnything) {
                activity.toast(
                    activity.getString(
                        R.string.storage_freed_fmt,
                        activity.humanSize(outcome.freedBytes),
                        outcome.sessionsDropped,
                    ),
                )
                activity.wireAnalysesDataSection()
            }
            refreshStorageTotals()
        }
    }
}
