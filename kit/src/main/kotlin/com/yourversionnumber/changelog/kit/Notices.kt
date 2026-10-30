package com.yourversionnumber.changelog.kit

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The daily prompt, planned for one morning. */
data class PlannedPrompt(val id: String, val fireAt: Instant, val body: String)

/**
 * The daily prompt (off by default): "You're 4.5.137 today. What changed?"
 * at the moment each morning's email is due, which opens Log. The versions
 * are the server's, from `/v1/me`'s `days`; the phone works out only when
 * the account's send time comes on each day.
 */
object DailyPrompt {
    const val PREFIX = "daily-prompt."

    /** The prompt's id for a day, the same on every top-up. */
    fun id(day: String) = PREFIX + day

    fun text(version: String) = Words.say(Phrase.DailyPrompt, version)

    /**
     * One prompt for each day `/v1/me` lists whose moment is still ahead,
     * except today once something was written today. A day the reminders
     * read also lists takes its `send_at` from there, so the two agree.
     */
    fun plan(me: Me, reminders: Reminders? = null, now: Instant): List<PlannedPrompt> {
        val zone = runCatching { ZoneId.of(me.tz) }.getOrNull() ?: return emptyList()
        val sendAt = reminders?.days.orEmpty().mapNotNull { d ->
            runCatching { d.date to OffsetDateTime.parse(d.sendAt).toInstant() }.getOrNull()
        }.toMap()
        val days = (if (me.days.none { it.date == me.today }) listOf(DayVersion(me.today, me.version)) else emptyList()) + me.days
        return days.distinctBy { it.date }.mapNotNull { day ->
            if (day.date == me.today && me.wroteToday) return@mapNotNull null
            val at = sendAt[day.date] ?: moment(day.date, me.sendTime, zone) ?: return@mapNotNull null
            if (!at.isAfter(now)) null else PlannedPrompt(id(day.date), at, text(day.version))
        }
    }

    /**
     * When a local day's clock first shows a time ("06:30") in a zone. A time
     * the clock skips is the change itself, and a time it shows twice the
     * first, as Release Notes' own `localMoment` reads it.
     */
    fun moment(day: String, time: String, zone: ZoneId): Instant? {
        val date = UpcomingDraft.parse(day) ?: return null
        val clock = time.split(":").mapNotNull { it.toIntOrNull() }
        if (clock.size != 2 || clock[0] !in 0..23 || clock[1] !in 0..59) return null
        val local = date.atTime(LocalTime.of(clock[0], clock[1]))
        val rules = zone.rules
        val offsets = rules.getValidOffsets(local)
        return try {
            if (offsets.isEmpty()) {
                // In a gap: the moment the clocks jump.
                rules.getTransition(local).instant
            } else {
                // Shown once, or twice and the first.
                ZonedDateTime.ofLocal(local, zone, offsets.first()).withEarlierOffsetAtOverlap().toInstant()
            }
        } catch (e: DateTimeException) {
            null
        }
    }
}

/** The kinds of activity a phone can be told about, each turned on by itself in Settings. */
enum class ActivityNoticeKind(private val phrase: Phrase) {
    /** Notes added by someone else: an agent, another token, an import, an email reply. */
    Others(Phrase.NoticeOthers),

    /** An email that wasn't filed, the email stopping, a connection ending. */
    Problems(Phrase.NoticeProblems),
    Badges(Phrase.NoticeBadges);

    val label: String get() = Words.say(phrase)
}

/** A notification for one activity row, or for several of one kind. It never carries a note's words: the log has none. */
data class ActivityNotice(val id: String, val kind: ActivityNoticeKind, val title: String, val body: String)

/**
 * Activity notifications, read from `GET /v1/activity?after=<last seen>` in
 * the background, so Android decides when. Never this phone's own notes or
 * badges, and never the daily email going out or the reminders it carried.
 * Changelog for iPhone's ActivityNotices, word for word.
 */
object ActivityNotices {
    const val PREFIX = "activity."

    /** The families a notice can come from, so each read stays small. */
    val families = listOf("note", "mail", "import", "badge", "status", "connection")

    /** More than this many of one kind from one read come as one notice. */
    const val PER_KIND = 3

    /**
     * Whether a row came through this phone's own token. The token's name is
     * what every row a token writes carries; `client` is only what a
     * User-Agent claimed, so it labels and never decides. Should another
     * token have the same name, its notes stay quiet too: a missed notice,
     * never a false one.
     */
    fun isOwn(item: ActivityItem, tokenName: String) = item.via == "token" && item.token == tokenName

