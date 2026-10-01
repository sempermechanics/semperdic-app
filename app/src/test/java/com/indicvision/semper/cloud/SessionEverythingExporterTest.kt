package com.indicvision.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.session.SessionEverythingExporter
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.fixtures.CleanAppState
import com.indicvision.semper.fixtures.sessionRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The local "export everything" archive. Entry names come from user-supplied
 * analysis names, so they are the part that can break an archive.
 */
@RunWith(RobolectricTestRunner::class)
class SessionEverythingExporterTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A local session with one readable frame; image size 0 skips report rendering. */
    private fun seedSession(id: String) {
        val dir = SessionStore.dirFor(context, id)
        val bytes = java.nio.ByteBuffer.allocate(DicResult.BYTES_PER_POINT).order(java.nio.ByteOrder.nativeOrder())
        repeat(DicResult.STRIDE) { bytes.putFloat(0.01f) }
        SessionPaths.frameDat(dir, 0).writeBytes(bytes.array())
        SessionStore.upsert(
            context,
            sessionRecord(
                id = id,
                subset = 21,
                imgW = 0,
                imgH = 0,
                sessionDir = dir.absolutePath,
                defNames = listOf("a.png"),
            ),
        )
    }

    @Test
    fun `progress counts sessions finished, reaching the total only once the last is in`() = runBlocking {
        seedSession("first")
        seedSession("second")
        val ticks = mutableListOf<Pair<Int, Int>>()

        val result = SessionEverythingExporter.exportMasterZip(context) { done, total ->
            ticks += done to total
        }

        assertNotNull(result)
        // It used to tick (index + 1) before building each session, so the
        // banner read 100% while the last session was still being packed.
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), ticks)
        assertEquals(2, result!!.sessionCount)
    }

    @Test
    fun `nothing to export yields no file rather than an empty zip`() = runBlocking {
        // A fresh install: no sessions on disk.
        assertNull(SessionEverythingExporter.exportMasterZip(context))
    }

    @Test
    fun `entry names keep readable characters and carry an id suffix`() {
        val name = SessionEverythingExporter.sanitizeZipName("Steel plate A-1_test.v2", "abcdef123456")

        assertEquals("Steel_plate_A-1_test.v2_abcdef12", name)
    }

    @Test
    fun `path separators and spaces cannot escape the entry name`() {
        val name = SessionEverythingExporter.sanitizeZipName("../../etc/passwd", "id123456")

        assertTrue("unexpected separators in '$name'", !name.contains('/') && !name.contains('\\'))
        assertEquals(".._.._etc_passwd_id123456", name)
    }

    @Test
    fun `a blank name still produces a usable entry`() {
        assertEquals("session_id123456", SessionEverythingExporter.sanitizeZipName("   ", "id123456"))
    }

    @Test
    fun `long names are truncated, keeping the archive entry bounded`() {
        val name = SessionEverythingExporter.sanitizeZipName("x".repeat(200), "abcdefghij")

        // 40 name chars + '_' + 8 id chars.
        assertEquals(49, name.length)
        assertTrue(name.startsWith("x".repeat(40) + "_"))
    }
}
