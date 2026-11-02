package com.sempermechanics.semper.ui.recyclebin

import android.app.Application
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sempermechanics.semper.data.cloud.CloudReconcile
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.SessionDeletes
import com.sempermechanics.semper.data.cloud.bin.BinOutcome
import com.sempermechanics.semper.data.cloud.bin.RecycleBin
import com.sempermechanics.semper.data.cloud.pendingPurgeIds
import com.sempermechanics.semper.data.net.RecycleBinItemDto
import com.sempermechanics.semper.data.net.RecycleBinResponse
import com.sempermechanics.semper.data.net.RecycleBinRestoreResponse
import com.sempermechanics.semper.ui.common.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The recycle bin screen's list and its Restore taps, kept across a rotation
 * (ADR-016): a call runs on [viewModelScope], and what a Restore came to is
 * handed out once ([notices]) to whichever screen is current.
 *
 * Delete permanently and Empty recycle bin are queued in `SessionDeletes`
 * (WorkManager) by the screen; this hides what they will erase meanwhile.
 */
@MainThread
class RecycleBinViewModel(app: Application) : AndroidViewModel(app) {

    /** What the screen shows. */
    sealed interface Screen {
        data object Loading : Screen

        /** The backups in the bin, soonest erased first; empty for the empty state. */
        data class Loaded(val items: List<RecycleBinItemDto>) : Screen

        /** The bin could not be read; [note] says why, and the screen offers to try again. */
        data class Failed(val note: Note) : Screen
    }

    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** A Restore is out: the row buttons wait. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val noticeChannel = Channel<Notice>(Channel.UNLIMITED)

    /** Each Restore's outcome once; buffered while no screen is there to take it. */
    val notices: Flow<Notice> = noticeChannel.receiveAsFlow()

    /** How the screen reaches the backend ([factory]). */
    private val bin: Bin = factory(app)

    /**
     * Backups a permanent delete was just queued for: hidden until WorkManager
     * lists that delete ([Bin.pendingPurges]) or it is undone.
     */
    private val justQueued = mutableSetOf<String>()

    /** Reads the bin again. */
    fun load() {
        viewModelScope.launch { reload() }
    }

    /** Hides [cloudIds] at once: a permanent delete was queued for them. */
    fun hideQueued(cloudIds: Collection<String>) {
        justQueued += cloudIds
        val shown = _screen.value
        if (shown is Screen.Loaded) _screen.value = Screen.Loaded(shown.items.filterNot { it.sessionId in justQueued })
    }

    /** The queued deletes reached WorkManager, or were undone: their own state shows from here. */
    fun clearQueued() {
        justQueued.clear()
    }

    /** Put [cloudId] (named [name], blank when unknown) back in the backups. */
    fun restore(cloudId: String, name: String = "") {
        if (cloudId.isBlank() || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            var restored = false
            try {
                val outcome = bin.restore(cloudId)
                noticeChannel.send(RecycleBinView.restored(outcome, name))
                restored = outcome is BinOutcome.Ok
                if (restored || outcome == BinOutcome.Gone) reload()
            } finally {
                _busy.value = false
            }
            // Not under busy: the screen is done, and Home's list catches up.
            if (restored) bin.reconcile()
        }
    }

    private suspend fun reload() {
        if (_screen.value !is Screen.Loaded) _screen.value = Screen.Loading
        _screen.value = when (val outcome = bin.list()) {
            is BinOutcome.Ok -> {
                val hidden = justQueued + bin.pendingPurges()
                Screen.Loaded(outcome.value.items.filterNot { it.sessionId in hidden }.sortedBy { it.purgeAtMs })
            }
            else -> Screen.Failed(RecycleBinView.listFailed(outcome))
        }
    }

    override fun onCleared() {
        noticeChannel.close()
        super.onCleared()
    }

    companion object {
        /** Builds each screen's [Bin]; tests stand in for it before the screen opens. */
        @VisibleForTesting
        internal var factory: (Application) -> Bin = { Bin.Live(it) }
    }

    /** The recycle bin and the reconcile, as the screen reaches them. */
    interface Bin {
        suspend fun list(): BinOutcome<RecycleBinResponse>

        suspend fun restore(cloudId: String): BinOutcome<RecycleBinRestoreResponse>

        /** Cloud ids a queued or running permanent delete will erase. */
        suspend fun pendingPurges(): Set<String>

        /** A backup came back: check the account's backups now, past the reconcile's throttle. */
        suspend fun reconcile()

        class Live(private val app: Application) : Bin {
            override suspend fun list() = RecycleBin.list(app)

            override suspend fun restore(cloudId: String) = RecycleBin.restore(app, cloudId)

            override suspend fun pendingPurges() = withContext(Dispatchers.IO) { SessionDeletes.pendingPurgeIds(app) }

            override suspend fun reconcile() {
                withContext(Dispatchers.IO) { CloudReconcile.reconcileDue(app) }
                CloudSync.reconcile(app)
            }
        }
    }
}
