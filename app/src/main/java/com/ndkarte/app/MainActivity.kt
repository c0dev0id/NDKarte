package com.ndkarte.app

import android.app.Activity
import android.os.Bundle
import android.util.Log
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView

/**
 * Main entry point for NDKarte.
 *
 * Hosts a fullscreen MapLibre MapView in landscape orientation and
 * delegates map lifecycle management to MapManager.
 */
class MainActivity : Activity() {

    private lateinit var mapView: MapView
    private lateinit var mapManager: MapManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)

        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapManager = MapManager(mapView)
        mapView.onCreate(savedInstanceState)
        mapManager.initialize()

        Log.i(TAG, "NDKarte started, Rust core version: ${RustBridge.version()}")
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    companion object {
        private const val TAG = "NDKarte"
    }
}
