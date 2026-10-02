package com.indicvision.semper.util

import java.io.File

/**
 * Writes [dest] the [AtomicFiles] way: [write] fills [tmp], and only once it
 * returns is [tmp] promoted onto [dest] ([AtomicFiles.promote]: a rename,
 * else copy and delete). A reader never sees a half-written [dest].
 *
 * Unless the promote completes, [tmp] is deleted: when [write] or the promote
 * throws, the `finally` deletes it and the throwable (cancellation included)
 * propagates unchanged. No abandoned write leaves a sidecar behind for the next
 * run to mistake for a finished one (the default `.part` name is the one
 * [com.indicvision.semper.data.net.drive.DriveTransfer] resumes from).
 *
 * [write] is `crossinline`, so a non-local `return` (or `break`/`continue`)
 * out of it does not compile. It has to be: inlined, such a return leaves
 * through the caller's own `return` and skips this function's `finally` (the
 * caller's bytecode had the `areturn` with no cleanup before it), which would
 * leave the sidecar behind. To stop early, return from the lambda
 * (`return@writeVia value`, which promotes what was written) or throw.
 *
 * No `fd.sync()` happens here. A caller that needs the bytes on disk before
 * the rename (the session index, `SessionStore`) keeps its own sync inside
 * [write].
 *
 * [tmp] defaults to [AtomicFiles.partOf]; a caller whose sidecar has another
 * established name (`index.json.tmp`, `Session.zip.tmp`, `<part>.tmp`) passes
 * it, because cleanup code elsewhere deletes the sidecar by that name.
 * [clearDest] deletes [dest] just before the promote, for the callers that did
 * so by hand; leave it off where [dest] must never be missing (the session
 * index relies on the rename replacing it in one step).
 *
 * Not for resumable downloads: those keep their `.part` across attempts on
 * purpose ([com.indicvision.semper.data.net.drive.DriveTransfer]).
 *
 * @return what [write] returned (a digest, a count, …).
 */
inline fun <T> AtomicFiles.writeVia(
    dest: File,
    tmp: File = partOf(dest),
    clearDest: Boolean = false,
    crossinline write: (tmp: File) -> T,
): T {
    var promoted = false
    try {
        val result = write(tmp)
        if (clearDest) dest.delete()
        promote(tmp, dest)
        promoted = true
        return result
    } finally {
        if (!promoted) tmp.delete()
    }
}
