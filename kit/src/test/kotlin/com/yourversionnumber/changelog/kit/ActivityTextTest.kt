package com.yourversionnumber.changelog.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityTextTest {
    private val pixel = TokenInfo(name = "Pixel", scopes = listOf("notes:write"), id = "a".repeat(32))

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
    fun filesTakenOffSaySo() {
        assertEquals("Files added in 4.5.137", row("note.files").copy(added = 1).headline)
        assertEquals("Files removed in 4.5.137", row("note.files").copy(removed = 1).headline)
        assertEquals("Files removed", row("note.files").copy(version = null, removed = 2).headline)
    }

    @Test
    fun checkInsReadAsTheIphoneAppDoes() {
        assertEquals("Checked in at Lake Harriet", row("checkin").copy(label = "Lake Harriet").headline)
        assertEquals("Checked in at an unnamed stop", row("checkin").copy(label = "Unnamed stop").headline)
        assertEquals("Checked in at an unnamed stop", row("checkin").headline)
        assertEquals("Named a check-in", row("checkin.named").headline)
        assertEquals("Deleted a check-in", row("checkin.deleted").copy(count = 2).headline)
        assertEquals("Deleted all your check-ins, 12", row("checkin.deleted").copy(all = true, count = 12).headline)
        assertEquals("Deleted all your check-ins", row("checkin.deleted").copy(all = true).headline)
        assertEquals("Remembered a place", row("checkin.place").copy(change = "added").headline)
        assertEquals("Added a spot to a remembered place", row("checkin.place").copy(change = "spot").headline)
        assertEquals("Renamed a remembered place", row("checkin.place").copy(change = "renamed").headline)
        assertEquals("Forgot a remembered place", row("checkin.place").copy(change = "forgotten").headline)
        assertEquals("Changed a remembered place’s tags", row("checkin.place").copy(change = "tagged").headline)
    }

    @Test
    fun anIgnoredPlaceSaysWhatItDid() {
        val ignored = row("checkin.ignored").copy(place = "plc_1", count = 3)
        assertEquals("Ignored a remembered place", ignored.headline)
        assertEquals("Its check-ins are hidden, and no new ones are kept there.", ignored.more)
        val back = row("checkin.unignored").copy(place = "plc_1", count = 3)
        assertEquals("Stopped ignoring a remembered place", back.headline)
        assertEquals("Its check-ins show again.", back.more)
        val went = row("checkin.deleted").copy(place = "plc_1", count = 4)
        assertEquals("Deleted the check-ins at a remembered place", went.headline)
        assertEquals("4 check-ins went.", went.more)
        assertEquals("1 check-in went.", went.copy(count = 1).more)
        // One check-in, or all of them, keep their one line.
        assertNull(row("checkin.deleted").more)
        assertNull(row("checkin.deleted").copy(all = true, count = 12).more)
        assertNull(row("checkin").more)
    }

    @Test
    fun aCheckInNeverNotifies() {
        val stop = row("checkin", via = "token", token = "Another phone").copy(label = "Home")
        assertNull(ActivityNotices.kind(stop, pixel))
    }

    @Test
    fun onlyThisTokensNotesAreMine() {
        val mine = row("note.added", via = "token", token = "Pixel", note = "t-1")
        assertTrue(mine.isMine(pixel))
        assertFalse(mine.isMine(TokenInfo(name = "Another phone", scopes = emptyList())))
        assertFalse(mine.copy(via = "web").isMine(pixel))
        assertFalse(mine.copy(kind = "note.edited").isMine(pixel))
        assertFalse(mine.copy(note = null).isMine(pixel))
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
