package com.sempermechanics.semper.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The viewer names and pictures a frame from the index in its `.dat` name, not
 * its position in the listing: past a skipped frame the two differ.
 */
class SessionPathsFrameIndexTest {

    @Test
    fun `a frame file names its planned index`() {
        assertEquals(0, SessionPaths.frameIndexOf(SessionPaths.frameDatName(0)))
        assertEquals(7, SessionPaths.frameIndexOf(SessionPaths.frameDatName(7)))
        assertEquals(12345, SessionPaths.frameIndexOf(SessionPaths.frameDatName(12345)))
    }

    @Test
    fun `a listing with a gap keeps each file's own index`() {
        val listing = listOf(0, 1, 3).map(SessionPaths::frameDatName)
        assertEquals(listOf(0, 1, 3), listing.map(SessionPaths::frameIndexOf))
    }

    @Test
    fun `a listing maps each position to its planned frame`() {
        val files = listOf(0, 2, 3).map { File(SessionPaths.frameDatName(it)) } + File("odd.dat")
        assertEquals(listOf(0, 2, 3, 3), SessionPaths.plannedFrameIndices(files))
    }

    @Test
    fun `other names have no index`() {
        assertNull(SessionPaths.frameIndexOf("ref.png"))
        assertNull(SessionPaths.frameIndexOf("frame_.dat"))
        assertNull(SessionPaths.frameIndexOf("frame_0001.dat.tmp"))
    }
}
