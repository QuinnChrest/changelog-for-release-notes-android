package com.yourversionnumber.changelog.kit

import kotlinx.serialization.json.JsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpcomingTest {
    private val today = "2026-10-29"
    private fun problem(r: Result<*>) = (r.exceptionOrNull() as UpcomingDraft.Refused).problem

    @Test
    fun aBirthdayIsAMonthAndDayWithItsYear() {
        val draft = UpcomingDraft.new(today).copy(title = "Mom", month = 11, day = 5, year = "1956")
        val f = draft.fields().getOrThrow()
        assertEquals("11-05", f.date)
        assertEquals(1956, f.year)
        assertEquals(listOf(7, 1, 0), f.reminders)
        assertEquals(UpcomingKind.Birthday, f.kind)
    }

    @Test
    fun leapDayBirthdaysKeepTheTwentyNinth() {
        assertEquals(29, UpcomingDraft.daysIn(2))
        val d = UpcomingDraft.new(today).copy(title = "Leap", day = 31).withMonth(2)
        assertEquals(29, d.day)
    }

    @Test
    fun aTripNeedsADestinationAndAnOrderlyEnd() {
        val trip = UpcomingDraft.new(today, UpcomingKind.Trip).copy(title = "Lake weekend", start = "2026-11-06", end = "2026-11-08")
        assertEquals(UpcomingDraft.Problem.Place, problem(trip.fields()))
        assertEquals(UpcomingDraft.Problem.End, problem(trip.copy(place = "Duluth", end = "2026-11-01").fields()))
        val f = trip.copy(place = " Duluth ").fields().getOrThrow()
        assertEquals("2026-11-06", f.date)
        assertEquals("2026-11-08", f.endDate)
        assertEquals("Duluth", f.place)
        assertEquals(listOf(14, 1, 0), f.reminders)
    }

    @Test
    fun theProblemsTheOwnerCanFix() {
        val d = UpcomingDraft.new(today).copy(title = "Mom")
        assertEquals(UpcomingDraft.Problem.Title, problem(d.copy(title = "  ").fields()))
        assertEquals(UpcomingDraft.Problem.Title, problem(d.copy(title = "Two\nlines").fields()))
        assertEquals(UpcomingDraft.Problem.TitleTooLong, problem(d.copy(title = "x".repeat(121)).fields()))
        assertEquals(UpcomingDraft.Problem.Year, problem(d.copy(year = "2027").fields()))
        assertEquals(UpcomingDraft.Problem.Tag, problem(d.copy(tag = "#2026").fields()))
        assertEquals(UpcomingDraft.Problem.Reminders, problem(d.copy(reminders = emptyList()).fields()))
        assertEquals("family", d.copy(tag = "#Family").fields().getOrThrow().tag)
    }

    @Test
    fun switchingKindCarriesDefaultRemindersAlongButNotChosenOnes() {
        val d = UpcomingDraft.new(today)
        assertEquals(listOf(14, 1, 0), d.withKind(UpcomingKind.Trip).reminders)
        assertEquals(listOf(30), d.copy(reminders = listOf(30)).withKind(UpcomingKind.Trip).reminders)
    }

    @Test
    fun anEntryComesBackIntoADraft() {
        val e = UpcomingEntry(
            id = "u1", revision = 3, next = "2026-11-06", through = "2026-11-08", line = "Lake weekend.",
            kind = UpcomingKind.Trip, title = "Lake weekend", date = "2026-11-06", endDate = "2026-11-08",
            place = "Minneapolis, Minnesota, United States", reminders = listOf(14, 1, 0),
        )
        val d = UpcomingDraft.of(e, today)
        assertEquals("2026-11-08", d.end)
        val batch = d.batch(editing = e).getOrThrow()
        assertEquals(3, batch.change!!.single().revision)
        assertNull(batch.add)
    }

    @Test
    fun aBatchSendsOnlyWhatItHolds() {
        val f = UpcomingFields(UpcomingKind.Event, "Recital", "2026-12-01")
        assertEquals(
            """{"add":[{"kind":"event","title":"Recital","date":"2026-12-01","reminders":[7,1,0],"email":true}]}""",
            Api.json.encodeToString(UpcomingBatch.serializer(), UpcomingBatch.adding(f)),
        )
        assertTrue(Api.json.encodeToString(UpcomingBatch.serializer(), UpcomingBatch.adding(f, dryRun = true)).endsWith(""","dry_run":true}"""))
    }

    @Test
    fun aListLeavesOutKindsItDoesntKnow() {
        val body = """{"today":"2026-10-29","entries":[
            {"id":"a","revision":1,"next":"2026-11-05","status":"upcoming","line":"Mom turns 7.0.0.","kind":"birthday","title":"Mom","date":"11-05","reminders":[7,1,0],"email":true},
            {"id":"b","revision":1,"next":"2026-11-07","status":"upcoming","line":"?","kind":"holiday","title":"?","date":"11-07"}
            ],"past_left_out":2,"max_entries":200}"""
        val list = UpcomingList.from(Api.json.decodeFromString(JsonObject.serializer(), body))
        assertEquals(listOf("a"), list.entries.map { it.id })
        assertEquals(1, list.unreadable)
        assertEquals(2, list.pastLeftOut)
    }

    @Test
    fun aRefusalReadsWithoutItsEntryName() {
        val e = ApiException(400, "invalid_request", "A trip needs a destination the city search knows. (add[0])", field = "add[0]")
        assertEquals("A trip needs a destination the city search knows.", e.upcomingSentence)
    }

    @Test
    fun aRequestKeyHoldsForTheSameRequestOnly() {
        val keys = RequestKey<String>()
        var n = 0
        val make = { "k${++n}" }
        assertEquals("k1", keys.key("save A", make))
        assertEquals("k1", keys.key("save A", make))
        assertEquals("k2", keys.key("save B", make))
        keys.reset()
        assertNotEquals("k2", keys.key("save B", make))
    }

    @Test
    fun remindersComeAtTheirMorningsSendTimeAndOnlyAhead() {
        val reminders = Reminders(
            from = "2026-10-29", through = "2026-11-11",
            days = listOf(
                ReminderDay("2026-10-29", "2026-10-29T06:30:00-05:00", listOf(Reminder("u0", "2026-11-05", "In 7 days", "Gone by now."))),
                ReminderDay("2026-11-04", "2026-11-04T06:30:00-06:00", listOf(
                    Reminder("u2", "2026-11-05", "Tomorrow", "Mom turns 7.0.0. A major release."),
                    Reminder("u1", "2026-11-06", "In 2 days", "Lake weekend."),
                )),
            ),
        )
        val now = Instant.parse("2026-10-29T18:00:00Z")
        val plan = ReminderPlan.plan(reminders, now)
        assertEquals(listOf("upcoming-reminder.2026-11-04.u1", "upcoming-reminder.2026-11-04.u2"), plan.map { it.id })
        // After the clocks change: CST, six hours behind.
        assertEquals(Instant.parse("2026-11-04T12:30:00Z"), plan.first().fireAt)
        assertEquals("In 2 days", plan.first().`when`)
    }
}
