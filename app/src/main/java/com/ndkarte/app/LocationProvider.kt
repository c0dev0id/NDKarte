package com.ndkarte.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

/**
 * Provides GPS location updates.
 *
 * Wraps Android LocationManager and delivers location updates to a
 * callback. Handles permission checking and provider availability.
 */
class LocationProvider(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var listener: LocationListener? = null
    private var callback: ((Location) -> Unit)? = null

    /** Whether location permissions have been granted. */
    fun hasPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start receiving location updates.
     *
     * Requires ACCESS_FINE_LOCATION permission to be granted first.
     * Updates are delivered at [intervalMs] intervals with [minDistanceM]
     * minimum distance filter.
     */
    fun start(
        intervalMs: Long = UPDATE_INTERVAL_MS,
        minDistanceM: Float = MIN_DISTANCE_M,
        onLocation: (Location) -> Unit
    ) {
        if (!hasPermission()) {
            Log.w(TAG, "Location permission not granted")
            return
        }

        callback = onLocation
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                callback?.invoke(location)
            }

            override fun onProviderEnabled(provider: String) {
                Log.i(TAG, "Provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Log.w(TAG, "Provider disabled: $provider")
            }

            @Deprecated("Deprecated in API")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMs,
                minDistanceM,
                listener!!
            )
            Log.i(TAG, "Location updates started")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to request location updates", e)
        }
    }

    /** Stop receiving location updates. */
    fun stop() {
        listener?.let {
            locationManager.removeUpdates(it)
            Log.i(TAG, "Location updates stopped")
        }
        listener = null
        callback = null
    }

    /** Get the last known location, or null if unavailable. */
    fun lastLocation(): Location? {
        if (!hasPermission()) return null
        return try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
    }

    companion object {
        private const val TAG = "NDKarte.Location"
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val MIN_DISTANCE_M = 5f
    }
}
