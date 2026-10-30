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
import androidx.annotation.StringRes
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
import com.yourversionnumber.changelog.kit.ActivityNotices
import com.yourversionnumber.changelog.kit.ApiException
import com.yourversionnumber.changelog.kit.DailyPrompt
import com.yourversionnumber.changelog.kit.ReminderPlan
import com.yourversionnumber.changelog.kit.Reminders
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * This phone's own notifications, with no push server, each off until the
 * owner turns it on in Settings:
 *
 * - Upcoming reminders, from `GET /v1/upcoming/reminders`, when each
 *   morning's email is due, in its words. Never worked out here.
 * - The daily prompt, "You're 4.5.137 today. What changed?", at the same
 *   moment, with the server's version for that day. Not today once
 *   something was written today.
 * - Activity notices: notes from elsewhere, problems and badges, read from
 *   `GET /v1/activity?after=` in the background, never with a note's words.
 *
 * Reminders and prompts are scheduled ahead, each an inexact alarm with a
 * ten-minute window: no exact-alarm permission, and not held for hours the
 * way a background job can be. Everything is replaced with each read; a
 * read that fails leaves what was scheduled. The plan is kept so it can be
 * set again after a restart. Read again on opening the app, after a date is
 * saved, and every 12 hours; activity is looked at every hour.
 */
object PhoneNotices {
    private const val REFRESH = "notices-refresh"
    private const val ACTIVITY = "notices-activity"
    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val ACTION = "com.yourversionnumber.changelog.NOTICE"

    /** Each kind has its own channel, so the owner can quiet one in Android's settings too. */
    enum class Channel(val id: String, @StringRes val title: Int, @StringRes val about: Int, val opens: String) {
        Reminder("upcoming", R.string.upcoming_reminders, R.string.birthdays_anniversaries_trips_and_events, "upcoming"),
        Prompt("prompt", R.string.daily_prompt, R.string.each_morning_your_version_for, "log"),
        Activity("activity", R.string.activity, R.string.notes_from_elsewhere_problems_and, "activity"),
    }

    @Serializable
    private data class Kept(val id: String, val at: Long, val channel: String, val title: String, val body: String)

    private val keptList = ListSerializer(Kept.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    /** Android 13 asks before an app may notify; before it, only the owner's own switch for the app counts. */
    fun allowed(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /** Reads what's needed and schedules the reminders and prompts that are on. */
    suspend fun refresh(context: Context) {
        val graph = context.graph
        val api = graph.api()
        if (api == null || (!graph.remindersOn && !graph.promptOn)) {
            cancelAlarms(context)
            prefs(context).edit().remove("plan").apply()
            WorkManager.getInstance(context).cancelUniqueWork(REFRESH)
            return
        }
        val hasUpcoming: Boolean
        val me = try {
            api.me().also { graph.me = it; hasUpcoming = "upcoming" in it.token.scopes }
        } catch (e: ApiException) {
            if (e.signedOut) graph.tokenLost(e.message)
            return
        }
        val reminders: Reminders? = if (hasUpcoming && graph.remindersOn) {
            try {
                api.reminders()
            } catch (e: ApiException) {
                if (e.signedOut) graph.tokenLost(e.message)
                return
            }
        } else {
            null
        }
        val now = Instant.now()
        val plan = (if (graph.remindersOn && reminders != null) ReminderPlan.plan(reminders, now) else emptyList())
            .map { Kept(it.id, it.fireAt.toEpochMilli(), Channel.Reminder.name, it.`when`, it.line) } +
            (if (graph.promptOn) DailyPrompt.plan(me, reminders, now) else emptyList())
                .map { Kept(it.id, it.fireAt.toEpochMilli(), Channel.Prompt.name, say(R.string.changelog), it.body) }
        schedule(context, plan)
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            REFRESH,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(12, TimeUnit.HOURS).setConstraints(online).build(),
        )
    }

    /** Takes back one waiting notification: today's prompt, once something was written today. */
    fun takeBack(context: Context, id: String) {
        val kept = kept(context)
        val gone = kept.filter { it.id == id }
        if (gone.isEmpty()) return
        cancelAlarms(context, gone)
        save(context, kept - gone.toSet())
    }

    /** After a restart: the kept plan's reminders and prompts still ahead, set again. */
    fun restore(context: Context) = setAlarms(context, kept(context))

    /** Takes back everything this app scheduled and stops looking. */
    fun clear(context: Context) {
        cancelAlarms(context)
        prefs(context).edit().clear().apply()
        WorkManager.getInstance(context).cancelUniqueWork(REFRESH)
        WorkManager.getInstance(context).cancelUniqueWork(ACTIVITY)
    }

    private fun schedule(context: Context, plan: List<Kept>) {
        cancelAlarms(context)
        save(context, plan)
        setAlarms(context, plan)
    }

    private fun save(context: Context, plan: List<Kept>) =
        prefs(context).edit().putString("plan", json.encodeToString(keptList, plan)).apply()

    private fun setAlarms(context: Context, plan: List<Kept>) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        for (n in plan) {
            if (n.at <= now) continue
            alarms.setWindow(AlarmManager.RTC_WAKEUP, n.at, WINDOW_MS, pending(context, n, PendingIntent.FLAG_UPDATE_CURRENT)!!)
        }
    }

