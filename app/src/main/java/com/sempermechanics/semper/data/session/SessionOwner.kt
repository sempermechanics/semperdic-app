package com.sempermechanics.semper.data.session

import android.content.Context
import com.sempermechanics.semper.data.net.AccountCache
import timber.log.Timber

/**
 * Whose analyses this phone shows. On a phone two accounts share, each
 * [SessionRecord] belongs to the account that ran or restored it
 * ([SessionRecord.ownerUid]); [SessionStore] lists only the [current] owner's
 * rows, so another account's analyses are never shown, counted against this
 * account's quota, reconciled, uploaded into this account or evicted. They stay
 * on the phone, hidden, and come back when their account signs in again
 * (owner decision, 2026-11-01).
 *
 * The owner is the signed-in account's uid ([AccountCache.cachedUid]). While
 * nobody is signed in it is the account that signed in last
 * ([AccountCache.lastSignedInUid], which sign-out keeps), so a run that
 * finishes after its account signed out still lands in that account's list.
 * On a phone no account ever signed in to (a build with no backend) it is "",
 * the owner of every row such a build writes.
 *
 * A row from a build before owners has a blank owner. The first account that
 * is the owner after the update adopts every such row ([adopted]), once: the
 * rows are written back with its uid.
 */
object SessionOwner {

    /** The account whose analyses are shown now; "" when no account ever signed in here. */
    fun current(context: Context): String =
        AccountCache.cachedUid(context) ?: AccountCache.lastSignedInUid(context) ?: ""

    /** The rows of [rows] that [owner] owns, in their order. */
    internal fun visible(rows: List<SessionRecord>, owner: String): List<SessionRecord> =
        rows.filter { it.ownerUid == owner }

    /** How many of [rows] the current account owns: the quota's phone count. */
    internal fun ownedCount(context: Context, rows: List<SessionRecord>): Int = visible(rows, current(context)).size

    /**
     * [rows] with every blank owner set to [owner], or null when there is
     * nothing to adopt: no row is blank, or [owner] is blank itself.
     */
    internal fun adopted(rows: List<SessionRecord>, owner: String): List<SessionRecord>? {
        val legacy = rows.count { it.ownerUid.isEmpty() }
        if (owner.isEmpty() || legacy == 0) return null
        Timber.i("The current account adopts %d analyses saved before rows had owners", legacy)
        return rows.map { if (it.ownerUid.isEmpty()) it.copy(ownerUid = owner) else it }
    }

    /**
     * [record] as [SessionStore.save] writes it: its own owner, else the owner
     * of the row it replaces ([previous]), else [owner]. An update never moves
     * a row to another account.
     */
    internal fun stamped(record: SessionRecord, previous: SessionRecord?, owner: String): SessionRecord {
        if (record.ownerUid.isNotEmpty()) return record
        val kept = previous?.ownerUid.orEmpty().ifEmpty { owner }
        return if (kept.isEmpty()) record else record.copy(ownerUid = kept)
    }
}
