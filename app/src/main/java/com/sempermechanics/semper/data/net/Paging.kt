package com.sempermechanics.semper.data.net

import java.io.IOException
import java.net.URLEncoder

/**
 * Most pages one listing may take. A session's files come 1000 to a page, so
 * this is far past any real listing; it stops a backend that keeps naming new
 * tokens from looping forever.
 */
internal const val MAX_PAGES = 1000

/**
 * Every page of one of the backend's cursor-paged listings (`page` in the
 * response, `page_token` in the request; `backend/app/routers/_shared.py`).
 *
 * [fetch] is called with null for the first page and then with each
 * `nextPageToken` until the backend stops naming one.
 *
 * A token seen before throws: the walk is going round, and what it collected
 * holds the same page twice (a proxy that drops `page_token` serves page one
 * again; a cursor whose document was deleted restarts from the beginning). A
 * pending-upload plan with a page twice starts two uploads into one Drive
 * session, so the caller gets an [IOException] to retry rather than the
 * duplicates. More than [MAX_PAGES] pages throws the same way.
 */
internal inline fun <P> fetchAllPages(fetch: (pageToken: String?) -> P, pageOf: (P) -> PageDto?): List<P> {
    val pages = mutableListOf<P>()
    val seen = mutableSetOf<String>()
    var token: String? = null
    do {
        if (pages.size >= MAX_PAGES) throw IOException("listing has more than $MAX_PAGES pages")
        val page = fetch(token)
        pages += page
        token = pageOf(page)?.takeIf { it.hasMore }?.nextPageToken?.takeIf { it.isNotBlank() }
        if (token != null && !seen.add(token)) throw IOException("page token repeated")
    } while (token != null)
    return pages
}

/** `?page_token=…` for a page after the first, else "" (the first page's URL is the bare route). */
internal fun pageTokenQuery(pageToken: String?): String = if (pageToken == null) "" else "?" + pageTokenParam(pageToken)

/** `page_token=…`, the token URL-encoded. */
internal fun pageTokenParam(pageToken: String): String =
    "page_token=" + URLEncoder.encode(pageToken, Charsets.UTF_8.name())

/** One manifest from its pages: the first page's session fields, every page's files. */
@JvmName("mergedFiles")
internal fun List<SessionFilesResponse>.merged(): SessionFilesResponse =
    first().copy(files = flatMap { it.files }, page = null)

/** One upload plan from its pages: the first page's session state, every page's pending files. */
@JvmName("mergedUploads")
internal fun List<SessionUploadsResponse>.merged(): SessionUploadsResponse =
    first().copy(uploads = flatMap { it.uploads }, page = null)
