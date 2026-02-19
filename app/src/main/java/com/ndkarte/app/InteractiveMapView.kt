package com.ndkarte.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.json.JSONArray
import kotlin.math.*

/**
 * Interactive world map view for browsing and downloading map regions.
 *
 * Renders a world landmass base layer plus semi-transparent region overlays
 * using Mercator projection. Supports pan, pinch-zoom, and tap gestures.
 * Regions are color-coded by download status. No text labels are drawn on canvas.
 */
class InteractiveMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Data model ────────────────────────────────────────────────────────────

    data class MapRegion(
        val path: String,
        val label: String,
        val polygon: List<PointF>,   // lon, lat coordinate pairs
        val centerLon: Float,
        val centerLat: Float,
        val hasSubRegions: Boolean
    )

    enum class RegionStatus { NOT_DOWNLOADED, DOWNLOADING, PAUSED, COMPLETED, ERROR }

    private data class WorldLandmass(val polygon: List<PointF>)

    interface OnRegionClickListener {
        fun onRegionClicked(region: MapRegion?)
        fun onEmptyTapped()
    }

    var onRegionClickListener: OnRegionClickListener? = null

    // ── Region data ───────────────────────────────────────────────────────────

    private val regions = mutableListOf<MapRegion>()
    private val regionStatusMap = mutableMapOf<String, RegionStatus>()
    private val regionProgressMap = mutableMapOf<String, Float>()
    private var selectedPath: String? = null

    // ── World landmass data ───────────────────────────────────────────────────

    private val worldLandmasses = mutableListOf<WorldLandmass>()

    // ── Tooltip state (set by handleTap; Activity reads and shows a TextView) ─

    var tooltipLabel: String? = null
    var tooltipX: Float = 0f
    var tooltipY: Float = 0f

    // ── User location ─────────────────────────────────────────────────────────

    var userLon: Double? = null
    var userLat: Double? = null

    // ── Viewport (Mercator) ───────────────────────────────────────────────────

    private var centerLon = 10.0    // Shows whole world when zoom=2
    private var centerLat = 20.0
    private var zoom = 2.0          // pixels per degree longitude
    private val minZoom = 1.5
    private val maxZoom = 80.0

    // ── Touch state ───────────────────────────────────────────────────────────

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var hasMoved = false
    private val tapThreshold = 12f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldZoom = zoom
                val focusLon = (detector.focusX - width / 2f) / oldZoom + centerLon
                val focalMerc = mercY(centerLat) + (height / 2f - detector.focusY) / oldZoom
                zoom = (zoom * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                centerLon = focusLon - (detector.focusX - width / 2f) / zoom
                val newCenterMerc = focalMerc - (height / 2f - detector.focusY) / zoom
                centerLat = inverseMercY(newCenterMerc)
                clampViewport()
                invalidate()
                return true
            }
        })

    // ── Paints ────────────────────────────────────────────────────────────────

    // Ocean background: very dark navy
    private val oceanPaint = Paint().apply {
        color = Color.parseColor("#0D1B2A")
    }

    // World landmass fill: dark green-gray
    private val landmassFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1C3A2A")
    }

    // World landmass border: slightly lighter green-gray
    private val landmassBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#243D2E")
        strokeWidth = 0.5f
    }

    // Region overlay fill (color set dynamically per status)
    private val regionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Region border – normal
    private val regionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3D6080")
        strokeWidth = 0.8f
    }

    // Region border – active (downloading / completed)
    private val regionActiveBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#64B5F6")
        strokeWidth = 1.5f
    }

    // User location dot
    private val userDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFEB3B")
    }

    private val userDotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f
    }

    // ── Status colors (with alpha baked in) ──────────────────────────────────

    private val colorNotDownloaded = Color.argb(180, 0x2A, 0x4F, 0x6E)  // #2A4F6E a=180
    private val colorDownloading   = Color.argb(200, 0x15, 0x65, 0xC0)  // #1565C0 a=200
    private val colorPaused        = Color.argb(160, 0x4A, 0x4A, 0x2E)  // #4A4A2E a=160
    private val colorCompleted     = Color.argb(200, 0x0D, 0x47, 0xA1)  // #0D47A1 a=200
    private val colorError         = Color.argb(180, 0x8B, 0x1A, 0x1A)  // #8B1A1A a=180

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Load region boundary data from a JSON string.
     * Initialises all loaded regions to NOT_DOWNLOADED in regionStatusMap.
     */
    fun loadRegions(json: String) {
        regions.clear()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val polyArr = obj.getJSONArray("polygon")
            val points = mutableListOf<PointF>()
            for (j in 0 until polyArr.length()) {
                val pt = polyArr.getJSONArray(j)
                points.add(PointF(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat()))
            }
            val center = obj.getJSONArray("center")
            val region = MapRegion(
                path = obj.getString("path"),
                label = obj.getString("label"),
                polygon = points,
                centerLon = center.getDouble(0).toFloat(),
                centerLat = center.getDouble(1).toFloat(),
                hasSubRegions = obj.optBoolean("hasSubRegions", false)
            )
            regions.add(region)
            // Initialise all regions as NOT_DOWNLOADED so they show as downloadable
            regionStatusMap.getOrPut(region.path) { RegionStatus.NOT_DOWNLOADED }
        }
        Log.i(TAG, "Loaded ${regions.size} map regions")
        invalidate()
    }

    /**
     * Load world landmass polygons from a JSON array of
     * {"id":"xx","polygon":[[lon,lat],...]} objects.
     */
    fun loadWorldLandmass(json: String) {
        worldLandmasses.clear()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val polyArr = obj.getJSONArray("polygon")
            val points = mutableListOf<PointF>()
            for (j in 0 until polyArr.length()) {
                val pt = polyArr.getJSONArray(j)
                points.add(PointF(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat()))
            }
            if (points.size >= 3) {
                worldLandmasses.add(WorldLandmass(points))
            }
        }
        Log.i(TAG, "Loaded ${worldLandmasses.size} world landmass polygons")
        invalidate()
    }

    /** Set the user's current GPS location; pass null lon/lat to clear. */
    fun setUserLocation(lon: Double, lat: Double) {
        userLon = lon
        userLat = lat
        invalidate()
    }

    /** Update the download status of a region (redraws the map). */
    fun updateRegionStatus(path: String, status: RegionStatus, progress: Float = 0f) {
        regionStatusMap[path] = status
        regionProgressMap[path] = progress
        invalidate()
    }

    /**
     * For countries with sub-regions, update the parent country status
     * based on the aggregate status of its sub-region downloads.
     */
    fun updateParentStatus(parentPath: String, subStatuses: List<RegionStatus>) {
        val status = when {
            subStatuses.all { it == RegionStatus.COMPLETED } -> RegionStatus.COMPLETED
            subStatuses.any { it == RegionStatus.DOWNLOADING } -> RegionStatus.DOWNLOADING
            subStatuses.any { it == RegionStatus.PAUSED } -> RegionStatus.PAUSED
            subStatuses.any { it == RegionStatus.COMPLETED } -> RegionStatus.COMPLETED
            subStatuses.any { it == RegionStatus.ERROR } -> RegionStatus.ERROR
            else -> RegionStatus.NOT_DOWNLOADED
        }
        regionStatusMap[parentPath] = status
        invalidate()
    }

    /**
     * Track the last-tapped region path (no visual highlight change;
     * drawing is driven by status color only).
     */
    fun setSelectedRegion(path: String?) {
        selectedPath = path
        // No invalidate needed: selection no longer changes drawing
    }

    // ── Mercator projection ───────────────────────────────────────────────────

    private fun mercY(lat: Double): Double =
        ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2)) * 180.0 / Math.PI

    private fun inverseMercY(merc: Double): Double =
        Math.toDegrees(2.0 * atan(exp(Math.toRadians(merc))) - Math.PI / 2)

    private fun lonToScreenX(lon: Double): Float =
        (width / 2f + (lon - centerLon) * zoom).toFloat()

    private fun latToScreenY(lat: Double): Float =
        (height / 2f - (mercY(lat) - mercY(centerLat)) * zoom).toFloat()

    private fun screenXToLon(x: Float): Double =
        (x - width / 2f) / zoom + centerLon

    private fun screenYToMerc(y: Float, z: Double = zoom): Double =
        mercY(centerLat) + (height / 2f - y) / z

    private fun screenYToLat(y: Float): Double =
        inverseMercY(screenYToMerc(y))

    private fun clampViewport() {
        centerLon = centerLon.coerceIn(-180.0, 180.0)
        centerLat = centerLat.coerceIn(-80.0, 80.0)
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Ocean background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), oceanPaint)

        // 2. World landmasses (base geography layer)
        for (landmass in worldLandmasses) {
            drawLandmassPolygon(canvas, landmass)
        }

        // 3. Region overlays (semi-transparent status-coloured polygons)
        for (region in regions) {
            drawRegionOverlay(canvas, region)
        }

        // 4. User location dot
        val uLon = userLon
        val uLat = userLat
        if (uLon != null && uLat != null) {
            val sx = lonToScreenX(uLon)
            val sy = latToScreenY(uLat)
            val radius = 8f
            canvas.drawCircle(sx, sy, radius, userDotPaint)
            canvas.drawCircle(sx, sy, radius, userDotBorderPaint)
        }
    }

    private fun buildPath(polygon: List<PointF>): Path? {
        if (polygon.size < 3) return null
        val path = Path()
        for ((i, pt) in polygon.withIndex()) {
            val sx = lonToScreenX(pt.x.toDouble())
            val sy = latToScreenY(pt.y.toDouble())
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        path.close()
        return path
    }

    private fun drawLandmassPolygon(canvas: Canvas, landmass: WorldLandmass) {
        val path = buildPath(landmass.polygon) ?: return
        canvas.drawPath(path, landmassFillPaint)
        canvas.drawPath(path, landmassBorderPaint)
    }

    private fun drawRegionOverlay(canvas: Canvas, region: MapRegion) {
        if (region.polygon.size < 3) return

        // Quick off-screen cull
        val cx = lonToScreenX(region.centerLon.toDouble())
        val cy = latToScreenY(region.centerLat.toDouble())
        val margin = (zoom * 30).toFloat()
        if (cx < -margin || cx > width + margin || cy < -margin || cy > height + margin) return

        val path = buildPath(region.polygon) ?: return

        val status = regionStatusMap[region.path] ?: RegionStatus.NOT_DOWNLOADED

        regionFillPaint.color = when (status) {
            RegionStatus.NOT_DOWNLOADED -> colorNotDownloaded
            RegionStatus.DOWNLOADING   -> colorDownloading
            RegionStatus.PAUSED        -> colorPaused
            RegionStatus.COMPLETED     -> colorCompleted
            RegionStatus.ERROR         -> colorError
        }

        canvas.drawPath(path, regionFillPaint)

        val useActiveBorder = status == RegionStatus.DOWNLOADING || status == RegionStatus.COMPLETED
        canvas.drawPath(path, if (useActiveBorder) regionActiveBorderPaint else regionBorderPaint)
    }

    // ── Touch handling ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                touchStartX = event.x
                touchStartY = event.y
                hasMoved = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY

                    if (!hasMoved) {
                        val totalDx = abs(event.x - touchStartX)
                        val totalDy = abs(event.y - touchStartY)
                        if (totalDx > tapThreshold || totalDy > tapThreshold) {
                            hasMoved = true
                        }
                    }

                    if (hasMoved) {
                        centerLon -= dx / zoom
                        val mercCenter = mercY(centerLat)
                        val newMercCenter = mercCenter + dy / zoom
                        centerLat = inverseMercY(newMercCenter)
                        clampViewport()
                        invalidate()
                    }

                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!hasMoved && !scaleDetector.isInProgress) {
                    handleTap(event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                hasMoved = true
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(x: Float, y: Float) {
        val tapLon = screenXToLon(x).toFloat()
        val tapLat = screenYToLat(y).toFloat()

        // Hit-test regions (reverse so frontmost/last-drawn wins)
        for (region in regions.reversed()) {
            if (pointInPolygon(region.polygon, tapLon, tapLat)) {
                selectedPath = region.path
                tooltipLabel = region.label
                tooltipX = x
                tooltipY = y
                invalidate()
                onRegionClickListener?.onRegionClicked(region)
                return
            }
        }

        // Tapped empty area
        selectedPath = null
        tooltipLabel = null
        invalidate()
        onRegionClickListener?.onEmptyTapped()
    }

    /** Ray-casting point-in-polygon test. */
    private fun pointInPolygon(polygon: List<PointF>, x: Float, y: Float): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].x; val yi = polygon[i].y
            val xj = polygon[j].x; val yj = polygon[j].y
            if ((yi > y) != (yj > y) &&
                x < (xj - xi) * (y - yi) / (yj - yi) + xi
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    companion object {
        private const val TAG = "NDKarte.MapView"
    }
}
