package com.sempermechanics.semper.cloud

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.cloud.restore.SafDestination
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The document a Save-to-Files download writes. Every operation reports its
 * failure instead of throwing, because the worker decides what a failure
 * means — so the failures are pinned as answers, not exceptions.
 */
@RunWith(RobolectricTestRunner::class)
class SafDestinationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `write copies the whole file into the document`() {
        val source = temp.newFile("Session.zip").apply { writeBytes(ByteArray(10_000) { it.toByte() }) }
        val target = temp.newFile("picked.zip")

        assertTrue(SafDestination(context, Uri.fromFile(target)).write(source))
        assertArrayEquals(source.readBytes(), target.readBytes())
    }

    @Test
    fun `an empty file counts as nothing written`() {
        val empty = temp.newFile("empty.zip")
        assertFalse(SafDestination(context, Uri.fromFile(temp.newFile("picked.zip"))).write(empty))
    }

    @Test
    fun `a document that cannot be opened is a false, not a crash`() {
        val source = temp.newFile("Session.zip").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val gone = SafDestination(context, Uri.parse("content://com.example.nowhere/document/1"))

        assertFalse(gone.write(source))
        // Clean-up after a failed download must not throw either.
        gone.delete()
        gone.releaseGrant()
    }

    @Test
    fun `a missing source file is a false`() {
        val target = temp.newFile("picked.zip")
        assertFalse(SafDestination(context, Uri.fromFile(target)).write(java.io.File(temp.root, "missing.zip")))
    }
}
