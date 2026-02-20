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
 * Renders a world landmass base layer plus region overlays using Mercator
 * projection. Supports pan, pinch-zoom, tap, and mouse-hover gestures.
 *
 * Visual contract:
 *  - Ocean: dark navy background.
 *  - Landmasses: filled dark-green polygons; coastline border.
 *  - NOT_DOWNLOADED regions: thin outline strokes ONLY, clipped to land —
 *    the landmass is fully visible; faint lines show downloadable areas.
 *  - Active regions (Downloading / Paused / Completed / Error):
 *    semi-transparent fill + border, also clipped to landmass so they
 *    never bleed into the ocean.
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
        val polygon: List<PointF>,   // lon/lat coordinate pairs
        val centerLon: Float,
        val centerLat: Float,
        val hasSubRegions: Boolean
    )

    enum class RegionStatus { NOT_DOWNLOADED, DOWNLOADING, PAUSED, COMPLETED, ERROR }

    private data class WorldLandmass(val polygon: List<PointF>)

    interface OnRegionClickListener {
        fun onRegionClicked(region: MapRegion?)
        fun onEmptyTapped()
        /** Called on mouse/stylus hover; null means pointer left all regions. */
        fun onRegionHovered(region: MapRegion?) {}   // default no-op
    }

    var onRegionClickListener: OnRegionClickListener? = null

    // ── Region data ───────────────────────────────────────────────────────────

    private val regions = mutableListOf<MapRegion>()
    private val regionStatusMap  = mutableMapOf<String, RegionStatus>()
    private val regionProgressMap = mutableMapOf<String, Float>()
    private var selectedPath: String? = null

    // ── World landmass data ───────────────────────────────────────────────────

    private val worldLandmasses = mutableListOf<WorldLandmass>()

    // ── Tooltip state ─────────────────────────────────────────────────────────

    var tooltipLabel: String? = null
    var tooltipX: Float = 0f
    var tooltipY: Float = 0f

    // ── User location ─────────────────────────────────────────────────────────

    var userLon: Double? = null
    var userLat: Double? = null

    // ── Viewport (Mercator) ───────────────────────────────────────────────────

    private var centerLon = 10.0
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
                val focusLon  = (detector.focusX - width / 2f) / oldZoom + centerLon
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

    private val oceanPaint = Paint().apply {
        color = Color.parseColor("#0D1B2A")
    }

    private val landmassFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1C3A2A")
    }

    private val landmassBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#2B5040")
        strokeWidth = 0.8f
    }

    /**
     * Border paint for NOT_DOWNLOADED regions — thin, semi-transparent, so the
     * landmass is fully visible and only faint grid lines appear.
     */
    private val regionOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(100, 90, 180, 130)   // translucent green-white
        strokeWidth = 0.7f
    }

    /** Fill for active download-state regions. Color set dynamically. */
    private val regionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Border for active download-state regions. */
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

    // ── Status fill colors (alpha baked in) ──────────────────────────────────

    private val colorDownloading = Color.argb(170, 0x15, 0x65, 0xC0)
    private val colorPaused      = Color.argb(140, 0x4A, 0x4A, 0x2E)
    private val colorCompleted   = Color.argb(180, 0x0D, 0x47, 0xA1)
    private val colorError       = Color.argb(160, 0x8B, 0x1A, 0x1A)

    // ── Public API ────────────────────────────────────────────────────────────

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
                path          = obj.getString("path"),
                label         = obj.getString("label"),
                polygon       = points,
                centerLon     = center.getDouble(0).toFloat(),
                centerLat     = center.getDouble(1).toFloat(),
                hasSubRegions = obj.optBoolean("hasSubRegions", false)
            )
            regions.add(region)
            regionStatusMap.getOrPut(region.path) { RegionStatus.NOT_DOWNLOADED }
        }
        Log.i(TAG, "Loaded ${regions.size} map regions")
        invalidate()
    }

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
            if (points.size >= 3) worldLandmasses.add(WorldLandmass(points))
        }
        Log.i(TAG, "Loaded ${worldLandmasses.size} world landmass polygons")
        invalidate()
    }

    fun setUserLocation(lon: Double, lat: Double) {
        userLon = lon; userLat = lat; invalidate()
    }

    fun updateRegionStatus(path: String, status: RegionStatus, progress: Float = 0f) {
        regionStatusMap[path]   = status
        regionProgressMap[path] = progress
        invalidate()
    }

    fun updateParentStatus(parentPath: String, subStatuses: List<RegionStatus>) {
        val status = when {
            subStatuses.all { it == RegionStatus.COMPLETED }      -> RegionStatus.COMPLETED
            subStatuses.any { it == RegionStatus.DOWNLOADING }    -> RegionStatus.DOWNLOADING
            subStatuses.any { it == RegionStatus.ERROR }          -> RegionStatus.ERROR
            subStatuses.any { it == RegionStatus.PAUSED }         -> RegionStatus.PAUSED
            subStatuses.any { it == RegionStatus.COMPLETED }      -> RegionStatus.COMPLETED
            else -> RegionStatus.NOT_DOWNLOADED
        }
        regionStatusMap[parentPath] = status
        invalidate()
    }

    fun setSelectedRegion(path: String?) { selectedPath = path }

    // ── Mercator projection ───────────────────────────────────────────────────

    private fun mercY(lat: Double): Double =
        ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2)) * 180.0 / Math.PI

    private fun inverseMercY(merc: Double): Double =
        Math.toDegrees(2.0 * atan(exp(Math.toRadians(merc))) - Math.PI / 2)

    private fun lonToScreenX(lon: Double): Float =
        (width / 2f + (lon - centerLon) * zoom).toFloat()

    private fun latToScreenY(lat: Double): Float =
        (height / 2f - (mercY(lat) - mercY(centerLat)) * zoom).toFloat()

    private fun screenXToLon(x: Float): Double = (x - width / 2f) / zoom + centerLon

    private fun screenYToLat(y: Float): Double =
        inverseMercY(mercY(centerLat) + (height / 2f - y) / zoom)

    private fun clampViewport() {
        centerLon = centerLon.coerceIn(-180.0, 180.0)
        centerLat = centerLat.coerceIn(-80.0,   80.0)
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Ocean
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), oceanPaint)

        // 2. Landmasses — draw AND build a combined clip path in one pass
        val landClipPath = Path()
        for (landmass in worldLandmasses) {
            val lp = buildScreenPath(landmass.polygon) ?: continue
            canvas.drawPath(lp, landmassFillPaint)
            canvas.drawPath(lp, landmassBorderPaint)
            landClipPath.addPath(lp)
        }

        // 3. Region overlays — clipped to landmass so nothing bleeds into ocean
        canvas.save()
        if (worldLandmasses.isNotEmpty()) {
            canvas.clipPath(landClipPath)
        }
        for (region in regions) {
            drawRegionOverlay(canvas, region)
        }
        canvas.restore()

        // 4. User location
        val uLon = userLon; val uLat = userLat
        if (uLon != null && uLat != null) {
            val sx = lonToScreenX(uLon); val sy = latToScreenY(uLat)
            canvas.drawCircle(sx, sy, 8f, userDotPaint)
            canvas.drawCircle(sx, sy, 8f, userDotBorderPaint)
        }
    }

    private fun buildScreenPath(polygon: List<PointF>): Path? {
        if (polygon.size < 3) return null
        val path = Path()
        polygon.forEachIndexed { i, pt ->
            val sx = lonToScreenX(pt.x.toDouble())
            val sy = latToScreenY(pt.y.toDouble())
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        path.close()
        return path
    }

    private fun drawRegionOverlay(canvas: Canvas, region: MapRegion) {
        if (region.polygon.size < 3) return

        // Broad off-screen cull (use generous margin so large regions aren't dropped)
        val cx = lonToScreenX(region.centerLon.toDouble())
        val cy = latToScreenY(region.centerLat.toDouble())
        val margin = max(width, height).toFloat() + (zoom * 20).toFloat()
        if (cx < -margin || cx > width + margin || cy < -margin || cy > height + margin) return

        val path = buildScreenPath(region.polygon) ?: return
        val status = regionStatusMap[region.path] ?: RegionStatus.NOT_DOWNLOADED

        if (status == RegionStatus.NOT_DOWNLOADED) {
            // Map fully visible — draw only a thin outline so the user can see
            // which areas are available for download without covering the land.
            canvas.drawPath(path, regionOutlinePaint)
        } else {
            // Fill + bright border for active/completed/error states
            regionFillPaint.color = when (status) {
                RegionStatus.DOWNLOADING -> colorDownloading
                RegionStatus.PAUSED      -> colorPaused
                RegionStatus.COMPLETED   -> colorCompleted
                RegionStatus.ERROR       -> colorError
                else                     -> colorDownloading
            }
            canvas.drawPath(path, regionFillPaint)
            canvas.drawPath(path, regionActiveBorderPaint)
        }
    }

    // ── Touch handling ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX  = event.x; lastTouchY  = event.y
                touchStartX = event.x; touchStartY = event.y
                hasMoved = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (!hasMoved) {
                        if (abs(event.x - touchStartX) > tapThreshold ||
                            abs(event.y - touchStartY) > tapThreshold) hasMoved = true
                    }
                    if (hasMoved) {
                        centerLon -= dx / zoom
                        centerLat  = inverseMercY(mercY(centerLat) + dy / zoom)
                        clampViewport()
                        invalidate()
                    }
                    lastTouchX = event.x; lastTouchY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!hasMoved && !scaleDetector.isInProgress) handleTap(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> { hasMoved = true; return true }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Hover events (mouse / stylus) — fire [OnRegionClickListener.onRegionHovered]
     * so the Activity can show the tooltip without a click.
     */
    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                val lon = screenXToLon(event.x).toFloat()
                val lat = screenYToLat(event.y).toFloat()
                val hit = regions.reversed().firstOrNull { pointInPolygon(it.polygon, lon, lat) }
                onRegionClickListener?.onRegionHovered(hit)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                onRegionClickListener?.onRegionHovered(null)
            }
        }
        return true   // consume so parent doesn't interfere
    }

    private fun handleTap(x: Float, y: Float) {
        val lon = screenXToLon(x).toFloat()
        val lat = screenYToLat(y).toFloat()
        for (region in regions.reversed()) {
            if (pointInPolygon(region.polygon, lon, lat)) {
                selectedPath  = region.path
                tooltipLabel  = region.label
                tooltipX = x; tooltipY = y
                invalidate()
                onRegionClickListener?.onRegionClicked(region)
                return
            }
        }
        selectedPath = null; tooltipLabel = null
        invalidate()
        onRegionClickListener?.onEmptyTapped()
    }

    /** Ray-casting point-in-polygon (geo coordinates, not screen). */
    private fun pointInPolygon(polygon: List<PointF>, x: Float, y: Float): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].x; val yi = polygon[i].y
            val xj = polygon[j].x; val yj = polygon[j].y
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi)
                inside = !inside
            j = i
        }
        return inside
    }

    companion object { private const val TAG = "NDKarte.MapView" }
}
