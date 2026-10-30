package com.yourversionnumber.changelog

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yourversionnumber.changelog.kit.ApiException
import com.yourversionnumber.changelog.kit.PlannedReminder
import com.yourversionnumber.changelog.kit.ReminderPlan
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * This phone's own Upcoming reminders, with no push server: each is
 * scheduled ahead from the server's list (`GET /v1/upcoming/reminders`), to
 * come when that morning's email is due, in its words. Off until the owner
 * turns it on. Everything scheduled is replaced with each read; a read that
 * fails leaves what was scheduled in place. Read again on opening the app,
 * after a date is saved, and twice a day in the background.
 *
 * Each reminder is an inexact alarm with a ten-minute window: it needs no
 * exact-alarm permission, and unlike a background job it isn't held back for
 * hours while the phone waits to charge. Alarms don't outlive a restart, so
 * the plan is kept, and set again when the phone starts.
 */
object PhoneReminders {
    const val CHANNEL = "upcoming"
    private const val REFRESH = "upcoming-refresh"
    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val ACTION = "com.yourversionnumber.changelog.REMINDER"

    @Serializable
    private data class Kept(val id: String, val at: Long, val `when`: String, val line: String)

    private val keptList = ListSerializer(Kept.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    /** Android 13 asks before an app may notify; before it, only the owner's own switch for the app counts. */
    fun allowed(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /** Reads the reminders and schedules them, when they're on and the token can read Upcoming. */
    suspend fun refresh(context: Context) {
        val graph = context.graph
        if (!graph.remindersOn) return
        val api = graph.api() ?: return
        val reminders = try {
            api.reminders()
        } catch (e: ApiException) {
            if (e.signedOut) graph.tokenLost(e.message)
            return
        }
        schedule(context, ReminderPlan.plan(reminders, Instant.now()))
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            REFRESH,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    /** Replaces everything scheduled with the plan, and keeps the plan for after a restart. */
    fun schedule(context: Context, plan: List<PlannedReminder>) {
        cancelAlarms(context)
        val kept = plan.map { Kept(it.id, it.fireAt.toEpochMilli(), it.`when`, it.line) }
        prefs(context).edit().putString("plan", json.encodeToString(keptList, kept)).apply()
        setAlarms(context, kept)
    }

    /** After a restart: the kept plan's reminders still ahead, set again. */
    fun restore(context: Context) {
        if (!context.graph.remindersOn) return
        setAlarms(context, kept(context))
    }

    /** Takes back every reminder this app scheduled, and stops reading for more. */
    fun clear(context: Context) {
        cancelAlarms(context)
        prefs(context).edit().remove("plan").apply()
        WorkManager.getInstance(context).cancelUniqueWork(REFRESH)
    }

    private fun setAlarms(context: Context, plan: List<Kept>) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        for (r in plan) {
            if (r.at <= now) continue
            alarms.setWindow(AlarmManager.RTC_WAKEUP, r.at, WINDOW_MS, pending(context, r, PendingIntent.FLAG_UPDATE_CURRENT)!!)
        }
    }

    private fun cancelAlarms(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        for (r in kept(context)) {
            pending(context, r, PendingIntent.FLAG_NO_CREATE)?.let {
                alarms.cancel(it)
                it.cancel()
            }
        }
    }

    private fun pending(context: Context, r: Kept, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        r.id.hashCode(),
        Intent(context, ReminderReceiver::class.java)
            .setAction("$ACTION.${r.id}")
            .putExtra("id", r.id)
            .putExtra("when", r.`when`)
            .putExtra("line", r.line),
        flag or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun kept(context: Context): List<Kept> =
        prefs(context).getString("plan", null)?.let { runCatching { json.decodeFromString(keptList, it) }.getOrNull() }.orEmpty()

    private fun prefs(context: Context) = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun channel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Upcoming reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Birthdays, anniversaries, trips and events, when each morning’s email is due."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // allowed() is the permission check, first thing.
    @SuppressLint("MissingPermission")
    fun show(context: Context, id: String, `when`: String, line: String) {
        if (!allowed(context) || !context.graph.remindersOn) return
        channel(context)
        val open = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.OPEN, "upcoming").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(`when`)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setSubText("Upcoming")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id.hashCode(), notification) }
    }
}

/** One Upcoming reminder, when its morning's email is due. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        PhoneReminders.show(context, id, intent.getStringExtra("when").orEmpty(), intent.getStringExtra("line").orEmpty())
    }
}

/** Alarms don't outlive a restart or an update: set the kept plan again. */
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            PhoneReminders.restore(context)
        }
    }
}

/** Reads the reminders again in the background, so a date changed on the web still reminds right. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        PhoneReminders.refresh(applicationContext)
        return Result.success()
    }
}
