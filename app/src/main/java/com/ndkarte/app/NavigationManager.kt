package com.ndkarte.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.location.Location
import android.speech.tts.TextToSpeech
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
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages live track navigation with GPS positioning, drag-line
 * rendering, and text-to-speech guidance.
 *
 * Heavy computation (JNI calls, JSON serialization) runs on a
 * background thread. Only MapLibre layer updates and TTS calls
 * are posted back to the main thread.
 */
class NavigationManager(
    private val context: Context,
    private val locationProvider: LocationProvider
) : TextToSpeech.OnInitListener {

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var activeTrackPoints: List<GpxPoint>? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val navigating = AtomicBoolean(false)

    private val computeExecutor = Executors.newSingleThreadExecutor()

    // Cached values computed once at startNavigation
    private var cachedTrackJson: String? = null
    private var cachedTrackLength: Double = 0.0

    @Volatile
    private var lastProjection: ProjectionData? = null
    private var lastOffTrackAnnounceM = 0.0

    /** Parsed projection result from Rust. */
    data class ProjectionData(
        val point: GpxPoint,
        val segmentIndex: Int,
        val distanceM: Double,
        val distanceAlongM: Double
    )

    /** Bind to a MapLibre map instance. Call after map style is loaded. */
    fun bind(mapLibreMap: MapLibreMap, loadedStyle: Style) {
        map = mapLibreMap
        style = loadedStyle
    }

    /**
     * Start navigation on a track.
     *
     * Pre-computes cached track JSON and length on a background thread,
     * then begins GPS updates.
     */
    fun startNavigation(track: GpxTrack) {
        if (track.points.size < 2) {
            Log.w(TAG, "Track has fewer than 2 points, cannot navigate")
            return
        }

        activeTrackPoints = track.points
        navigating.set(true)
        lastProjection = null
        lastOffTrackAnnounceM = 0.0

        tts = TextToSpeech(context, this)

        // Pre-compute cached values on background thread
        computeExecutor.execute {
            cachedTrackJson = pointsToJson(track.points)
            cachedTrackLength = estimateTrackLength(track.points)

            // Start GPS after caches are ready
            runOnUiThread {
                locationProvider.start { location ->
                    onLocationUpdate(location)
                }
            }
        }

        Log.i(TAG, "Navigation started on track: ${track.name ?: "unnamed"}")
    }

    /** Stop navigation and clean up resources. */
    fun stopNavigation() {
        navigating.set(false)
        locationProvider.stop()
        clearNavigationLayers()

        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false

        activeTrackPoints = null
        lastProjection = null
        cachedTrackJson = null
        cachedTrackLength = 0.0

        Log.i(TAG, "Navigation stopped")
    }

    /** Whether navigation is currently active. */
    val isNavigating: Boolean get() = navigating.get()

    /** Latest projection data, or null if not navigating. */
    val currentProjection: ProjectionData? get() = lastProjection

    // -- TextToSpeech.OnInitListener --

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            if (ttsReady) {
                Log.i(TAG, "TTS initialized")
            } else {
                Log.w(TAG, "TTS language not supported")
            }
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    // -- Internal --

    /**
     * Handle a GPS location update. Dispatches heavy computation
     * (JNI projection call) to background thread, then posts UI
     * updates back to the main thread.
     */
    private fun onLocationUpdate(location: Location) {
        if (!navigating.get()) return

        val trackJson = cachedTrackJson ?: return
        val lat = location.latitude
        val lon = location.longitude

        computeExecutor.execute {
            if (!navigating.get()) return@execute

            val resultJson = RustBridge.projectOnTrack(lat, lon, trackJson)
            val projection = parseProjection(resultJson) ?: return@execute

            runOnUiThread {
                if (!navigating.get()) return@runOnUiThread

                val prev = lastProjection
                lastProjection = projection
                updateNavigationLayers(location, projection)
                checkOffTrackWarning(projection)
                checkProgressAnnouncement(projection, prev)
            }
        }
    }

    /**
     * Update map layers showing the rider position and drag-line.
     */
    private fun updateNavigationLayers(location: Location, projection: ProjectionData) {
        val currentStyle = style ?: return

        val riderLat = location.latitude
        val riderLon = location.longitude
        val projLat = projection.point.lat
        val projLon = projection.point.lon

        // Rider position (blue dot)
        val riderGeoJson = JSONObject().apply {
            put("type", "Feature")
            put("geometry", JSONObject().apply {
                put("type", "Point")
                put("coordinates", JSONArray().put(riderLon).put(riderLat))
            })
            put("properties", JSONObject())
        }.toString()

        // Drag-line from rider to projected point on track
        val dragLineGeoJson = JSONObject().apply {
            put("type", "Feature")
            put("geometry", JSONObject().apply {
                put("type", "LineString")
                put("coordinates", JSONArray().apply {
                    put(JSONArray().put(riderLon).put(riderLat))
                    put(JSONArray().put(projLon).put(projLat))
                })
            })
            put("properties", JSONObject())
        }.toString()

        // Projected point (small white dot on track)
        val projGeoJson = JSONObject().apply {
            put("type", "Feature")
            put("geometry", JSONObject().apply {
                put("type", "Point")
                put("coordinates", JSONArray().put(projLon).put(projLat))
            })
            put("properties", JSONObject())
        }.toString()

        if (currentStyle.getSource(RIDER_SOURCE_ID) != null) {
            (currentStyle.getSource(RIDER_SOURCE_ID) as? GeoJsonSource)
                ?.setGeoJson(riderGeoJson)
            (currentStyle.getSource(DRAGLINE_SOURCE_ID) as? GeoJsonSource)
                ?.setGeoJson(dragLineGeoJson)
            (currentStyle.getSource(PROJ_SOURCE_ID) as? GeoJsonSource)
                ?.setGeoJson(projGeoJson)
        } else {
            currentStyle.addSource(GeoJsonSource(RIDER_SOURCE_ID, riderGeoJson))
            currentStyle.addLayer(
                CircleLayer(RIDER_LAYER_ID, RIDER_SOURCE_ID).withProperties(
                    PropertyFactory.circleColor(Color.parseColor(RIDER_COLOR)),
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleStrokeColor(Color.WHITE),
                    PropertyFactory.circleStrokeWidth(3f)
                )
            )

            currentStyle.addSource(GeoJsonSource(DRAGLINE_SOURCE_ID, dragLineGeoJson))
            currentStyle.addLayer(
                LineLayer(DRAGLINE_LAYER_ID, DRAGLINE_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor(Color.parseColor(DRAGLINE_COLOR)),
                    PropertyFactory.lineWidth(2f),
                    PropertyFactory.lineOpacity(0.7f),
                    PropertyFactory.lineDasharray(arrayOf(3f, 2f))
                )
            )

            currentStyle.addSource(GeoJsonSource(PROJ_SOURCE_ID, projGeoJson))
            currentStyle.addLayer(
                CircleLayer(PROJ_LAYER_ID, PROJ_SOURCE_ID).withProperties(
                    PropertyFactory.circleColor(Color.WHITE),
                    PropertyFactory.circleRadius(5f),
                    PropertyFactory.circleStrokeColor(Color.parseColor(RIDER_COLOR)),
                    PropertyFactory.circleStrokeWidth(2f)
                )
            )
        }
    }

    private fun clearNavigationLayers() {
        val currentStyle = style ?: return
        for (layerId in listOf(RIDER_LAYER_ID, DRAGLINE_LAYER_ID, PROJ_LAYER_ID)) {
            currentStyle.removeLayer(layerId)
        }
        for (sourceId in listOf(RIDER_SOURCE_ID, DRAGLINE_SOURCE_ID, PROJ_SOURCE_ID)) {
            currentStyle.removeSource(sourceId)
        }
    }

    /**
     * Announce via TTS when the rider drifts significantly off-track.
     * Only announces once per threshold crossing to avoid spamming.
     */
    private fun checkOffTrackWarning(projection: ProjectionData) {
        if (!ttsReady) return

        val dist = projection.distanceM

        if (dist > OFF_TRACK_WARN_M && lastOffTrackAnnounceM <= OFF_TRACK_WARN_M) {
            speak("Off track. ${dist.toInt()} meters away.")
        } else if (dist > OFF_TRACK_CRITICAL_M && lastOffTrackAnnounceM <= OFF_TRACK_CRITICAL_M) {
            speak("Warning. ${dist.toInt()} meters off track.")
        } else if (dist < OFF_TRACK_RECOVERED_M && lastOffTrackAnnounceM >= OFF_TRACK_WARN_M) {
            speak("Back on track.")
        }

        lastOffTrackAnnounceM = dist
    }

    /**
     * Announce progress milestones along the track.
     * Uses cached track length instead of recalculating per update.
     */
    private fun checkProgressAnnouncement(projection: ProjectionData, prev: ProjectionData?) {
        if (!ttsReady) return
        if (prev == null) return
        if (cachedTrackLength < 1000.0) return

        val remaining = cachedTrackLength - projection.distanceAlongM
        val prevRemaining = cachedTrackLength - prev.distanceAlongM

        if (remaining < APPROACH_ANNOUNCE_M && prevRemaining >= APPROACH_ANNOUNCE_M) {
            speak("${remaining.toInt()} meters to destination.")
        }

        if (remaining < ARRIVAL_M && prevRemaining >= ARRIVAL_M) {
            speak("You have arrived.")
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        @Suppress("DEPRECATION")
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, null)
        Log.d(TAG, "TTS: $text")
    }

    /** Compute track length once. Called from background thread. */
    private fun estimateTrackLength(points: List<GpxPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            val dlat = points[i].lat - points[i - 1].lat
            val dlon = points[i].lon - points[i - 1].lon
            val cosLat = Math.toRadians((points[i].lat + points[i - 1].lat) / 2.0)
            val x = dlon * Math.cos(cosLat) * 111320.0
            val y = dlat * 111320.0
            total += Math.sqrt(x * x + y * y)
        }
        return total
    }

    private fun pointsToJson(points: List<GpxPoint>): String {
        val arr = JSONArray()
        for (p in points) {
            val obj = JSONObject()
            obj.put("lat", p.lat)
            obj.put("lon", p.lon)
            if (p.ele != null) obj.put("ele", p.ele)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun parseProjection(json: String): ProjectionData? {
        try {
            val obj = JSONObject(json)
            if (obj.has("error")) {
                Log.e(TAG, "Projection error: ${obj.getString("error")}")
                return null
            }

            val pt = obj.getJSONObject("point")
            return ProjectionData(
                point = GpxPoint(
                    lat = pt.getDouble("lat"),
                    lon = pt.getDouble("lon"),
                    ele = if (pt.has("ele")) pt.getDouble("ele") else null
                ),
                segmentIndex = obj.getInt("segment_index"),
                distanceM = obj.getDouble("distance_m"),
                distanceAlongM = obj.getDouble("distance_along_m")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse projection JSON", e)
            return null
        }
    }

    private fun runOnUiThread(action: () -> Unit) {
        (context as? Activity)?.runOnUiThread(action)
    }

    companion object {
        private const val TAG = "NDKarte.Nav"

        private const val RIDER_SOURCE_ID = "nav-rider"
        private const val RIDER_LAYER_ID = "nav-rider-layer"
        private const val RIDER_COLOR = "#1565C0"

        private const val DRAGLINE_SOURCE_ID = "nav-dragline"
        private const val DRAGLINE_LAYER_ID = "nav-dragline-layer"
        private const val DRAGLINE_COLOR = "#E53935"

        private const val PROJ_SOURCE_ID = "nav-projection"
        private const val PROJ_LAYER_ID = "nav-projection-layer"

        /** Distance threshold to warn about being off-track (meters). */
        private const val OFF_TRACK_WARN_M = 100.0
        private const val OFF_TRACK_CRITICAL_M = 500.0
        private const val OFF_TRACK_RECOVERED_M = 50.0

        /** Distance from end of track to announce approach (meters). */
        private const val APPROACH_ANNOUNCE_M = 500.0
        private const val ARRIVAL_M = 50.0
    }
}
