package com.sempermechanics.semper.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.cloud.restore.CloudRestore
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.sessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * A session id names a directory directly under the sessions root, or no
 * directory at all (L5-1): a backup's `localSessionId` of `..` once made
 * `filesDir` the session dir, which a delete or a failed restore then wiped.
 */
@RunWith(RobolectricTestRunner::class)
class SessionStoreDirForTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A file beside the sessions root that nothing in this test may delete. */
    private lateinit var sentinel: File

    private val sessionsRoot: File get() = File(context.filesDir, SessionPaths.SESSIONS_ROOT)

    @Before
    fun setUp() {
        sentinel = File(context.filesDir, "sentinel.txt").apply { writeText("keep") }
    }

    @Test
    fun `an id that is not a direct child of the sessions root names no session dir`() {
        for (id in listOf("..", ".", "", "a/../b", "a/b", "a\\b", "../escape", SessionPaths.INDEX_JSON)) {
            assertThrows(id, IllegalArgumentException::class.java) { SessionStore.dirFor(context, id) }
        }
        assertFalse("nothing is created outside the root", File(context.filesDir, "escape").exists())
        assertTrue(sentinel.isFile)
    }

    @Test
    fun `a plain id is its own folder under the sessions root`() {
        val dir = SessionStore.dirFor(context, "ok-id_1")

        assertTrue(dir.isDirectory)
        assertEquals("ok-id_1", dir.name)
        assertEquals(sessionsRoot.canonicalFile, dir.canonicalFile.parentFile)
    }

    @Test
    fun `deleting a row whose id names no session dir drops the row and no directory`() {
        SessionStore.save(context, sessionRecord(".."))

        SessionStore.delete(context, "..")

        assertNull(SessionStore.get(context, ".."))
        assertTrue("filesDir is not the session dir", sentinel.isFile)
    }

    @Test
    fun `an id that names no session dir has no size`() {
        assertEquals(0L, SessionStore.sizeOf(context, ".."))
    }

    @Test
    fun `a restore's partial-artifact reset refuses a dir outside the sessions root`() {
        assertThrows(IllegalArgumentException::class.java) { CloudRestore.clearPartialArtifacts(context, "..") }
        assertThrows(IllegalArgumentException::class.java) { CloudRestore.clearPartialArtifacts(context, ".") }

        assertTrue(sentinel.isFile)
        assertTrue("the sessions root is not reset", sessionsRoot.isDirectory)
    }

    @Test
    fun `isSafeId takes the ids the app mints and nothing that can leave a directory`() {
        for (id in listOf("f0f0406f-8c2", "restored-abcdefghijkl", "local_1", "a".repeat(128))) {
            assertTrue(id, SessionPaths.isSafeId(id))
        }
        for (id in listOf("", ".", "..", "a.b", "a/b", "a\\b", "é", "a b", "a".repeat(129))) {
            assertFalse(id, SessionPaths.isSafeId(id))
        }
    }
}
