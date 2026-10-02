package com.indicvision.semper.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * The session-file manifest and the pending-upload list are cursor-paged by the
 * backend (1000 per page). Reading only the first page restored a large
 * analysis with files missing, and resumed an upload without the files past it.
 */
class PagingTest {

    private fun file(id: String) = CloudFileDto(fileId = id)

    private fun more(token: String) = PageDto(nextPageToken = token, hasMore = true)

    @Test
    fun `a manifest is every page's files under the first page's session fields`() {
        val pages = mapOf(
            null to SessionFilesResponse(
                "s1",
                "local",
                status = "COMPLETED",
                files = listOf(file("a")),
                page = more("t1"),
            ),
            "t1" to SessionFilesResponse("s1", files = listOf(file("b")), page = more("t2")),
            "t2" to SessionFilesResponse("s1", files = listOf(file("c")), page = PageDto()),
        )
        val asked = mutableListOf<String?>()

        val manifest = fetchAllPages(
            fetch = { token ->
                asked += token
                pages.getValue(token)
            },
            pageOf = { it.page },
        ).merged()

        assertEquals(listOf(null, "t1", "t2"), asked)
        assertEquals(listOf("a", "b", "c"), manifest.files.map { it.fileId })
        assertEquals("local", manifest.localSessionId)
        assertEquals("COMPLETED", manifest.status)
        assertNull(manifest.page)
    }

    @Test
    fun `an upload plan is every page's pending files`() {
        fun pending(id: String) = PendingUploadDto(fileId = id, uploadUrl = "https://drive/$id")
        val pages = mapOf(
            null to SessionUploadsResponse(
                "s1",
                status = "UPLOADING",
                uploads = listOf(pending("a")),
                page = more("t1"),
            ),
            "t1" to SessionUploadsResponse("s1", uploads = listOf(pending("b"))),
        )

        val plan = fetchAllPages(fetch = { pages.getValue(it) }, pageOf = { it.page }).merged()

        assertEquals(listOf("a", "b"), plan.uploads.map { it.fileId })
        assertEquals("UPLOADING", plan.status)
    }

    @Test
    fun `a token seen before fails the walk rather than keep a page twice`() {
        // A proxy that drops page_token serves page one again, naming the same token.
        var calls = 0
        val e = assertThrows(IOException::class.java) {
            fetchAllPages(
                fetch = {
                    calls++
                    SessionFilesResponse("s1", files = listOf(file("a")), page = more("t1"))
                },
                pageOf = { it.page },
            )
        }
        assertEquals("page token repeated", e.message)
        assertEquals(2, calls)
    }

    @Test
    fun `a listing past the page ceiling fails`() {
        var calls = 0
        assertThrows(IOException::class.java) {
            fetchAllPages(
                fetch = {
                    calls++
                    SessionFilesResponse("s1", page = more("t$calls"))
                },
                pageOf = { it.page },
            )
        }
        assertEquals(MAX_PAGES, calls)
    }

    @Test
    fun `a blank token or hasMore false is the last page`() {
        val blank = fetchAllPages(
            fetch = { SessionFilesResponse("s1", page = PageDto(nextPageToken = " ", hasMore = true)) },
            pageOf = { it.page },
        )
        val done = fetchAllPages(
            fetch = { SessionFilesResponse("s1", page = PageDto(nextPageToken = "t", hasMore = false)) },
            pageOf = { it.page },
        )
        assertEquals(1, blank.size)
        assertEquals(1, done.size)
    }

    @Test
    fun `the first page is the bare route and later pages carry an encoded token`() {
        assertEquals("", pageTokenQuery(null))
        assertEquals("?page_token=abc123", pageTokenQuery("abc123"))
        assertEquals("?page_token=a+b%26c", pageTokenQuery("a b&c"))
    }
}
