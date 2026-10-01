package com.indicvision.semper.ui.viewer

import android.app.Application
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import timber.log.Timber
import java.io.File

/**
 * The "Save to Files" copy, held across a rotation of [SaveExportActivity].
 *
 * The copy used to run in the activity's own scope, so a rotation mid-copy
 * cancelled the wait and the recreated proxy started a second copy into the
 * same document while the first, blocking in IO, was still writing it. Here one
 * copy runs per proxy, and whichever instance is current awaits it.
 */
internal class SaveExportViewModel(app: Application) : AndroidViewModel(app) {

    private var copy: Deferred<Boolean>? = null

    /** The one copy of [file] into [uri]: started on the first call, shared after. */
    fun copyOnce(uri: Uri, file: File): Deferred<Boolean> = copy ?: start(uri, file).also { copy = it }

    private fun start(uri: Uri, file: File): Deferred<Boolean> {
        val resolver = getApplication<Application>().contentResolver
        return viewModelScope.async(ioDispatcher) {
            runCatching {
                // Treat the copy as successful only if bytes actually landed —
                // an opened-but-empty stream shouldn't report "Saved".
                val copied = resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: 0L
                copied > 0L
            }.onFailure { Timber.e(it, "Save to Files failed") }.getOrDefault(false)
        }
    }

    companion object {
        /** Where the copy runs; a test holds it to rotate mid-copy. */
        @VisibleForTesting
        @Volatile
        var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    }
}
