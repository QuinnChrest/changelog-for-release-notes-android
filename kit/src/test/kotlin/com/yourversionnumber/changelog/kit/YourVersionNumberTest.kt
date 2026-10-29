package com.yourversionnumber.changelog.kit

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class YourVersionNumberTest {
    private fun check(since: String, day: String, want: String) =
        assertEquals(want, YourVersionNumber.on(LocalDate.parse(since), LocalDate.parse(day)), "$since on $day")

    // Your Version Number's own edge cases, then Changelog's first days,
    // as Changelog for iPhone's scripts/version.sh tests them.
    @Test
    fun arithmetic() {
        check("1990-09-15", "2026-09-15", "3.6.0")
        check("2026-09-15", "2026-09-15", "0.0.0")
        check("1966-09-15", "2026-09-15", "6.0.0")
        check("2004-02-29", "2026-03-01", "2.2.0")
        check("2004-02-29", "2028-02-29", "2.4.0")
        check("2004-02-29", "2026-02-28", "2.1.364")
        check("1974-01-15", "2027-01-01", "5.2.351")
    }

    @Test
    fun changelogsOwnDays() {
        val start = YourVersionNumber.START.toString()
        check(start, "2026-10-29", "0.0.0")
        check(start, "2026-10-30", "0.0.1")
        check(start, "2026-11-01", "0.0.3")
        check(start, "2027-03-14", "0.0.136")
        check(start, "2027-10-28", "0.0.364")
        check(start, "2027-10-29", "0.1.0")
        check(start, "2028-10-28", "0.1.365")
        check(start, "2036-10-29", "1.0.0")
    }
}
