package com.sempermechanics.semper.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.data.session.SessionEverythingExporter
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.fixtures.sessionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Cancelling Settings → Your data → Export stops the export (TD-41).
 *
 * The export copies files with blocking IO inside `withContext(IO)`, and used
 * to catch every `Exception` — cancellation included — so pressing Cancel on
 * the banner left it packing every remaining session in the background. It now
 * checks between sessions and rethrows cancellation, so after a cancel on the
 * first progress callback no second session is started and no archive is left
 * in the share folder.
 */
@RunWith(AndroidJUnit4::class)
class LocalExportCancelTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ids = listOf("test_export_cancel_a", "test_export_cancel_b", "test_export_cancel_c")

    @Before
    fun seedSessions() {
        ids.forEachIndexed { index, id -> seed(id, index) }
    }

    @After
    fun removeSessions() {
        ids.forEach { SessionStore.delete(context, it) }
    }

    @Test
    fun cancellingOnTheFirstSessionStopsTheExportAndLeavesNoArchive() = runBlocking {
        var progressCalls = 0
        val export = launch(Dispatchers.Default) {
            val self = coroutineContext.job
            SessionEverythingExporter.exportMasterZip(context) { _, _ ->
                progressCalls++
                self.cancel()
            }
        }
        export.join()

        assertTrue("the export job should end cancelled", export.isCancelled)
        assertEquals("no session after the cancel may be started", 1, progressCalls)
        val share = File(context.cacheDir, "share")
        val leftovers = share.listFiles()
            ?.filter { it.name.startsWith("Semper_sessions_export_") || it.name.startsWith("export_staging_") }
            .orEmpty()
        assertTrue("cancelled export left $leftovers", leftovers.isEmpty())
    }

    private fun seed(id: String, index: Int) {
        val dir = SessionStore.dirFor(context, id)
        val ref = File(dir, "reference.png").apply { writeBytes(Random(index).nextBytes(REF_BYTES)) }
        val floats = FloatArray(COLS * ROWS * DicResult.STRIDE)
        for (p in floats.indices step DicResult.STRIDE) {
            floats[p + DicResult.IDX_X] = (p / DicResult.STRIDE % COLS * STEP).toFloat()
            floats[p + DicResult.IDX_Y] = (p / DicResult.STRIDE / COLS * STEP).toFloat()
            floats[p + DicResult.IDX_ZNSSD] = 0.01f
        }
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(floats)
        repeat(FRAMES) { SessionPaths.frameDat(dir, it).writeBytes(buf.array()) }

        val now = System.currentTimeMillis()
        val record = sessionRecord(
            id = id,
            name = "Export cancel $index",
            createdAt = now,
            frameCount = FRAMES,
            subset = 21,
            step = STEP,
            strainWindow = 5,
            imgW = COLS * STEP,
            imgH = ROWS * STEP,
            refPath = ref.absolutePath,
            refName = ref.name,
            sessionDir = dir.absolutePath,
        )
        assertTrue("could not seed $id", SessionStore.upsert(context, record, allowOverLimit = true))
    }

    private companion object {
        const val FRAMES = 3
        const val COLS = 40
        const val ROWS = 30
        const val STEP = 4
        const val REF_BYTES = 256 * 1024
    }
}
