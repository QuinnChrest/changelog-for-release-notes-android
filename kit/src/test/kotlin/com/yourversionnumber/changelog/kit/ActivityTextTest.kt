package com.yourversionnumber.changelog.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityTextTest {
    init {
        English.use()
    }

    private fun row(kind: String, via: String? = null, token: String? = null, day: String? = "2026-10-29", note: String? = null, at: String = "2026-10-29T12:00:00Z") =
        ActivityItem(id = "a1", at = at, kind = kind, via = via, token = token, day = day, version = "4.5.137", note = note)

    @Test
    fun readsAsTheIphoneAppDoes() {
        val filed = row("note.added", via = "token", token = "Changelog on my Pixel")
        assertEquals("Filed in 4.5.137", filed.headline)
        assertEquals("via Changelog on my Pixel · 2026-10-29", filed.detail)
        assertEquals("Email not filed", row("mail").copy(outcome = "failed").headline)
        assertEquals("Badge: API release", row("badge.earned").copy(name = "API release").headline)
        assertEquals("Mom's birthday added", row("upcoming.added").copy(title = "Mom's birthday").headline)
        assertEquals("Something New", row("something.new").headline)
        assertNull(row("signin", day = null).detail)
    }

    @Test
    fun onlyThisTokensNotesAreMine() {
        val mine = row("note.added", via = "token", token = "Pixel", note = "t-1")
        assertTrue(mine.isMine("Pixel"))
        assertFalse(mine.isMine("Another phone"))
        assertFalse(mine.copy(via = "web").isMine("Pixel"))
        assertFalse(mine.copy(kind = "note.edited").isMine("Pixel"))
        assertFalse(mine.copy(note = null).isMine("Pixel"))
    }

    @Test
    fun aDaylessRowIsOnTheDayOfItsTime() {
        assertTrue(row("signin", day = null).isOn("2026-10-29"))
        assertFalse(row("signin", day = null, at = "2026-10-28T23:00:00Z").isOn("2026-10-29"))
        assertTrue(row("mail", day = "2026-10-29", at = "2026-10-30T01:00:00Z").isOn("2026-10-29"))
    }

    @Test
    fun problems() {
        assertTrue(row("mail").copy(outcome = "not-filed").isProblem)
        assertFalse(row("mail").copy(outcome = "filed").isProblem)
        assertTrue(row("status.stopped").isProblem)
    }
}
