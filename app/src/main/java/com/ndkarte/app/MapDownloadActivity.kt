package com.ndkarte.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONArray
import java.util.concurrent.Executors

/**
 * Interactive map download screen.
 *
 * Full-screen world map (InteractiveMapView) with:
 *  - Semi-transparent left-side overlay showing active downloads
 *    with live progress, speed, and error/retry support.
 *  - Centre-screen tooltip that appears for 3 s when a region is tapped.
 *  - User GPS location dot.
 *  - Bottom-right legend (Available / Downloading / Downloaded).
 */
class MapDownloadActivity : Activity() {

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var mapView: InteractiveMapView
    private lateinit var downloadsOverlay: View
    private lateinit var downloadsPanel: LinearLayout
    private lateinit var tvTooltip: TextView

    // ── State ─────────────────────────────────────────────────────────────────

    private lateinit var downloadManager: MapDownloadManager
    private val ioExecutor = Executors.newCachedThreadPool()
    private val uiHandler  = Handler(Looper.getMainLooper())

    /** Runnable that hides the tooltip after a delay. */
    private val hideTooltipRunnable = Runnable {
        tvTooltip.visibility = View.GONE
    }

    /**
     * Per-region download-progress rows in the left panel.
     *
     * [btnAction] is a cancel button during active download
     * and a "retry" button after an error.
     */
    private data class PanelRow(
        val container: View,
        val tvStatus: TextView,
        val progress: ProgressBar,
        val btnAction: Button
    )
    private val panelRows = mutableMapOf<String, PanelRow>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_download)

        mapView          = findViewById(R.id.mapView)
        downloadsOverlay = findViewById(R.id.downloadsOverlay)
        downloadsPanel   = findViewById(R.id.downloadsPanel)
        tvTooltip        = findViewById(R.id.tvTooltip)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        downloadManager = MapDownloadManager(this)

        mapView.onRegionClickListener = object : InteractiveMapView.OnRegionClickListener {
            override fun onRegionClicked(region: InteractiveMapView.MapRegion?) {
                if (region == null || region.path.isEmpty()) return
                // Show tooltip on tap too (fallback for touch devices without hover)
                showTooltipTimed(region.label)
                showActionDialog(region.path, region.label, null)
            }
            override fun onEmptyTapped() {
                dismissTooltip()
            }
            override fun onRegionHovered(region: InteractiveMapView.MapRegion?) {
                // Mouse/stylus hover: show tooltip immediately, no auto-hide timer
                if (region != null) showTooltipPersistent(region.label)
                else dismissTooltip()
            }
        }

        loadBoundaryData()
        requestLocationIfPermitted()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
        downloadManager.shutdown()
    }

    // ── Tooltip ───────────────────────────────────────────────────────────────

    /** Show tooltip and auto-hide after 3 s (used on tap for touch devices). */
    private fun showTooltipTimed(label: String) {
        uiHandler.removeCallbacks(hideTooltipRunnable)
        tvTooltip.text = label
        tvTooltip.visibility = View.VISIBLE
        uiHandler.postDelayed(hideTooltipRunnable, 3_000L)
    }

    /** Show tooltip and keep it visible until [dismissTooltip] is called (used on hover). */
    private fun showTooltipPersistent(label: String) {
        uiHandler.removeCallbacks(hideTooltipRunnable)
        tvTooltip.text = label
        tvTooltip.visibility = View.VISIBLE
    }

    private fun dismissTooltip() {
        uiHandler.removeCallbacks(hideTooltipRunnable)
        tvTooltip.visibility = View.GONE
    }

    // ── GPS location ──────────────────────────────────────────────────────────

    private fun requestLocationIfPermitted() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc != null) {
                mapView.setUserLocation(loc.longitude, loc.latitude)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not get last known location", e)
        }
    }

    // ── Boundary + world landmass data loading ────────────────────────────────

    private fun loadBoundaryData() {
        ioExecutor.execute {
            val regionsJson = try {
                assets.open("region_boundaries.json").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load region_boundaries.json", e)
                return@execute
            }

            val landmassJson = try {
                assets.open("world_landmass.json").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.w(TAG, "world_landmass.json not found — map will show without background", e)
                null
            }

            runOnUiThread {
                if (landmassJson != null) mapView.loadWorldLandmass(landmassJson)
                mapView.loadRegions(regionsJson)
                restoreDownloadStates(regionsJson)
            }
        }
    }

    /** Colour-code all regions based on their persisted download state. */
    private fun restoreDownloadStates(json: String) {
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val path = obj.optString("path").takeIf { it.isNotEmpty() } ?: continue
                val state = downloadManager.getStateByPath(path)
                mapView.updateRegionStatus(path, state.status.toMapStatus(), state.overallFraction)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore download states", e)
        }
    }

    // ── Action dialog (download / resume / cancel / delete) ───────────────────

    private fun showActionDialog(
        path: String,
        label: String,
        entry: MapDownloadManager.RegionEntry?
    ) {
        val state = downloadManager.getStateByPath(path)

        when (state.status) {
            MapDownloadManager.DownloadStatus.IN_PROGRESS -> {
                AlertDialog.Builder(this)
                    .setTitle(label)
                    .setMessage("This region is currently downloading.")
                    .setPositiveButton("Cancel download") { _, _ ->
                        val e = entry ?: regionEntryFromPath(path)
                        downloadManager.cancelDownload(e)
                        mapView.updateRegionStatus(path, InteractiveMapView.RegionStatus.PAUSED)
                        removePanelRow(path)
                    }
                    .setNegativeButton("Keep downloading", null)
                    .show()
            }

            MapDownloadManager.DownloadStatus.COMPLETED -> {
                val size = MapDownloadManager.formatBytes(
                    state.map.total + state.poi.total + state.poly.total)
                AlertDialog.Builder(this)
                    .setTitle(label)
                    .setMessage("Downloaded ($size). Remove from device?")
                    .setPositiveButton("Delete") { _, _ ->
                        val e = entry ?: regionEntryFromPath(path)
                        downloadManager.deleteRegion(e)
                        mapView.updateRegionStatus(path, InteractiveMapView.RegionStatus.NOT_DOWNLOADED)
                    }
                    .setNegativeButton("Keep", null)
                    .show()
            }

            MapDownloadManager.DownloadStatus.ERROR -> {
                AlertDialog.Builder(this)
                    .setTitle(label)
                    .setMessage("Download failed. Retry?")
                    .setPositiveButton("Retry") { _, _ ->
                        val e = entry ?: regionEntryFromPath(path)
                        startDownload(path, label, e)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            else -> {
                val actionLabel = when (state.status) {
                    MapDownloadManager.DownloadStatus.PAUSED,
                    MapDownloadManager.DownloadStatus.PARTIAL -> "Resume"
                    else -> "Download"
                }
                AlertDialog.Builder(this)
                    .setTitle(label)
                    .setMessage("$actionLabel map, POI, and elevation data for this region?")
                    .setPositiveButton(actionLabel) { _, _ ->
                        val e = entry ?: regionEntryFromPath(path)
                        startDownload(path, label, e)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    // ── Download execution ────────────────────────────────────────────────────

    private fun startDownload(
        path: String,
        label: String,
        entry: MapDownloadManager.RegionEntry
    ) {
        mapView.updateRegionStatus(path, InteractiveMapView.RegionStatus.DOWNLOADING, 0f)
        upsertPanelRow(path, label, 0f, 0L, extracting = false, isError = false)
        showPanel()

        downloadManager.downloadRegion(entry, object : MapDownloadManager.DownloadProgressListener {

            override fun onProgress(
                region: MapDownloadManager.RegionEntry,
                state: MapDownloadManager.RegionDownloadState
            ) {
                val pct   = state.overallFraction
                val speed = state.speedBytesPerSec
                val extracting = state.demDownloaded > 0 && state.demExtracted < state.demDownloaded
                runOnUiThread {
                    mapView.updateRegionStatus(region.path,
                        InteractiveMapView.RegionStatus.DOWNLOADING, pct)
                    upsertPanelRow(region.path, label, pct, speed, extracting, isError = false)
                }
            }

            override fun onComplete(
                region: MapDownloadManager.RegionEntry,
                state: MapDownloadManager.RegionDownloadState
            ) {
                runOnUiThread {
                    mapView.updateRegionStatus(region.path,
                        InteractiveMapView.RegionStatus.COMPLETED, 1f)
                    upsertPanelRow(region.path, label, 1f, 0L, extracting = false, isError = false)
                    uiHandler.postDelayed({ removePanelRow(region.path) }, 3_000L)
                }
            }

            override fun onError(region: MapDownloadManager.RegionEntry, error: String) {
                Log.e(TAG, "Download error for ${region.path}: $error")
                runOnUiThread {
                    mapView.updateRegionStatus(region.path, InteractiveMapView.RegionStatus.ERROR)
                    upsertPanelRow(region.path, label, 0f, 0L, extracting = false, isError = true)
                }
            }
        })
    }

    // ── Panel helpers ─────────────────────────────────────────────────────────

    private fun showPanel() {
        downloadsOverlay.visibility = View.VISIBLE
    }

    private fun hidePanel() {
        downloadsOverlay.visibility = View.GONE
    }

    /**
     * Create or update a download row.
     *
     * Status label examples:
     *   "Austria  35%  (3.5 MB/s)"
     *   "Germany – Bayern  72%  (extracting…)"
     *   "France – Alsace  100%  ✓"
     *   "Italy  ⚠ Failed"
     */
    private fun upsertPanelRow(
        path: String,
        label: String,
        fraction: Float,
        speedBytesPerSec: Long,
        extracting: Boolean,
        isError: Boolean
    ) {
        val pct = (fraction * 100).toInt().coerceIn(0, 100)

        val suffix = when {
            isError        -> "  ⚠ Failed"
            pct >= 100     -> "  ✓"
            extracting     -> "  (extracting…)"
            speedBytesPerSec > 0 ->
                "  (${MapDownloadManager.formatSpeed(speedBytesPerSec)})"
            else -> ""
        }
        val statusLine = "$label  $pct%$suffix"

        val row = panelRows[path]
        if (row != null) {
            row.tvStatus.text  = statusLine
            row.progress.progress = pct
            // Flip the action button between cancel and retry
            if (isError) {
                row.btnAction.text = "↺"
                row.btnAction.setBackgroundColor(android.graphics.Color.parseColor("#7B1FA2"))
                row.btnAction.setOnClickListener {
                    val entry = regionEntryFromPath(path)
                    startDownload(path, label, entry)
                }
            } else {
                row.btnAction.text = "✕"
                row.btnAction.setBackgroundColor(android.graphics.Color.parseColor("#333333"))
                row.btnAction.setOnClickListener {
                    downloadManager.cancelDownload(regionEntryFromPath(path))
                    mapView.updateRegionStatus(path, InteractiveMapView.RegionStatus.PAUSED)
                    removePanelRow(path)
                }
            }
        } else {
            val newRow = buildPanelRow(path, label, statusLine, pct, isError)
            panelRows[path] = newRow
            downloadsPanel.addView(newRow.container)
        }
    }

    private fun buildPanelRow(
        path: String,
        label: String,
        statusLine: String,
        pct: Int,
        isError: Boolean
    ): PanelRow {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Transparent: the overlay panel itself provides the background
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        // Top row: status text + action button (cancel or retry)
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvStatus = TextView(this).apply {
            text = statusLine
            textSize = 11f
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnAction = Button(this).apply {
            text = if (isError) "↺" else "✕"
            textSize = 11f
            setPadding(dp(6), 0, dp(6), 0)
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(
                android.graphics.Color.parseColor(if (isError) "#7B1FA2" else "#333333"))
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(24))
            setOnClickListener {
                if (isError) {
                    startDownload(path, label, regionEntryFromPath(path))
                } else {
                    downloadManager.cancelDownload(regionEntryFromPath(path))
                    mapView.updateRegionStatus(path, InteractiveMapView.RegionStatus.PAUSED)
                    removePanelRow(path)
                }
            }
        }

        topRow.addView(tvStatus)
        topRow.addView(btnAction)

        // Thin progress bar
        val pb = ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = pct
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
            ).apply { topMargin = dp(3) }
        }

        // Thin separator
        val divider = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#44FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = dp(4) }
        }

        container.addView(topRow)
        container.addView(pb)
        container.addView(divider)

        return PanelRow(container, tvStatus, pb, btnAction)
    }

    private fun removePanelRow(path: String) {
        val row = panelRows.remove(path) ?: return
        downloadsPanel.removeView(row.container)
        if (downloadsPanel.childCount == 0) hidePanel()
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun regionEntryFromPath(path: String): MapDownloadManager.RegionEntry {
        val parts = path.split("/")
        val continent = parts[0]
        val displayName = parts.drop(1).joinToString(" \u2013 ") {
            MapDownloadManager.toDisplayName(it)
        }
        return MapDownloadManager.RegionEntry(
            continent   = continent,
            path        = path,
            displayName = displayName,
            isSubRegion = parts.size > 2
        )
    }

    private fun MapDownloadManager.DownloadStatus.toMapStatus(): InteractiveMapView.RegionStatus =
        when (this) {
            MapDownloadManager.DownloadStatus.IN_PROGRESS -> InteractiveMapView.RegionStatus.DOWNLOADING
            MapDownloadManager.DownloadStatus.PAUSED,
            MapDownloadManager.DownloadStatus.PARTIAL     -> InteractiveMapView.RegionStatus.PAUSED
            MapDownloadManager.DownloadStatus.COMPLETED   -> InteractiveMapView.RegionStatus.COMPLETED
            MapDownloadManager.DownloadStatus.ERROR       -> InteractiveMapView.RegionStatus.ERROR
            else -> InteractiveMapView.RegionStatus.NOT_DOWNLOADED
        }

    private fun dp(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "NDKarte.DownloadUI"
    }
}
