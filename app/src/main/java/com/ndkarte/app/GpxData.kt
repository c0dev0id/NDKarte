package com.ndkarte.app

import android.util.Log
import org.json.JSONObject

/**
 * Kotlin representation of parsed GPX data.
 *
 * Deserialized from the JSON string returned by RustBridge.parseGpx().
 */
data class GpxPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double?
)

data class GpxTrack(
    val name: String?,
    val points: List<GpxPoint>
)

data class GpxRoute(
    val name: String?,
    val points: List<GpxPoint>
)

data class GpxWaypoint(
    val name: String?,
    val point: GpxPoint,
    val icon: String? = null
)

data class GpxData(
    val tracks: List<GpxTrack>,
    val routes: List<GpxRoute>,
    val waypoints: List<GpxWaypoint>
) {
    companion object {
        private const val TAG = "NDKarte.Gpx"

        /**
         * Parse a GPX file from raw bytes via the Rust core.
         * Returns null on parse failure.
         */
        fun parse(data: ByteArray): GpxData? {
            val json = RustBridge.parseGpx(data)
            return fromJson(json)
        }

        /**
         * Deserialize GpxData from the JSON string produced by Rust.
         */
        fun fromJson(json: String): GpxData? {
            try {
                val root = JSONObject(json)

                if (root.has("error")) {
                    Log.e(TAG, "GPX parse error: ${root.getString("error")}")
                    return null
                }

                val tracks = (0 until root.getJSONArray("tracks").length()).map { i ->
                    val t = root.getJSONArray("tracks").getJSONObject(i)
                    GpxTrack(
                        name = t.optString("name", null),
                        points = parsePoints(t.getJSONArray("points"))
                    )
                }

                val routes = (0 until root.getJSONArray("routes").length()).map { i ->
                    val r = root.getJSONArray("routes").getJSONObject(i)
                    GpxRoute(
                        name = r.optString("name", null),
                        points = parsePoints(r.getJSONArray("points"))
                    )
                }

                val waypoints = (0 until root.getJSONArray("waypoints").length()).map { i ->
                    val w = root.getJSONArray("waypoints").getJSONObject(i)
                    val p = w.getJSONObject("point")
                    GpxWaypoint(
                        name = w.optString("name", null),
                        point = GpxPoint(
                            lat = p.getDouble("lat"),
                            lon = p.getDouble("lon"),
                            ele = if (p.has("ele")) p.getDouble("ele") else null
                        ),
                        icon = w.optString("icon", null)
                    )
                }

                return GpxData(tracks, routes, waypoints)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to deserialize GPX JSON", e)
                return null
            }
        }

        private fun parsePoints(arr: org.json.JSONArray): List<GpxPoint> {
            return (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                GpxPoint(
                    lat = p.getDouble("lat"),
                    lon = p.getDouble("lon"),
                    ele = if (p.has("ele")) p.getDouble("ele") else null
                )
            }
        }
    }
}
