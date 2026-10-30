package com.yourversionnumber.changelog

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.yourversionnumber.changelog.kit.Spot
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One location fix, when the owner asks, in the foreground only. Android's
 * own LocationManager, so Changelog needs no Play services: the fused
 * provider where the phone has one, else GPS, else the network.
 */
object Locator {
    private const val WAIT_MS = 20_000L

    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Where the phone is now: each provider it has, in turn, until one says,
     * then a position it already knew from the last few minutes; or null.
     */
    suspend fun fix(context: Context): Spot? {
        if (!allowed(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        for (provider in providers) {
            withTimeoutOrNull(WAIT_MS / providers.size) { current(context, manager, provider) }?.let { return it }
        }
        return recent(manager, providers)
    }

    // allowed() is checked first, in fix().
    @SuppressLint("MissingPermission")
    private fun recent(manager: LocationManager, providers: List<String>): Spot? {
        val since = SystemClock.elapsedRealtimeNanos() - RECENT_NS
        return providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { it.elapsedRealtimeNanos >= since }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.let { Spot(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy.toDouble() else null) }
    }

    private const val RECENT_NS = 5L * 60 * 1_000_000_000

    // allowed() is checked first, in fix().
    @SuppressLint("MissingPermission")
    private suspend fun current(context: Context, manager: LocationManager, provider: String): Spot? =
        suspendCancellableCoroutine { done ->
            val signal = CancellationSignal()
            done.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                done.resume(location?.let { Spot(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy.toDouble() else null) })
            }
        }
}
