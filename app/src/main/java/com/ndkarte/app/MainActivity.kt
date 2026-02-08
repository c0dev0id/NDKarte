package com.ndkarte.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import java.io.File

/**
 * Main entry point for NDKarte.
 *
 * Hosts a fullscreen MapLibre MapView in landscape orientation and
 * delegates map lifecycle management to MapManager. Loads GPX files
 * from app-private storage, renders them on the map, and starts
 * track navigation with GPS positioning and TTS guidance.
 */
class MainActivity : Activity() {

    private lateinit var mapView: MapView
    private lateinit var mapManager: MapManager
    private lateinit var locationProvider: LocationProvider
    private lateinit var navigationManager: NavigationManager

    private var pendingGpxData: GpxData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapManager = MapManager(this, mapView)
        locationProvider = LocationProvider(this)
        navigationManager = NavigationManager(this, locationProvider)
        mapView.onCreate(savedInstanceState)
        mapManager.initialize { map, style ->
            navigationManager.bind(map, style)
            startNavigationIfReady()
        }

        loadGpxFiles()
        requestLocationPermission()

        Log.i(TAG, "NDKarte started, Rust core version: ${RustBridge.version()}")
    }

    /**
     * Scan the app's files/gpx/ directory for GPX files and render
     * the first one found on the map.
     */
    private fun loadGpxFiles() {
        val gpxDir = File(filesDir, GPX_DIR)
        if (!gpxDir.isDirectory) {
            gpxDir.mkdirs()
            return
        }

        val gpxFile = gpxDir.listFiles()
            ?.filter { it.extension == "gpx" }
            ?.firstOrNull()
            ?: return

        Log.i(TAG, "Loading GPX: ${gpxFile.name}")
        val data = GpxData.parse(gpxFile.readBytes())
        if (data != null) {
            mapManager.showGpxData(data)
            pendingGpxData = data
            Log.i(TAG, "GPX loaded: ${data.tracks.size} tracks, " +
                "${data.routes.size} routes, ${data.waypoints.size} waypoints")
        }
    }

    private fun requestLocationPermission() {
        if (locationProvider.hasPermission()) {
            startNavigationIfReady()
            return
        }

        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "Location permission granted")
                startNavigationIfReady()
            } else {
                Log.w(TAG, "Location permission denied")
            }
        }
    }

    /**
     * Start navigation on the first track if GPS permission is granted,
     * the map is ready, and GPX data has been loaded.
     */
    private fun startNavigationIfReady() {
        if (!locationProvider.hasPermission()) return
        if (navigationManager.isNavigating) return

        val data = pendingGpxData ?: return
        val track = data.tracks.firstOrNull() ?: return

        navigationManager.startNavigation(track)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterImmersiveMode()
        }
    }

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
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
        navigationManager.stopNavigation()
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
        private const val GPX_DIR = "gpx"
        private const val LOCATION_PERMISSION_REQUEST = 1001
    }
}
