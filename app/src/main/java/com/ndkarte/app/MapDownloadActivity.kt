package com.ndkarte.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Browse and download offline map regions from the Mapsforge FTP mirror.
 *
 * Displays a collapsible list of continents; tapping a continent lazily
 * fetches and expands its region entries. Each region shows its download
 * status and provides a Download / Resume / Cancel / Delete action button.
 */
class MapDownloadActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var tvStatus: TextView
    private lateinit var adapter: DownloadAdapter
    private lateinit var downloadManager: MapDownloadManager

    private val ioExecutor = Executors.newCachedThreadPool()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_download)

        listView = findViewById(R.id.regionList)
        tvStatus  = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        downloadManager = MapDownloadManager(this)
        adapter = DownloadAdapter(this, downloadManager)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            adapter.onItemClick(position)
        }

        loadContinents()
    }

    private fun loadContinents() {
        tvStatus.text = "Loading available regions…"
        tvStatus.visibility = View.VISIBLE

        ioExecutor.execute {
            val continents = try {
                downloadManager.fetchContinents()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch continents", e)
                emptyList()
            }

            runOnUiThread {
                if (continents.isEmpty()) {
                    tvStatus.text = "Failed to load regions. Check your internet connection."
                } else {
                    tvStatus.visibility = View.GONE
                    adapter.setContinents(continents)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
        downloadManager.shutdown()
    }

    companion object {
        private const val TAG = "NDKarte.DownloadUI"
    }

    // ── List item model ───────────────────────────────────────────────────────

    private sealed class ListItem {
        data class Header(
            val continent: String,
            val displayName: String,
            var expanded: Boolean = false,
            var loading: Boolean = false
        ) : ListItem()

        data class Region(val entry: MapDownloadManager.RegionEntry) : ListItem()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private inner class DownloadAdapter(
        private val ctx: Context,
        private val manager: MapDownloadManager
    ) : BaseAdapter() {

        private val items = mutableListOf<ListItem>()
        /** Cached region lists per continent (populated after first fetch). */
        private val regionCache = mutableMapOf<String, List<MapDownloadManager.RegionEntry>>()

        private val TYPE_HEADER = 0
        private val TYPE_REGION = 1

        fun setContinents(continents: List<String>) {
            items.clear()
            for (c in continents) {
                items.add(ListItem.Header(c, MapDownloadManager.toDisplayName(c)))
            }
            notifyDataSetChanged()
        }

        fun onItemClick(position: Int) {
            when (val item = items[position]) {
                is ListItem.Header -> toggleContinent(item, position)
                is ListItem.Region -> { /* region clicks handled by button */ }
            }
        }

        private fun toggleContinent(header: ListItem.Header, position: Int) {
            if (header.expanded) {
                collapseContinent(header, position)
            } else {
                expandContinent(header, position)
            }
        }

        private fun expandContinent(header: ListItem.Header, position: Int) {
            val cached = regionCache[header.continent]
            if (cached != null) {
                insertRegions(cached, position)
                header.expanded = true
                header.loading  = false
                notifyDataSetChanged()
                return
            }

            header.loading = true
            notifyDataSetChanged()

            ioExecutor.execute {
                val regions = try {
                    manager.fetchRegions(header.continent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch regions for ${header.continent}", e)
                    emptyList()
                }

                runOnUiThread {
                    regionCache[header.continent] = regions
                    header.loading  = false
                    header.expanded = true
                    insertRegions(regions, items.indexOf(header))
                    notifyDataSetChanged()
                }
            }
        }

        private fun insertRegions(regions: List<MapDownloadManager.RegionEntry>, headerPosition: Int) {
            // Remove any existing region children first (idempotent)
            val insertAt = headerPosition + 1
            while (insertAt < items.size && items[insertAt] is ListItem.Region) {
                items.removeAt(insertAt)
            }
            regions.forEachIndexed { i, r ->
                items.add(insertAt + i, ListItem.Region(r))
            }
        }

        private fun collapseContinent(header: ListItem.Header, position: Int) {
            header.expanded = false
            val removeFrom = position + 1
            while (removeFrom < items.size && items[removeFrom] is ListItem.Region) {
                items.removeAt(removeFrom)
            }
            notifyDataSetChanged()
        }

        // ── BaseAdapter ───────────────────────────────────────────────────────

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int =
            if (items[position] is ListItem.Header) TYPE_HEADER else TYPE_REGION

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val item = items[position]) {
                is ListItem.Header -> getHeaderView(item, convertView, parent)
                is ListItem.Region -> getRegionView(item.entry, convertView, parent)
            }
        }

        // ── Header view ───────────────────────────────────────────────────────

        private fun getHeaderView(header: ListItem.Header, convert: View?, parent: ViewGroup): View {
            val view = convert ?: layoutInflater.inflate(R.layout.item_download_header, parent, false)
            view.findViewById<TextView>(R.id.tvHeaderName).text = when {
                header.loading  -> "${header.displayName}  (loading…)"
                header.expanded -> "▼  ${header.displayName}"
                else            -> "▶  ${header.displayName}"
            }
            view.findViewById<TextView>(R.id.tvExpand).visibility = View.GONE
            return view
        }

        // ── Region view ───────────────────────────────────────────────────────

        private fun getRegionView(
            entry: MapDownloadManager.RegionEntry,
            convert: View?,
            parent: ViewGroup
        ): View {
            val view = convert ?: layoutInflater.inflate(R.layout.item_download_region, parent, false)

            val tvName     = view.findViewById<TextView>(R.id.tvRegionName)
            val tvStatus   = view.findViewById<TextView>(R.id.tvRegionStatus)
            val btn        = view.findViewById<Button>(R.id.btnDownload)
            val progress   = view.findViewById<ProgressBar>(R.id.progressBar)

            tvName.text = "  ${entry.displayName}"

            val state = manager.getState(entry)
            bindStateToRow(state, tvStatus, btn, progress, entry)

            return view
        }

        private fun bindStateToRow(
            state: MapDownloadManager.RegionDownloadState,
            tvStatus: TextView,
            btn: Button,
            progress: ProgressBar,
            entry: MapDownloadManager.RegionEntry
        ) {
            when (state.status) {
                MapDownloadManager.DownloadStatus.NOT_DOWNLOADED -> {
                    tvStatus.text = "Not downloaded"
                    btn.text = "Download"
                    progress.visibility = View.GONE
                    btn.setOnClickListener { startDownload(entry) }
                }
                MapDownloadManager.DownloadStatus.IN_PROGRESS -> {
                    val pct = (state.overallFraction * 100).toInt()
                    val demInfo = if (state.demTotal > 0)
                        "  •  DEM ${state.demDownloaded}/${state.demTotal}" else ""
                    tvStatus.text = "Downloading $pct%$demInfo"
                    btn.text = "Cancel"
                    progress.visibility = View.VISIBLE
                    progress.progress = pct
                    btn.setOnClickListener { cancelDownload(entry) }
                }
                MapDownloadManager.DownloadStatus.PAUSED -> {
                    val pct = (state.overallFraction * 100).toInt()
                    tvStatus.text = "Paused – $pct% done"
                    btn.text = "Resume"
                    progress.visibility = View.VISIBLE
                    progress.progress = pct
                    btn.setOnClickListener { startDownload(entry) }
                }
                MapDownloadManager.DownloadStatus.PARTIAL -> {
                    val pct = (state.overallFraction * 100).toInt()
                    tvStatus.text = "Partial – $pct% done"
                    btn.text = "Resume"
                    progress.visibility = View.VISIBLE
                    progress.progress = pct
                    btn.setOnClickListener { startDownload(entry) }
                }
                MapDownloadManager.DownloadStatus.COMPLETED -> {
                    val totalMb = MapDownloadManager.formatBytes(
                        state.map.total + state.poi.total + state.poly.total
                    )
                    tvStatus.text = "Downloaded ($totalMb)"
                    btn.text = "Delete"
                    progress.visibility = View.GONE
                    btn.setOnClickListener { deleteRegion(entry) }
                }
            }
        }

        // ── Download actions ──────────────────────────────────────────────────

        private fun startDownload(entry: MapDownloadManager.RegionEntry) {
            manager.downloadRegion(entry, object : MapDownloadManager.DownloadProgressListener {
                override fun onProgress(
                    region: MapDownloadManager.RegionEntry,
                    state: MapDownloadManager.RegionDownloadState
                ) {
                    runOnUiThread { notifyDataSetChanged() }
                }

                override fun onComplete(
                    region: MapDownloadManager.RegionEntry,
                    state: MapDownloadManager.RegionDownloadState
                ) {
                    runOnUiThread { notifyDataSetChanged() }
                }

                override fun onError(region: MapDownloadManager.RegionEntry, error: String) {
                    Log.e(TAG, "Download error for ${region.path}: $error")
                    runOnUiThread { notifyDataSetChanged() }
                }
            })
            notifyDataSetChanged()
        }

        private fun cancelDownload(entry: MapDownloadManager.RegionEntry) {
            manager.cancelDownload(entry)
            notifyDataSetChanged()
        }

        private fun deleteRegion(entry: MapDownloadManager.RegionEntry) {
            manager.deleteRegion(entry)
            notifyDataSetChanged()
        }
    }
}
