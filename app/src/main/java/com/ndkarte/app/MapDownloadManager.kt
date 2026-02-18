package com.ndkarte.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.floor

/**
 * Manages offline map downloads from the Mapsforge FTP mirror.
 *
 * For each region, downloads the vector map (.map), POI database (.poi),
 * boundary polygon (.poly), and all SRTM DEM elevation tiles (.hgt.zip)
 * that intersect the region's bounding box.
 *
 * Downloads are resumable: if a partial file exists, it resumes from the
 * last downloaded byte using HTTP Range requests (HTTP 206 Partial Content).
 *
 * Download state is persisted to download_state.json so progress survives
 * app restarts.
 */
class MapDownloadManager(private val context: Context) {

    // ── Data models ──────────────────────────────────────────────────────────

    data class RegionEntry(
        val continent: String,
        /** Relative path without extension, e.g. "europe/germany" or "europe/france/alsace" */
        val path: String,
        val displayName: String,
        val isSubRegion: Boolean
    )

    enum class DownloadStatus { NOT_DOWNLOADED, IN_PROGRESS, PAUSED, COMPLETED, PARTIAL }

    data class FileProgress(val downloaded: Long = 0L, val total: Long = 0L) {
        val fraction: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
    }

    data class RegionDownloadState(
        val status: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
        val map: FileProgress = FileProgress(),
        val poi: FileProgress = FileProgress(),
        val poly: FileProgress = FileProgress(),
        val demDownloaded: Int = 0,
        val demTotal: Int = 0
    ) {
        val overallFraction: Float get() {
            val totalBytes = map.total + poi.total + poly.total
            val downloadedBytes = map.downloaded + poi.downloaded + poly.downloaded
            return if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
        }
    }

    interface DownloadProgressListener {
        fun onProgress(region: RegionEntry, state: RegionDownloadState)
        fun onComplete(region: RegionEntry, state: RegionDownloadState)
        fun onError(region: RegionEntry, error: String)
    }

    // ── Base URLs ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "NDKarte.Download"

        const val BASE_MAP  = "https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/v5/"
        const val BASE_POI  = "https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/pois/"
        const val BASE_POLY = "https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/poly/"
        const val BASE_DEM  = "https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/dem/dem3/"

        private const val STATE_FILE = "download_state.json"
        private const val PROGRESS_SAVE_INTERVAL_BYTES = 4L * 1024 * 1024  // 4 MB

