package com.sempermechanics.semper.ui.viewer.share

import android.content.ContentResolver
import android.net.Uri
import com.sempermechanics.semper.ui.viewer.ResultViewerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The viewer's running share/export jobs, held by [ResultViewerViewModel] so a
 * rotation does not cancel one (the viewer has no `configChanges`).
 *
 * The screen side ([ShareExportUi]) watches [running] for progress and takes
 * each finished job from [outcomes]; a recreated viewer re-attaches to both.
 *
 * What survives what:
 * - **Rotation / any configuration change:** the job runs on in [scope]
 *   (`viewModelScope`). The new viewer shows its progress again and delivers its
 *   result: the share sheet, or the "Saved" toast for a save-as document, which
 *   this class writes itself so it never needs a live screen.
 * - **Leaving the viewer** (Back, Home): the ViewModel is cleared and the job
 *   cancelled, as before.
 * - **Process death:** running jobs are lost. A save-as picker that was open
 *   still returns to the restored viewer, which starts that export then.
 *
 * A job holds plain data only, never the viewer that started it: the share
 * snapshot taken on the main thread, the application's resources and cache dir
 * ([ShareExportBuilder]). So a rotation frees the old viewer at once, even while
 * a long export runs on, and each job writes into its own directory under
 * `cacheDir/share`, so two jobs naming the same file cannot overwrite each other.
 */
internal class ShareExportJobs(
    private val scope: CoroutineScope,
    private val resolver: ContentResolver,
) {

    /** A running export, as the screen shows it. */
    data class Running(
        val id: String,
        val title: String,
        val percent: Int = 0,
        val status: String? = null,
        /** Sent to the banner (Back, or a tap outside the dialog) rather than watched in the dialog. */
        val background: Boolean = false,
    )

    /** How a job ended, for the screen to tell the user. */
    sealed interface Outcome {
        val id: String

        /** A file for the share sheet; [direct] goes straight to the system chooser. */
        data class Ready(override val id: String, val file: File, val mime: String, val direct: Boolean) : Outcome

        /** Written to a save-as document; [ok] is whether any bytes landed. */
        data class Saved(override val id: String, val ok: Boolean) : Outcome

        data class Failed(override val id: String) : Outcome

        data class Cancelled(override val id: String) : Outcome
    }

    private val _running = MutableStateFlow<Map<String, Running>>(emptyMap())

    /** Every running job by id, in start order. Written from any thread. */
    val running: StateFlow<Map<String, Running>> = _running.asStateFlow()

    private val outcomeChannel = Channel<Outcome>(Channel.UNLIMITED)

    /** Each finished job once; buffered until a screen is there to take it. */
    val outcomes: Flow<Outcome> = outcomeChannel.receiveAsFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val nextId = AtomicInteger()

    /**
     * A fresh id for a job of [kind]. Unique per job: two jobs of a kind, or two
     * kinds sharing a progress title, never share a banner entry.
     */
    fun newId(kind: String): String = "share-$kind-${nextId.incrementAndGet()}"

    /**
     * Runs [produce] (on [Dispatchers.Default]) for job [id], then either writes
     * its file to [destUri] or hands it to the share sheet. [produce] reports
     * progress through its argument and throws when it has nothing usable.
     */
    fun start(
        id: String,
        title: String,
        destUri: Uri?,
        direct: Boolean,
        produce: suspend (report: (Int, String) -> Unit) -> Pair<File, String>,
    ) {
        _running.update { it + (id to Running(id, title)) }
        val job = scope.launch {
            val outcome = try {
                val (file, mime) = withContext(Dispatchers.Default) {
                    produce { percent, status -> progress(id, percent, status) }
                }
                if (destUri != null) {
                    Outcome.Saved(id, writeTo(destUri, file))
                } else {
                    Outcome.Ready(id, file, mime, direct)
                }
            } catch (e: CancellationException) {
                finish(Outcome.Cancelled(id))
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                // Throwable, not just Exception: a large multi-frame ZIP/PDF export
                // can hit OutOfMemoryError (an Error), which we'd rather surface as
                // "share failed" than let crash the app.
                Timber.e(e, "Share generation failed")
                Outcome.Failed(id)
            }
            finish(outcome)
        }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id, job) }
    }

    /**
     * Stops job [id]. It leaves [running] at once, so no screen shows it while
     * the generator winds down; its [Outcome.Cancelled] follows.
     */
    fun cancel(id: String) {
        _running.update { it - id }
        jobs[id]?.cancel()
    }

    /** The user sent job [id] to the background: show it in the banner, not the dialog. */
    fun sendToBackground(id: String) {
        _running.update { all -> all[id]?.let { all + (id to it.copy(background = true)) } ?: all }
    }

    private fun progress(id: String, percent: Int, status: String) {
        _running.update { all -> all[id]?.let { all + (id to it.copy(percent = percent, status = status)) } ?: all }
    }

    private fun finish(outcome: Outcome) {
        _running.update { it - outcome.id }
        outcomeChannel.trySend(outcome)
    }

    /** Copies [file] into the picked document; true when any bytes landed. */
    private suspend fun writeTo(uri: Uri, file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: 0L
        }.onFailure { Timber.e(it, "Save to Files failed") }.getOrDefault(0L) > 0L
    }
}
