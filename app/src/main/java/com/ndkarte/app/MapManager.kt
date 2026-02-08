package com.ndkarte.app

import android.content.Context
import android.graphics.Color
import android.util.Log
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages MapLibre MapView configuration and interaction.
 *
 * Handles offline MBTiles tile source discovery, style loading,
 * camera control, UI settings, and GPX track/route/waypoint overlays.
 */
class MapManager(private val context: Context, private val mapView: MapView) {

    private var map: MapLibreMap? = null
    private var style: Style? = null

    /**
     * Initialize the map with offline MBTiles tiles if available,
     * or fall back to an empty style with just a background layer.
     */
    fun initialize() {
        mapView.getMapAsync { mapLibreMap ->
            map = mapLibreMap
            configureUiSettings(mapLibreMap)

            val styleJson = buildStyleJson()
            mapLibreMap.setStyle(styleJson) { loadedStyle ->
                style = loadedStyle
                Log.i(TAG, "Style loaded")
            }

            mapLibreMap.cameraPosition = CameraPosition.Builder()
                .target(LatLng(48.2, 16.3))
                .zoom(10.0)
                .build()

            Log.i(TAG, "Map initialized")
        }
    }

    /**
     * Display parsed GPX data on the map as overlay layers.
     *
     * Tracks are rendered as blue polylines, routes as orange dashed
     * polylines, and waypoints as red circles.
     */
    fun showGpxData(data: GpxData) {
        val currentStyle = style ?: run {
            Log.w(TAG, "Style not loaded yet, cannot show GPX data")
            return
        }

        addTracks(currentStyle, data.tracks)
        addRoutes(currentStyle, data.routes)
        addWaypoints(currentStyle, data.waypoints)

        fitBoundsToGpx(data)
    }

    /** Remove all GPX overlay layers and sources from the map. */
    fun clearGpxData() {
        val currentStyle = style ?: return

        currentStyle.removeLayer(TRACK_LAYER_ID)
        currentStyle.removeSource(TRACK_SOURCE_ID)
        currentStyle.removeLayer(ROUTE_LAYER_ID)
        currentStyle.removeSource(ROUTE_SOURCE_ID)
        currentStyle.removeLayer(WAYPOINT_LAYER_ID)
        currentStyle.removeSource(WAYPOINT_SOURCE_ID)
    }

    private fun addTracks(style: Style, tracks: List<GpxTrack>) {
        if (tracks.isEmpty()) return

        val features = JSONArray()
        for (track in tracks) {
            if (track.points.size < 2) continue
            features.put(lineFeature(track.points, track.name))
        }

        val geojson = featureCollection(features)
        style.addSource(GeoJsonSource(TRACK_SOURCE_ID, geojson.toString()))
        style.addLayer(
            LineLayer(TRACK_LAYER_ID, TRACK_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(Color.parseColor(TRACK_COLOR)),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineOpacity(0.8f)
            )
        )

        Log.i(TAG, "Added ${tracks.size} track(s)")
    }

