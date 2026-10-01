package com.indicvision.semper.data.cloud

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import com.indicvision.semper.data.net.CloudSessionDto
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * The account's finished backups as the last successful reconcile listed them,
 * so Home can say which are not on this phone without asking the backend again.
 *
 * Home lists only rows the phone has (or once had). A backup made on another
 * phone, or before a reinstall, used to show up only under Settings, so a
 * new phone opened on "No analyses yet" while the account had backups. Home now
 * offers those backups from this saved listing.
 *
 * Kept honest between reconciles: a cloud delete forgets its backup here, and
 * sign-out or account deletion clears the lot. Anything else that changes the
 * cloud shows up at the next reconcile.
 */
object CloudBackupListing {

    /** One finished backup: enough to name it, size it and restore it. */
    data class Backup(val cloudId: String, val localId: String, val name: String, val bytes: Long)

    private const val PREFS = "indic_cloud_listing"
    private const val KEY_BACKUPS = "backups"
    private const val KEY_HIDDEN = "hidden"

    /** Save what a successful listing found. Only COMPLETED backups can be restored. */
    fun record(context: Context, sessions: List<CloudSessionDto>) {
        val backups = sessions.filter {
            it.status == UploadWorkOutcomes.STATUS_COMPLETED && it.sessionId.isNotBlank()
        }.map {
            Backup(it.sessionId, it.localSessionId, it.specimen.orEmpty(), it.totalBytes)
        }
        val ids = backups.map { it.cloudId }.toSet()
        val prefs = prefs(context)
        // A hidden backup that has since gone from the cloud needs no memory.
        val hidden = hiddenIds(context).filterTo(mutableSetOf()) { it in ids }
        prefs.edit {
            putString(KEY_BACKUPS, encode(backups))
            putStringSet(KEY_HIDDEN, hidden)
        }
    }

    fun load(context: Context): List<Backup> = decode(prefs(context).getString(KEY_BACKUPS, null))

    /** This backup was deleted: stop offering it before the next reconcile says so. */
    fun forget(context: Context, cloudId: String) {
        if (cloudId.isBlank()) return
        val left = load(context).filterNot { it.cloudId == cloudId }
        prefs(context).edit { putString(KEY_BACKUPS, encode(left)) }
    }

    /** Another account, or none: nothing saved here applies any more. */
    fun clear(context: Context) {
        prefs(context).edit { clear() }
    }

    /**
     * Backups with no row on this phone. A row claims a backup by its local id,
     * or by the cloud id it stored, which is the rule Settings' list uses
     * ([com.indicvision.semper.ui.settings.AnalysisEntries.merge]).
     */
    fun notOnPhone(backups: List<Backup>, records: List<SessionRecord>): List<Backup> {
        val localIds = records.mapTo(mutableSetOf()) { it.id }
        val cloudIds = records.mapNotNullTo(mutableSetOf()) { it.cloudSessionId.takeIf(String::isNotBlank) }
        return backups.filterNot { (it.localId.isNotBlank() && it.localId in localIds) || it.cloudId in cloudIds }
    }

    /** What Home offers: backups not on this phone that the user has not hidden. */
    @WorkerThread
    fun offered(context: Context): List<Backup> {
        val hidden = hiddenIds(context)
        return notOnPhone(load(context), SessionStore.list(context)).filterNot { it.cloudId in hidden }
    }

    /** Stop offering these on Home. A backup added later is offered again. */
    fun hide(context: Context, cloudIds: Collection<String>) {
        prefs(context).edit { putStringSet(KEY_HIDDEN, hiddenIds(context) + cloudIds) }
    }

    private fun hiddenIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_HIDDEN, null).orEmpty().toSet()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

// Saved as a JSON array of backups; unreadable JSON offers nothing until the next reconcile.

private fun encode(backups: List<CloudBackupListing.Backup>): String = JSONArray().apply {
    backups.forEach {
        put(
            JSONObject()
                .put("cloudId", it.cloudId)
                .put("localId", it.localId)
                .put("name", it.name)
                .put("bytes", it.bytes),
        )
    }
}.toString()

private fun decode(json: String?): List<CloudBackupListing.Backup> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            CloudBackupListing.Backup(
                cloudId = o.getString("cloudId"),
                localId = o.optString("localId"),
                name = o.optString("name"),
                bytes = o.optLong("bytes"),
            )
        }
    }.onFailure { Timber.w(it, "Saved cloud listing unreadable; waiting for the next reconcile") }
        .getOrDefault(emptyList())
}
