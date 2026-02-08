package com.ndkarte.app

import android.util.Log
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap

/**
 * Manages MapLibre MapView configuration and interaction.
 *
 * Encapsulates map setup, camera control, and layer management so
 * MainActivity stays thin. Will later handle track/route overlays
 * and gesture callbacks.
 */
class MapManager(private val mapView: MapView) {

    private var map: MapLibreMap? = null

    /**
     * Set up the map with a default camera position.
     * Uses an empty style since we target offline MBTiles — a real style
     * URI will be configured once tile storage is implemented.
     */
    fun initialize() {
        mapView.getMapAsync { mapLibreMap ->
            map = mapLibreMap

            mapLibreMap.cameraPosition = CameraPosition.Builder()
                .target(LatLng(48.2, 16.3))  // Vienna as default center
                .zoom(10.0)
                .build()

            Log.i(TAG, "Map initialized")
        }
    }

    companion object {
        private const val TAG = "NDKarte.Map"
    }
}
