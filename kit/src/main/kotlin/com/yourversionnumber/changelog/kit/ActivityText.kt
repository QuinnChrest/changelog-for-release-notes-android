package com.yourversionnumber.changelog.kit

// How an activity row reads, in Changelog for iPhone's words
// (ActivityView.swift). The server leaves a note's words out of every row.

/** Through what it came, in the owner's words. */
val ActivityItem.source: String?
    get() = when (via) {
        "token" -> token?.let { "via $it" } ?: "via a token"
        "assistant" -> client?.let { "by $it" } ?: "by an agent"
        "email" -> "by email"
        "web" -> "on the web"
        "import" -> "by import"
        else -> null
    }

val ActivityItem.headline: String
    get() {
        val v = version?.let { " $it" }.orEmpty()
        return when (kind) {
            "note.added" -> "Filed in$v"
            "note.edited" -> "Edited in$v"
            "note.deleted" -> "Deleted from$v"
            "note.files" -> "Files added in$v"
            "mail" -> if (outcome == "filed") "Reply filed in$v" else "Email not filed"
            "email.sent" -> "Email sent for$v"
            "reminder" -> title?.let { "Reminder: $it" } ?: "Reminder in the email"
            "badge.earned" -> name?.let { "Badge: $it" } ?: "Badge earned"
            "transcript.added" -> "Recording written out"
            "description.added" -> "Photos described"
            "import.notes" -> "Notes imported"
            "upcoming.added", "upcoming.changed", "upcoming.removed" -> {
                val verb = kind.substringAfter('.')
                title?.let { "$it $verb" } ?: "Upcoming date $verb"
            }
            "connection.added" -> "Connected ${client ?: "a client"}"
            "connection.changed" -> "${client ?: "A connection"} changed"
            "connection.removed" -> "${client ?: "A connection"} disconnected"
            "reads" -> "${client ?: "An agent"} read your notes"
            "status.stopped" -> "Email stopped"
            "status.restarted" -> "Email restarted"
            "signin" -> "Signed in on the web"
            "settings.changed" -> "Settings changed"
            "pause.set" -> "Email paused"
            "pause.ended" -> "Pause ended"
            "place.set", "place.removed" -> "Place for the day changed"
            else -> kind.split('.').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        }
    }

val ActivityItem.detail: String?
    get() = listOfNotNull(source, day).joinToString(" · ").ifEmpty { null }

/** A mail row's outcome is filed, not-filed or failed. */
val ActivityItem.isProblem: Boolean
    get() = (kind == "mail" && outcome != "filed") || kind == "status.stopped" || kind == "connection.removed"

/** On a day, by the server's own day, or by its timestamp when it has none. */
fun ActivityItem.isOn(date: String): Boolean = day == date || (day == null && at.startsWith(date))

/** A note this token wrote, the only kind it may read back, change or delete. */
fun ActivityItem.isMine(tokenName: String?): Boolean =
    kind == "note.added" && via == "token" && token != null && token == tokenName && note != null && day != null

/** The Activity screen's filters, by the API's kind families. */
enum class ActivityFilter(val label: String, val kinds: List<String>) {
    All("All", emptyList()),
    Notes("Notes", listOf("note", "transcript", "description", "import")),
    Email("Email", listOf("mail", "email", "reminder")),
    Badges("Badges", listOf("badge")),
    Upcoming("Upcoming", listOf("upcoming", "reminder")),
    Connections("Connections", listOf("connection", "reads")),
    Problems("Problems", listOf("mail", "status", "connection")),
}