    private fun addRoutes(style: Style, routes: List<GpxRoute>) {
        if (routes.isEmpty()) return

        val features = JSONArray()
        for (route in routes) {
            if (route.points.size < 2) continue
            features.put(lineFeature(route.points, route.name))
        }

        val geojson = featureCollection(features)
        style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, geojson.toString()))
        style.addLayer(
            LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(Color.parseColor(ROUTE_COLOR)),
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineOpacity(0.8f),
                PropertyFactory.lineDasharray(arrayOf(4f, 2f))
            )
        )

        Log.i(TAG, "Added ${routes.size} route(s)")
    }

    private fun addWaypoints(style: Style, waypoints: List<GpxWaypoint>) {
        if (waypoints.isEmpty()) return

        val features = JSONArray()
        for (wpt in waypoints) {
            features.put(pointFeature(wpt.point, wpt.name))
        }

        val geojson = featureCollection(features)
        style.addSource(GeoJsonSource(WAYPOINT_SOURCE_ID, geojson.toString()))
        style.addLayer(
            CircleLayer(WAYPOINT_LAYER_ID, WAYPOINT_SOURCE_ID).withProperties(
                PropertyFactory.circleColor(Color.parseColor(WAYPOINT_COLOR)),
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleStrokeColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(2f)
            )
        )

        Log.i(TAG, "Added ${waypoints.size} waypoint(s)")
    }

    /** Adjust camera to fit all GPX geometry with padding. */
    private fun fitBoundsToGpx(data: GpxData) {
        val allPoints = mutableListOf<GpxPoint>()
        data.tracks.forEach { allPoints.addAll(it.points) }
        data.routes.forEach { allPoints.addAll(it.points) }
        data.waypoints.forEach { allPoints.add(it.point) }

        if (allPoints.size < 2) return

        val builder = LatLngBounds.Builder()
        allPoints.forEach { builder.include(LatLng(it.lat, it.lon)) }

        map?.animateCamera(
            CameraUpdateFactory.newLatLngBounds(builder.build(), BOUNDS_PADDING)
        )
    }

    private fun configureUiSettings(mapLibreMap: MapLibreMap) {
        mapLibreMap.uiSettings.apply {
            isCompassEnabled = true
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
            isZoomGesturesEnabled = true
            isScrollGesturesEnabled = true
            isDoubleTapGesturesEnabled = true
            isAttributionEnabled = false
            isLogoEnabled = false
        }
    }

    private fun buildStyleJson(): String {
        val mbtilesFile = findMbtilesFile()

        if (mbtilesFile != null) {
            Log.i(TAG, "Using MBTiles: ${mbtilesFile.absolutePath}")
            return loadOfflineStyle(mbtilesFile)
        }

        Log.i(TAG, "No MBTiles file found, using empty style")
        return EMPTY_STYLE
    }

    private fun findMbtilesFile(): File? {
        val mapsDir = File(context.filesDir, MAPS_DIR)
        if (!mapsDir.isDirectory) {
            mapsDir.mkdirs()
            return null
        }

        return mapsDir.listFiles()
            ?.firstOrNull { it.extension == "mbtiles" }
    }

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
        private const val BOUNDS_PADDING = 80

        private const val TRACK_SOURCE_ID = "gpx-tracks"
        private const val TRACK_LAYER_ID = "gpx-tracks-layer"
        private const val TRACK_COLOR = "#2196F3"

        private const val ROUTE_SOURCE_ID = "gpx-routes"
        private const val ROUTE_LAYER_ID = "gpx-routes-layer"
        private const val ROUTE_COLOR = "#FF9800"

        private const val WAYPOINT_SOURCE_ID = "gpx-waypoints"
        private const val WAYPOINT_LAYER_ID = "gpx-waypoints-layer"
        private const val WAYPOINT_COLOR = "#F44336"

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

        /** Build a GeoJSON LineString feature from GPX points. */
        private fun lineFeature(points: List<GpxPoint>, name: String?): JSONObject {
            val coords = JSONArray()
            for (p in points) {
                val coord = JSONArray().put(p.lon).put(p.lat)
                if (p.ele != null) coord.put(p.ele)
                coords.put(coord)
            }

            return JSONObject().apply {
                put("type", "Feature")
                put("geometry", JSONObject().apply {
                    put("type", "LineString")
                    put("coordinates", coords)
                })
                put("properties", JSONObject().apply {
                    if (name != null) put("name", name)
                })
            }
        }

        /** Build a GeoJSON Point feature from a GPX point. */
        private fun pointFeature(point: GpxPoint, name: String?): JSONObject {
            val coord = JSONArray().put(point.lon).put(point.lat)
            if (point.ele != null) coord.put(point.ele)

            return JSONObject().apply {
                put("type", "Feature")
                put("geometry", JSONObject().apply {
                    put("type", "Point")
                    put("coordinates", coord)
                })
                put("properties", JSONObject().apply {
                    if (name != null) put("name", name)
                })
            }
        }

        private fun featureCollection(features: JSONArray): JSONObject {
            return JSONObject().apply {
                put("type", "FeatureCollection")
                put("features", features)
            }
        }
    }
}
