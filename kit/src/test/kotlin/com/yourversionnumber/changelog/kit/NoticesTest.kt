package com.yourversionnumber.changelog.kit

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoticesTest {
    init {
        English.use()
    }

    private val chicago = ZoneId.of("America/Chicago")

    private fun me(wroteToday: Boolean = false) = Me(
        today = "2026-10-29", version = "4.5.137", tz = "America/Chicago", sendTime = "06:30",
        wroteToday = wroteToday, streak = 2, status = "active", showBadges = true,
        days = listOf(DayVersion("2026-10-29", "4.5.137"), DayVersion("2026-10-30", "4.5.138"), DayVersion("2026-11-02", "4.5.141")),
        token = TokenInfo("Pixel", listOf("notes:write")), apiVersion = "1.2.0",
    )

    @Test
    fun aPromptEachMorningAheadWithThatDaysVersion() {
        val now = Instant.parse("2026-10-29T10:00:00Z") // 5 a.m. in Chicago
        val plan = DailyPrompt.plan(me(), now = now)
        assertEquals(listOf("daily-prompt.2026-10-29", "daily-prompt.2026-10-30", "daily-prompt.2026-11-02"), plan.map { it.id })
        assertEquals("You're 4.5.138 today. What changed?", plan[1].body)
        assertEquals(Instant.parse("2026-10-29T11:30:00Z"), plan[0].fireAt)
        // After the clocks go back: CST.
        assertEquals(Instant.parse("2026-11-02T12:30:00Z"), plan[2].fireAt)
    }

    @Test
    fun notTodayOnceSomethingWasWrittenOrOnceItsTimeHasGone() {
        assertEquals("daily-prompt.2026-10-30", DailyPrompt.plan(me(wroteToday = true), now = Instant.parse("2026-10-29T10:00:00Z")).first().id)
        assertEquals("daily-prompt.2026-10-30", DailyPrompt.plan(me(), now = Instant.parse("2026-10-29T12:00:00Z")).first().id)
    }

    @Test
    fun aMorningTheRemindersReadAlsoListsTakesItsSendAt() {
        val reminders = Reminders("2026-10-29", "2026-11-11", listOf(ReminderDay("2026-10-30", "2026-10-30T06:45:00-05:00")))
        val plan = DailyPrompt.plan(me(), reminders, Instant.parse("2026-10-29T10:00:00Z"))
        assertEquals(Instant.parse("2026-10-30T11:45:00Z"), plan.first { it.id.endsWith("10-30") }.fireAt)
    }

    @Test
    fun aSkippedTimeIsTheChangeAndATwiceShownTimeTheFirst() {
        // 2:30 a.m. doesn't happen on 8 March 2026 in Chicago; the clocks jump at 2:00 CST.
        assertEquals(Instant.parse("2026-03-08T08:00:00Z"), DailyPrompt.moment("2026-03-08", "02:30", chicago))
        // 1:30 a.m. happens twice on 1 November 2026; the first is CDT.
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), DailyPrompt.moment("2026-11-01", "01:30", chicago))
        assertNull(DailyPrompt.moment("2026-11-01", "25:00", chicago))
    }

    // Activity notices

    private val pixel = TokenInfo(name = "Pixel", scopes = listOf("notes:write"), id = "a".repeat(32))

    private fun row(id: String, kind: String, via: String? = null, token: String? = null, outcome: String? = null, name: String? = null, client: String? = null, tokenId: String? = null) =
        ActivityItem(id = id, at = "2026-10-29T12:00:00Z", kind = kind, via = via, token = token, tokenId = tokenId, outcome = outcome, name = name, client = client, version = "4.5.137")

    @Test
    fun aRowIsKnownByTheTokensIdWhenItHasOne() {
        // Another token that took the same name later is not this phone's.
        val other = row("1", "note.added", via = "token", token = "Pixel", tokenId = "b".repeat(32))
        assertEquals(ActivityNoticeKind.Others, ActivityNotices.kind(other, pixel))
        // This token's own row, whatever name it carries.
        assertNull(ActivityNotices.kind(row("2", "note.added", via = "token", token = "Renamed", tokenId = "a".repeat(32)), pixel))
        // A row from before ids has only the name.
        assertNull(ActivityNotices.kind(row("3", "note.added", via = "token", token = "Pixel"), pixel))
    }

    @Test
    fun neverThisPhonesOwnNorTheDailyEmail() {
        assertNull(ActivityNotices.kind(row("1", "note.added", via = "token", token = "Pixel"), pixel))
        assertNull(ActivityNotices.kind(row("2", "badge.earned", via = "token", token = "Pixel"), pixel))
        assertNull(ActivityNotices.kind(row("3", "email.sent"), pixel))
        assertNull(ActivityNotices.kind(row("4", "note.added", via = "web"), pixel))
        assertEquals(ActivityNoticeKind.Others, ActivityNotices.kind(row("5", "note.added", via = "token", token = "Laptop"), pixel))
        assertEquals(ActivityNoticeKind.Others, ActivityNotices.kind(row("6", "mail", outcome = "filed"), pixel))
        assertEquals(ActivityNoticeKind.Problems, ActivityNotices.kind(row("7", "mail", outcome = "failed"), pixel))
        assertEquals(ActivityNoticeKind.Problems, ActivityNotices.kind(row("8", "status.stopped"), pixel))
    }

    @Test
    fun noticesComeOldestFirstOnlyOfTheKindsWanted() {
        val newestFirst = listOf(
            row("3", "badge.earned", name = "API release"),
            row("2", "note.added", via = "assistant", client = "Claude"),
            row("1", "note.added", via = "token", token = "Laptop"),
        )
        val all = ActivityNotices.notices(newestFirst, pixel, ActivityNoticeKind.entries.toSet())
        assertEquals(listOf("activity.1", "activity.2", "activity.3"), all.map { it.id })
        assertEquals("Laptop added a note.", all[0].body)
        assertEquals("Claude added a note.", all[1].body)
        assertEquals("Added to 4.5.137", all[0].title)
        assertEquals(listOf("activity.3"), ActivityNotices.notices(newestFirst, pixel, setOf(ActivityNoticeKind.Badges)).map { it.id })
    }

    @Test
    fun manyOfOneKindComeAsOne() {
        val rows = (5 downTo 1).map { row("$it", "note.added", via = "import") }
        val notices = ActivityNotices.notices(rows, pixel, setOf(ActivityNoticeKind.Others))
        assertEquals(1, notices.size)
        assertEquals("5 notes from elsewhere", notices.single().title)
        assertEquals("activity.others.5", notices.single().id)
    }

    @Test
    fun theCursorIsTheNewestRowRead() {
        assertEquals("9", ActivityNotices.cursor(listOf(row("9", "mail"), row("8", "mail")), "1"))
        assertEquals("1", ActivityNotices.cursor(emptyList(), "1"))
        assertTrue(ActivityNotices.families.contains("badge"))
    }
}
