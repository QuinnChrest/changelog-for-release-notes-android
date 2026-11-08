package com.yourversionnumber.changelog.kit

// How an activity row reads, in Changelog for iPhone's words
// (ActivityView.swift). The server leaves a note's words out of every row.

/** Through what it came, in the owner's words. */
val ActivityItem.source: String?
    get() = when (via) {
        "token" -> token?.let { Words.say(Phrase.ViaToken, it) } ?: Words.say(Phrase.ViaAToken)
        "assistant" -> client?.let { Words.say(Phrase.ByClient, it) } ?: Words.say(Phrase.ByAnAgent)
        "email" -> Words.say(Phrase.ByEmail)
        "web" -> Words.say(Phrase.OnTheWeb)
        "import" -> Words.say(Phrase.ByImport)
        else -> null
    }

val ActivityItem.headline: String
    get() {
        // A phrase with the version, or without it when the row has none.
        fun inVersion(with: Phrase, bare: Phrase) = version?.let { Words.say(with, it) } ?: Words.say(bare)
        fun named(with: Phrase, bare: Phrase, who: String?) = who?.let { Words.say(with, it) } ?: Words.say(bare)
        return when (kind) {
            "note.added" -> inVersion(Phrase.NoteAdded, Phrase.NoteAddedBare)
            "note.edited" -> inVersion(Phrase.NoteEdited, Phrase.NoteEditedBare)
            "note.deleted" -> inVersion(Phrase.NoteDeleted, Phrase.NoteDeletedBare)
            "note.files" ->
                if (removed != null) inVersion(Phrase.NoteFilesRemoved, Phrase.NoteFilesRemovedBare)
                else inVersion(Phrase.NoteFiles, Phrase.NoteFilesBare)
            "mail" -> if (outcome == "filed") inVersion(Phrase.MailFiled, Phrase.MailFiledBare) else Words.say(Phrase.MailNotFiled)
            "email.sent" -> inVersion(Phrase.EmailSent, Phrase.EmailSentBare)
            "reminder" -> named(Phrase.ReminderTitled, Phrase.ReminderRow, title)
            "badge.earned" -> named(Phrase.BadgeNamed, Phrase.BadgeEarned, name)
            "transcript.added" -> Words.say(Phrase.TranscriptAdded)
            "description.added" -> Words.say(Phrase.DescriptionAdded)
            "import.notes" -> Words.say(Phrase.ImportNotes)
            "upcoming.added" -> named(Phrase.UpcomingAddedTitled, Phrase.UpcomingAdded, title)
            "upcoming.changed" -> named(Phrase.UpcomingChangedTitled, Phrase.UpcomingChanged, title)
            "upcoming.removed" -> named(Phrase.UpcomingRemovedTitled, Phrase.UpcomingRemoved, title)
            "connection.added" -> named(Phrase.ConnectionAdded, Phrase.ConnectionAddedBare, client)
            "connection.changed" -> named(Phrase.ConnectionChanged, Phrase.ConnectionChangedBare, client)
            "connection.removed" -> named(Phrase.ConnectionRemoved, Phrase.ConnectionRemovedBare, client)
            "reads" -> named(Phrase.Reads, Phrase.ReadsBare, client)
            "status.stopped" -> Words.say(Phrase.StatusStopped)
            "status.restarted" -> Words.say(Phrase.StatusRestarted)
            "signin" -> Words.say(Phrase.SignedIn)
            "settings.changed" -> Words.say(Phrase.SettingsChanged)
            "pause.set" -> Words.say(Phrase.PauseSet)
            "pause.ended" -> Words.say(Phrase.PauseEnded)
            "place.set", "place.removed" -> Words.say(Phrase.PlaceChanged)
            "checkin" -> Words.say(Phrase.CheckedIn, stopName(label))
            "checkin.named" -> Words.say(Phrase.CheckInNamed)
            "checkin.deleted" -> when {
                all != true -> Words.say(Phrase.CheckInDeleted)
                count != null -> Words.say(Phrase.CheckInsDeletedAllCount, count)
                else -> Words.say(Phrase.CheckInsDeletedAll)
            }
            "checkin.place" -> when (change) {
                "added" -> Words.say(Phrase.PlaceRemembered)
                "spot" -> Words.say(Phrase.PlaceSpotAdded)
                "renamed" -> Words.say(Phrase.PlaceRenamed)
                else -> Words.say(Phrase.PlaceForgotten)
            }
            else -> kind.split('.').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        }
    }

/** The server names a stop it can't place "Unnamed stop"; a row says "an unnamed stop", as the iPhone app does. */
fun stopName(label: String?): String =
    if (label.isNullOrEmpty() || label == UNNAMED_STOP) Words.say(Phrase.UnnamedStop) else label

private const val UNNAMED_STOP = "Unnamed stop"

val ActivityItem.detail: String?
    get() = listOfNotNull(source, day).joinToString(" · ").ifEmpty { null }

/** A mail row's outcome is filed, not-filed or failed. */
val ActivityItem.isProblem: Boolean
    get() = (kind == "mail" && outcome != "filed") || kind == "status.stopped" || kind == "connection.removed"

/** On a day, by the server's own day, or by its timestamp when it has none. */
fun ActivityItem.isOn(date: String): Boolean = day == date || (day == null && at.startsWith(date))

/** A note this token wrote, the only kind it may read back, change or delete. */
fun ActivityItem.isMine(by: TokenInfo?): Boolean =
    kind == "note.added" && by != null && isFrom(by) && note != null && day != null

/** The Activity screen's filters, by the API's kind families. */
enum class ActivityFilter(private val phrase: Phrase, val kinds: List<String>) {
    All(Phrase.FilterAll, emptyList()),
    Notes(Phrase.FilterNotes, listOf("note", "transcript", "description", "import")),
    CheckIns(Phrase.FilterCheckIns, listOf("checkin")),
    Email(Phrase.FilterEmail, listOf("mail", "email", "reminder")),
    Badges(Phrase.FilterBadges, listOf("badge")),
    Upcoming(Phrase.FilterUpcoming, listOf("upcoming", "reminder")),
    Connections(Phrase.FilterConnections, listOf("connection", "reads")),
    Problems(Phrase.FilterProblems, listOf("mail", "status", "connection"));

    val label: String get() = Words.say(phrase)
}

/** What tapping an Activity row opens, as the iPhone app: the note it names, or a filed reply's day. */
sealed interface ActivityOpens {
    data class Note(val date: String, val id: String) : ActivityOpens

    data class Day(val date: String) : ActivityOpens
}

private val noteKinds = setOf("note.added", "note.edited", "note.files", "description.added", "transcript.added")

/**
 * What this row opens for that token, or null. A note row opens its note when the token reads every note, or
 * wrote it; a filed email reply opens its day, which needs every note read. A deleted note, and every other
 * row, stays still.
 */
fun ActivityItem.opens(by: TokenInfo?): ActivityOpens? {
    val readsNotes = by?.readsNotes == true
    return when {
        kind in noteKinds && note != null && day != null && (readsNotes || (by != null && isFrom(by))) ->
            ActivityOpens.Note(day, note)
        kind == "mail" && outcome == "filed" && day != null && readsNotes -> ActivityOpens.Day(day)
        else -> null
    }
}
