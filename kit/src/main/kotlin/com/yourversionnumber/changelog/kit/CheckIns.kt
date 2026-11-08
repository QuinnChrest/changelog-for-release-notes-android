package com.yourversionnumber.changelog.kit

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Check-ins (API 1.18.0): the places the account stopped, as the iPhone app
// sends them. This app only reads them (`GET /v1/check-ins`, the `activity`
// scope): sending them would need background location, which it doesn't ask
// for. A stay's name and spot are the owner's: never logged, never in a notice.

/** A stay: one check-in, or back-to-back ones at one place. */
@Serializable
data class CheckIn(
    /** Its first check-in's id. */
    val id: String,
    val ids: List<String> = emptyList(),
    /** When it began, or was first heard of. */
    val at: String,
    val arrived: String? = null,
    /** Null while still there, and for a tap. */
    val left: String? = null,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double? = null,
    /** The zone there, for its times. */
    val timeZone: String? = null,
    val day: String,
    /** visit or tap. */
    val source: String = "visit",
    /** The name it shows. */
    val label: String,
    val namedBy: String? = null,
    /** The remembered place naming it. */
    val place: String? = null,
    val name: String? = null,
    val district: String? = null,
    val revision: Int = 0,
)

/** `GET /v1/check-ins`: newest first; `next` is the `before` of the next older page. */
@Serializable
data class CheckInPage(val items: List<CheckIn> = emptyList(), val next: String? = null)

/** One pin on the map: every stay at one place, newest first, so a place visited each day is one pin. */
data class CheckInPin(val stays: List<CheckIn>) {
    val id: String get() = stays[0].id
    val label: String get() = stays[0].label
    val lat: Double get() = stays[0].lat
    val lon: Double get() = stays[0].lon
}

/** The iPhone app's `CheckInMap`: stays gathered into pins. */
object CheckInMap {
    /** How near counts as the same place, as on the server (`SAME_PLACE_METRES`, packages/domain/check-ins.ts). */
    const val SAME_PLACE_METRES = 100.0

    /**
     * Stays as `/v1` lists them, newest first, gathered into pins. Stays share a pin at one remembered place,
     * or under one name within 100 m. Two real shops next door keep their own, as two stops do in Activity.
     */
    fun pins(stays: List<CheckIn>): List<CheckInPin> {
        val pins = mutableListOf<MutableList<CheckIn>>()
        val remembered = mutableMapOf<String, Int>()
        for (stay in stays) {
            val found = if (stay.place != null) {
                remembered[stay.place]
            } else {
                pins.indexOfFirst { pin ->
                    val first = pin[0]
                    first.place == null && first.label == stay.label && metres(first.lat, first.lon, stay.lat, stay.lon) <= SAME_PLACE_METRES
                }.takeIf { it >= 0 }
            }
            if (found != null) {
                pins[found] += stay
            } else {
                pins += mutableListOf(stay)
                stay.place?.let { remembered[it] = pins.size - 1 }
            }
        }
        return pins.map { CheckInPin(it.toList()) }
    }

    /** Metres between two spots, along the ground, as the server works it out. */
    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = Math.PI / 180
        val dLat = (lat2 - lat1) * r
        val dLon = (lon2 - lon1) * r
        val h = sin(dLat / 2).pow(2) + cos(lat1 * r) * cos(lat2 * r) * sin(dLon / 2).pow(2)
        return 2 * 6_371_000 * asin(min(1.0, sqrt(h)))
    }
}

/** How a stay reads, as the iPhone app's `CheckInWords`. */
object CheckInWords {
    /** "70 min", and from two hours "3 hr 5 min". */
    fun lasted(minutes: Long): String = when {
        minutes < 120 -> Words.say(Phrase.LastedMinutes, minutes)
        minutes % 60 == 0L -> Words.say(Phrase.LastedHours, minutes / 60)
        else -> Words.say(Phrase.LastedHoursMinutes, minutes / 60, minutes % 60)
    }

