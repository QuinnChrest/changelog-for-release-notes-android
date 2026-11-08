package com.yourversionnumber.changelog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.yourversionnumber.changelog.kit.UpcomingDraft
import java.time.format.DateTimeFormatter

/**
 * Today, on the home screen, as the iPhone app's Today widget: the app's mark and TODAY, today's version large,
 * the weeks and days lived, and "Written today" or "What changed?". A tap opens Log ready to write. Everything is
 * the server's, from the last `/v1/me` the app read, never worked out here. Updated whenever the app reads it,
 * and by Android twice a day (`updatePeriodMillis`), which is enough to turn the day over.
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
                .forEach { manager.updateAppWidget(it, TodayWidgets.wide(context)) }
        }
    }
}

/** Today beside Coming up, as the iPhone app's medium widget: the next two dates in Upcoming, each in its words. */
class ComingUpWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, TodayWidgets.wide(context))
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

    fun wide(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_coming_up)
        today(context, views)
        val soon = context.graph.soon.take(2)
        val said = if (soon.isEmpty()) say(R.string.nothing_coming_up) else say(R.string.coming_up) + ". " + soon.joinToString(". ") { "${dateLabel(it)}, ${it.title}" }
        for ((id, night) in listOf(R.id.widget_soon to false, R.id.widget_soon_dark to true)) {
            views.setImageViewBitmap(id, Draw(context, night).comingUp(soon))
            views.setContentDescription(id, said)
            views.setOnClickPendingIntent(id, open(context, "upcoming", write = false))
        }
        views.setOnClickPendingIntent(R.id.widget_root, open(context, "log", write = true))
        return views
    }

    /** The Today side: drawn, and said. */
    private fun today(context: Context, views: RemoteViews) {
        val graph = context.graph
        val me = graph.me
        val version = me?.version ?: "?.?.???"
        val written = graph.signedIn.value && me?.wroteToday == true
        val status = when {
            !graph.signedIn.value -> say(R.string.sign_in_to_changelog)
            written -> say(R.string.written_today)
            else -> say(R.string.what_changed)
        }
        val lived = me?.lived
        val said = say(R.string.version, version, listOfNotNull(lived?.text, status).joinToString(". "))
        for ((id, night) in listOf(R.id.widget_words to false, R.id.widget_words_dark to true)) {
            views.setImageViewBitmap(id, Draw(context, night).today(version, lived?.week, lived?.day, status, written))
            views.setContentDescription(id, said)
        }
    }

    private fun open(context: Context, screen: String, write: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.OPEN, screen)
            .putExtra(MainActivity.WRITE, write)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, screen.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private val day = DateTimeFormatter.ofPattern("EEE, MMM d")

    /** "MON, NOV 16", "TODAY" or "ON NOW", as the iPhone widget heads a date. */
    fun dateLabel(soon: Soon): String = when (soon.status) {
        "today" -> say(R.string.today)
        "ongoing" -> say(R.string.on_now)
        else -> UpcomingDraft.parse(soon.date)?.format(day) ?: soon.date
    }.uppercase()

    /** The drawing, at the screen's density, in the light palette or the dark one. */
    private class Draw(base: Context, night: Boolean) {
        private val context: Context = base.createConfigurationContext(
            android.content.res.Configuration(base.resources.configuration).apply {
                uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
            },
        )
        private val d = context.resources.displayMetrics.density
        private val ink = ContextCompat.getColor(context, R.color.ink)
        private val quiet = Color.argb(170, Color.red(ink), Color.green(ink), Color.blue(ink))
        private val cobalt = ContextCompat.getColor(context, R.color.cobalt)

        private fun font(res: Int, weight: Int) = Typeface.create(ResourcesCompat.getFont(context, res) ?: Typeface.DEFAULT, weight, false)

        private fun paint(res: Int, weight: Int, sp: Float, color: Int, spacing: Float = 0f) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font(res, weight)
            textSize = sp * d
            this.color = color
            letterSpacing = spacing
        }

        private fun height(p: Paint) = -p.fontMetrics.ascent + p.fontMetrics.descent

        /** A line of text with its top at y; answers the y under it. */
        private fun Canvas.line(text: String, x: Float, y: Float, p: Paint): Float {
            drawText(text, x, y - p.fontMetrics.ascent, p)
            return y + height(p)
        }

        /** TODAY with the mark, the version, Week and Day, and the status. */
        fun today(version: String, week: Int?, dayNumber: Int?, status: String, written: Boolean): Bitmap {
            val head = paint(R.font.martian_mono, 500, 12f, quiet, 0.08f)
            val big = paint(R.font.bricolage_grotesque, 800, 34f, ink, -0.02f)
            val small = paint(R.font.bricolage_grotesque, 500, 15f, quiet)
            val mark = 18 * d
            val lines = listOfNotNull(week?.let { say(R.string.week_n, "%,d".format(it)) }, dayNumber?.let { say(R.string.day_n, "%,d".format(it)) })
            val check = if (written) 16 * d + 6 * d else 0f
            val width = maxOf(
                mark + 8 * d + head.measureText(say(R.string.today_caps)),
                big.measureText(version),
                lines.maxOfOrNull(small::measureText) ?: 0f,
                check + small.measureText(status),
            )
            val height = mark + 8 * d + height(big) + 4 * d + lines.size * height(small) + 14 * d + height(small)
            val bitmap = Bitmap.createBitmap(width.toInt() + 2, height.toInt() + 2, Bitmap.Config.ARGB_8888)
            val c = Canvas(bitmap)
            // The app's mark, as the iPhone widget's corner.
            ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.let { icon ->
                c.save()
                c.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, mark, mark), 4 * d, 4 * d, Path.Direction.CW) })
                icon.setBounds(0, 0, mark.toInt(), mark.toInt())
                icon.draw(c)
                c.restore()
            }
            c.drawText(say(R.string.today_caps), mark + 8 * d, (mark - height(head)) / 2 - head.fontMetrics.ascent, head)
            var y = mark + 8 * d
            y = c.line(version, 0f, y, big) + 4 * d
            for (l in lines) y = c.line(l, 0f, y, small)
            y += 14 * d
            if (written) {
                // A filled check, in ink, as the iPhone's.
                val r = 8 * d
                val cy = y + height(small) / 2
                c.drawCircle(r, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
                val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ContextCompat.getColor(context, R.color.paper)
                    style = Paint.Style.STROKE
                    strokeWidth = 1.8f * d
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                c.drawPath(Path().apply { moveTo(r - 3.5f * d, cy); lineTo(r - 1f * d, cy + 2.5f * d); lineTo(r + 3.8f * d, cy - 2.8f * d) }, tick)
            }
            c.line(status, check, y, small)
            return bitmap
        }

        /** COMING UP, and the next dates: each day in cobalt capitals, its title under it. */
        fun comingUp(soon: List<Soon>): Bitmap {
            val head = paint(R.font.martian_mono, 500, 12f, quiet, 0.08f)
            val date = paint(R.font.martian_mono, 600, 13f, cobalt)
            val title = paint(R.font.bricolage_grotesque, 500, 16f, ink)
            val width = 170 * d
            val rows = if (soon.isEmpty()) 1 else soon.size
            val height = height(head) + 10 * d + rows * (height(date) + 2 * d + height(title) + 10 * d)
            val bitmap = Bitmap.createBitmap(width.toInt() + 2, height.toInt() + 2, Bitmap.Config.ARGB_8888)
            val c = Canvas(bitmap)
            var y = c.line(say(R.string.coming_up).uppercase(), 0f, 0f, head) + 10 * d
            if (soon.isEmpty()) {
                val none = paint(R.font.bricolage_grotesque, 500, 15f, quiet)
                c.line(TextUtils.ellipsize(say(R.string.nothing_coming_up), none, width, TextUtils.TruncateAt.END).toString(), 0f, y, none)
            }
            for (s in soon) {
                y = c.line(dateLabel(s), 0f, y, date) + 2 * d
                val cut = TextUtils.ellipsize(s.title, title, width, TextUtils.TruncateAt.END).toString()
                y = c.line(cut, 0f, y, title) + 10 * d
            }
            return bitmap
        }
    }
}
