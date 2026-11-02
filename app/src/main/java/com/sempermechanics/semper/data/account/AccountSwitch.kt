package com.sempermechanics.semper.data.account

import android.content.Context
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.LicenseConfigWorker
import com.sempermechanics.semper.data.cloud.CloudBackupListing
import com.sempermechanics.semper.data.cloud.sync.PhoneSyncWorker
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.session.SessionOwner
import com.sempermechanics.semper.data.session.SessionStore
import timber.log.Timber

/**
 * What this phone keeps per account, and what changes when the account does.
 *
 * [AccountCache] (Terms acceptance, device binding, quota, role, the
 * `/v1/config` and entitlement it holds), the cloud-backup listing and the
 * account's background refreshes all belong to one account. A sign-out drops
 * them ([clearLocalState]); so does a sign-in as another account over a live
 * session, which never passed through a sign-out ([signedIn]). Without that,
 * account B inherited A's cache: B skipped the Terms clickwrap, and A's
 * unsynced acceptance was sent with B's token.
 *
 * The analyses on the phone are not dropped: each has an owner, and only the
 * signed-in account's are shown ([SessionOwner]).
 */
internal object AccountSwitch {

    /**
     * The local half of a sign-out: stop the account's refreshes and forget
     * what was cached for it. The account stays the owner of the analyses
     * shown until another signs in ([AccountCache.lastSignedInUid]).
     */
    fun clearLocalState(context: Context) {
        LicenseConfigWorker.cancel(context)
        PhoneSyncWorker.cancel(context)
        AccountCache.clear(context)
        // The next account's backups are not these.
        CloudBackupListing.clear(context)
    }

    /**
     * A Firebase sign-in as [uid] went through. When the cache still holds
     * another account, its state is cleared first ([clearLocalState]); the same
     * account keeps it. Then [uid] is cached and becomes the analyses' owner,
     * adopting the rows an older build left without one.
     */
    @WorkerThread
    fun signedIn(context: Context, uid: String, email: String?) {
        val previous = AccountCache.cachedUid(context)
        if (previous != null && previous != uid) {
            Timber.i("Signed in as another account; clearing the previous account's local state")
            clearLocalState(context)
        }
        AccountCache.saveIdentity(context, uid, email)
        // Reading the index adopts rows without an owner; its size is this
        // account's phone count, which the cleared cache no longer holds.
        AccountCache.refreshSessionLimit(context, SessionStore.list(context).size)
    }
}
