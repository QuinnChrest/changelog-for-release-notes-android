package com.yourversionnumber.changelog.kit

/**
 * Every sentence the kit makes for a screen, by name. The words themselves
 * are the app's, in `strings.xml`, supplied through [Words]: the kit has no
 * Android to read them with. Arguments fill `%1$s`, `%2$s` in order.
 */
enum class Phrase {
    ViaToken,
    ViaAToken,
    ByClient,
    ByAnAgent,
    ByEmail,
    OnTheWeb,
    ByImport,
    NoteAdded,
    NoteAddedBare,
    NoteEdited,
    NoteEditedBare,
    NoteDeleted,
    NoteDeletedBare,
    NoteFiles,
    NoteFilesBare,
    MailFiled,
    MailFiledBare,
    MailNotFiled,
    EmailSent,
    EmailSentBare,
    ReminderTitled,
    ReminderRow,
    BadgeNamed,
    BadgeEarned,
    TranscriptAdded,
    DescriptionAdded,
    ImportNotes,
    UpcomingAddedTitled,
    UpcomingChangedTitled,
    UpcomingRemovedTitled,
    UpcomingAdded,
    UpcomingChanged,
    UpcomingRemoved,
    ConnectionAdded,
    ConnectionAddedBare,
    ConnectionChanged,
    ConnectionChangedBare,
    ConnectionRemoved,
    ConnectionRemovedBare,
    Reads,
    ReadsBare,
    StatusStopped,
    StatusRestarted,
    SignedIn,
    SettingsChanged,
    PauseSet,
    PauseEnded,
    PlaceChanged,
    FilterAll,
    FilterNotes,
    FilterEmail,
    FilterBadges,
    FilterUpcoming,
    FilterConnections,
    FilterProblems,
    KindBirthday,
    KindAnniversary,
    KindTrip,
    KindEvent,
    NoticeOthers,
    NoticeProblems,
    NoticeBadges,
    DailyPrompt,
    AddedTo,
    AddedToBare,
    AnAgent,
    AnImport,
    AnotherToken,
    AConnection,
    NoteAddedBody,
    ImportBody,
    MailFiledBody,
    MailNotFiledTitle,
    MailNotFiledBody,
    BadgeFallbackBody,
    EmailStoppedTitle,
    EmailStoppedBody,
    ConnectionEndedTitle,
    ConnectionEndedBody,
    SummaryOthers,
    SummaryProblems,
    SummaryBadges,
    SummaryBody,
    UploadAddressBad,
    UploadFailed,
    NetworkDown,
    ServerAddressBad,
    AnswerUnreadable,
    FileMissing,
    ServerUnavailable,
    ServerRefused,
    NoteNotKept,
    ProblemTitle,
    ProblemTitleTooLong,
    ProblemYear,
    ProblemEnd,
    ProblemPlace,
    ProblemTag,
    ProblemReminders,
}

/** The phrases that change with a count: one photo, two photos. */
enum class CountPhrase {
    PhotoCount,
    MemoCount,
    FileCount,
}

/** A language for the kit's phrases. */
interface Phrasebook {
    fun say(phrase: Phrase, args: List<Any>): String

    fun count(phrase: CountPhrase, n: Int): String
}

/**
 * The phrasebook in use. The app sets it as it starts, before anything can
 * speak; until then (and in a test that sets none) a phrase reads as its name.
 */
object Words {
    @Volatile
    var book: Phrasebook = object : Phrasebook {
        override fun say(phrase: Phrase, args: List<Any>) = (listOf(phrase.name) + args).joinToString(" ")

        override fun count(phrase: CountPhrase, n: Int) = "${phrase.name} $n"
    }

    fun say(phrase: Phrase, vararg args: Any): String = book.say(phrase, args.toList())

    fun count(phrase: CountPhrase, n: Int): String = book.count(phrase, n)
}
