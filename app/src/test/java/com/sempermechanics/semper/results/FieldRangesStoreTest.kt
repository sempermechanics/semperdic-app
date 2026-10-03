package com.sempermechanics.semper.results

import com.sempermechanics.semper.report.FieldRangesStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [FieldRangesStore] is purely a cache in front of [DicResult]-decoded ranges:
 * a bad read must always fall back cleanly (return null) rather than hand back
 * wrong data, since nothing downstream re-validates its content against the
 * frames it claims to describe.
 */
class FieldRangesStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val fields = intArrayOf(2, 3, 4, 5, 6)

    @Test
    fun `round-trips per-frame ranges exactly, including absent fields`() {
        val file = temp.newFile("ranges.bin")
        val data = listOf(
            mapOf(2 to (0f to 1f), 3 to (-2f to 2f), 4 to null, 5 to (0.001f to 0.002f), 6 to (-1f to 1f)),
            mapOf(2 to (5f to 9f), 3 to null, 4 to null, 5 to null, 6 to null),
        )

        FieldRangesStore.write(file, fields, data)
        val read = FieldRangesStore.read(file, fields)

        assertEquals(data, read)
    }

    @Test
    fun `an empty frame list round-trips to an empty list, not null`() {
        val file = temp.newFile("ranges.bin")
        FieldRangesStore.write(file, fields, emptyList())
        assertEquals(emptyList<Map<Int, Pair<Float, Float>?>>(), FieldRangesStore.read(file, fields))
    }

    @Test
    fun `a missing file reads as null`() {
        val missing = temp.root.resolve("does_not_exist.bin")
        assertNull(FieldRangesStore.read(missing, fields))
    }

    @Test
    fun `truncated content reads as null instead of throwing`() {
        val file = temp.newFile("ranges.bin")
        FieldRangesStore.write(file, fields, listOf(mapOf(2 to (0f to 1f))))
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 3))

        assertNull(FieldRangesStore.read(file, fields))
    }

    @Test
    fun `a different field COUNT reads as null`() {
        val file = temp.newFile("ranges.bin")
        FieldRangesStore.write(file, fields, listOf(mapOf(2 to (0f to 1f))))

        assertNull(FieldRangesStore.read(file, intArrayOf(2, 3)))
    }

    @Test
    fun `the same field count in a DIFFERENT order reads as null, not misattributed data`() {
        // The exact bug this guards: same 5 indices, reordered. Without checking the
        // stored indices themselves (not just the count), this would silently hand
        // back field N's range under field M's key.
        val file = temp.newFile("ranges.bin")
        FieldRangesStore.write(file, fields, listOf(mapOf(2 to (0f to 1f), 3 to (10f to 20f))))

        val reordered = intArrayOf(3, 2, 4, 5, 6)
        assertNull(FieldRangesStore.read(file, reordered))
    }
}
