package com.sempermechanics.semper.data.session

import android.content.Context
import androidx.annotation.WorkerThread

// Whether a backup's metadata.json is still current, beside SessionStore so its
// file keeps within the ADR-015 budget. A pure move out of SessionStore.

/**
 * The backend now holds metadata built from [sent]. Clears
 * [SessionRecord.metadataStale] only if what the metadata carries and can
 * change after a backup ([haveSameMetadataInputs]) is still [sent]'s, so a
 * change made while the send was in flight is sent again. Returns whether
 * it cleared.
 */
@WorkerThread
fun SessionStore.clearMetadataStale(context: Context, id: String, sent: SessionRecord): Boolean {
    var cleared = false
    val written = update(context, id) {
        if (haveSameMetadataInputs(it, sent)) {
            cleared = true
            it.copy(metadataStale = false)
        } else {
            it
        }
    }
    return written && cleared
}

/**
 * The fields a change after the backup can alter in metadata.json, the ones
 * that mark [SessionRecord.metadataStale]. One place, so a field added to
 * that list is compared here too.
 */
private fun haveSameMetadataInputs(a: SessionRecord, b: SessionRecord): Boolean = a.name == b.name
