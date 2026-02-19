package com.ndkarte.app

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.util.concurrent.Executors

/**
 * Interactive map download screen.
 *
 * Shows a zoomable/pannable world map with countries colour-coded by download status.
 * Tapping a country either initiates the download directly (leaf regions) or opens a
 * list of sub-regions (e.g. German states, French departments). A bottom panel shows
 * all active downloads with a 0–100 % progress bar and live speed readout.
 */
class MapDownloadActivity : Activity() {

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var mapView: InteractiveMapView
    private lateinit var tvPanelHint: TextView
    private lateinit var downloadsScroll: ScrollView
    private lateinit var downloadsPanel: LinearLayout

    // ── State ─────────────────────────────────────────────────────────────────

    private lateinit var downloadManager: MapDownloadManager
    private val ioExecutor = Executors.newCachedThreadPool()
    private val uiHandler  = Handler(Looper.getMainLooper())

    /** Per-region download progress rows in the bottom panel. */
    private data class PanelRow(
        val container: View,
        val tvStatus: TextView,
        val progress: ProgressBar
    )
    private val panelRows = mutableMapOf<String, PanelRow>()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_download)

        mapView        = findViewById(R.id.mapView)
        tvPanelHint    = findViewById(R.id.tvPanelHint)
        downloadsScroll = findViewById(R.id.downloadsScroll)
        downloadsPanel  = findViewById(R.id.downloadsPanel)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        downloadManager = MapDownloadManager(this)

        mapView.onRegionClickListener = object : InteractiveMapView.OnRegionClickListener {
            override fun onRegionClicked(region: InteractiveMapView.MapRegion) {
                if (region.hasSubRegions) showSubRegionDialog(region)
                else showActionDialog(region.path, region.label, null)
            }
        }

        loadBoundaryData()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
        downloadManager.shutdown()
    }

    // ── Boundary data loading ─────────────────────────────────────────────────

    private fun loadBoundaryData() {
        ioExecutor.execute {
            val json = try {
                assets.open("region_boundaries.json").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load region_boundaries.json", e)
                return@execute
            }

            runOnUiThread {
                mapView.loadRegions(json)
                restoreDownloadStates(json)
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

    // ── Sub-region dialog ─────────────────────────────────────────────────────

    private fun showSubRegionDialog(parentRegion: InteractiveMapView.MapRegion) {
        val continent = parentRegion.path.split("/").firstOrNull() ?: return

        ioExecutor.execute {
            val allRegions = try {
                downloadManager.fetchRegions(continent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch regions for $continent", e)
                emptyList()
            }

            // Keep only direct children of the tapped parent path
            val children = allRegions.filter { entry ->
                entry.path.startsWith(parentRegion.path + "/") || entry.path == parentRegion.path
            }

            runOnUiThread {
                if (children.isEmpty()) return@runOnUiThread

                val labels = children.map { entry ->
                    val state = downloadManager.getState(entry)
                    val badge = when (state.status) {
                        MapDownloadManager.DownloadStatus.COMPLETED   -> "  ✓"
                        MapDownloadManager.DownloadStatus.IN_PROGRESS -> "  ↓"
                        MapDownloadManager.DownloadStatus.PAUSED,
                        MapDownloadManager.DownloadStatus.PARTIAL     -> "  ⏸"
                        else -> ""
                    }
                    entry.displayName + badge
                }.toTypedArray()

                AlertDialog.Builder(this)
                    .setTitle(parentRegion.label)
                    .setItems(labels) { _, idx ->
                        val entry = children[idx]
                        showActionDialog(entry.path, entry.displayName, entry)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
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
        upsertPanelRow(path, label, 0f, 0L, extracting = false)
        showPanel()

        downloadManager.downloadRegion(entry, object : MapDownloadManager.DownloadProgressListener {

            override fun onProgress(
                region: MapDownloadManager.RegionEntry,
                state: MapDownloadManager.RegionDownloadState
            ) {
                val pct = state.overallFraction
                val speed = state.speedBytesPerSec
                val extracting = state.demDownloaded > 0 && state.demExtracted < state.demDownloaded
                runOnUiThread {
                    mapView.updateRegionStatus(region.path,
                        InteractiveMapView.RegionStatus.DOWNLOADING, pct)
                    upsertPanelRow(region.path, label, pct, speed, extracting)
                }
            }

            override fun onComplete(
                region: MapDownloadManager.RegionEntry,
                state: MapDownloadManager.RegionDownloadState
            ) {
                runOnUiThread {
                    mapView.updateRegionStatus(region.path,
                        InteractiveMapView.RegionStatus.COMPLETED, 1f)
                    upsertPanelRow(region.path, label, 1f, 0L, extracting = false)
                    uiHandler.postDelayed({ removePanelRow(region.path) }, 3_000L)
                }
            }

            override fun onError(region: MapDownloadManager.RegionEntry, error: String) {
                Log.e(TAG, "Download error for ${region.path}: $error")
                runOnUiThread {
                    mapView.updateRegionStatus(region.path, InteractiveMapView.RegionStatus.PAUSED)
                    upsertPanelRow(region.path, "$label  ⚠", 0f, 0L, extracting = false)
                }
            }
        })
    }

    // ── Bottom panel helpers ──────────────────────────────────────────────────

    private fun showPanel() {
        tvPanelHint.visibility    = View.GONE
        downloadsScroll.visibility = View.VISIBLE
    }

    private fun hidePanel() {
        downloadsScroll.visibility = View.GONE
        tvPanelHint.visibility    = View.VISIBLE
    }

    /**
     * Create or update a download row in the bottom panel.
     *
     * Status line format examples:
     *   "Austria  35%  (3.5 MB/s)"
     *   "Germany – Bayern  72%  (extracting…)"
     *   "France – Alsace  100%  ✓"
     */
    private fun upsertPanelRow(
        path: String,
        label: String,
        fraction: Float,
        speedBytesPerSec: Long,
        extracting: Boolean
    ) {
        val pct = (fraction * 100).toInt().coerceIn(0, 100)

        val speedStr = when {
            pct >= 100     -> "  ✓"
            extracting     -> "  (extracting…)"
            speedBytesPerSec > 0 ->
                "  (${MapDownloadManager.formatSpeed(speedBytesPerSec)})"
            else -> ""
        }
        val statusLine = "$label  $pct%$speedStr"

        val row = panelRows[path]
        if (row != null) {
            row.tvStatus.text  = statusLine
            row.progress.progress = pct
        } else {
            val newRow = buildPanelRow(path, statusLine, pct)
            panelRows[path] = newRow
            downloadsPanel.addView(newRow.container)
        }
    }

    private fun buildPanelRow(path: String, statusLine: String, pct: Int): PanelRow {
        // Root container
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(5), dp(12), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(android.graphics.Color.WHITE)
        }

        // Top row: status text + cancel button
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvStatus = TextView(this).apply {
            text = statusLine
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#263238"))
            layoutParams = LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnCancel = Button(this).apply {
            text = "✕"
            textSize = 11f
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(28))
            setOnClickListener {
                val entry = regionEntryFromPath(path)
                downloadManager.cancelDownload(entry)
                mapView.updateRegionStatus(path, InteractiveMapView.RegionStatus.PAUSED)
                removePanelRow(path)
            }
        }

        topRow.addView(tvStatus)
        topRow.addView(btnCancel)

        // Progress bar
        val pb = ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = pct
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(5)
            ).apply { topMargin = dp(3) }
        }

        container.addView(topRow)
        container.addView(pb)

        // Thin divider below each row
        container.addView(View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#E0E0E0"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1)
                .apply { topMargin = dp(4) }
        })

        return PanelRow(container, tvStatus, pb)
    }

    private fun removePanelRow(path: String) {
        val row = panelRows.remove(path) ?: return
        downloadsPanel.removeView(row.container)
        if (downloadsPanel.childCount == 0) hidePanel()
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** Build a RegionEntry from a slash-separated catalog path. */
    private fun regionEntryFromPath(path: String): MapDownloadManager.RegionEntry {
        val parts = path.split("/")
        val continent = parts[0]
        val displayName = parts.drop(1).joinToString(" \u2013 ") {
            MapDownloadManager.toDisplayName(it)
        }
        return MapDownloadManager.RegionEntry(
            continent    = continent,
            path         = path,
            displayName  = displayName,
            isSubRegion  = parts.size > 2
        )
    }

    private fun MapDownloadManager.DownloadStatus.toMapStatus(): InteractiveMapView.RegionStatus =
        when (this) {
            MapDownloadManager.DownloadStatus.IN_PROGRESS -> InteractiveMapView.RegionStatus.DOWNLOADING
            MapDownloadManager.DownloadStatus.PAUSED,
            MapDownloadManager.DownloadStatus.PARTIAL     -> InteractiveMapView.RegionStatus.PAUSED
            MapDownloadManager.DownloadStatus.COMPLETED   -> InteractiveMapView.RegionStatus.COMPLETED
            else -> InteractiveMapView.RegionStatus.NOT_DOWNLOADED
        }

    private fun dp(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val TAG = "NDKarte.DownloadUI"
    }
}
