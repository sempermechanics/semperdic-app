package com.sempermechanics.semper.data.cloud

import android.content.Context
import androidx.core.content.edit
import com.sempermechanics.semper.data.prefs.PrefFiles.CloudListing
import com.sempermechanics.semper.data.prefs.get
import com.sempermechanics.semper.data.prefs.privatePrefs
import com.sempermechanics.semper.data.prefs.put

/**
 * The SYNCED rows whose only backup another app made (ADR-014, TD-153), as the
 * last reconcile found them (`CloudReconcile.list`'s keep). The backend answers
 * this app's restore of one `404 session_not_found`, so its phone copy is not a
 * cache to drop ([com.sempermechanics.semper.data.session.StorageBudget]), and
 * Home warns before "From this phone" deletes it.
 *
 * Saved beside [CloudBackupListing], so sign-out's clear of that listing drops
 * these too.
 */
object OtherAppBackups {

    /** The local ids a reconcile found backed up by another app; replaces the last set. */
    fun record(context: Context, localIds: Set<String>) {
        privatePrefs(context, CloudListing.NAME).edit { put(CloudListing.OTHER_APP, localIds) }
    }

    /** The local ids [record] last saved. */
    fun ids(context: Context): Set<String> = privatePrefs(context, CloudListing.NAME)[CloudListing.OTHER_APP]
}
