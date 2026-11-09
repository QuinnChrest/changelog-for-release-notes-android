package com.yourversionnumber.changelog.kit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One day as the widgets and the Quick Settings tile show it, read from the last `/v1/me` the app kept. The
 * version is still the server's, picked from `days` by the day it is now in the account's own time zone, never
 * worked out here. As the iPhone app's `WidgetSnapshot.timeline`.
 */
data class ShownDay(
    val date: String,
    val version: String,
    /** This day's weeks and days lived, when the server said. */
    val lived: Lived?,
    /** Something is written on this day: known only for the day `/v1/me` was read on. */
    val wrote: Boolean,
    /** The next midnight in the account's time zone, when this day's version is over. */
    val ends: Instant,
)

/**
 * The account's day at [now], in its own time zone (the phone's if the server's can't be read). Never before
 * [Me.today]: a phone whose clock is behind the server's stays on the day the server said.
 */
fun Me.dateAt(now: Instant): LocalDate {
    val date = now.atZone(zone).toLocalDate()
    val read = UpcomingDraft.parse(today) ?: return date
    return if (date < read) read else date
}

/**
 * The day to show at [now]: today's version once midnight has passed, from the days `/v1/me` listed ahead. Null
 * once [now] is past the last of them, when only opening the app can tell: "Open Changelog to catch up".
 */
fun Me.shownAt(now: Instant): ShownDay? {
    val date = dateAt(now)
    val key = date.toString()
    val version = versionOn(key) ?: return null
    return ShownDay(
        date = key,
        version = version,
        lived = if (key == today) lived else days.firstOrNull { it.date == key }?.lived,
        wrote = key == today && wroteToday,
        ends = date.plusDays(1).atStartOfDay(zone).toInstant(),
    )
}

private val Me.zone: ZoneId get() = runCatching { ZoneId.of(tz) }.getOrElse { ZoneId.systemDefault() }

/**
 * An Upcoming date's status on [day], from what the server said ([status]) on the day it was read: `upcoming`,
 * `today` or `ongoing` as the server words them, or null once it's over. [next] is its next date (today while a
 * trip is on), [through] a trip's last day. A trip on now that was kept without its last day stays on until the
 * app reads Upcoming again.
 */
fun upcomingStatusOn(next: String, through: String?, status: String, day: String): String? = when {
    status == "past" -> null
    next > day -> "upcoming"
    next == day -> if (status == "ongoing") "ongoing" else "today"
    through == null -> if (status == "ongoing") "ongoing" else null
    through >= day -> "ongoing"
    else -> null
}
