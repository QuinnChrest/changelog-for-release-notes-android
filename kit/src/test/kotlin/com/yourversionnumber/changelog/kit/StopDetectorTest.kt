package com.yourversionnumber.changelog.kit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stops found in background fixes, and notes gathered into pins. */
class StopDetectorTest {
    private val zone = "America/Chicago"
    private val start = Instant.parse("2026-11-07T14:00:00Z")

    // About 0.0009° of latitude is 100 m.
    private fun fix(minutes: Long, lat: Double = 44.9778, lon: Double = -93.2650, accuracy: Double? = 20.0) =
        Fix(lat, lon, accuracy, start.plusSeconds(minutes * 60))

    /** Runs the fixes through a new detector, answering every report in order. */
    private fun run(vararg fixes: Fix): Pair<StopDetector, List<CheckInReport>> {
        var d = StopDetector()
        val all = mutableListOf<CheckInReport>()
        for (f in fixes) {
            val (next, reports) = d.next(f, zone)
            d = next
            all += reports
        }
        return d to all
    }

    @Test
    fun aStopIsReportedOnceAfterTenMinutesThenEndsWhenThePhoneMovesOn() {
        val (_, reports) = run(fix(0), fix(5), fix(10), fix(15), fix(20), fix(25, lat = 44.9900))
        assertEquals(2, reports.size)
        val (arrival, departure) = reports
        assertEquals("2026-11-07T14:00:00Z", arrival.arrived)
        assertNull(arrival.left)
        assertEquals("visit", arrival.source)
        assertEquals(zone, arrival.timeZone)
        // The same arrival to the second, so the server fills in the same stay.
        assertEquals(arrival.arrived, departure.arrived)
        assertEquals("2026-11-07T14:20:00Z", departure.left)
    }

    @Test
    fun passingThroughIsNoStop() {
        val (_, reports) = run(fix(0), fix(5), fix(8, lat = 44.9900), fix(12, lat = 45.0100))
        assertTrue(reports.isEmpty())
    }

    @Test
    fun aVagueFixNeitherMovesNorEndsAStop() {
        val (d, reports) = run(fix(0), fix(11), fix(14, lat = 45.1, accuracy = 900.0))
        assertEquals(1, reports.size)
        assertTrue(d.reported)
        assertEquals(44.9778, d.lat)
    }

    @Test
    fun driftWithinAHundredMetresIsTheSamePlaceAndTheSharpestFixNamesIt() {
        val (d, reports) = run(fix(0, accuracy = 60.0), fix(4, lat = 44.9783, accuracy = 8.0), fix(10, lat = 44.9775))
        assertEquals(1, reports.size)
        assertEquals(44.9783, reports.single().lat)
        assertEquals(8.0, d.accuracyM)
    }

    @Test
    fun aLateFixFromAnotherSourceSaysNothing() {
        // At the stop for twelve minutes; then a fix from five minutes in, from elsewhere, arrives late.
        val (d, reports) = run(fix(0), fix(12), fix(5, lat = 44.9900))
        assertEquals(1, reports.size)
        assertTrue(d.reported)
        assertEquals(44.9778, d.lat)
    }

    @Test
    fun aStopAtHomeIsNeverSentButOneElsewhereIs() {
        val home = Spot(44.9778, -93.2650)
        // A stop at home (and its end), then one 2 km away.
        val (_, reports) = run(fix(0), fix(12), fix(20, lat = 44.9960), fix(32, lat = 44.9960), fix(40, lat = 45.0100))
        assertEquals(4, reports.size)
        val sent = QuietPlaces.keep(reports, listOf(home))
        assertEquals(listOf(44.9960, 44.9960), sent.map { it.lat })
        // 120 m off is still home; 200 m off isn't.
        val near = reports[0].copy(lat = 44.9789)
        val far = reports[0].copy(lat = 44.9796)
        assertEquals(listOf(far), QuietPlaces.keep(listOf(near, far), listOf(home)))
        // Nothing quiet set: everything goes.
        assertEquals(reports, QuietPlaces.keep(reports, emptyList()))
    }

    @Test
    fun aStopReportsOnceHoweverLongItLasts() {
        // Five hours at one spot, a fix every five minutes, a little drift: one arrival, nothing more until leaving.
        val stay = (0..60L).map { fix(it * 5, lat = 44.9778 + (it % 3) * 0.0002) }
        val (d, reports) = run(*stay.toTypedArray())
        assertEquals(1, reports.size)
        assertNull(reports.single().left)
        assertTrue(d.reported)
    }

    @Test
    fun theStateKeepsAsJson() {
        val (d, _) = run(fix(0), fix(11))
        assertEquals(d, Api.json.decodeFromString(StopDetector.serializer(), Api.json.encodeToString(StopDetector.serializer(), d)))
    }

    @Test
    fun notesGatherUnderOnePlaceNearby() {
        fun pin(id: String, date: String, place: String, lat: Double) = MapPin(date, id, lat, -93.265, place)
        val pins = NotePin.pins(
            listOf(
                pin("a", "2026-11-01", "Café", 44.9778),
                pin("b", "2026-11-05", "Café", 44.9780),
                pin("c", "2026-11-03", "Library", 44.9779),
                pin("d", "2026-11-02", "Café", 44.9900),
            ),
        )
        assertEquals(listOf(listOf("b", "a"), listOf("c"), listOf("d")), pins.map { p -> p.notes.map { it.id } })
    }
}
