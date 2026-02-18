package com.ndkarte.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import java.io.File
import java.util.concurrent.Executors

/**
 * Main entry point for NDKarte.
 *
 * Hosts a fullscreen MapLibre MapView in landscape orientation and
 * delegates map lifecycle management to MapManager. Loads GPX files
 * from app-private storage, renders them on the map, and starts
 * track navigation with GPS positioning and TTS guidance. Syncs
 * GPX files with Google Drive when signed in.
 */
class MainActivity : Activity() {

    private lateinit var mapView: MapView
    private lateinit var mapManager: MapManager
    private lateinit var locationProvider: LocationProvider
    private lateinit var navigationManager: NavigationManager
    private lateinit var syncManager: SyncManager

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var pendingGpxData: GpxData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)

        // Floating download button — opens the offline map download browser.
        findViewById<Button>(R.id.btnOpenDownloads).setOnClickListener {
            startActivity(Intent(this, MapDownloadActivity::class.java))
        }
        mapManager = MapManager(this, mapView)
        locationProvider = LocationProvider(this)
        navigationManager = NavigationManager(this, locationProvider)
        syncManager = SyncManager(this)
        mapView.onCreate(savedInstanceState)
        mapManager.initialize { map, style ->
            navigationManager.bind(map, style)
            startNavigationIfReady()
        }

        syncManager.initialize()
        loadGpxFiles()
        requestLocationPermission()
        triggerSync()

        Log.i(TAG, "NDKarte started, Rust core version: ${RustBridge.version()}")
    }

    /**
     * Scan the app's files/gpx/ directory for GPX files and render
     * the first one found on the map. File I/O and Rust parsing
     * run on a background thread to keep the UI fluid.
     */
    private fun loadGpxFiles() {
        ioExecutor.execute {
            val gpxDir = File(filesDir, GPX_DIR)
            if (!gpxDir.isDirectory) {
                gpxDir.mkdirs()
                return@execute
            }

            val gpxFile = gpxDir.listFiles()
                ?.filter { it.extension == "gpx" }
                ?.firstOrNull()
                ?: return@execute

            Log.i(TAG, "Loading GPX: ${gpxFile.name}")
            val data = GpxData.parse(gpxFile.readBytes()) ?: return@execute

            runOnUiThread {
                mapManager.showGpxData(data)
                pendingGpxData = data
                startNavigationIfReady()
                Log.i(TAG, "GPX loaded: ${data.tracks.size} tracks, " +
                    "${data.routes.size} routes, ${data.waypoints.size} waypoints")
            }
        }
    }

    /**
     * Trigger a Google Drive sync if signed in. After sync completes,
     * reload GPX files in case new ones were downloaded.
     */
    private fun triggerSync() {
        if (!syncManager.isSignedIn) return

        syncManager.sync { result ->
            if (result.error != null) {
                Log.w(TAG, "Sync error: ${result.error}")
            } else {
                Log.i(TAG, "Sync: ${result.uploaded} up, ${result.downloaded} down")
                if (result.downloaded > 0) {
                    loadGpxFiles()
                }
            }
        }
    }

    /**
     * Initiate Google Sign-In flow. Call this from a UI action
     * (e.g. settings menu) to connect to Google Drive.
     */
    fun startSignIn() {
        val intent = syncManager.getSignInIntent() ?: return
        @Suppress("DEPRECATION")
        startActivityForResult(intent, SIGN_IN_REQUEST)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SIGN_IN_REQUEST) {
            syncManager.handleSignInResult(data)
            if (syncManager.isSignedIn) {
                triggerSync()
            }
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
        private const val SIGN_IN_REQUEST = 1002
    }
}
