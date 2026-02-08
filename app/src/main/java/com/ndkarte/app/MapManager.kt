package com.ndkarte.app

import android.content.Context
import android.util.Log
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import java.io.File

/**
 * Manages MapLibre MapView configuration and interaction.
 *
 * Handles offline MBTiles tile source discovery, style loading,
 * camera control, and UI settings. Track/route overlays will be
 * added here as navigation features are implemented.
 */
class MapManager(private val context: Context, private val mapView: MapView) {

    private var map: MapLibreMap? = null

    /**
     * Initialize the map with offline MBTiles tiles if available,
     * or fall back to an empty style with just a background layer.
     */
    fun initialize() {
        mapView.getMapAsync { mapLibreMap ->
            map = mapLibreMap
            configureUiSettings(mapLibreMap)

            val styleJson = buildStyleJson()
            mapLibreMap.setStyle(styleJson) { style ->
                Log.i(TAG, "Style loaded")
            }

            mapLibreMap.cameraPosition = CameraPosition.Builder()
                .target(LatLng(48.2, 16.3))
                .zoom(10.0)
                .build()

            Log.i(TAG, "Map initialized")
        }
    }

    private fun configureUiSettings(mapLibreMap: MapLibreMap) {
        mapLibreMap.uiSettings.apply {
            isCompassEnabled = true
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
            isZoomGesturesEnabled = true
            isScrollGesturesEnabled = true
            isDoubleTapGesturesEnabled = true

            // Attribution not needed for self-hosted offline tiles
            isAttributionEnabled = false
            isLogoEnabled = false
        }
    }

    /**
     * Build a MapLibre style JSON string.
     *
     * Looks for an MBTiles file in the app's files directory. If found,
     * loads the offline style template from assets and injects the file
     * path. Otherwise returns a minimal empty style.
     */
    private fun buildStyleJson(): String {
        val mbtilesFile = findMbtilesFile()

        if (mbtilesFile != null) {
            Log.i(TAG, "Using MBTiles: ${mbtilesFile.absolutePath}")
            return loadOfflineStyle(mbtilesFile)
        }

        Log.i(TAG, "No MBTiles file found, using empty style")
        return EMPTY_STYLE
    }

    /**
     * Scan the app's files/maps/ directory for an MBTiles file.
     * Uses the first .mbtiles file found.
     */
    private fun findMbtilesFile(): File? {
        val mapsDir = File(context.filesDir, MAPS_DIR)
        if (!mapsDir.isDirectory) {
            mapsDir.mkdirs()
            return null
        }

        return mapsDir.listFiles()
            ?.firstOrNull { it.extension == "mbtiles" }
    }

    /**
     * Load the offline style template from assets and substitute
     * the MBTiles file path into the source URL.
     */
    private fun loadOfflineStyle(mbtilesFile: File): String {
        val template = context.assets.open(STYLE_ASSET)
            .bufferedReader()
            .use { it.readText() }

        return template.replace(MBTILES_PLACEHOLDER, mbtilesFile.absolutePath)
    }

    companion object {
        private const val TAG = "NDKarte.Map"
        private const val MAPS_DIR = "maps"
        private const val STYLE_ASSET = "styles/offline.json"
        private const val MBTILES_PLACEHOLDER = "{MBTILES_PATH}"

        /** Minimal style with just a background — used when no tiles are available. */
        private const val EMPTY_STYLE = """
            {
              "version": 8,
              "name": "empty",
              "sources": {},
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": { "background-color": "#f0ede8" }
                }
              ]
            }
        """
    }
}
