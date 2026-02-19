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
 * Renders simplified country/region boundaries as colored polygons using
 * Mercator projection. Supports pan, pinch-zoom, and tap gestures.
 * Regions are color-coded by download status.
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

    enum class RegionStatus { NOT_DOWNLOADED, DOWNLOADING, PAUSED, COMPLETED }

    interface OnRegionClickListener {
        fun onRegionClicked(region: MapRegion)
    }

    var onRegionClickListener: OnRegionClickListener? = null

    // ── Region data ───────────────────────────────────────────────────────────

    private val regions = mutableListOf<MapRegion>()
    private val regionStatusMap = mutableMapOf<String, RegionStatus>()
    private val regionProgressMap = mutableMapOf<String, Float>()
    private var selectedPath: String? = null

    // ── Viewport (Mercator) ───────────────────────────────────────────────────

    private var centerLon = 15.0    // Europe-centered default
    private var centerLat = 50.0
    private var zoom = 5.0          // pixels per degree longitude
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
                // Compute focal geo-coords at the OLD zoom BEFORE changing it
                val focusLon = (detector.focusX - width / 2f) / oldZoom + centerLon
                val focalMerc = mercY(centerLat) + (height / 2f - detector.focusY) / oldZoom
                // Apply new zoom
                zoom = (zoom * detector.scaleFactor).coerceIn(minZoom, maxZoom)
                // Adjust center so the focal point stays fixed under the finger
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
        color = Color.parseColor("#BBDEFB")
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#78909C")
        strokeWidth = 1f
    }

    private val selectedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#F57F17")
        strokeWidth = 3f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#263238")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.FILL
    }

    // Status colors
    private val colorNotDownloaded = Color.parseColor("#CFD8DC")
    private val colorDownloading   = Color.parseColor("#64B5F6")
    private val colorPaused        = Color.parseColor("#FFB74D")
    private val colorCompleted     = Color.parseColor("#81C784")
    private val colorSelected      = Color.parseColor("#FFF176")

    // ── Public API ────────────────────────────────────────────────────────────

    /** Load region boundary data from a JSON string (array of region objects). */
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
            regions.add(MapRegion(
                path = obj.getString("path"),
                label = obj.getString("label"),
                polygon = points,
                centerLon = center.getDouble(0).toFloat(),
                centerLat = center.getDouble(1).toFloat(),
                hasSubRegions = obj.optBoolean("hasSubRegions", false)
            ))
        }
        Log.i(TAG, "Loaded ${regions.size} map regions")
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
            else -> RegionStatus.NOT_DOWNLOADED
        }
        regionStatusMap[parentPath] = status
        invalidate()
    }

    /** Set the currently selected region (highlighted on map). */
    fun setSelectedRegion(path: String?) {
        selectedPath = path
        invalidate()
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

        // Ocean background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), oceanPaint)

        // Draw regions
        for (region in regions) {
            drawRegionPolygon(canvas, region)
        }

        // Draw labels when zoomed in enough
        val labelThreshold = 5.0
        if (zoom >= labelThreshold) {
            for (region in regions) {
                drawRegionLabel(canvas, region)
            }
        }
    }

    private fun drawRegionPolygon(canvas: Canvas, region: MapRegion) {
        if (region.polygon.size < 3) return

        // Quick visibility check using center
        val cx = lonToScreenX(region.centerLon.toDouble())
        val cy = latToScreenY(region.centerLat.toDouble())
        val margin = (zoom * 30).toFloat()
        if (cx < -margin || cx > width + margin || cy < -margin || cy > height + margin) return

        val path = Path()
        for ((i, pt) in region.polygon.withIndex()) {
            val sx = lonToScreenX(pt.x.toDouble())
            val sy = latToScreenY(pt.y.toDouble())
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        path.close()

        // Fill color based on status
        val isSelected = region.path == selectedPath
        val status = regionStatusMap[region.path] ?: RegionStatus.NOT_DOWNLOADED

        fillPaint.color = if (isSelected) colorSelected else when (status) {
            RegionStatus.NOT_DOWNLOADED -> colorNotDownloaded
            RegionStatus.DOWNLOADING -> colorDownloading
            RegionStatus.PAUSED -> colorPaused
            RegionStatus.COMPLETED -> colorCompleted
        }

        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, if (isSelected) selectedBorderPaint else borderPaint)
    }

    private fun drawRegionLabel(canvas: Canvas, region: MapRegion) {
        val sx = lonToScreenX(region.centerLon.toDouble())
        val sy = latToScreenY(region.centerLat.toDouble())

        // Skip if off-screen
        if (sx < -100 || sx > width + 100 || sy < -30 || sy > height + 30) return

        // Scale text with zoom
        textPaint.textSize = (9 * sqrt(zoom / 5.0)).toFloat().coerceIn(8f, 16f)

        val label = region.label
        val textWidth = textPaint.measureText(label)
        val textHeight = textPaint.textSize
        val pad = 3f

        // Background pill
        canvas.drawRoundRect(
            sx - textWidth / 2 - pad, sy - textHeight / 2 - pad,
            sx + textWidth / 2 + pad, sy + textHeight / 2 + pad,
            4f, 4f, textBgPaint
        )

        // Label text
        canvas.drawText(label, sx, sy + textHeight / 3, textPaint)
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
                        // Pan: shift viewport center
                        centerLon -= dx / zoom
                        // For latitude, use Mercator: shift in merc space
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

        // Hit-test regions (reverse order so last-drawn / frontmost wins)
        for (region in regions.reversed()) {
            if (pointInPolygon(region.polygon, tapLon, tapLat)) {
                selectedPath = region.path
                invalidate()
                onRegionClickListener?.onRegionClicked(region)
                return
            }
        }

        // Tapped empty area
        selectedPath = null
        invalidate()
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
