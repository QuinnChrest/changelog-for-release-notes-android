package com.yourversionnumber.changelog.kit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShownDayTest {
    /** The dev server's kind of account, read on Nov 9 in Chicago, with two days listed ahead. */
    private val me = Me(
        today = "2026-11-09",
        version = "4.5.148",
        tz = "America/Chicago",
        wroteToday = true,
        days = listOf(
            DayVersion("2026-11-09", "4.5.148", Lived(2369, 16583)),
            DayVersion("2026-11-10", "4.5.149", Lived(2370, 16584)),
            DayVersion("2026-11-11", "4.5.150"),
        ),
        token = TokenInfo("Pixel", listOf("notes:write")),
        apiVersion = "1.21.0",
        lived = Lived(2369, 16583),
    )

    private fun at(s: String) = Instant.parse(s)

    @Test
    fun theDayItWasReadIsTodayAsTheServerSaid() {
        val day = me.shownAt(at("2026-11-09T23:59:00-06:00"))!!
        assertEquals("2026-11-09", day.date)
        assertEquals("4.5.148", day.version)
        assertEquals(Lived(2369, 16583), day.lived)
        assertTrue(day.wrote)
        assertEquals(at("2026-11-10T00:00:00-06:00"), day.ends)
    }

    @Test
    fun pastMidnightIsTheNextListedDayWithNothingWrittenYet() {
        val day = me.shownAt(at("2026-11-10T00:01:00-06:00"))!!
        assertEquals("2026-11-10", day.date)
        assertEquals("4.5.149", day.version)
        assertEquals(Lived(2370, 16584), day.lived)
        assertFalse(day.wrote)
        assertEquals(at("2026-11-11T00:00:00-06:00"), day.ends)
    }

    @Test
    fun midnightIsTheAccountsNotThePhones() {
        // 05:30 UTC on the 10th is still the 9th in Chicago.
        assertEquals("2026-11-09", me.shownAt(at("2026-11-10T05:30:00Z"))!!.date)
        assertEquals("2026-11-10", me.shownAt(at("2026-11-10T06:00:00Z"))!!.date)
    }

    @Test
    fun aDayListedWithoutLivedHasNone() {
        val day = me.shownAt(at("2026-11-11T12:00:00-06:00"))!!
        assertEquals("4.5.150", day.version)
        assertNull(day.lived)
    }

    @Test
    fun pastTheLastListedDayOnlyTheAppCanSay() {
        assertNull(me.shownAt(at("2026-11-12T00:00:00-06:00")))
        assertEquals("2026-11-12", me.dateAt(at("2026-11-12T00:00:00-06:00")).toString())
    }

    @Test
    fun aClockBehindTheServersStaysOnToday() {
        val day = me.shownAt(at("2026-11-08T22:00:00-06:00"))!!
        assertEquals("2026-11-09", day.date)
        assertEquals("4.5.148", day.version)
    }

    @Test
    fun anOlderServerWithNoDaysStillHasToday() {
        val bare = me.copy(days = emptyList())
        assertEquals("4.5.148", bare.shownAt(at("2026-11-09T12:00:00-06:00"))!!.version)
        assertNull(bare.shownAt(at("2026-11-10T12:00:00-06:00")))
    }

    @Test
    fun upcomingDatesCountFromTheDayShown() {
        // Read on the 9th: tomorrow's birthday was upcoming, today's was today.
        assertEquals("today", upcomingStatusOn("2026-11-10", null, "upcoming", "2026-11-10"))
        assertEquals("upcoming", upcomingStatusOn("2026-11-12", null, "upcoming", "2026-11-10"))
        assertNull(upcomingStatusOn("2026-11-09", null, "today", "2026-11-10"))
        assertNull(upcomingStatusOn("2026-11-09", null, "past", "2026-11-09"))
    }

    @Test
    fun aTripIsOnThroughItsLastDay() {
        assertEquals("ongoing", upcomingStatusOn("2026-11-09", "2026-11-11", "ongoing", "2026-11-10"))
        assertEquals("ongoing", upcomingStatusOn("2026-11-09", "2026-11-11", "today", "2026-11-11"))
        assertNull(upcomingStatusOn("2026-11-09", "2026-11-11", "ongoing", "2026-11-12"))
        // Starting today, as the server says it.
        assertEquals("ongoing", upcomingStatusOn("2026-11-10", "2026-11-12", "ongoing", "2026-11-10"))
        // Kept before trips kept their last day: on until Upcoming is read again.
        assertEquals("ongoing", upcomingStatusOn("2026-11-09", null, "ongoing", "2026-11-10"))
    }

    @Test
    fun onTheDayReadTheServersStatusStands() {
        for ((next, through, status) in listOf(
            Triple("2026-11-12", null, "upcoming"),
            Triple("2026-11-09", null, "today"),
            Triple("2026-11-08", "2026-11-10", "ongoing"),
            Triple("2026-11-09", "2026-11-10", "ongoing"),
        )) {
            assertEquals(status, upcomingStatusOn(next, through, status, "2026-11-09"))
        }
    }
}
