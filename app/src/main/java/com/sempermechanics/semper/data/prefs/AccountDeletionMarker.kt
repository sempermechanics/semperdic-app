package com.sempermechanics.semper.data.prefs

import android.content.Context
import androidx.core.content.edit
import com.sempermechanics.semper.data.prefs.PrefFiles.AccountDeletion

/**
 * What an account deletion still owes this phone, kept on disk so it survives
 * process death (TD-165).
 *
 * The deletion runs on an application-lifetime scope, which ends with the
 * process. Killed between the cloud erase and the local wipe, the phone kept
 * every analysis of an account the server no longer had, and a new process
 * did not know. The marker is written before the erase is sent and cleared
 * once nothing more is owed; the next start reads it and finishes the job.
 *
 * The stage writes are `commit()`s: the erase may land on the server and the
 * process die before an `apply()` reached the disk. [clear] is an `apply()`,
 * safe on the main thread: a clear that never reached the disk only makes the
 * next start repeat a step that is safe to repeat (ask again, wipe again).
 */
object AccountDeletionMarker {

    /** How far the deletion got. */
    enum class Stage {
        /**
         * The erase was sent (or about to be) and its answer is not known: it
         * may never have reached the server, or the server may have erased
         * the account and the answer was lost.
         */
        REQUESTED,

        /** The server said the account is erased; the phone may still hold its data. */
        ERASED,
    }

    /** A deletion this phone has not finished: its [stage], for account [uid] ("" when unknown). */
    data class Owed(val stage: Stage, val uid: String)

    private fun prefs(context: Context) = privatePrefs(context, AccountDeletion.NAME)

    /** The deletion still owed, or null when there is none. An unreadable stage counts as none. */
    fun read(context: Context): Owed? {
        val p = prefs(context)
        val stage = p[AccountDeletion.STAGE]?.let { name -> Stage.entries.firstOrNull { it.name == name } }
        return stage?.let { Owed(it, p[AccountDeletion.UID]) }
    }

    /** Before the erase is sent for account [uid]. */
    fun requested(context: Context, uid: String?) = prefs(context).edit(commit = true) {
        put(AccountDeletion.STAGE, Stage.REQUESTED.name)
        put(AccountDeletion.UID, uid.orEmpty())
    }

    /** The server answered the erase: only the local half is left. Keeps the uid. */
    fun erased(context: Context) = prefs(context).edit(commit = true) {
        put(AccountDeletion.STAGE, Stage.ERASED.name)
    }

    /** Nothing more is owed. */
    fun clear(context: Context) = prefs(context).edit {
        remove(AccountDeletion.STAGE)
        remove(AccountDeletion.UID)
    }
}
