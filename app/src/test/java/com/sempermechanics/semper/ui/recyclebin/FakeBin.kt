package com.sempermechanics.semper.ui.recyclebin

import com.sempermechanics.semper.data.cloud.bin.BinOutcome
import com.sempermechanics.semper.data.net.RecycleBinItemDto
import com.sempermechanics.semper.data.net.RecycleBinResponse
import com.sempermechanics.semper.data.net.RecycleBinRestoreResponse
import java.time.Duration
import java.time.Instant

/**
 * The recycle bin screen's backend, stood in for: [items] is what the next
 * list answers ([listAnswer] overrides it), [restoreAnswer] what a restore
 * does, and every call is recorded in [calls].
 */
internal class FakeBin : RecycleBinViewModel.Bin {
    val calls = mutableListOf<String>()
    var items = mutableListOf(item("S1", "steel_00", purgeDays = 20), item("S2", "pmma_00", purgeDays = 5))
    var listAnswer: BinOutcome<RecycleBinResponse>? = null
    var restoreAnswer: suspend (String) -> BinOutcome<RecycleBinRestoreResponse> = { id ->
        items.removeAll { it.sessionId == id }
        BinOutcome.Ok(RecycleBinRestoreResponse(sessionId = id, status = "COMPLETED", restored = true))
    }
    var pending: () -> Set<String> = { emptySet() }

    override suspend fun list(): BinOutcome<RecycleBinResponse> {
        calls += "list"
        return listAnswer ?: BinOutcome.Ok(RecycleBinResponse(items = items.toList(), retentionDays = 30))
    }

    override suspend fun restore(cloudId: String): BinOutcome<RecycleBinRestoreResponse> {
        calls += "restore($cloudId)"
        return restoreAnswer(cloudId)
    }

    override suspend fun pendingPurges(): Set<String> = pending()

    override suspend fun reconcile() {
        calls += "reconcile"
    }

    companion object {
        /** A binned backup with [purgeDays] days left: deleted 30 − [purgeDays] days ago, erased 30 days after. */
        fun item(id: String, name: String, purgeDays: Int, bytes: Long = 120L * 1024 * 1024): RecycleBinItemDto {
            val purgeAt = Instant.now().plus(Duration.ofDays(purgeDays.toLong())).minus(Duration.ofHours(1))
            return RecycleBinItemDto(
                sessionId = id,
                localSessionId = "local-$id",
                name = name,
                totalBytes = bytes,
                fileCount = 3,
                deletedAt = purgeAt.minus(Duration.ofDays(30)).toString(),
                purgeAt = purgeAt.toString(),
            )
        }
    }
}
