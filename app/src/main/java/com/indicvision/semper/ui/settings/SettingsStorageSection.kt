package com.indicvision.semper.ui.settings

import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.indicvision.semper.R
import com.indicvision.semper.data.account.LicenseEntitlements
import com.indicvision.semper.data.prefs.DicSettings
import com.indicvision.semper.data.session.CacheJanitor
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.data.session.StorageBudget
import com.indicvision.semper.databinding.SettingsScrollContentBinding
import com.indicvision.semper.ui.common.ByteSize
import com.indicvision.semper.ui.common.dialog.Dialogs
import com.indicvision.semper.ui.common.dialog.bindInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On-device storage: sizes, auto-free budget, free-up, and cache clear.
 * Session restore / download / delete stay on [SettingsActivity].
 */
class SettingsStorageSection(
    private val activity: SettingsActivity,
    private val views: SettingsScrollContentBinding,
) {
    fun wire() {
        views.btnStorageClearCache.setOnClickListener { clearTemporaryFiles() }
        // Free-up and the auto-free budget evict local copies the cloud can
        // give back — the licensed half of cloud. A demo account has no
        // restore, so neither control exists for it (StorageBudget itself
        // also refuses, so a stale budget pref cannot drop anything).
        if (!LicenseEntitlements.cloudBackupEnabled(activity)) {
            listOf(
                views.tvStorageFreeUpSub,
                views.btnStorageFreeUp,
                views.rowAutoFreeHeader,
                views.tvAutoFreeValue,
                views.sliderAutoFree,
            ).forEach { it.isVisible = false }
            refreshStorageTotals()
            return
        }
        views.btnStorageFreeUp.setOnClickListener { confirmFreeUpSpace() }
        views.btnAutoFreeInfo.bindInfo(activity, R.string.storage_auto_free, R.string.storage_auto_free_info)

        val valueLabel = views.tvAutoFreeValue
        views.sliderAutoFree.apply {
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
            views.tvStorageAnalysesSize.text = ByteSize.format(analyses)
            views.tvStorageCacheSize.text = ByteSize.format(cache)
            views.btnStorageClearCache.isEnabled = cache > 0

            views.btnStorageFreeUp.isEnabled = reclaimable > 0
            views.tvStorageFreeUpSub.text = if (reclaimable > 0) {
                activity.getString(R.string.storage_free_up_sub_fmt, ByteSize.format(reclaimable))
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
            Dialogs.confirm(
                activity,
                activity.getText(R.string.storage_free_up_title),
                activity.getString(R.string.storage_free_up_body, ByteSize.format(reclaimable)),
                R.string.storage_free_up_confirm,
            ) { freeUpSpace() }
        }
    }

    private fun freeUpSpace() {
        activity.lifecycleScope.launch {
            val outcome = StorageBudget.freeAllBackedUpAsync(activity)
            if (outcome.didAnything) {
                activity.toast(
                    activity.resources.getQuantityString(
                        R.plurals.storage_freed_fmt,
                        outcome.sessionsDropped,
                        ByteSize.format(outcome.freedBytes),
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
                activity.toast(activity.getString(R.string.storage_cache_cleared_fmt, ByteSize.format(freed)))
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
                    activity.resources.getQuantityString(
                        R.plurals.storage_freed_fmt,
                        outcome.sessionsDropped,
                        ByteSize.format(outcome.freedBytes),
                        outcome.sessionsDropped,
                    ),
                )
                activity.wireAnalysesDataSection()
            }
            refreshStorageTotals()
        }
    }
}
