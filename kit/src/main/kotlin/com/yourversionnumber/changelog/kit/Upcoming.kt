package com.yourversionnumber.changelog.kit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

// Upcoming dates as /v1 names them (GET /v1/upcoming, POST
// /v1/upcoming/batch, DELETE /v1/upcoming/{id}). A date's title, day and
// destination are the owner's words: nothing here logs them or puts them in
// an error.

@Serializable
enum class UpcomingKind(private val phrase: Phrase) {
    @SerialName("birthday") Birthday(Phrase.KindBirthday),
    @SerialName("anniversary") Anniversary(Phrase.KindAnniversary),
    @SerialName("trip") Trip(Phrase.KindTrip),
    @SerialName("event") Event(Phrase.KindEvent);

    val label: String get() = Words.say(phrase)

    /** Comes back every year, written `MM-DD`, with its first year if known. */
    val annual: Boolean get() = this == Birthday || this == Anniversary

    /** The server's own defaults: a week before, the day before and on the day; two weeks for a trip. */
    val defaultReminders: List<Int> get() = if (this == Trip) listOf(14, 1, 0) else listOf(7, 1, 0)
}

/** A date as it is saved. A change sends every field again; a field left null goes unsent, which the server reads as none. */
@Serializable
data class UpcomingFields(
    val kind: UpcomingKind,
    /** One line, up to 120 characters. */
    val title: String,
    /** `MM-DD` for a birthday or anniversary, `YYYY-MM-DD` for a trip's first day or an event. */
    val date: String,
    val year: Int? = null,
    /** A trip's last day. */
    val endDate: String? = null,
    /** A trip's destination (an event's place, if it has one), as the city search knows it. */
    val place: String? = null,
    /** One word, like `family`, without the `#`. */
    val tag: String? = null,
    /** Days before to remind: one to six. */
    val reminders: List<Int> = kind.defaultReminders,
    /** Remind in the morning email, and so in apps. */
    val email: Boolean = true,
)

/** One saved date, with the server's own line ("Mom turns 7.0.0. A major release.") and its next occurrence. */
@Serializable
data class UpcomingEntry(
    val id: String,
    /** Send it back to change or remove the date. */
    val revision: Int,
    /** Its next date, or today while a trip is on. */
    val next: String,
    /** A trip's last day this time. */
    val through: String? = null,
    /** upcoming, today, ongoing or past. */
    val status: String = "upcoming",
    val line: String,
    val kind: UpcomingKind,
    val title: String,
    val date: String,
    val year: Int? = null,
    val endDate: String? = null,
    val place: String? = null,
    val tag: String? = null,
    val reminders: List<Int> = emptyList(),
    val email: Boolean = true,
) {
    val fields: UpcomingFields get() = UpcomingFields(kind, title, date, year, endDate, place, tag, reminders, email)
}

/**
 * `GET /v1/upcoming`: every date ahead, soonest first. A one-time date
 * already over is left out and only counted. Dates of a kind this version
 * doesn't know, from a later server, are left out and counted as unreadable
 * rather than failing the whole list.
 */
data class UpcomingList(
    val today: String,
    val entries: List<UpcomingEntry>,
    val pastLeftOut: Int = 0,
    val maxEntries: Int = 200,
    val unreadable: Int = 0,
) {
    companion object {
        fun from(o: JsonObject): UpcomingList {
            val rows = o["entries"]?.jsonArray.orEmpty()
            val entries = rows.mapNotNull { runCatching { Api.json.decodeFromJsonElement<UpcomingEntry>(it) }.getOrNull() }
            return UpcomingList(
                today = o["today"]!!.jsonPrimitive.content,
                entries = entries,
                pastLeftOut = o["past_left_out"]?.jsonPrimitive?.int ?: 0,
                maxEntries = o["max_entries"]?.jsonPrimitive?.int ?: 200,
                unreadable = rows.size - entries.size,
            )
        }
    }
}

/** A change: every field again, from the revision read. Sent flat, as the API has it. */
@Serializable
data class UpcomingChange(
    val id: String,
    val revision: Int,
    val kind: UpcomingKind,
    val title: String,
    val date: String,
    val year: Int? = null,
    val endDate: String? = null,
    val place: String? = null,
    val tag: String? = null,
    val reminders: List<Int>,
    val email: Boolean,
) {
    constructor(entry: UpcomingEntry, f: UpcomingFields) :
        this(entry.id, entry.revision, f.kind, f.title, f.date, f.year, f.endDate, f.place, f.tag, f.reminders, f.email)
}