        /** Human-readable file size string. */
        fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        }

        /** Convert a file path segment like "great-britain" into "Great Britain". */
        fun toDisplayName(segment: String): String =
            segment.split("-").joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    // ── Directories ───────────────────────────────────────────────────────────

    private val mapsforgeDir get() = File(context.filesDir, "mapsforge")
    private val poisDir      get() = File(context.filesDir, "pois")
    private val polyDir      get() = File(context.filesDir, "poly")
    private val demDir       get() = File(context.filesDir, "dem")
    private val stateFile    get() = File(context.filesDir, STATE_FILE)

    // ── Threading ─────────────────────────────────────────────────────────────

    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val activeFutures = ConcurrentHashMap<String, Future<*>>()
    private val cancelFlags   = ConcurrentHashMap<String, Boolean>()

    // ── Catalog (loaded from bundled asset) ───────────────────────────────────

    /** Full region catalog read once from assets/map_catalog.json. */
    private val catalogJson: JSONObject by lazy {
        context.assets.open("map_catalog.json").bufferedReader().use {
            JSONObject(it.readText())
        }
    }

    /** Download states, kept in sync with the state JSON file. */
    private val states = ConcurrentHashMap<String, RegionDownloadState>()

    init {
        loadStates()
    }

    // ── Catalog access ────────────────────────────────────────────────────────

    /** Return all continent keys from the bundled catalog, sorted alphabetically. */
    fun fetchContinents(): List<String> =
        catalogJson.keys().asSequence().sorted().toList()

    /**
     * Return all downloadable regions for [continent] from the bundled catalog.
     *
     * The JSON supports two shapes per continent value:
     *  - JSONArray  → flat list of region slugs (no country sub-division)
     *  - JSONObject → map of country slug → null (leaf) or JSONArray of sub-region slugs
     */
    fun fetchRegions(continent: String): List<RegionEntry> {
        val entries = mutableListOf<RegionEntry>()
        when (val value = catalogJson.get(continent)) {
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    val slug = value.getString(i)
                    entries.add(RegionEntry(continent, "$continent/$slug",
                        toDisplayName(slug), false))
                }
            }
            is JSONObject -> {
                for (country in value.keys()) {
                    val subs = value.opt(country)
                    if (subs is JSONArray && subs.length() > 0) {
                        for (i in 0 until subs.length()) {
                            val sub = subs.getString(i)
                            entries.add(RegionEntry(continent,
                                "$continent/$country/$sub",
                                "${toDisplayName(country)} – ${toDisplayName(sub)}",
                                true))
                        }
                    } else {
                        entries.add(RegionEntry(continent, "$continent/$country",
                            toDisplayName(country), false))
                    }
                }
            }
        }
        return entries.sortedBy { it.displayName }
    }

    // ── Download control ──────────────────────────────────────────────────────

    /**
     * Start (or resume) downloading all files for a region.
     * Runs in a background thread; progress is reported via [listener].
     */
    fun downloadRegion(region: RegionEntry, listener: DownloadProgressListener) {
        cancelFlags[region.path] = false
        val future = executor.submit {
            try {
                performDownload(region, listener)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${region.path}", e)
                updateStatus(region, DownloadStatus.PARTIAL)
                listener.onError(region, e.message ?: "Unknown error")
            } finally {
                activeFutures.remove(region.path)
            }
        }
        activeFutures[region.path] = future
    }

    /** Cancel an in-progress download (leaves partial files intact for resumption). */
    fun cancelDownload(region: RegionEntry) {
        cancelFlags[region.path] = true
        activeFutures[region.path]?.cancel(true)
        activeFutures.remove(region.path)
        updateStatus(region, DownloadStatus.PAUSED)
        Log.i(TAG, "Cancelled download for ${region.path}")
    }

    /** Delete all downloaded files for a region and clear its state. */
    fun deleteRegion(region: RegionEntry) {
        cancelDownload(region)
        val segments = region.path.split("/")
        val continent = segments[0]
        val sub = segments.drop(1).joinToString("/")

        File(mapsforgeDir, "$continent/$sub.map").delete()
        File(poisDir, "$continent/$sub.poi").delete()
        File(polyDir, "$continent/$sub.poly").delete()
        // DEM tiles are shared between regions; do not delete them here.

        states.remove(region.path)
        saveStates()
        Log.i(TAG, "Deleted region ${region.path}")
    }

    fun getState(region: RegionEntry): RegionDownloadState =
        states[region.path] ?: RegionDownloadState()

    fun isDownloading(region: RegionEntry): Boolean =
        activeFutures.containsKey(region.path) && cancelFlags[region.path] != true

    // ── Core download logic ───────────────────────────────────────────────────

    private fun performDownload(region: RegionEntry, listener: DownloadProgressListener) {
        val segments = region.path.split("/")
        val continent = segments[0]
        val regionSubPath = segments.drop(1).joinToString("/")   // e.g. "germany" or "france/alsace"

        val mapUrl  = "$BASE_MAP$continent/$regionSubPath.map"
        val poiUrl  = "$BASE_POI$continent/$regionSubPath.poi"
        val polyUrl = "$BASE_POLY$continent/$regionSubPath.poly"

        val mapDest  = File(mapsforgeDir, "$continent/$regionSubPath.map")
        val poiDest  = File(poisDir,      "$continent/$regionSubPath.poi")
        val polyDest = File(polyDir,      "$continent/$regionSubPath.poly")

        var currentState = getState(region).copy(status = DownloadStatus.IN_PROGRESS)
        states[region.path] = currentState
        saveStates()

        fun isCancelled() = cancelFlags[region.path] == true

        // 1. Download .poly (small — needed to compute DEM tiles)
        if (polyDest.length() == 0L || currentState.poly.downloaded < currentState.poly.total) {
            Log.i(TAG, "Downloading poly: $polyUrl")
            downloadFile(polyUrl, polyDest,
                onProgress = { dl, total ->
                    currentState = currentState.copy(poly = FileProgress(dl, total))
                    states[region.path] = currentState
                    listener.onProgress(region, currentState)
                },
                cancelCheck = ::isCancelled
            )
        }
        if (isCancelled()) return

        // Mark poly complete and recompute DEM tiles now that the file exists
        currentState = currentState.copy(poly = FileProgress(polyDest.length(), polyDest.length()))

        // 2. Download .map (large)
        Log.i(TAG, "Downloading map: $mapUrl")
        downloadFile(mapUrl, mapDest,
            onProgress = { dl, total ->
                currentState = currentState.copy(map = FileProgress(dl, total))
                states[region.path] = currentState
                listener.onProgress(region, currentState)
            },
            cancelCheck = ::isCancelled
        )
        if (isCancelled()) return

        // 3. Download .poi (medium)
        Log.i(TAG, "Downloading poi: $poiUrl")
        downloadFile(poiUrl, poiDest,
            onProgress = { dl, total ->
                currentState = currentState.copy(poi = FileProgress(dl, total))
                states[region.path] = currentState
                listener.onProgress(region, currentState)
            },
            cancelCheck = ::isCancelled
        )
        if (isCancelled()) return

        // 4. Download DEM tiles derived from .poly bounding box
        if (polyDest.exists() && polyDest.length() > 0) {
            val demTiles = computeDemTilesForPoly(polyDest)
            Log.i(TAG, "DEM tiles for ${region.path}: ${demTiles.size} tiles")
            currentState = currentState.copy(demTotal = demTiles.size)
            var demDone = currentState.demDownloaded

            for ((band, filename) in demTiles) {
                if (isCancelled()) break
                val demUrl = "$BASE_DEM$band/$filename"
                val demDest = File(demDir, "$band/$filename")
                if (demDest.exists() && demDest.length() > 0) {
                    // Already downloaded (possibly by another region)
                    demDone++
                    currentState = currentState.copy(demDownloaded = demDone)
                    states[region.path] = currentState
                    listener.onProgress(region, currentState)
                    continue
                }
                downloadFile(demUrl, demDest,
                    onProgress = { _, _ -> /* fine-grained DEM progress not tracked per tile */ },
                    cancelCheck = ::isCancelled
                )
                demDone++
                currentState = currentState.copy(demDownloaded = demDone)
                states[region.path] = currentState
                listener.onProgress(region, currentState)
            }
        }

        if (!isCancelled()) {
            currentState = currentState.copy(status = DownloadStatus.COMPLETED)
            states[region.path] = currentState
            saveStates()
            listener.onComplete(region, currentState)
            Log.i(TAG, "Completed download for ${region.path}")
        }
    }

    /**
     * Download a single file with HTTP Range resume support.
     *
     * Returns true if the file was fully downloaded (or was already complete),
     * false if the download failed or was cancelled.
     * 404 responses are treated as success (file not available on server).
     */
    private fun downloadFile(
        url: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        cancelCheck: () -> Boolean
    ): Boolean {
        dest.parentFile?.mkdirs()
        val startByte = if (dest.exists()) dest.length() else 0L

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout    = 60_000
            conn.setRequestProperty("User-Agent", "NDKarte/1.0")
            if (startByte > 0L) {
                conn.setRequestProperty("Range", "bytes=$startByte-")
            }
            conn.connect()

            val code = conn.responseCode
            when {
                code == 404 -> {
                    Log.d(TAG, "404 (not available): $url")
                    return true
                }
                code == 416 -> {
                    // Range Not Satisfiable — file is already fully downloaded.
                    Log.d(TAG, "Already complete (416): $url")
                    onProgress(startByte, startByte)
                    return true
                }
                code != 200 && code != 206 -> {
                    Log.w(TAG, "HTTP $code for $url")
                    return false
                }
            }

            val contentLength = conn.contentLengthLong
            val totalBytes = if (code == 206 && startByte > 0) contentLength + startByte
                             else contentLength
            val append = (code == 206)

            var bytesSinceLastSave = 0L

            FileOutputStream(dest, append).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(128 * 1024)
                    var written = startByte
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        if (cancelCheck()) return false
                        out.write(buf, 0, n)
                        written += n
                        bytesSinceLastSave += n
                        onProgress(written, totalBytes)
                        if (bytesSinceLastSave >= PROGRESS_SAVE_INTERVAL_BYTES) {
                            saveStates()
                            bytesSinceLastSave = 0
                        }
                    }
                }
            }
            return true
        } finally {
            conn.disconnect()
        }
    }

    // ── DEM tile computation ──────────────────────────────────────────────────

    /**
     * Compute the list of DEM tiles (band dir, filename) that cover the
     * bounding box of the given .poly file.
     *
     * The SRTM DEM3 files are named like N48E008.hgt.zip and each covers
     * exactly 1°×1° starting at the given integer lat/lon.
     */
    fun computeDemTilesForPoly(polyFile: File): List<Pair<String, String>> {
        val bbox = parsePolyBbox(polyFile) ?: return emptyList()
        return computeDemTiles(bbox.minLat, bbox.maxLat, bbox.minLon, bbox.maxLon)
    }

    data class BoundingBox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

    /**
     * Parse lat/lon bounding box from a Osmosis .poly file.
     *
     * .poly format:
     *   region-name
     *   1
     *      lon  lat
     *      ...
     *   END
     *   END
     */
    fun parsePolyBbox(polyFile: File): BoundingBox? {
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        var hasCoords = false

        polyFile.bufferedReader().use { reader ->
            for (line in reader.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed == "END" || trimmed.first().isLetter()) continue
                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val lon = parts[0].toDoubleOrNull() ?: continue
                    val lat = parts[1].toDoubleOrNull() ?: continue
                    if (lat < minLat) minLat = lat
                    if (lat > maxLat) maxLat = lat
                    if (lon < minLon) minLon = lon
                    if (lon > maxLon) maxLon = lon
                    hasCoords = true
                }
            }
        }

        return if (hasCoords) BoundingBox(minLat, maxLat, minLon, maxLon) else null
    }

    /**
     * Enumerate 1°×1° DEM tiles covering the given bounding box.
     * Returns list of (band-directory, filename) pairs.
     */
    fun computeDemTiles(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ): List<Pair<String, String>> {
        val tiles = mutableListOf<Pair<String, String>>()
        val latStart = floor(minLat).toInt()
        val latEnd   = floor(maxLat).toInt()
        val lonStart = floor(minLon).toInt()
        val lonEnd   = floor(maxLon).toInt()

        for (lat in latStart..latEnd) {
            val latStr = if (lat >= 0) "N${lat.toString().padStart(2, '0')}"
                         else "S${(-lat).toString().padStart(2, '0')}"
            for (lon in lonStart..lonEnd) {
                val lonStr = if (lon >= 0) "E${lon.toString().padStart(3, '0')}"
                             else "W${(-lon).toString().padStart(3, '0')}"
                val filename = "$latStr$lonStr.hgt.zip"
                tiles.add(latStr to filename)
            }
        }
        return tiles
    }

    // ── State persistence ─────────────────────────────────────────────────────

    private fun updateStatus(region: RegionEntry, status: DownloadStatus) {
        val current = states[region.path] ?: RegionDownloadState()
        states[region.path] = current.copy(status = status)
        saveStates()
    }

    private fun saveStates() {
        try {
            val root = JSONObject()
            for ((path, state) in states) {
                val obj = JSONObject().apply {
                    put("status", state.status.name)
                    put("mapDownloaded", state.map.downloaded)
                    put("mapTotal", state.map.total)
                    put("poiDownloaded", state.poi.downloaded)
                    put("poiTotal", state.poi.total)
                    put("polyDownloaded", state.poly.downloaded)
                    put("polyTotal", state.poly.total)
                    put("demDownloaded", state.demDownloaded)
                    put("demTotal", state.demTotal)
                }
                root.put(path, obj)
            }
            val tmp = File(context.filesDir, "$STATE_FILE.tmp")
            tmp.writeText(root.toString(2))
            tmp.renameTo(stateFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save download state", e)
        }
    }

    private fun loadStates() {
        if (!stateFile.exists()) return
        try {
            val root = JSONObject(stateFile.readText())
            for (path in root.keys()) {
                val obj = root.getJSONObject(path)
                val status = try {
                    DownloadStatus.valueOf(obj.optString("status", "NOT_DOWNLOADED"))
                } catch (e: IllegalArgumentException) {
                    DownloadStatus.NOT_DOWNLOADED
                }
                // A download that was IN_PROGRESS when the app died is treated as PAUSED
                val effectiveStatus = if (status == DownloadStatus.IN_PROGRESS) DownloadStatus.PAUSED else status
                states[path] = RegionDownloadState(
                    status = effectiveStatus,
                    map  = FileProgress(obj.optLong("mapDownloaded"), obj.optLong("mapTotal")),
                    poi  = FileProgress(obj.optLong("poiDownloaded"), obj.optLong("poiTotal")),
                    poly = FileProgress(obj.optLong("polyDownloaded"), obj.optLong("polyTotal")),
                    demDownloaded = obj.optInt("demDownloaded"),
                    demTotal      = obj.optInt("demTotal")
                )
            }
            Log.i(TAG, "Loaded state for ${states.size} region(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load download state", e)
        }
    }

    /** Release background threads (call from onDestroy). */
    fun shutdown() {
        executor.shutdownNow()
    }
}
