@file:Suppress("TooManyFunctions")

package com.sempermechanics.semper.ui.common.media

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.CoachPrefs
import com.sempermechanics.semper.databinding.SheetMediaPickerBinding
import com.sempermechanics.semper.ui.common.CoachMarkController
import com.sempermechanics.semper.ui.common.SerialJob
import com.sempermechanics.semper.ui.common.auth.confirm
import com.sempermechanics.semper.ui.common.dialog.CrispToast
import com.sempermechanics.semper.ui.common.dialog.inflateSheet
import com.sempermechanics.semper.ui.common.onButtonChecked
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-sheet Images picker. Files dismisses this sheet and hands off to SAF
 * (the host Activity owns that launcher). Multi-select stays on the gallery.
 */
class MediaPickerSheet private constructor(
    private val activity: AppCompatActivity,
    private val mode: MediaSourceChooser.Mode,
    private val requestPermission: () -> Unit,
    private val onBrowseSaf: () -> Unit,
    private val onPicked: (List<Uri>) -> Unit,
) {
    /** Where the MediaStore query runs, fixed when the sheet opens. */
    private val queryDispatcher: CoroutineDispatcher = Companion.queryDispatcher

    private val content = inflateSheet(activity, R.layout.sheet_media_picker)
    private val sheet = content.dialog
    private val views = SheetMediaPickerBinding.bind(content.view)
    private val btnUse = views.btnMediaUse
    private val btnAllow = views.btnMediaAllow
    private val empty = views.mediaEmpty
    private val emptyText = views.tvMediaEmpty
    private val list = views.listMedia
    private val pickScrim = views.mediaPickScrim
    private val coach = CoachMarkController(activity)
    private val selected = linkedSetOf<Uri>()
    private val includeVideo = mode == MediaSourceChooser.Mode.HOME_REFERENCE
    private val multi = mode == MediaSourceChooser.Mode.DEFORMED

    /** Reference modes wait [REF_HINT_MS] so the hint is readable first. */
    private var selectionUnlocked = multi

    private val adapter = MediaGridAdapter(
        resolver = activity.contentResolver,
        isSelected = { selected.contains(it) },
        onClick = { item -> onTile(item) },
    )

    private val unlockSelection = Runnable {
        selectionUnlocked = true
        pickScrim.animate()
            .alpha(0f)
            .setDuration(SCRIM_FADE_MS)
            .withEndAction {
                pickScrim.isVisible = false
                pickScrim.alpha = 1f
            }
            .start()
    }

    /** The gallery query in flight; a newer reload (permission granted) replaces it. */
    private val reloadJob = SerialJob()

    private var expandWaitBehavior: BottomSheetBehavior<View>? = null
    private var expandWaitCallback: BottomSheetBehavior.BottomSheetCallback? = null

    init {
        views.tvMediaTitle.setText(
            when (mode) {
                MediaSourceChooser.Mode.HOME_REFERENCE -> R.string.new_analysis_title
                MediaSourceChooser.Mode.REFERENCE -> R.string.reference_image
                MediaSourceChooser.Mode.DEFORMED -> R.string.deformed_frames
            },
        )
        list.layoutManager = GridLayoutManager(activity, GRID_COLUMNS)
        list.adapter = adapter
        views.rgMediaSource.onButtonChecked { checkedId ->
            if (checkedId == R.id.btnMediaFiles) {
                sheet.dismiss()
                onBrowseSaf()
            }
        }
        btnAllow.setOnClickListener { requestPermission() }
        btnUse.setOnClickListener { confirm() }
        sheet.setOnDismissListener {
            reloadJob.cancel()
            list.removeCallbacks(unlockSelection)
            clearExpandWait()
            coach.dismiss(markSeen = false)
            adapter.shutdown()
        }
        content.view.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        sheet.behavior.skipCollapsed = true
        // Keep the first layout off-screen so wrap→match-parent remesaure is invisible.
        sheet.behavior.peekHeight = 0
        sheet.setOnShowListener {
            prepareFullHeightThenExpand {
                // Gallery query + thumbnails after the slide so they cannot hitch settle.
                reload()
                beginReferenceHintGate()
                maybeCoach()
            }
        }
        sheet.show()
    }

    fun onPermissionResult() {
        reload()
    }

    /**
     * Full-height sheet with one continuous expand. Remeasuring wrap→match while
     * collapsed at a normal peek made the drawer grow taller, then slide — two
     * motions. peekHeight=0 keeps that remesaure off-screen; only the expand shows.
     */
    private fun prepareFullHeightThenExpand(onReady: () -> Unit) {
        val bottom = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: run {
                onReady()
                return
            }
        val behavior = BottomSheetBehavior.from(bottom)
        behavior.skipCollapsed = true
        behavior.peekHeight = 0
        if (bottom.layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            bottom.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            bottom.requestLayout()
        }
        bottom.post {
            if (behavior.state != BottomSheetBehavior.STATE_EXPANDED) {
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
            whenExpanded(onReady)
        }
    }

    /**
     * Runs [action] once the sheet reaches [BottomSheetBehavior.STATE_EXPANDED].
     * If it is already expanded (or the sheet view is missing), runs immediately.
     */
    private fun whenExpanded(action: () -> Unit) {
        clearExpandWait()
        val bottom = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        val behavior = bottom?.let { BottomSheetBehavior.from(it) }
        if (behavior == null || behavior.state == BottomSheetBehavior.STATE_EXPANDED) {
            action()
            return
        }
        var done = false
        val finish = {
            if (!done) {
                done = true
                clearExpandWait()
                action()
            }
        }
        val callback = object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) finish()
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        }
        expandWaitBehavior = behavior
        expandWaitCallback = callback
        behavior.addBottomSheetCallback(callback)
    }

    private fun clearExpandWait() {
        val callback = expandWaitCallback
        val behavior = expandWaitBehavior
        if (callback != null && behavior != null) {
            behavior.removeBottomSheetCallback(callback)
        }
        expandWaitCallback = null
        expandWaitBehavior = null
    }

    private fun reload() {
        reloadJob.cancel()
        if (!MediaStoreBrowser.hasReadAccess(activity)) {
            adapter.submit(emptyList())
            showEmpty(needPermission = true)
            return
        }
        // A content-resolver query over the whole gallery: it can take a
        // while on a big one, so it stays off the main thread.
        reloadJob.launch(activity.lifecycleScope) {
            val items = withContext(queryDispatcher) {
                runCatching {
                    MediaStoreBrowser.query(
                        context = activity,
                        includeVideo = includeVideo,
                    )
                }.getOrDefault(emptyList())
            }
            if (sheet.isShowing) showItems(items)
        }
    }

    private fun showItems(items: List<MediaStoreBrowser.Item>) {
        adapter.submit(items)
        empty.isVisible = items.isEmpty()
        list.isVisible = items.isNotEmpty()
        btnAllow.isVisible = false
        emptyText.setText(R.string.media_empty)
        refreshUse()
    }

    private fun showEmpty(needPermission: Boolean) {
        empty.isVisible = true
        list.isVisible = false
        btnAllow.isVisible = needPermission
        emptyText.setText(
            if (needPermission) R.string.media_need_permission else R.string.media_empty,
        )
    }

    private fun beginReferenceHintGate() {
        if (multi) {
            pickScrim.isVisible = false
            selectionUnlocked = true
            showToast(durationMs = null)
            return
        }
        selectionUnlocked = false
        pickScrim.animate().cancel()
        pickScrim.alpha = 1f
        pickScrim.isVisible = true
        showToast(durationMs = REF_HINT_MS)
        list.removeCallbacks(unlockSelection)
        list.postDelayed(unlockSelection, REF_HINT_MS)
    }

    private fun onTile(item: MediaStoreBrowser.Item) {
        if (!selectionUnlocked) return
        if (!multi) {
            onPicked(listOf(item.uri))
            sheet.dismiss()
            return
        }
        if (!selected.add(item.uri)) selected.remove(item.uri)
        val position = adapter.indexOf(item.uri)
        if (position >= 0) adapter.notifyItemChanged(position)
        refreshUse()
    }

    private fun refreshUse() {
        btnUse.isVisible = multi && selected.isNotEmpty()
        if (btnUse.isVisible) {
            btnUse.text = activity.resources.getQuantityString(
                R.plurals.media_use_fmt,
                selected.size,
                selected.size,
            )
        }
    }

    private fun confirm() {
        if (selected.isEmpty()) return
        onPicked(selected.toList())
        sheet.dismiss()
    }

    private fun showToast(durationMs: Long?) {
        val message = when (mode) {
            MediaSourceChooser.Mode.DEFORMED -> activity.getString(R.string.picker_select_deformed)
            else -> activity.getString(R.string.picker_select_reference_toast)
        }
        // Reference gate: centre the larger pill over the dimmed grid. Deformed
        // tips stay a compact top toast on the sheet chrome.
        if (durationMs != null) {
            val overlay = pickScrim.parent as? ViewGroup
            if (overlay != null) CrispToast.showProminent(activity, message, overlay, durationMs)
        } else {
            val overlay = sheet.window?.decorView as? ViewGroup
            if (overlay != null) {
                CrispToast.show(
                    activity,
                    message,
                    overlayRoot = overlay,
                    fromTop = true,
                    durationMs = 2000L,
                )
            }
        }
    }

    private fun maybeCoach() {
        val overlay = sheet.window?.decorView as? FrameLayout ?: return
        if (multi) {
            coach.maybeShow(
                CoachPrefs.Screen.MEDIA_PICKER_DEF,
                listOf(
                    CoachMarkController.Step(
                        list,
                        activity.getString(R.string.coach_picker_multi),
                    ),
                    CoachMarkController.Step(
                        views.btnMediaFiles,
                        activity.getString(R.string.coach_picker_files_select_all),
                        illustration = R.drawable.coach_saf_select_all,
                    ),
                ),
                overlayParent = overlay,
            )
            return
        }
        coach.maybeShow(
            CoachPrefs.Screen.MEDIA_PICKER_REF,
            listOf(
                CoachMarkController.Step(
                    views.btnMediaImages,
                    activity.getString(R.string.coach_picker_images),
                ),
                CoachMarkController.Step(
                    views.btnMediaFiles,
                    activity.getString(R.string.coach_picker_files),
                ),
            ),
            overlayParent = overlay,
        )
    }

    companion object {
        private const val REF_HINT_MS = 1000L
        private const val SCRIM_FADE_MS = 180L
        private const val GRID_COLUMNS = 3

        /** Seam for tests, which query inline so the grid fills as the main thread idles. */
        @VisibleForTesting
        internal var queryDispatcher: CoroutineDispatcher = Dispatchers.IO

        fun show(
            activity: AppCompatActivity,
            mode: MediaSourceChooser.Mode,
            requestPermission: () -> Unit,
            onBrowseSaf: () -> Unit,
            onPicked: (List<Uri>) -> Unit,
        ): MediaPickerSheet = MediaPickerSheet(
            activity,
            mode,
            requestPermission,
            onBrowseSaf,
            onPicked,
        )
    }
}
