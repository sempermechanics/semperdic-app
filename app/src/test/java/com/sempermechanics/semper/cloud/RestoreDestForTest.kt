package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.cloud.CorruptTransferException
import com.sempermechanics.semper.data.cloud.restore.RestoreUnpacker
import com.sempermechanics.semper.data.session.SessionLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * [RestoreUnpacker.destFor] is the one zip-slip guard both restore paths share:
 * every artifact must land strictly inside its own session directory. Also
 * which entry an unpack takes for the reference.
 */
class RestoreDestForTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val layout by lazy {
        val sessionDir = File(tmp.root, "sessions/s1").apply { mkdirs() }
        SessionLayout(sessionDir).apply { rawDeformedDir.mkdirs() }
    }

    private fun dest(role: String, name: String) = RestoreUnpacker.destFor(role, name, layout)

    @Test
    fun `each role lands where a local run puts it`() {
        val dir = layout.dir
        assertEquals(File(dir, "reference.png"), dest("raw", "Reference.png"))
        assertEquals(File(layout.rawDeformedDir, "def.png"), dest("raw", "def.png"))
        assertEquals(File(dir, "frame_0001.dat"), dest("dat", "frame_0001.dat"))
        assertEquals(File(dir, "reports/frame_0001.pdf"), dest("reports", "frame_0001.pdf"))
        assertEquals(File(dir, "processed/u.png"), dest("processed", "u.png"))
    }

    @Test
    fun `a dot-dot that stays inside the session is allowed`() {
        assertEquals(
            File(layout.dir, "frame_0001.dat").canonicalFile,
            dest("raw", "../frame_0001.dat").canonicalFile,
        )
    }

    @Test
    fun `a sibling directory sharing the session's name as a prefix is outside it`() {
        assertThrows(CorruptTransferException::class.java) { dest("dat", "../s1X/frame_0001.dat") }
        assertThrows(CorruptTransferException::class.java) { dest("raw", "../../s1-evil/a.png") }
    }

    @Test
    fun `climbing out of the session is refused`() {
        assertThrows(CorruptTransferException::class.java) { dest("dat", "../../escape.dat") }
        assertThrows(CorruptTransferException::class.java) { dest("reports", "../../../escape.pdf") }
    }

    @Test
    fun `the session directory itself is not an artifact`() {
        assertThrows(CorruptTransferException::class.java) { dest("dat", ".") }
        assertThrows(CorruptTransferException::class.java) { dest("raw", "..") }
    }

    @Test
    fun `a legacy prefix's deformed image named reference_png is not taken for the reference`() {
        val prefix = File(tmp.root, "prefix.zip").apply {
            writeBytes(
                FakeRestoreApi.zipOf(
                    listOf(
                        "raw/Reference.png" to byteArrayOf(1, 2, 3),
                        "raw/reference.png" to byteArrayOf(4, 5),
                        "dat/frame_0001.dat" to FakeRestoreApi.onePointDat(),
                    ),
                ),
            )
        }
        val crcByName = ZipFile(prefix).use { zip -> zip.entries().toList().associate { it.name to it.crc } }

        val refPath = RestoreUnpacker.unpackPrefix(prefix, layout, crcByName)

        assertEquals(layout.referencePng.absolutePath, refPath)
        assertEquals(listOf<Byte>(4, 5), layout.rawDeformed("reference.png").readBytes().toList())
    }
}
