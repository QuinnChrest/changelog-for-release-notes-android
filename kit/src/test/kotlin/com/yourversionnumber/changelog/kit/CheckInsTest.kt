package com.yourversionnumber.changelog.kit

import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The map's pins and a stay's words, as the iPhone app's `CheckInsTests` has them. */
class CheckInsTest {
    init {
        English.use()
        Locale.setDefault(Locale.US)
    }

    private fun stay(
        id: String,
        label: String,
        lat: Double,
        lon: Double,
        place: String? = null,
        arrived: String? = null,
        left: String? = null,
        source: String = "visit",
    ) = CheckIn(
        id = id, at = arrived ?: "2026-11-07T15:00:00Z", arrived = arrived, left = left, lat = lat, lon = lon,
        timeZone = "America/Chicago", day = "2026-11-07", source = source, label = label, place = place,
    )

    @Test
    fun staysShareAPinAtOneRememberedPlaceOrOneNameNearby() {
        val pins = CheckInMap.pins(
            listOf(
                stay("a", "Home", 44.9800, -93.2600, place = "p1"),
                stay("b", "Home", 44.9900, -93.2700, place = "p1"), // a remembered place, wherever the spot
                stay("c", "Café", 44.9700, -93.2500),
                stay("d", "Café", 44.9704, -93.2504), // the same name about 50 m on
                stay("e", "Bakery", 44.9705, -93.2505), // next door, another name
                stay("f", "Café", 44.9900, -93.2500), // the same name, 2 km off
            ),
        )
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d"), listOf("e"), listOf("f")), pins.map { p -> p.stays.map { it.id } })
        assertEquals("a", pins[0].id)
    }

    @Test
    fun metresAlongTheGround() {
        // A degree of latitude is about 111 km.
        assertEquals(111_195.0, CheckInMap.metres(0.0, 0.0, 1.0, 0.0), 1.0)
    }

    @Test
    fun aStaySaysWhatIsKnownOfIt() {
        val chicago = ZoneId.of("America/Chicago")
        assertEquals(
            "9:05 AM to 10:15 AM, 70 min",
            CheckInWords.stay(stay("a", "x", 0.0, 0.0, arrived = "2026-11-07T15:05:00Z", left = "2026-11-07T16:15:00Z"), chicago),
        )
        assertEquals(
            "11:00 PM to Nov 8, 2:05 AM, 3 hr 5 min",
            CheckInWords.stay(stay("a", "x", 0.0, 0.0, arrived = "2026-11-08T05:00:00Z", left = "2026-11-08T08:05:00Z"), chicago),
        )
        assertEquals("Since 9:05 AM", CheckInWords.stay(stay("a", "x", 0.0, 0.0, arrived = "2026-11-07T15:05:00Z"), chicago))
        assertEquals("At 9:05 AM", CheckInWords.stay(stay("a", "x", 0.0, 0.0, arrived = "2026-11-07T15:05:00Z", source = "tap"), chicago))
        assertEquals("Until 10:15 AM", CheckInWords.stay(stay("a", "x", 0.0, 0.0, left = "2026-11-07T16:15:00Z"), chicago))
        assertNull(CheckInWords.stay(stay("a", "x", 0.0, 0.0), chicago))
        assertEquals("2 hr", CheckInWords.lasted(120))
    }
}