@Serializable
data class UpcomingRemoval(val id: String, val revision: Int)

/**
 * `POST /v1/upcoming/batch`: all or nothing, at most 50 entries. With
 * `dryRun` it checks everything and finds each destination's city, and
 * saves nothing. Only the lists that hold something are sent.
 */
@Serializable
data class UpcomingBatch(
    val add: List<UpcomingFields>? = null,
    val change: List<UpcomingChange>? = null,
    val remove: List<UpcomingRemoval>? = null,
    val dryRun: Boolean? = null,
) {
    companion object {
        fun adding(f: UpcomingFields, dryRun: Boolean = false) = UpcomingBatch(add = listOf(f), dryRun = dryRun.takeIf { it })
        fun changing(e: UpcomingEntry, f: UpcomingFields, dryRun: Boolean = false) =
            UpcomingBatch(change = listOf(UpcomingChange(e, f)), dryRun = dryRun.takeIf { it })
        fun removing(e: UpcomingEntry) = UpcomingBatch(remove = listOf(UpcomingRemoval(e.id, e.revision)))
    }
}

/** What a batch saved, or with a dry run would have: the dates as they now read, with the cities found. */
@Serializable
data class UpcomingSaved(
    val dryRun: Boolean = false,
    val added: List<UpcomingEntry> = emptyList(),
    val changed: List<UpcomingEntry> = emptyList(),
    val removed: List<String> = emptyList(),
    val unlocked: List<Unlocked>? = null,
) {
    /** The one date a single add or change saved. */
    val saved: UpcomingEntry? get() = added.firstOrNull() ?: changed.firstOrNull()
}

/** The server's sentence for a refused date, without the `(add[0])` it names the entry by: the app sends one at a time. */
val ApiException.upcomingSentence: String
    get() = message.replace(Regex(" \\((?:add|change|remove)\\[\\d+]\\)"), "")

/**
 * `GET /v1/upcoming/reminders`: the reminders each morning's email carries,
 * for the days ahead, with when that email is due. Only days with a reminder
 * are listed.
 */
@Serializable
data class Reminders(val from: String, val through: String, val days: List<ReminderDay> = emptyList())

@Serializable
data class ReminderDay(
    val date: String,
    /** When that morning's email is due, with the account's offset that day. */
    val sendAt: String,
    val reminders: List<Reminder> = emptyList(),
)

/** One reminder, in the email's own words: `when` ("In 7 days") and `line`. */
@Serializable
data class Reminder(val upcoming: String, val date: String, val `when`: String, val line: String)

/**
 * The `Idempotency-Key` for a write sent at once rather than queued: the
 * same key for as long as the same request is tried again, a new one as soon
 * as it changes (the server refuses a key that went with a different
 * request). So an answer lost on a bad connection is safe to try again.
 */
class RequestKey<R> {
    private var request: R? = null
    private var current = ""

    fun key(request: R, make: () -> String = { UUID.randomUUID().toString() }): String {
        if (request != this.request || current.isEmpty()) {
            this.request = request
            current = make()
        }
        return current
    }

    /** Once a request has landed, the next one is new even if it looks alike. */
    fun reset() {
        request = null
        current = ""
    }
}

/**
 * A date being added or edited on the phone. It keeps a month and day for
 * the yearly kinds and full days for the rest, so switching kind and back
 * loses nothing, and it checks only what the phone can before sending: the
 * server stays the authority, and the one that knows its cities.
 */
