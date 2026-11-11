package com.yourversionnumber.changelog.kit

/**
 * Reads a list a page at a time until it has no `next`, handing each page to [each] as it comes. A cursor that
 * comes round again ends it, so a server that repeats one can't hold the phone in a loop (Codex's review of
 * Changelog for iPhone #57). Answers how many pages were read.
 */
suspend fun <P> everyPage(read: suspend (cursor: String?) -> P, next: (P) -> String?, each: (P) -> Unit = {}): Int {
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    var pages = 0
    do {
        val page = read(cursor)
        pages++
        each(page)
        cursor = next(page)?.takeIf { seen.add(it) }
    } while (cursor != null)
    return pages
}
