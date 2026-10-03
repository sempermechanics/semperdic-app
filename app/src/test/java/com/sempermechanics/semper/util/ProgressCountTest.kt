package com.sempermechanics.semper.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Progress callbacks report finished items. A "k of N" label fed that count
 * read "Importing image 0 of N" and trailed by one; it names the item in
 * progress instead.
 */
class ProgressCountTest {

    @Test
    fun `the label names the item being worked on`() {
        assertEquals(1, ProgressCount.current(done = 0, total = 5))
        assertEquals(3, ProgressCount.current(done = 2, total = 5))
    }

    @Test
    fun `the label stops at the total once every item is done`() {
        assertEquals(5, ProgressCount.current(done = 4, total = 5))
        assertEquals(5, ProgressCount.current(done = 5, total = 5))
    }

    @Test
    fun `nothing to do is item 0`() {
        assertEquals(0, ProgressCount.current(done = 0, total = 0))
    }
}