data class UpcomingDraft(
    val kind: UpcomingKind = UpcomingKind.Birthday,
    val title: String = "",
    /** A birthday's or anniversary's month and day. */
    val month: Int,
    val day: Int,
    val year: String = "",
    /** A trip's first day or an event's day, `YYYY-MM-DD`. */
    val start: String,
    /** A trip's last day. */
    val end: String,
    /** A trip's destination, or an event's place. */
    val place: String = "",
    val tag: String = "",
    val reminders: List<Int> = kind.defaultReminders,
    val email: Boolean = true,
    /** The account's today, for the year check. */
    val today: String,
) {
    /** What the owner can fix before anything is sent. The words never name the date's own title, day or place. */
    enum class Problem(private val phrase: Phrase) {
        Title(Phrase.ProblemTitle),
        TitleTooLong(Phrase.ProblemTitleTooLong),
        Year(Phrase.ProblemYear),
        End(Phrase.ProblemEnd),
        Place(Phrase.ProblemPlace),
        Tag(Phrase.ProblemTag),
        Reminders(Phrase.ProblemReminders);

        val message: String get() = Words.say(phrase)
    }

    /** Switching kind: reminders left at one kind's default follow the new kind's. */
    fun withKind(next: UpcomingKind) =
        copy(kind = next, reminders = if (reminders.sorted() == kind.defaultReminders.sorted()) next.defaultReminders else reminders)

    fun withMonth(m: Int) = copy(month = m, day = minOf(day, daysIn(m)))

    fun withStart(s: String) = copy(start = s, end = if (end < s) s else end)

    /** The fields to send, or the first thing to fix. */
    fun fields(): Result<UpcomingFields> {
        val title = title.trim()
        if (title.isEmpty() || title.any { it == '\n' || it == '\r' }) return fail(Problem.Title)
        if (title.codePointCount(0, title.length) > TITLE_MAX) return fail(Problem.TitleTooLong)

        val typed = tag.trim()
        val tag = if (typed.isEmpty() || typed == "#") null else Hashtags.normalize(typed) ?: return fail(Problem.Tag)

        val days = reminders.filter { it in 0..365 }.distinct().sortedDescending()
        if (days.size !in 1..6) return fail(Problem.Reminders)

        val place = place.trim()
        return when (kind) {
            UpcomingKind.Birthday, UpcomingKind.Anniversary -> {
                val y = year.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: return fail(Problem.Year) }
                if (y != null && (y < 1 || y > (parse(today)?.year ?: Int.MAX_VALUE))) return fail(Problem.Year)
                val date = "%02d-%02d".format(month, minOf(day, daysIn(month)))
                Result.success(UpcomingFields(kind, title, date, year = y, tag = tag, reminders = days, email = email))
            }
            UpcomingKind.Trip -> {
                if (end < start) return fail(Problem.End)
                if (place.isEmpty()) return fail(Problem.Place)
                Result.success(UpcomingFields(kind, title, start, endDate = end, place = place, tag = tag, reminders = days, email = email))
            }
            UpcomingKind.Event ->
                Result.success(UpcomingFields(kind, title, start, place = place.ifEmpty { null }, tag = tag, reminders = days, email = email))
        }
    }

    /** The batch to save it: an add, or a change from the revision read. */
    fun batch(editing: UpcomingEntry?, dryRun: Boolean = false): Result<UpcomingBatch> = fields().map { f ->
        if (editing != null) UpcomingBatch.changing(editing, f, dryRun) else UpcomingBatch.adding(f, dryRun)
    }

    /** A dry run that only looks up the destination: a title not yet typed stands in as the kind's name. */
    fun placeCheck(editing: UpcomingEntry?): Result<UpcomingBatch> =
        (if (title.isBlank()) copy(title = kind.label) else this).batch(editing, dryRun = true)

    class Refused(val problem: Problem) : Exception(problem.message)

    private fun fail(p: Problem): Result<Nothing> = Result.failure(Refused(p))

    companion object {
        const val TITLE_MAX = 120

        fun new(today: String, kind: UpcomingKind = UpcomingKind.Birthday): UpcomingDraft {
            val d = parse(today)
            return UpcomingDraft(kind = kind, month = d?.monthValue ?: 1, day = d?.dayOfMonth ?: 1, start = today, end = today, today = today)
        }

        fun of(entry: UpcomingEntry, today: String): UpcomingDraft {
            val base = new(today, entry.kind).copy(
                title = entry.title,
                year = entry.year?.toString().orEmpty(),
                place = entry.place.orEmpty(),
                tag = entry.tag.orEmpty(),
                reminders = entry.reminders.ifEmpty { entry.kind.defaultReminders },
                email = entry.email,
            )
            return if (entry.kind.annual) {
                val parts = entry.date.split("-").mapNotNull { it.toIntOrNull() }
                if (parts.size == 2) base.copy(month = parts[0], day = parts[1]) else base
            } else if (parse(entry.date) != null) {
                base.copy(start = entry.date, end = entry.endDate?.takeIf { parse(it) != null } ?: entry.date)
            } else {
                base
            }
        }

        /** Days in a month for a yearly date: February has the 29th, which the server keeps for leap-day birthdays. */
        fun daysIn(month: Int): Int = when (month) {
            2 -> 29
            4, 6, 9, 11 -> 30
            else -> 31
        }

        fun parse(day: String): LocalDate? = try {
            if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(day)) LocalDate.parse(day) else null
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
