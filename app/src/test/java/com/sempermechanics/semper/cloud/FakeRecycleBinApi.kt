package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.net.RecycleBinApi
import com.sempermechanics.semper.data.net.RecycleBinPurgeResponse
import com.sempermechanics.semper.data.net.RecycleBinResponse
import com.sempermechanics.semper.data.net.RecycleBinRestoreResponse

/**
 * A hand-written [RecycleBinApi] for JVM tests, as [FakeCloudApi] is for
 * [com.sempermechanics.semper.data.net.CloudApi]: each call is recorded in
 * [calls], and an unscripted one fails the test.
 */
class FakeRecycleBinApi(override var enabled: Boolean = true) : RecycleBinApi {

    val calls = mutableListOf<String>()

    var onList: suspend (String) -> RecycleBinResponse = { unscripted("listRecycleBin") }
    var onRestore: suspend (String, String) -> RecycleBinRestoreResponse =
        { _, _ -> unscripted("restoreFromRecycleBin") }
    var onPurge: suspend (String, String) -> RecycleBinPurgeResponse = { _, _ -> unscripted("purgeFromRecycleBin") }

    override suspend fun listRecycleBin(idToken: String): RecycleBinResponse {
        synchronized(calls) { calls += "listRecycleBin" }
        return onList(idToken)
    }

    override suspend fun restoreFromRecycleBin(idToken: String, sessionId: String): RecycleBinRestoreResponse {
        synchronized(calls) { calls += "restoreFromRecycleBin($sessionId)" }
        return onRestore(idToken, sessionId)
    }

    override suspend fun purgeFromRecycleBin(idToken: String, sessionId: String): RecycleBinPurgeResponse {
        synchronized(calls) { calls += "purgeFromRecycleBin($sessionId)" }
        return onPurge(idToken, sessionId)
    }

    private fun unscripted(name: String): Nothing = throw AssertionError("unexpected RecycleBinApi.$name call")

    companion object {
        /** A bin that holds nothing, for a test whose reconcile reads it. */
        val EMPTY = RecycleBinResponse(retentionDays = 30)

        /** A fake whose bin is [EMPTY]: a reconcile that reads it finds nothing deleted. */
        fun empty(): FakeRecycleBinApi = FakeRecycleBinApi().apply { onList = { EMPTY } }
    }
}
