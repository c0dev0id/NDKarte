package com.ndkarte.app

import android.content.Context
import android.location.Location
import android.speech.tts.TextToSpeech
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Turn-by-turn route navigator with TTS voice guidance.
 *
 * Uses Rust-generated instructions to announce upcoming turns
 * as the rider approaches each route waypoint.
 */
class RouteNavigator(
    private val context: Context,
    private val locationProvider: LocationProvider
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var navigating = false

    private var instructions = emptyList<InstructionData>()
    private var routePoints = emptyList<GpxPoint>()
    private var currentInstructionIndex = 0
    private var announcedIndices = mutableSetOf<Int>()

    data class InstructionData(
        val waypointIndex: Int,
        val distanceM: Double,
        val turn: String,
        val text: String
    )

    /** Start turn-by-turn navigation on a route. */
    fun startNavigation(route: GpxRoute) {
        if (route.points.size < 2) {
            Log.w(TAG, "Route has fewer than 2 points, cannot navigate")
            return
        }

        routePoints = route.points
        instructions = generateInstructions(route.points)
        currentInstructionIndex = 0
        announcedIndices.clear()
        navigating = true

        tts = TextToSpeech(context, this)

        locationProvider.start { location ->
            onLocationUpdate(location)
        }

        Log.i(TAG, "Route navigation started: ${route.name ?: "unnamed"}, " +
            "${instructions.size} instructions")
    }

    /** Stop route navigation. */
    fun stopNavigation() {
        navigating = false
        locationProvider.stop()

        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false

        instructions = emptyList()
        routePoints = emptyList()
        currentInstructionIndex = 0

        Log.i(TAG, "Route navigation stopped")
    }

    val isNavigating: Boolean get() = navigating

    // -- TextToSpeech.OnInitListener --

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            if (ttsReady && navigating && instructions.isNotEmpty()) {
                // Announce start instruction
                speak(instructions[0].text)
                announcedIndices.add(0)
            }
        }
    }

    // -- Internal --

    private fun onLocationUpdate(location: Location) {
        if (!navigating || !ttsReady) return
        if (instructions.size < 2) return

        // Find the next unannounced instruction
        val nextIdx = (currentInstructionIndex + 1).coerceAtMost(instructions.size - 1)
        if (nextIdx in announcedIndices) return

        val nextInstruction = instructions[nextIdx]
        val waypointIdx = nextInstruction.waypointIndex
        if (waypointIdx >= routePoints.size) return

        val waypoint = routePoints[waypointIdx]
        val distToWaypoint = distanceTo(
            location.latitude, location.longitude,
            waypoint.lat, waypoint.lon
        )

        // Announce when within approach distance
        if (distToWaypoint < APPROACH_DISTANCE_M) {
            speak(nextInstruction.text)
            announcedIndices.add(nextIdx)
            currentInstructionIndex = nextIdx
            Log.d(TAG, "Instruction #$nextIdx: ${nextInstruction.text}")
        }
    }

    private fun generateInstructions(points: List<GpxPoint>): List<InstructionData> {
        val arr = JSONArray()
        for (p in points) {
            val obj = JSONObject()
            obj.put("lat", p.lat)
            obj.put("lon", p.lon)
            if (p.ele != null) obj.put("ele", p.ele)
            arr.put(obj)
        }

        val json = RustBridge.generateInstructions(arr.toString())
        return parseInstructions(json)
    }

    private fun parseInstructions(json: String): List<InstructionData> {
        try {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                InstructionData(
                    waypointIndex = obj.getInt("waypoint_index"),
                    distanceM = obj.getDouble("distance_m"),
                    turn = obj.getString("turn"),
                    text = obj.getString("text")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse instructions", e)
            return emptyList()
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        @Suppress("DEPRECATION")
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, null)
        Log.d(TAG, "TTS: $text")
    }

    private fun distanceTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dlat = Math.toRadians(lat2 - lat1)
        val dlon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dlat / 2) * Math.sin(dlat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dlon / 2) * Math.sin(dlon / 2)
        return 2.0 * 6371008.8 * Math.asin(Math.sqrt(a))
    }

    companion object {
        private const val TAG = "NDKarte.RouteNav"
        private const val APPROACH_DISTANCE_M = 200.0
    }
}
