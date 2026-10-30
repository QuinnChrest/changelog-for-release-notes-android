package com.yourversionnumber.changelog

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * A Quick Settings tile: "Changelog", today's version under it, and a tap
 * that opens Log with the note box ready, from anywhere the shade comes down.
 * The version is the server's, from the last `/v1/me` the app read.
 */
class WriteTile : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val graph = applicationContext.graph
        tile.state = if (graph.signedIn.value) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.label = "Changelog"
        tile.subtitle = graph.me?.version ?: "Sign in first"
        tile.contentDescription = graph.me?.version?.let { "Changelog, version $it. Write what changed." } ?: "Changelog"
        tile.updateTile()
    }

    override fun onClick() {
        val open = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.OPEN, "log")
            .putExtra(MainActivity.WRITE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Both close the shade on the way: Android 14 and later take a
        // PendingIntent; 12 and 13 the Intent, which 14 no longer allows.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(open)
        }
    }
}
