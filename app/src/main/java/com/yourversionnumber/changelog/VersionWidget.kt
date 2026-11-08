package com.yourversionnumber.changelog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.yourversionnumber.changelog.kit.Countdown
import com.yourversionnumber.changelog.kit.UpcomingDraft
import com.yourversionnumber.changelog.kit.UpcomingEntry
import com.yourversionnumber.changelog.kit.UpcomingKind
import java.time.format.DateTimeFormatter

/**
 * Today's version on the home screen, as the site writes it (cobalt, the dots tangerine), the weeks and days
 * lived under it, and "What changed?"; a tap opens Log ready to write. The version is the server's, from the last
 * `/v1/me` the app read, never worked out here. Updated whenever the app reads it, and by Android twice a day
 * (`updatePeriodMillis`), which is enough to turn the day over.
 *
 * A widget can't use the app's fonts, so the words are drawn into images in them, and said aloud from each
 * image's description. Each is drawn twice, light and dark: the layout shows one or the other by resources the
 * home screen reads in the phone's own theme (`widget_light`, `widget_dark`), so a switch to dark needs no redraw.
 */
class VersionWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, TodayWidgets.small(context))
    }

    companion object {
        /** Redraws every Changelog widget, after the app reads a new version or new dates. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, VersionWidget::class.java))
                .forEach { manager.updateAppWidget(it, TodayWidgets.small(context)) }
            manager.getAppWidgetIds(ComponentName(context, ComingUpWidget::class.java))
                .forEach { manager.updateAppWidget(it, TodayWidgets.wide(context, manager, it)) }
        }
    }
}

/**
 * The bigger widget: today's version, as the small one has it, with the streak under it, beside the next dates in
 * Upcoming (as many as its height holds), each in the server's own line with its day and how far off, as the
 * Upcoming tab says them. Taps open Log or Upcoming.
 */
class ComingUpWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, TodayWidgets.wide(context, manager, id))
    }

    /** Resized: as many dates as now fit. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) {
        manager.updateAppWidget(id, TodayWidgets.wide(context, manager, id))
    }
}

/** What both widgets draw. */
internal object TodayWidgets {
    fun small(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_version)
        today(context, views)
        views.setOnClickPendingIntent(R.id.widget_root, open(context, "log", write = true))
        return views
    }

    /**
     * Today beside Coming up, drawn as one picture so both keep one scale (as separate images the home screen
     * squeezed the second); clear halves over it take the taps: Today opens Log, Coming up opens Upcoming.
     */
    fun wide(context: Context, manager: AppWidgetManager, id: Int): RemoteViews {
        val graph = context.graph
        val me = graph.me
        val views = RemoteViews(context.packageName, R.layout.widget_coming_up)
        val t = Today.of(context)
        val day = me?.today
        val standing = when {
            me == null || !graph.signedIn.value -> null
            me.status == "stopped" -> say(R.string.your_email_is_stopped)
            me.streak > 1 -> sayCount(R.plurals.streak, me.streak, me.streak)
            else -> null
        }
        // As many dates as the widget's height holds: its height on a phone held upright, in dp.
        val tall = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 180
        val room = tall - 32 - 28
        val soon = graph.soon.take((room / 46).coerceIn(1, 4))
        val said = listOfNotNull(
            t.said,
            standing,
            if (soon.isEmpty()) say(R.string.nothing_coming_up) else say(R.string.coming_up) + ". " + soon.joinToString(". ") { "${it.words}, ${whenSaid(it, day)}" },
        ).joinToString(". ")
        for ((view, night) in listOf(R.id.widget_words to false, R.id.widget_words_dark to true)) {
            val draw = Draw(context, night)
            views.setImageViewBitmap(view, draw.beside(draw.words(t.version, t.lived, t.prompt, standing), draw.comingUp(soon, day)))
            views.setContentDescription(view, said)
        }
        views.setOnClickPendingIntent(R.id.widget_root, open(context, "log", write = true))
        views.setOnClickPendingIntent(R.id.widget_tap_today, open(context, "log", write = true))
        views.setOnClickPendingIntent(R.id.widget_tap_soon, open(context, "upcoming", write = false))
        return views
    }

    /** What Today says: the version, the weeks and days lived, and the prompt. */
    private data class Today(val version: String, val lived: String?, val prompt: String) {
        val said: String get() = say(R.string.version, version, listOfNotNull(lived, prompt).joinToString(". "))

        companion object {
            fun of(context: Context): Today {
                val me = context.graph.me
                val prompt = when {
                    !context.graph.signedIn.value -> say(R.string.sign_in_to_changelog)
                    me?.wroteToday == true -> say(R.string.written_today_anything_else)
                    else -> say(R.string.what_changed)
                }
                // "Week 2,369 — Day 16,577" under the version, as the site and the email have it (API 1.13.0).
                return Today(me?.version ?: "?.?.???", me?.lived?.text, prompt)
            }
        }
    }

    /** Today's version, lived and prompt: drawn in both palettes, and said. */
    private fun today(context: Context, views: RemoteViews) {
        val t = Today.of(context)
        for ((id, night) in listOf(R.id.widget_words to false, R.id.widget_words_dark to true)) {
            views.setImageViewBitmap(id, Draw(context, night).words(t.version, t.lived, t.prompt))
            views.setContentDescription(id, t.said)
        }
    }

    private fun open(context: Context, screen: String, write: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.OPEN, screen)
            .putExtra(MainActivity.WRITE, write)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, screen.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private val dayFormat = DateTimeFormatter.ofPattern("EEE, MMM d")

    /** "Mon, Nov 16 · In 9 days", "Today" or "On now", as the Upcoming tab says when a date is. */
    fun whenSaid(soon: Soon, today: String?): String = when (soon.status) {
        "today" -> say(R.string.today)
        "ongoing" -> say(R.string.on_now)
        else -> {
            val day = UpcomingDraft.parse(soon.date)?.format(dayFormat) ?: soon.date
            val entry = UpcomingEntry(id = "", revision = 0, next = soon.date, status = soon.status, line = "", kind = UpcomingKind.Event, title = "", date = soon.date)
            val off = today?.let { Countdown.of(entry, it) }?.let { c ->
                val days = c.days.toInt()
                when {
                    days == 1 -> say(R.string.tomorrow)
                    !c.inWeeks -> sayCount(R.plurals.in_days, days, days)
                    c.rest == 0L -> sayCount(R.plurals.weeks_count, c.weeks.toInt(), c.weeks.toInt())
                    else -> sayCount(R.plurals.weeks_count, c.weeks.toInt(), c.weeks.toInt()) + " " + sayCount(R.plurals.days_count, c.rest.toInt(), c.rest.toInt())
                }
            }
            if (off == null) day else "$day · $off"
        }
    }

    /** The drawing, at the screen's density, in the light palette or the dark one. */
    private class Draw(base: Context, night: Boolean) {
        private val context: Context = base.createConfigurationContext(
            Configuration(base.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            },
        )
        private val density = context.resources.displayMetrics.density
        private val ink = ContextCompat.getColor(context, R.color.ink)

        private fun font(res: Int, weight: Int) = Typeface.create(ResourcesCompat.getFont(context, res) ?: Typeface.DEFAULT, weight, false)

        private fun height(p: Paint) = -p.fontMetrics.ascent + p.fontMetrics.descent

        /** The version in Martian Mono (cobalt, dots tangerine), the weeks and days lived, and the prompt, in Bricolage Grotesque. */
        fun words(version: String, lived: String?, prompt: String, standing: String? = null): Bitmap {
            val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = font(R.font.martian_mono, 700)
                textSize = 26 * density
                letterSpacing = -0.02f
            }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = font(R.font.bricolage_grotesque, 500)
                textSize = 15 * density
                color = ink
            }
            // The lived line is quieter: the ink at two thirds, as the site's hint.
            val hint = Paint(small).apply {
                textSize = 13 * density
                alpha = 170
            }
            val gap = 8 * density
            // The streak, or the email stopped, quietly under the prompt.
            val extraHeight = if (standing == null) 0f else 10 * density + height(hint)
            val width = maxOf(big.measureText(version), small.measureText(prompt), lived?.let(hint::measureText) ?: 0f, standing?.let(hint::measureText) ?: 0f).toInt() + 2
            val bigHeight = height(big)
            val hintHeight = if (lived == null) 0f else height(hint) + 2 * density
            val bitmap = Bitmap.createBitmap(width, (bigHeight + hintHeight + gap + height(small) + extraHeight).toInt() + 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val cobalt = ContextCompat.getColor(context, R.color.cobalt)
            val tangerine = ContextCompat.getColor(context, R.color.tangerine)
            var x = 0f
            for (c in version) {
                big.color = if (c == '.') tangerine else cobalt
                canvas.drawText(c.toString(), x, -big.fontMetrics.ascent, big)
                x += big.measureText(c.toString())
            }
            lived?.let { canvas.drawText(it, 0f, bigHeight + 2 * density - hint.fontMetrics.ascent, hint) }
            canvas.drawText(prompt, 0f, bigHeight + hintHeight + gap - small.fontMetrics.ascent, small)
            val y = bigHeight + hintHeight + gap + height(small) + 10 * density
            standing?.let { canvas.drawText(it, 0f, y - hint.fontMetrics.ascent, hint) }
            return bitmap
        }

        /** Two drawings side by side, centred on each other, with a rule between, as the app draws its rules. */
        fun beside(left: Bitmap, right: Bitmap): Bitmap {
            val gap = 16 * density
            val height = maxOf(left.height, right.height)
            val out = Bitmap.createBitmap((left.width + gap * 2 + density + right.width).toInt(), height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(left, 0f, 0f, null)
            val x = left.width + gap
            canvas.drawRect(x, 0f, x + density, height.toFloat(), Paint().apply { color = ContextCompat.getColor(context, R.color.widget_rule) })
            canvas.drawBitmap(right, x + density + gap, 0f, null)
            return out
        }

        /** "Coming up", then each date's own line in ink with its day and how far off in the quieter ink, as Upcoming. */
        fun comingUp(soon: List<Soon>, today: String?): Bitmap {
            // Named apart: inside a TextPaint's apply, `density` is the paint's own (1), not the screen's.
            val dp = density
            val heading = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = font(R.font.bricolage_grotesque, 700)
                textSize = 15 * dp
                color = ink
            }
            val line = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = font(R.font.bricolage_grotesque, 500)
                textSize = 15 * dp
                color = ink
            }
            val caption = TextPaint(line).apply {
                textSize = 13 * dp
                alpha = 170
            }
            // As wide as its longest line, up to 160dp: the rest is cut short with an ellipsis.
            val widest = (listOf(heading.measureText(say(R.string.coming_up))) +
                soon.flatMap { listOf(line.measureText(it.words), caption.measureText(whenSaid(it, today))) } +
                listOfNotNull(caption.measureText(say(R.string.nothing_coming_up)).takeIf { soon.isEmpty() })).max()
            val width = minOf(widest, 160 * density)
            val rows = if (soon.isEmpty()) 1 else soon.size
            val height = height(heading) + 8 * density + rows * (height(line) + height(caption) + 8 * density)
            val bitmap = Bitmap.createBitmap(width.toInt() + 2, height.toInt() + 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            fun Canvas.text(s: String, y: Float, p: TextPaint): Float {
                drawText(TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END).toString(), 0f, y - p.fontMetrics.ascent, p)
                return y + height(p)
            }
            var y = canvas.text(say(R.string.coming_up), 0f, heading) + 8 * density
            if (soon.isEmpty()) canvas.text(say(R.string.nothing_coming_up), y, caption)
            for (s in soon) {
                y = canvas.text(s.words, y, line)
                y = canvas.text(whenSaid(s, today), y, caption) + 8 * density
            }
            return bitmap
        }
    }
}
