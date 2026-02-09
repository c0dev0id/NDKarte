package com.ndkarte.app

/**
 * JNI bridge to the Rust core library (libndkarte.so).
 *
 * All native method declarations live here. The Rust side implements
 * these via the jni crate in rust-core/src/android_jni.rs.
 */
object RustBridge {

    init {
        System.loadLibrary("ndkarte")
    }

    /** Returns the rust-core library version string. */
    external fun version(): String

    /**
     * Parse a GPX file from raw bytes.
     *
     * Returns a JSON string with the structure:
     * { "tracks": [...], "routes": [...], "waypoints": [...] }
     *
     * On failure returns: { "error": "description" }
     */
    external fun parseGpx(data: ByteArray): String

    /**
     * Project a position onto a track and return the nearest point.
     *
     * [trackJson] is a JSON array of {lat, lon, ele?} objects.
     * Returns JSON: { "point": {}, "segment_index": N,
     *   "distance_m": N, "distance_along_m": N }
     */
    external fun projectOnTrack(lat: Double, lon: Double, trackJson: String): String

    /**
     * Simplify a track to a route using Ramer-Douglas-Peucker.
     *
     * [trackJson]: { "name"?: str, "points": [{lat, lon, ele?}] }
     * [toleranceM]: simplification tolerance in meters.
     * Returns: { "name"?: str, "points": [{lat, lon, ele?}] }
     */
    external fun trackToRoute(trackJson: String, toleranceM: Double): String

    /**
     * Convert a route to a track (direct point copy).
     *
     * [routeJson]: { "name"?: str, "points": [{lat, lon, ele?}] }
     * Returns: { "name"?: str, "points": [{lat, lon, ele?}] }
     */
    external fun routeToTrack(routeJson: String): String

    /**
     * Generate turn-by-turn instructions for a route.
     *
     * [routePointsJson]: JSON array of {lat, lon, ele?} objects.
     * Returns: JSON array of instruction objects with waypoint_index,
     *   distance_m, turn, and text fields.
     */
    external fun generateInstructions(routePointsJson: String): String
}
