package com.yourversionnumber.changelog

import com.yourversionnumber.changelog.kit.CountPhrase
import com.yourversionnumber.changelog.kit.Phrase
import com.yourversionnumber.changelog.kit.Phrasebook

/** The kit's phrases, from strings.xml. `when` has no `else`, so a phrase with no words won't build. */
object AppPhrases : Phrasebook {
    override fun say(phrase: Phrase, args: List<Any>): String = ChangelogApp.words.getString(
        when (phrase) {
            Phrase.ViaToken -> R.string.kit_via_token
            Phrase.ViaAToken -> R.string.kit_via_a_token
            Phrase.ByClient -> R.string.kit_by_client
            Phrase.ByAnAgent -> R.string.kit_by_an_agent
            Phrase.ByEmail -> R.string.kit_by_email
            Phrase.OnTheWeb -> R.string.kit_on_the_web
            Phrase.ByImport -> R.string.kit_by_import
            Phrase.NoteAdded -> R.string.kit_note_added
            Phrase.NoteAddedBare -> R.string.kit_note_added_bare
            Phrase.NoteEdited -> R.string.kit_note_edited
            Phrase.NoteEditedBare -> R.string.kit_note_edited_bare
            Phrase.NoteDeleted -> R.string.kit_note_deleted
            Phrase.NoteDeletedBare -> R.string.kit_note_deleted_bare
            Phrase.NoteFiles -> R.string.kit_note_files
            Phrase.NoteFilesBare -> R.string.kit_note_files_bare
            Phrase.NoteFilesRemoved -> R.string.kit_note_files_removed
            Phrase.NoteFilesRemovedBare -> R.string.kit_note_files_removed_bare
            Phrase.MailFiled -> R.string.kit_mail_filed
            Phrase.MailFiledBare -> R.string.kit_mail_filed_bare
            Phrase.MailNotFiled -> R.string.kit_mail_not_filed
            Phrase.EmailSent -> R.string.kit_email_sent
            Phrase.EmailSentBare -> R.string.kit_email_sent_bare
            Phrase.ReminderTitled -> R.string.kit_reminder_titled
            Phrase.ReminderRow -> R.string.kit_reminder_row
            Phrase.BadgeNamed -> R.string.kit_badge_named
            Phrase.BadgeEarned -> R.string.kit_badge_earned
            Phrase.TranscriptAdded -> R.string.kit_transcript_added
            Phrase.DescriptionAdded -> R.string.kit_description_added
            Phrase.ImportNotes -> R.string.kit_import_notes
            Phrase.UpcomingAddedTitled -> R.string.kit_upcoming_added_titled
            Phrase.UpcomingChangedTitled -> R.string.kit_upcoming_changed_titled
            Phrase.UpcomingRemovedTitled -> R.string.kit_upcoming_removed_titled
            Phrase.UpcomingAdded -> R.string.kit_upcoming_added
            Phrase.UpcomingChanged -> R.string.kit_upcoming_changed
            Phrase.UpcomingRemoved -> R.string.kit_upcoming_removed
            Phrase.ConnectionAdded -> R.string.kit_connection_added
            Phrase.ConnectionAddedBare -> R.string.kit_connection_added_bare
            Phrase.ConnectionChanged -> R.string.kit_connection_changed
            Phrase.ConnectionChangedBare -> R.string.kit_connection_changed_bare
            Phrase.ConnectionRemoved -> R.string.kit_connection_removed
            Phrase.ConnectionRemovedBare -> R.string.kit_connection_removed_bare
            Phrase.Reads -> R.string.kit_reads
            Phrase.ReadsBare -> R.string.kit_reads_bare
            Phrase.StatusStopped -> R.string.kit_status_stopped
            Phrase.StatusRestarted -> R.string.kit_status_restarted
            Phrase.SignedIn -> R.string.kit_signed_in
            Phrase.SettingsChanged -> R.string.kit_settings_changed
            Phrase.PauseSet -> R.string.kit_pause_set
            Phrase.PauseEnded -> R.string.kit_pause_ended
            Phrase.PlaceChanged -> R.string.kit_place_changed
            Phrase.CheckedIn -> R.string.kit_checked_in
            Phrase.UnnamedStop -> R.string.kit_unnamed_stop
            Phrase.CheckInNamed -> R.string.kit_check_in_named
            Phrase.CheckInTagged -> R.string.kit_check_in_tagged
            Phrase.CheckInsTaggedAtPlace -> R.string.kit_check_ins_tagged_at_place
            Phrase.CheckInDeleted -> R.string.kit_check_in_deleted
            Phrase.CheckInsDeletedAll -> R.string.kit_check_ins_deleted_all
            Phrase.CheckInsDeletedAllCount -> R.string.kit_check_ins_deleted_all_count
            Phrase.CheckInsDeletedAtPlace -> R.string.kit_check_ins_deleted_at_place
            Phrase.PlaceRemembered -> R.string.kit_place_remembered
            Phrase.PlaceSpotAdded -> R.string.kit_place_spot_added
            Phrase.PlaceRenamed -> R.string.kit_place_renamed
            Phrase.PlaceTagged -> R.string.kit_place_tagged
            Phrase.PlaceForgotten -> R.string.kit_place_forgotten
            Phrase.PlaceIgnored -> R.string.kit_place_ignored
            Phrase.PlaceIgnoredMore -> R.string.kit_place_ignored_more
            Phrase.PlaceUnignored -> R.string.kit_place_unignored
            Phrase.PlaceUnignoredMore -> R.string.kit_place_unignored_more
            Phrase.HomeNotIgnored -> R.string.kit_home_not_ignored
            Phrase.FilterAll -> R.string.kit_filter_all
            Phrase.FilterNotes -> R.string.kit_filter_notes
            Phrase.FilterCheckIns -> R.string.kit_filter_check_ins
            Phrase.FilterEmail -> R.string.kit_filter_email
            Phrase.FilterBadges -> R.string.kit_filter_badges
            Phrase.FilterUpcoming -> R.string.kit_filter_upcoming
            Phrase.FilterConnections -> R.string.kit_filter_connections
            Phrase.FilterProblems -> R.string.kit_filter_problems
            Phrase.KindBirthday -> R.string.kit_kind_birthday
            Phrase.KindAnniversary -> R.string.kit_kind_anniversary
            Phrase.KindTrip -> R.string.kit_kind_trip
            Phrase.KindEvent -> R.string.kit_kind_event
            Phrase.NoticeOthers -> R.string.kit_notice_others
            Phrase.NoticeProblems -> R.string.kit_notice_problems
            Phrase.NoticeBadges -> R.string.kit_notice_badges
            Phrase.DailyPrompt -> R.string.kit_daily_prompt
            Phrase.AddedTo -> R.string.kit_added_to
            Phrase.AddedToBare -> R.string.kit_added_to_bare
            Phrase.AnAgent -> R.string.kit_an_agent
            Phrase.AnImport -> R.string.kit_an_import
            Phrase.AnotherToken -> R.string.kit_another_token
            Phrase.AConnection -> R.string.kit_a_connection
            Phrase.NoteAddedBody -> R.string.kit_note_added_body
            Phrase.ImportBody -> R.string.kit_import_body
            Phrase.MailFiledBody -> R.string.kit_mail_filed_body
            Phrase.MailNotFiledTitle -> R.string.kit_mail_not_filed_title
            Phrase.MailNotFiledBody -> R.string.kit_mail_not_filed_body
            Phrase.BadgeFallbackBody -> R.string.kit_badge_fallback_body
            Phrase.EmailStoppedTitle -> R.string.kit_email_stopped_title
            Phrase.EmailStoppedBody -> R.string.kit_email_stopped_body
            Phrase.ConnectionEndedTitle -> R.string.kit_connection_ended_title
            Phrase.ConnectionEndedBody -> R.string.kit_connection_ended_body
            Phrase.SummaryOthers -> R.string.kit_summary_others
            Phrase.SummaryProblems -> R.string.kit_summary_problems
            Phrase.SummaryBadges -> R.string.kit_summary_badges
            Phrase.SummaryBody -> R.string.kit_summary_body
            Phrase.UploadAddressBad -> R.string.kit_upload_address_bad
            Phrase.UploadFailed -> R.string.kit_upload_failed
            Phrase.NetworkDown -> R.string.kit_network_down
            Phrase.ServerAddressBad -> R.string.kit_server_address_bad
            Phrase.AnswerUnreadable -> R.string.kit_answer_unreadable
            Phrase.FileMissing -> R.string.kit_file_missing
            Phrase.ServerUnavailable -> R.string.kit_server_unavailable
            Phrase.ServerRefused -> R.string.kit_server_refused
            Phrase.NoteNotKept -> R.string.kit_note_not_kept
            Phrase.ProblemTitle -> R.string.kit_problem_title
            Phrase.ProblemTitleTooLong -> R.string.kit_problem_title_too_long
            Phrase.ProblemYear -> R.string.kit_problem_year
            Phrase.ProblemEnd -> R.string.kit_problem_end
            Phrase.ProblemPlace -> R.string.kit_problem_place
            Phrase.ProblemReminders -> R.string.kit_problem_reminders
            Phrase.Lived -> R.string.kit_lived
            Phrase.LastedMinutes -> R.string.kit_lasted_minutes
            Phrase.LastedHours -> R.string.kit_lasted_hours
            Phrase.LastedHoursMinutes -> R.string.kit_lasted_hours_minutes
            Phrase.StayRange -> R.string.kit_stay_range
            Phrase.StayAt -> R.string.kit_stay_at
            Phrase.StaySince -> R.string.kit_stay_since
            Phrase.StayUntil -> R.string.kit_stay_until
            Phrase.TagMany -> R.string.kit_tag_many
            Phrase.TagBreak -> R.string.kit_tag_break
            Phrase.TagComma -> R.string.kit_tag_comma
            Phrase.TagLong -> R.string.kit_tag_long
            Phrase.TagEmpty -> R.string.kit_tag_empty
            Phrase.TagsNeedWords -> R.string.kit_tags_need_words
        },
        *args.toTypedArray(),
    )

    override fun count(phrase: CountPhrase, n: Int): String = ChangelogApp.words.resources.getQuantityString(
        when (phrase) {
            CountPhrase.PhotoCount -> R.plurals.kit_photo_count
            CountPhrase.MemoCount -> R.plurals.kit_memo_count
            CountPhrase.FileCount -> R.plurals.kit_file_count
            CountPhrase.CheckInsWent -> R.plurals.kit_check_ins_went
            CountPhrase.CheckInsTagged -> R.plurals.kit_check_ins_tagged
        },
        n,
        n,
    )
}
