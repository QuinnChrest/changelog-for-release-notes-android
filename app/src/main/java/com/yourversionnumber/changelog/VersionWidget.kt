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
                !signedIn -> "Sign in to Changelog"
                me?.wroteToday == true -> "Written today. Anything else?"
                else -> "What changed?"
            }
            val views = RemoteViews(context.packageName, R.layout.widget_version)
            // A widget can't use the app's fonts, so the words are drawn in them,
            // and said aloud from the description.
            views.setImageViewBitmap(R.id.widget_words, words(context, version, prompt))
            views.setContentDescription(R.id.widget_words, "Version $version. $prompt")
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

        /** The version in Martian Mono (cobalt, dots tangerine) over the prompt in Bricolage Grotesque. */
        private fun words(context: Context, version: String, prompt: String): Bitmap {
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
            val gap = 8 * density
            val width = maxOf(big.measureText(version), small.measureText(prompt)).toInt() + 2
            val bigHeight = -big.fontMetrics.ascent + big.fontMetrics.descent
            val smallHeight = -small.fontMetrics.ascent + small.fontMetrics.descent
            val bitmap = Bitmap.createBitmap(width, (bigHeight + gap + smallHeight).toInt() + 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val cobalt = ContextCompat.getColor(context, R.color.cobalt)
            val tangerine = ContextCompat.getColor(context, R.color.tangerine)
            var x = 0f
            for (c in version) {
                big.color = if (c == '.') tangerine else cobalt
                canvas.drawText(c.toString(), x, -big.fontMetrics.ascent, big)
                x += big.measureText(c.toString())
            }
            canvas.drawText(prompt, 0f, bigHeight + gap - small.fontMetrics.ascent, small)
            return bitmap
        }

    }
}
