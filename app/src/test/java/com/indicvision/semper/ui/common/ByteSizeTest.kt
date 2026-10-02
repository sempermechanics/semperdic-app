package com.indicvision.semper.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * [ByteSize.format] is Settings' `humanSize`, ported: both run over the same
 * counts (every unit boundary and rounding edge) and must print the same.
 */
class ByteSizeTest {

    /** `SettingsActivity.humanSize` as it was before Settings moved to [ByteSize], verbatim. */
    private fun humanSize(bytes: Long): String = when {
        bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb.toDouble())
        bytes >= mb -> String.format(Locale.US, "%.0f MB", bytes / mb.toDouble())
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    private val kb = 1024L
    private val mb = 1_048_576L
    private val gb = 1_073_741_824L

    private val counts: List<Long> = buildList {
        addAll(listOf(0L, 1L, 511L, 512L, 513L, 1023L, kb, kb + 1, 1535L, 1536L, 999_999L))
        for (unit in listOf(mb, gb)) {
            addAll(listOf(unit - 1, unit, unit + 1, unit + unit / 2 - 1, unit + unit / 2, unit * 3 / 2 + 1))
        }
        addAll(listOf(mb * 999, mb * 1023, gb * 2 - 1, gb * 25 + gb / 20, gb * 1024, Long.MAX_VALUE))
        addAll((0..40).map { 7L shl it })
    }

    @Test
    fun `matches Settings' humanSize everywhere`() {
        for (bytes in counts) {
            assertEquals("bytes=$bytes", humanSize(bytes), ByteSize.format(bytes))
        }
    }

    @Test
    fun `prints US digits whatever the device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.5 GB", ByteSize.format(gb + gb / 2))
            assertEquals(humanSize(gb + gb / 2), ByteSize.format(gb + gb / 2))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `examples`() {
        assertEquals("0 KB", ByteSize.format(0))
        assertEquals("2 KB", ByteSize.format(1536))
        assertEquals("1 MB", ByteSize.format(mb))
        assertEquals("1024 MB", ByteSize.format(gb - 1))
        assertEquals("1.0 GB", ByteSize.format(gb))
    }
}
