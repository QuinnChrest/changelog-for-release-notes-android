package com.yourversionnumber.changelog.kit

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Your Version Number's arithmetic: decades.years.days since `since`. Only
 * for Changelog's own build number. A person's version always comes from
 * the server (`GET /v1/me`), never from here.
 *
 * `app/build.gradle.kts` stamps builds with the same arithmetic; keep the two
 * in step. These are tested in `YourVersionNumberTest`.
 */
object YourVersionNumber {
    /** Changelog for Android's first day, which is 0.0.0. */
    val START: LocalDate = LocalDate.of(2026, 10, 29)

    /** Builds are numbered by the day in Chicago, as Release Notes' are. */
    const val ZONE = "America/Chicago"

    fun on(since: LocalDate, day: LocalDate): String {
        var year = day.year
        var last = anniversary(year, since)
        if (day < last) {
            year -= 1
            last = anniversary(year, since)
        }
        val age = year - since.year
        return "${age / 10}.${age % 10}.${ChronoUnit.DAYS.between(last, day)}"
    }

    /** The anniversary of a day in a year; 29 February is 1 March in the rest. */
    private fun anniversary(year: Int, since: LocalDate): LocalDate =
        if (since.monthValue == 2 && since.dayOfMonth == 29 && !java.time.Year.isLeap(year.toLong())) {
            LocalDate.of(year, 3, 1)
        } else {
            LocalDate.of(year, since.monthValue, since.dayOfMonth)
        }
}
