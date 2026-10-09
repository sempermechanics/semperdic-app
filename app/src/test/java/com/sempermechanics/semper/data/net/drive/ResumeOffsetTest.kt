package com.sempermechanics.semper.data.net.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How a 308's `Range` header becomes the next upload offset ([resumeOffsetOf]). */
class ResumeOffsetTest {

    @Test
    fun `no Range means Drive holds nothing`() {
        assertEquals(0L, resumeOffsetOf(null, 1000))
    }

    @Test
    fun `bytes 0-N continues at N + 1`() {
        assertEquals(400L, resumeOffsetOf("bytes=0-399", 1000))
        assertEquals(1L, resumeOffsetOf("bytes=0-0", 1000))
        assertEquals(1000L, resumeOffsetOf(" bytes=0-999 ", 1000))
    }

    @Test
    fun `a Range that is not bytes 0-N is unreadable`() {
        listOf("", "bytes=0-", "bytes=0-abc", "bytes=10-399", "0-399", "bytes 0-399", "bytes=0--5", "bytes=0-1,5-9")
            .forEach { assertNull(it, resumeOffsetOf(it, 1000)) }
    }

    @Test
    fun `a Range past the end of the file is unreadable`() {
        assertNull(resumeOffsetOf("bytes=0-1000", 1000))
        assertNull(resumeOffsetOf("bytes=0-0", 0))
    }
}
