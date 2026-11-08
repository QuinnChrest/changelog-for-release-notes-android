package com.yourversionnumber.changelog

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import androidx.core.content.ContextCompat
import com.yourversionnumber.changelog.kit.Api
import com.yourversionnumber.changelog.kit.CheckInReport
import com.yourversionnumber.changelog.kit.Fix
import com.yourversionnumber.changelog.kit.OutboxNotKept
import com.yourversionnumber.changelog.kit.OutboxOperation
import com.yourversionnumber.changelog.kit.StopDetector
import com.yourversionnumber.changelog.kit.Timestamp
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Check-ins from this phone (API 1.18.0), as the iPhone app sends them.
 *
 * - **Check in now** ([tap]): one fix while the app is open, sent as a `tap`.
 * - **Recording stops** ([start]), off until the owner turns it on in Settings: Android's own location service
 *   (no Play services) wakes [StopReceiver] with a fix every few minutes, in the background, and the kit's
 *   [StopDetector] turns them into stops: an arrival once the phone has stayed ten minutes, its end once it moves
 *   on. It needs location "all the time", asked for only when the switch goes on.
 *
 * Every report goes through the Outbox with its own key, so a bad connection loses nothing and a resend is the same
 * stop. Spots are never logged; the detector's state (one spot) is kept in no-backup storage.
 */
object Stops {
    private const val EVERY_MS = 5 * 60 * 1000L
    private val lock = Mutex()

    fun allowedInBackground(context: Context): Boolean =
        Locator.allowed(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Where fixes come from, each its own request to the same receiver:
     * - the phone's fused provider, at balanced power (on a phone, mostly Wi-Fi and cell towers), every five minutes;
     * - the passive provider, which costs nothing: any fix another app or the system got anyway;
     * - GPS, every fifteen minutes, for where the fused provider has nothing to go on (no Wi-Fi or cell to read,
     *   as on an emulator, or a phone without Google's fused provider).
     * Android holds a background app to a few fixes an hour whatever is asked; together these give the detector
     * enough to tell a ten-minute stop.
     */
    private data class Source(val provider: String, val code: Int, val every: Long, val quality: Int)

    private val sources = listOf(
        Source(LocationManager.FUSED_PROVIDER, 1, EVERY_MS, LocationRequest.QUALITY_BALANCED_POWER_ACCURACY),
        Source(LocationManager.PASSIVE_PROVIDER, 2, EVERY_MS, LocationRequest.QUALITY_LOW_POWER),
        Source(LocationManager.GPS_PROVIDER, 3, EVERY_MS * 3, LocationRequest.QUALITY_BALANCED_POWER_ACCURACY),
    )

    private fun pending(context: Context, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        code,
        Intent(context, StopReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /**
     * Starts (or starts again) recording, if it's on, signed in and allowed. Called when the switch goes on, as the
     * app opens, and after a restart or an update, since Android forgets a request then. Answers whether any
     * source took the request.
     */
    @SuppressLint("MissingPermission") // allowedInBackground() is checked first.
    fun start(context: Context): Boolean {
        val graph = context.graph
        if (!graph.recordingStops || !graph.signedIn.value || !allowedInBackground(context)) return false
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        val providers = runCatching { manager.allProviders }.getOrDefault(emptyList())
        return sources.count { source ->
            if (source.provider !in providers) return@count false
            val request = LocationRequest.Builder(source.every)
                // No distance floor: a phone sitting still must still hear fixes, or a stop never reaches ten minutes.
                .setMinUpdateDistanceMeters(0f)
                .setQuality(source.quality)
                .setMaxUpdateDelayMillis(source.every * 3)
                .build()
            runCatching { manager.requestLocationUpdates(source.provider, request, pending(context, source.code)) }.isSuccess
        } > 0
    }

    /** Stops recording and forgets the stop in progress (signing out, or the switch off). Sent stops stay. */
    fun stop(context: Context) {
        context.getSystemService(LocationManager::class.java)?.let { manager ->
            // 0 is the one request of an earlier build.
            for (code in listOf(0) + sources.map { it.code }) runCatching { manager.removeUpdates(pending(context, code)) }
        }
        stateFile(context).delete()
    }

    /** Fixes from the background, oldest first, into stops: each one found goes in the Outbox. */
    suspend fun heard(context: Context, locations: List<Location>) = lock.withLock {
        val graph = context.graph
        if (!graph.recordingStops || !graph.signedIn.value) return@withLock
        val zone = ZoneId.systemDefault().id
        var detector = load(context)
        val reports = mutableListOf<CheckInReport>()
        for (l in locations.sortedBy { it.time }) {
            val (next, found) = detector.next(Fix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy.toDouble() else null, Instant.ofEpochMilli(l.time)), zone)
            detector = next
            reports += found
        }
        save(context, detector)
        if (reports.isEmpty()) return@withLock
        try {
            reports.forEach { graph.outbox.enqueue(OutboxOperation.CheckIn(it)) }
            graph.send()
        } catch (e: OutboxNotKept) {
            // Nothing to show from the background; the next fix tries again from the state kept.
        }
    }

    /**
     * Check in now: one fix, sent as a `tap` at this moment through the Outbox. Answers null once queued, or the
     * sentence to show.
     */
    suspend fun tap(context: Context): String? {
        if (!Locator.allowed(context)) return say(R.string.changelog_cant_check_in_without)
        val spot = Locator.fix(context) ?: return say(R.string.this_phone_couldnt_say_where)
        val report = CheckInReport(
            arrived = Timestamp.format(java.time.OffsetDateTime.now()),
            lat = spot.lat,
            lon = spot.lon,
            accuracyM = spot.accuracyM,
            timeZone = ZoneId.systemDefault().id,
            source = "tap",
        )
        return try {
            context.graph.outbox.enqueue(OutboxOperation.CheckIn(report))
            context.graph.send()
            null
        } catch (e: OutboxNotKept) {
            e.message
        }
    }

    private fun stateFile(context: Context) = File(context.noBackupFilesDir, "stops.json")

    private fun load(context: Context): StopDetector =
        runCatching { Api.json.decodeFromString(StopDetector.serializer(), stateFile(context).readText()) }.getOrDefault(StopDetector())

    private fun save(context: Context, detector: StopDetector) {
        runCatching { stateFile(context).writeText(Api.json.encodeToString(StopDetector.serializer(), detector)) }
    }
}

/** Android's location service, with fixes for [Stops]. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val locations: List<Location> = intent.getParcelableArrayListExtra<Location>(LocationManager.KEY_LOCATIONS)
            ?: listOfNotNull(intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED) as? Location)
        if (locations.isEmpty()) return
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Stops.heard(context.applicationContext, locations)
            } finally {
                done.finish()
            }
        }
    }
}