    /**
     * What is known of a stay, in the zone it was in: "9:05 AM to 10:15 AM, 70 min", the day too when it ends
     * on another; "Since 9:05 AM" while it goes on, "At 9:05 AM" for a tap. Null with no time at all.
     */
    fun stay(stay: CheckIn, fallback: ZoneId = ZoneId.systemDefault()): String? {
        val zone = stay.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallback
        val from = stay.arrived?.let(::parse)?.atZoneSameInstant(zone)
        val to = stay.left?.let(::parse)?.atZoneSameInstant(zone)
        val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        val dayOf = DateTimeFormatter.ofPattern("MMM d")
        return when {
            from != null && to != null -> {
                val minutes = Math.round(Duration.between(from, to).seconds / 60.0)
                val end = (if (from.toLocalDate() != to.toLocalDate()) to.format(dayOf) + ", " else "") + to.format(clock)
                Words.say(Phrase.StayRange, from.format(clock), end, lasted(minutes))
            }
            from != null -> Words.say(if (stay.source == "tap") Phrase.StayAt else Phrase.StaySince, from.format(clock))
            to != null -> Words.say(Phrase.StayUntil, to.format(clock))
            else -> null
        }
    }

    private fun parse(text: String): OffsetDateTime? = runCatching { OffsetDateTime.parse(text) }.getOrNull()
}

/**
 * `POST /v1/check-ins`: a stop as the phone is told of it. A later report with the same `source` and `arrived`,
 * to the second, is the same stay: it fills in `left`. A `tap` sends the moment it was tapped as `arrived`.
 */
@Serializable
data class CheckInReport(
    val arrived: String? = null,
    val left: String? = null,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double? = null,
    /** The IANA zone the phone is in. */
    val timeZone: String? = null,
    /** visit (the phone noticed a stop) or tap (the owner asked). */
    val source: String,
)

/** `POST /v1/check-ins`'s answer. */
@Serializable
data class CheckInWritten(val checkIn: CheckIn)

/** One location fix, as the phone's location service reports it in the background. */
data class Fix(val lat: Double, val lon: Double, val accuracyM: Double?, val at: java.time.Instant)

/**
 * Finds stops in a stream of background fixes, as iOS's visits do for the iPhone app: Android has no such service
 * without Play services. A stop is the phone staying within [RADIUS_METRES] of one spot for [DWELL] or more.
 * Once it has, its arrival is reported (no `left` yet); when a fix lands well away, its departure is reported with
 * the same `arrived`, which the server takes as the same stay. Fixes vaguer than [WORST_ACCURACY], and fixes
 * older than the last one heard (another source's, late), are passed over.
 * Plain state, kept by the app as JSON between fixes; it holds a spot, so it never leaves the phone or is logged.
 */