    /** Which notice a row is, if any. */
    fun kind(item: ActivityItem, tokenName: String): ActivityNoticeKind? {
        if (isOwn(item, tokenName)) return null
        return when (item.kind) {
            // The web is the owner at a keyboard; email replies are mail rows.
            "note.added" -> if (item.via in listOf("assistant", "token", "import")) ActivityNoticeKind.Others else null
            "import.notes" -> ActivityNoticeKind.Others
            "mail" -> when (item.outcome) {
                "filed" -> ActivityNoticeKind.Others
                "not-filed", "failed" -> ActivityNoticeKind.Problems
                else -> null
            }
            "badge.earned" -> ActivityNoticeKind.Badges
            "status.stopped", "connection.removed" -> ActivityNoticeKind.Problems
            // email.sent, reminder and the rest never notify.
            else -> null
        }
    }

    /**
     * The notices for rows read newest first, oldest first so they arrive in
     * order, only of the kinds wanted. A kind with more than PER_KIND rows
     * comes as one notice instead.
     */
    fun notices(items: List<ActivityItem>, tokenName: String, wanted: Set<ActivityNoticeKind>): List<ActivityNotice> {
        val rows = items.reversed().mapNotNull { item -> kind(item, tokenName)?.takeIf { it in wanted }?.let { item to it } }
        return ActivityNoticeKind.entries.flatMap { kind ->
            val mine = rows.filter { it.second == kind }
            val newest = mine.lastOrNull()?.first ?: return@flatMap emptyList()
            if (mine.size > PER_KIND) listOf(summary(kind, mine.size, newest)) else mine.map { notice(it.first, it.second) }
        }
    }

    /** Where the next read starts: the newest row read, or where it was. */
    fun cursor(items: List<ActivityItem>, previous: String?): String? = items.firstOrNull()?.id ?: previous

    internal fun notice(item: ActivityItem, kind: ActivityNoticeKind): ActivityNotice {
        val id = PREFIX + item.id
        fun inVersion(with: Phrase, bare: Phrase) = item.version?.let { Words.say(with, it) } ?: Words.say(bare)
        return when {
            item.kind == "note.added" -> {
                val who = when (item.via) {
                    "assistant" -> item.client ?: Words.say(Phrase.AnAgent)
                    "import" -> Words.say(Phrase.AnImport)
                    else -> item.token ?: Words.say(Phrase.AnotherToken)
                }
                ActivityNotice(id, kind, inVersion(Phrase.AddedTo, Phrase.AddedToBare), Words.say(Phrase.NoteAddedBody, who))
            }
            item.kind == "import.notes" -> ActivityNotice(id, kind, Words.say(Phrase.ImportNotes), Words.say(Phrase.ImportBody))
            item.kind == "mail" && kind == ActivityNoticeKind.Others ->
                ActivityNotice(id, kind, inVersion(Phrase.MailFiled, Phrase.MailFiledBare), Words.say(Phrase.MailFiledBody))
            item.kind == "mail" -> ActivityNotice(id, kind, Words.say(Phrase.MailNotFiledTitle), Words.say(Phrase.MailNotFiledBody))
            item.kind == "badge.earned" -> ActivityNotice(id, kind, Words.say(Phrase.BadgeEarned), item.name ?: Words.say(Phrase.BadgeFallbackBody))
            item.kind == "status.stopped" -> ActivityNotice(id, kind, Words.say(Phrase.EmailStoppedTitle), Words.say(Phrase.EmailStoppedBody))
            else -> ActivityNotice(
                id, kind, Words.say(Phrase.ConnectionEndedTitle),
                Words.say(Phrase.ConnectionEndedBody, item.client ?: item.token ?: Words.say(Phrase.AConnection)),
            )
        }
    }

    internal fun summary(kind: ActivityNoticeKind, count: Int, newest: ActivityItem): ActivityNotice {
        val id = "$PREFIX${kind.name.lowercase()}.${newest.id}"
        val title = when (kind) {
            ActivityNoticeKind.Others -> Words.say(Phrase.SummaryOthers, count)
            ActivityNoticeKind.Problems -> Words.say(Phrase.SummaryProblems, count)
            ActivityNoticeKind.Badges -> Words.say(Phrase.SummaryBadges, count)
        }
        return ActivityNotice(id, kind, title, Words.say(Phrase.SummaryBody))
    }
}

