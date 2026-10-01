package com.indicvision.semper.ui.viewer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.indicvision.semper.ui.viewer.share.ShareExportJobs
import java.util.concurrent.ConcurrentHashMap

/**
 * Survives configuration changes for the results browser: frame scrubber index,
 * active field type, the user's fixed colour scales and running exports. Heavy
 * bitmaps stay in the Activity (they are bound to view lifetime).
 */
class ResultViewerViewModel(
    app: Application,
    private val state: SavedStateHandle,
) : AndroidViewModel(app) {
    var currentFrameIndex: Int = 0
    var currentDataIndex: Int = 2
    var currentTypeString: String = "U"

    /**
     * Fixed colour-scale bounds per field, set in the custom-scale dialog.
     * Concurrent: an export reads them off the main thread (the GIF bounds)
     * while the dialog may change them.
     */
    val customBounds: MutableMap<Int, Pair<Float, Float>> = ConcurrentHashMap()

    /** Share/export jobs; in [viewModelScope], so a rotation does not cancel them. */
    internal val exports = ShareExportJobs(viewModelScope, app.contentResolver)

    /**
     * A picked save-as document (export kind and destination) whose export has
     * not started yet: the picker's answer can reach a viewer whose frames are
     * not listed yet. Held here, and in the saved state for process death, so
     * whichever viewer is current when the frames are known starts it — once.
     */
    val hasPendingSave: Boolean get() = state.contains(KEY_SAVE_KIND)

    fun setPendingSave(kind: String, uri: Uri) {
        state[KEY_SAVE_URI] = uri
        state[KEY_SAVE_KIND] = kind
    }

    /** The pending save-as, cleared as it is handed out so its export starts once. */
    fun takePendingSave(): Pair<String, Uri>? {
        val kind = state.remove<String>(KEY_SAVE_KIND)
        val uri = state.remove<Uri>(KEY_SAVE_URI)
        return if (kind != null && uri != null) kind to uri else null
    }

    private companion object {
        const val KEY_SAVE_KIND = "pending_save_kind"
        const val KEY_SAVE_URI = "pending_save_uri"
    }
}