@Serializable
data class StopDetector(
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Double? = null,
    /** When the phone came to this spot (epoch seconds). */
    val since: Long? = null,
    /** The last fix still at this spot (epoch seconds). */
    val lastSeen: Long? = null,
    /** Whether the arrival here has been reported. */
    val reported: Boolean = false,
) {
    /** The detector after this fix, and the reports to send for it (none, an arrival, or a departure). */
    fun next(fix: Fix, zone: String): Pair<StopDetector, List<CheckInReport>> {
        val now = fix.at.epochSecond
        if ((fix.accuracyM ?: 0.0) > WORST_ACCURACY) return this to emptyList()
        // Fixes come from more than one source, so one can arrive after a newer one: it says nothing new.
        if (lastSeen != null && now < lastSeen) return this to emptyList()
        if (lat == null || lon == null || since == null) return fresh(fix) to emptyList()
        val away = CheckInMap.metres(lat, lon, fix.lat, fix.lon)
        val reach = maxOf(RADIUS_METRES, fix.accuracyM ?: 0.0)
        if (away <= reach) {
            // Still here: the clearest fix names the spot.
            val sharper = fix.accuracyM != null && (accuracyM == null || fix.accuracyM < accuracyM)
            val stayed = copy(
                lat = if (sharper) fix.lat else lat,
                lon = if (sharper) fix.lon else lon,
                accuracyM = if (sharper) fix.accuracyM else accuracyM,
                lastSeen = maxOf(now, lastSeen ?: now),
            )
            if (!reported && now - since >= DWELL.seconds) {
                return stayed.copy(reported = true) to listOf(stayed.report(zone, left = null))
            }
            return stayed to emptyList()
        }
        // Moved on: a stop that was reported ends at the last fix seen there.
        val reports = if (reported) listOf(report(zone, left = maxOf(lastSeen ?: now, since))) else emptyList()
        return fresh(fix) to reports
    }

    private fun fresh(fix: Fix) = StopDetector(fix.lat, fix.lon, fix.accuracyM, fix.at.epochSecond, fix.at.epochSecond, false)

    private fun report(zone: String, left: Long?) = CheckInReport(
        arrived = stamp(since!!),
        left = left?.let(::stamp),
        lat = lat!!,
        lon = lon!!,
        accuracyM = accuracyM,
        timeZone = zone,
        source = "visit",
    )

    companion object {
        /** The server's same place (`SAME_PLACE_METRES`). */
        const val RADIUS_METRES = CheckInMap.SAME_PLACE_METRES
        val DWELL: Duration = Duration.ofMinutes(10)
        const val WORST_ACCURACY = 250.0

        private fun stamp(epochSecond: Long): String = java.time.Instant.ofEpochSecond(epochSecond).toString()
    }
}

/** One note with a place of its own, from `GET /v1/map/pins` (API 1.15.0). `text` is its opening words. */
@Serializable
data class MapPin(
    val date: String,
    val id: String,
    val lat: Double,
    val lon: Double,
    val place: String = "",
    /** False when a city was chosen: it stands for the city, not a spot. */
    val exact: Boolean = true,
    val version: String? = null,
    val text: String = "",
)

/** `GET /v1/map/pins`: oldest first; `next` is the `after` of the next page. */
@Serializable
data class MapPins(val notes: Int = 0, val pins: List<MapPin> = emptyList(), val next: String? = null)

/** Notes at one place on the map, newest first: a place written from often is one pin. */
data class NotePin(val notes: List<MapPin>) {
    val id: String get() = notes[0].id
    val label: String get() = notes[0].place
    val lat: Double get() = notes[0].lat
    val lon: Double get() = notes[0].lon

    companion object {
        /** Notes gathered as check-ins are: under one place name within 100 m. */
        fun pins(notes: List<MapPin>): List<NotePin> {
            val pins = mutableListOf<MutableList<MapPin>>()
            for (note in notes.sortedByDescending { it.date }) {
                val found = pins.firstOrNull { pin ->
                    pin[0].place == note.place && CheckInMap.metres(pin[0].lat, pin[0].lon, note.lat, note.lon) <= CheckInMap.SAME_PLACE_METRES
                }
                if (found != null) found += note else pins += mutableListOf(note)
            }
            return pins.map { NotePin(it.toList()) }
        }
    }
}

/**
 * Spots the owner keeps out of Record my stops (home): a stop found within [METRES] of one is never sent, its
 * arrival or its end. Wider than a stop's own 100 m, so drift inside a house doesn't leak out as a stop next door.
 * Check in now isn't filtered: a tap is the owner's own choice.
 */
object QuietPlaces {
    const val METRES = 150.0

    /** The reports to send: those not near any quiet spot. */
    fun keep(reports: List<CheckInReport>, quiet: List<Spot>): List<CheckInReport> =
        reports.filter { r -> quiet.none { CheckInMap.metres(it.lat, it.lon, r.lat, r.lon) <= METRES } }
}
