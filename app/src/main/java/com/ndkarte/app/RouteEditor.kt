package com.ndkarte.app

import android.graphics.Color
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Interactive route editor for creating and editing routes on the map.
 *
 * Supports adding waypoints by map tap, moving waypoints by long-press
 * drag, and deleting the last waypoint. Renders the route as connected
 * line segments with numbered waypoint markers.
 */
class RouteEditor(
    private val map: MapLibreMap,
    private val style: Style
) {
    private val waypoints = mutableListOf<LatLng>()
    private var routeName: String? = null
    private var editing = false
    private var listener: EditorListener? = null

    interface EditorListener {
        fun onRouteChanged(pointCount: Int)
    }

    fun setListener(l: EditorListener) {
        listener = l
    }

    /** Start editing mode. Enables map tap to add waypoints. */
    fun startEditing(name: String? = null) {
        routeName = name
        editing = true

        map.addOnMapClickListener(mapClickListener)
        Log.i(TAG, "Route editor started")
    }

    /** Stop editing mode. */
    fun stopEditing() {
        editing = false
        map.removeOnMapClickListener(mapClickListener)
        Log.i(TAG, "Route editor stopped")
    }

    /** Load existing route points for editing. */
    fun loadRoute(route: GpxRoute) {
        waypoints.clear()
        routeName = route.name
        for (p in route.points) {
            waypoints.add(LatLng(p.lat, p.lon))
        }
        updateLayers()
        listener?.onRouteChanged(waypoints.size)
    }

    /** Add a waypoint at the given position. */
    fun addWaypoint(latLng: LatLng) {
        waypoints.add(latLng)
        updateLayers()
        listener?.onRouteChanged(waypoints.size)
        Log.d(TAG, "Added waypoint #${waypoints.size}: ${latLng.latitude}, ${latLng.longitude}")
    }

    /** Remove the last waypoint. */
    fun removeLastWaypoint() {
        if (waypoints.isEmpty()) return
        waypoints.removeAt(waypoints.size - 1)
        updateLayers()
        listener?.onRouteChanged(waypoints.size)
        Log.d(TAG, "Removed last waypoint, ${waypoints.size} remaining")
    }

    /** Move a waypoint at the given index to a new position. */
    fun moveWaypoint(index: Int, latLng: LatLng) {
        if (index < 0 || index >= waypoints.size) return
        waypoints[index] = latLng
        updateLayers()
        listener?.onRouteChanged(waypoints.size)
    }

    /** Clear all waypoints. */
    fun clearRoute() {
        waypoints.clear()
        removeLayers()
        listener?.onRouteChanged(0)
    }

    /** Get the current route as a GpxRoute. */
    fun toGpxRoute(): GpxRoute {
        return GpxRoute(
            name = routeName,
            points = waypoints.map { GpxPoint(it.latitude, it.longitude, null) }
        )
    }

    /** Export the route as a GPX 1.1 XML string. */
    fun toGpxXml(): String {
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="NDKarte">""")
        sb.appendLine("""  <rte>""")
        if (routeName != null) {
            sb.appendLine("""    <name>${escapeXml(routeName!!)}</name>""")
        }
        for (wpt in waypoints) {
            sb.appendLine("""    <rtept lat="${wpt.latitude}" lon="${wpt.longitude}" />""")
        }
        sb.appendLine("""  </rte>""")
        sb.appendLine("""</gpx>""")
        return sb.toString()
    }

    /** Number of waypoints in the current route. */
    val waypointCount: Int get() = waypoints.size

    /** Whether editor is currently active. */
    val isEditing: Boolean get() = editing

    /**
     * Find the nearest waypoint within a tap radius.
     * Returns the index or -1 if none found.
     */
    fun findNearestWaypoint(latLng: LatLng, radiusM: Double = 50.0): Int {
        var bestIndex = -1
        var bestDist = Double.MAX_VALUE
        for ((i, wpt) in waypoints.withIndex()) {
            val dist = latLng.distanceTo(wpt)
            if (dist < radiusM && dist < bestDist) {
                bestDist = dist
                bestIndex = i
            }
        }
        return bestIndex
    }

    // -- Internal --

    private val mapClickListener = MapLibreMap.OnMapClickListener { latLng ->
        if (!editing) return@OnMapClickListener false
        addWaypoint(latLng)
        true
    }

    private fun updateLayers() {
        removeLayers()

        if (waypoints.isEmpty()) return

        // Route line
        if (waypoints.size >= 2) {
            val lineCoords = JSONArray()
            for (wpt in waypoints) {
                lineCoords.put(JSONArray().put(wpt.longitude).put(wpt.latitude))
            }

            val lineGeoJson = JSONObject().apply {
                put("type", "Feature")
                put("geometry", JSONObject().apply {
                    put("type", "LineString")
                    put("coordinates", lineCoords)
                })
                put("properties", JSONObject())
            }.toString()

            style.addSource(GeoJsonSource(EDITOR_LINE_SOURCE, lineGeoJson))
            style.addLayer(
                LineLayer(EDITOR_LINE_LAYER, EDITOR_LINE_SOURCE).withProperties(
                    PropertyFactory.lineColor(Color.parseColor(EDITOR_LINE_COLOR)),
                    PropertyFactory.lineWidth(3f),
                    PropertyFactory.lineOpacity(0.9f)
                )
            )
        }

        // Waypoint circles
        val pointFeatures = JSONArray()
        for ((i, wpt) in waypoints.withIndex()) {
            pointFeatures.put(JSONObject().apply {
                put("type", "Feature")
                put("geometry", JSONObject().apply {
                    put("type", "Point")
                    put("coordinates", JSONArray().put(wpt.longitude).put(wpt.latitude))
                })
                put("properties", JSONObject().apply {
                    put("index", i)
                })
            })
        }

        val pointsGeoJson = JSONObject().apply {
            put("type", "FeatureCollection")
            put("features", pointFeatures)
        }.toString()

        style.addSource(GeoJsonSource(EDITOR_POINT_SOURCE, pointsGeoJson))
        style.addLayer(
            CircleLayer(EDITOR_POINT_LAYER, EDITOR_POINT_SOURCE).withProperties(
                PropertyFactory.circleColor(Color.parseColor(EDITOR_POINT_COLOR)),
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(2f)
            )
        )
    }

    private fun removeLayers() {
        style.removeLayer(EDITOR_LINE_LAYER)
        style.removeSource(EDITOR_LINE_SOURCE)
        style.removeLayer(EDITOR_POINT_LAYER)
        style.removeSource(EDITOR_POINT_SOURCE)
    }

    private fun escapeXml(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    companion object {
        private const val TAG = "NDKarte.Editor"

        private const val EDITOR_LINE_SOURCE = "editor-line"
        private const val EDITOR_LINE_LAYER = "editor-line-layer"
        private const val EDITOR_LINE_COLOR = "#4CAF50"

        private const val EDITOR_POINT_SOURCE = "editor-points"
        private const val EDITOR_POINT_LAYER = "editor-points-layer"
        private const val EDITOR_POINT_COLOR = "#4CAF50"
    }
}