    private fun cancelAlarms(context: Context, which: List<Kept> = kept(context)) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        for (n in which) {
            pending(context, n, PendingIntent.FLAG_NO_CREATE)?.let {
                alarms.cancel(it)
                it.cancel()
            }
        }
    }

    private fun pending(context: Context, n: Kept, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        n.id.hashCode(),
        Intent(context, NoticeReceiver::class.java)
            .setAction("$ACTION.${n.id}")
            .putExtra("id", n.id)
            .putExtra("channel", n.channel)
            .putExtra("title", n.title)
            .putExtra("body", n.body),
        flag or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun kept(context: Context): List<Kept> =
        prefs(context).getString("plan", null)?.let { runCatching { json.decodeFromString(keptList, it) }.getOrNull() }.orEmpty()

    private fun prefs(context: Context) = context.getSharedPreferences("notices", Context.MODE_PRIVATE)

    private val online get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    // Activity

    /** Starts or stops the hourly look at activity, as the kinds chosen say. */
    suspend fun activityChanged(context: Context, wasOff: Boolean) {
        val graph = context.graph
        val work = WorkManager.getInstance(context)
        if (graph.noticeKinds.isEmpty()) {
            graph.activityCursor = null
            work.cancelUniqueWork(ACTIVITY)
            return
        }
        // Turned on: only what happens from now on is news.
        if (wasOff) startCursor(context)
        work.enqueueUniquePeriodicWork(
            ACTIVITY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ActivityWorker>(1, TimeUnit.HOURS).setConstraints(online).build(),
        )
    }

    /** Marks the newest row as seen, telling of nothing before it. */
    private suspend fun startCursor(context: Context) {
        val graph = context.graph
        val api = graph.api() ?: return
        runCatching { api.activity(kinds = ActivityNotices.families, limit = 1) }.getOrNull()?.let {
            graph.activityCursor = it.items.firstOrNull()?.id.orEmpty()
        }
    }

    /** Reads what happened since the last look and shows the kinds chosen. */
    suspend fun checkActivity(context: Context) {
        val graph = context.graph
        val wanted = graph.noticeKinds
        val me = graph.me ?: return
        val api = graph.api() ?: return
        if (wanted.isEmpty() || "activity" !in me.token.scopes) return
        val cursor = graph.activityCursor ?: return startCursor(context)
        val page = try {
            api.activity(after = cursor.ifEmpty { null }, kinds = ActivityNotices.families, limit = 100)
        } catch (e: ApiException) {
            if (e.signedOut) graph.tokenLost(e.message)
            return
        }
        // Turned off, or signed out, while the read was out.
        if (graph.noticeKinds != wanted || graph.activityCursor != cursor) return
        ActivityNotices.cursor(page.items, cursor)?.let { graph.activityCursor = it }
        for (n in ActivityNotices.notices(page.items, me.token.name, wanted)) show(context, n.id, Channel.Activity, n.title, n.body)
    }

    // Showing

    // allowed() is the permission check, first thing.
    @SuppressLint("MissingPermission")
    fun show(context: Context, id: String, channel: Channel, title: String, body: String) {
        if (!allowed(context)) return
        val graph = context.graph
        val on = when (channel) {
            Channel.Reminder -> graph.remindersOn
            Channel.Prompt -> graph.promptOn
            Channel.Activity -> graph.noticeKinds.isNotEmpty()
        }
        if (!on) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channel.id, say(channel.title), NotificationManager.IMPORTANCE_DEFAULT).apply { description = say(channel.about) },
        )
        val open = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.OPEN, channel.opens).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .apply { if (channel == Channel.Reminder) setSubText(say(R.string.upcoming)) }
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id.hashCode(), notification) }
    }
}

/** A reminder or the daily prompt, when its morning's email is due. */
class NoticeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val channel = runCatching { PhoneNotices.Channel.valueOf(intent.getStringExtra("channel").orEmpty()) }.getOrNull() ?: return
        PhoneNotices.show(context, id, channel, intent.getStringExtra("title").orEmpty(), intent.getStringExtra("body").orEmpty())
    }
}

/** Alarms don't outlive a restart or an update: set the kept plan again. */
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            PhoneNotices.restore(context)
        }
    }
}

/** Reads the reminders and versions again, so a date changed on the web still reminds right. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        PhoneNotices.refresh(applicationContext)
        return Result.success()
    }
}

/** Looks at what happened elsewhere and tells of the kinds chosen. */
class ActivityWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        PhoneNotices.checkActivity(applicationContext)
        return Result.success()
    }
}
