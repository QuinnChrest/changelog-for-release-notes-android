package com.yourversionnumber.changelog.kit

import java.time.Instant
import java.time.OffsetDateTime

/** One Upcoming reminder the phone shows for itself, at the moment that morning's email is due. */
data class PlannedReminder(
    /** The same for the same reminder on the same day, so a top-up replaces it. */
    val id: String,
    val fireAt: Instant,
    /** "In 7 days", as the email says it. */
    val `when`: String,
    /** "Mom turns 7.0.0. A major release.", the email's own line. */
    val line: String,
)

/**
 * Upcoming reminders on the phone, never worked out here: each comes from
 * `GET /v1/upcoming/reminders`, at its day's `send_at`, in the email's words.
 * Read two weeks ahead, and replace everything scheduled with each read.
 */
object ReminderPlan {
    const val PREFIX = "upcoming-reminder."

    /** How far ahead to read: two weeks, the client guide's default. */
    const val DAYS = 14

    fun plan(reminders: Reminders, now: Instant): List<PlannedReminder> =
        reminders.days.flatMap { day ->
            val at = runCatching { OffsetDateTime.parse(day.sendAt).toInstant() }.getOrNull()
            if (at == null || !at.isAfter(now)) {
                emptyList()
            } else {
                day.reminders.map { PlannedReminder("$PREFIX${day.date}.${it.upcoming}", at, it.`when`, it.line) }
            }
        }.sortedWith(compareBy({ it.fireAt }, { it.id }))
}
