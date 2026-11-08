package com.yourversionnumber.changelog

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

/**
 * Today's version on the home screen, as the site writes it (cobalt, the dots
 * tangerine), with "What changed?" under it; a tap opens Log ready to write.
 * The version is the server's, from the last `/v1/me` the app read, never
 * worked out here. Updated whenever the app reads it, and by Android twice a
 * day (`updatePeriodMillis`), which is enough to turn the day over.
 */
class VersionWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context))
    }

    companion object {
        /** Redraws every Changelog widget, after the app reads a new version. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, VersionWidget::class.java))
            if (ids.isNotEmpty()) for (id in ids) manager.updateAppWidget(id, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val me = context.graph.me
            val signedIn = context.graph.signedIn.value
            val version = me?.version ?: "?.?.???"
            val prompt = when {
                !signedIn -> say(R.string.sign_in_to_changelog)
                me?.wroteToday == true -> say(R.string.written_today_anything_else)
                else -> say(R.string.what_changed)
            }
            val views = RemoteViews(context.packageName, R.layout.widget_version)
            // A widget can't use the app's fonts, so the words are drawn in them,
            // and said aloud from the description.
            // "Week 2,369 — Day 16,577" under the version, as the site and the email have it (API 1.13.0).
            val lived = me?.lived?.text
            views.setImageViewBitmap(R.id.widget_words, words(context, version, lived, prompt))
            views.setContentDescription(R.id.widget_words, say(R.string.version, version, listOfNotNull(lived, prompt).joinToString(". ")))
            val open = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.OPEN, "log")
                .putExtra(MainActivity.WRITE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            return views
        }

        /** The version in Martian Mono (cobalt, dots tangerine), the weeks and days lived, and the prompt, in Bricolage Grotesque. */
        private fun words(context: Context, version: String, lived: String?, prompt: String): Bitmap {
            val density = context.resources.displayMetrics.density
            fun font(res: Int, weight: Int) = Typeface.create(ResourcesCompat.getFont(context, res) ?: Typeface.DEFAULT, weight, false)
            val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = font(R.font.martian_mono, 700)
                textSize = 26 * density
                letterSpacing = -0.02f
            }
            val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = font(R.font.bricolage_grotesque, 500)
                textSize = 15 * density
                color = ContextCompat.getColor(context, R.color.ink)
            }
            // The lived line is quieter: the ink at two thirds, as the site's hint.
            val hint = Paint(small).apply {
                textSize = 13 * density
                alpha = 170
            }
            val gap = 8 * density
            val width = maxOf(big.measureText(version), small.measureText(prompt), lived?.let(hint::measureText) ?: 0f).toInt() + 2
            val bigHeight = -big.fontMetrics.ascent + big.fontMetrics.descent
            val smallHeight = -small.fontMetrics.ascent + small.fontMetrics.descent
            val hintHeight = if (lived == null) 0f else -hint.fontMetrics.ascent + hint.fontMetrics.descent + 2 * density
            val bitmap = Bitmap.createBitmap(width, (bigHeight + hintHeight + gap + smallHeight).toInt() + 2, Bitmap.Config.ARGB_8888)
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
            return bitmap
        }

    }
}
