package com.knightmeya.safetravelmonitor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.knightmeya.safetravelmonitor.models.MapFeature
import kotlin.math.max
import kotlin.math.min

class CustomMapView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var normalBitmap: Bitmap? = null
    private var satelliteBitmap: Bitmap? = null
    private var currentBitmap: Bitmap? = null
    
    private val matrix = Matrix()
    private val inverseMatrix = Matrix()
    
    private var scaleFactor = 1.0f
    private val scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    private var travelerPos: PointF? = null
    private var destinationPos: PointF? = null
    private val travelerHistory = mutableListOf<PointF>()
    private val poiList = mutableListOf<MapFeature>()
    
    private var followMode = false
    
    private val travelerPaint = Paint().apply {
        color = Color.parseColor("#3B82F6")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val pathPaint = Paint().apply {
        color = Color.parseColor("#60A5FA")
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    
    private val destinationPaint = Paint().apply {
        color = Color.parseColor("#EF4444")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val poiPaint = Paint().apply {
        textSize = 24f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    var onMapClickListener: ((PointF) -> Unit)? = null

    init {
        val normalId = resources.getIdentifier("custom_map", "drawable", context.packageName)
        if (normalId != 0) normalBitmap = BitmapFactory.decodeResource(resources, normalId)
        
        val satId = resources.getIdentifier("custom_map_sat", "drawable", context.packageName)
        if (satId != 0) satelliteBitmap = BitmapFactory.decodeResource(resources, satId)
        
        currentBitmap = normalBitmap
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            centerMap()
        }
    }

    private fun centerMap() {
        val bitmap = currentBitmap ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        
        val scaleX = viewWidth / bitmap.width
        val scaleY = viewHeight / bitmap.height
        scaleFactor = min(scaleX, scaleY)
        
        matrix.reset()
        matrix.postScale(scaleFactor, scaleFactor)
        matrix.postTranslate(
            (viewWidth - bitmap.width * scaleFactor) / 2f,
            (viewHeight - bitmap.height * scaleFactor) / 2f
        )
        invalidate()
    }

    fun setSatelliteView(isSatellite: Boolean) {
        val oldBitmap = currentBitmap
        currentBitmap = if (isSatellite) satelliteBitmap ?: normalBitmap else normalBitmap
        if (currentBitmap != oldBitmap) {
            centerMap()
        }
    }

    fun setPOIs(pois: List<MapFeature>) {
        poiList.clear()
        poiList.addAll(pois)
        invalidate()
    }

    fun setFollowMode(enabled: Boolean) {
        followMode = enabled
        if (enabled) centerOnTraveler()
    }

    fun setTravelerPosition(x: Float, y: Float) {
        val newPos = PointF(x, y)
        travelerPos = newPos
        travelerHistory.add(newPos)
        if (followMode) centerOnTraveler()
        invalidate()
    }

    private fun centerOnTraveler() {
        travelerPos?.let { pos ->
            val centerX = width / 2f
            val centerY = height / 2f
            matrix.setTranslate(centerX - pos.x * scaleFactor, centerY - pos.y * scaleFactor)
            matrix.preScale(scaleFactor, scaleFactor, pos.x, pos.y)
            invalidate()
        }
    }

    fun setDestinationPosition(x: Float, y: Float) {
        destinationPos = PointF(x, y)
        invalidate()
    }

    fun clearHistory() {
        travelerHistory.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (currentBitmap == null) {
            // Draw a helpful message if no map image is found
            val paint = Paint().apply {
                color = Color.DKGRAY
                textSize = 40f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Waiting for custom_map.png...", width/2f, height/2f, paint)
            return
        }

        canvas.save()
        canvas.concat(matrix)

        currentBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
            
            // Draw POIs
            poiList.forEach { poi ->
                val px = poi.xPercent * it.width
                val py = poi.yPercent * it.height
                
                // Draw Icon if available
                poi.iconResId?.let { resId ->
                    val drawable = context.getDrawable(resId)
                    drawable?.let { d ->
                        val size = (32f / scaleFactor).toInt()
                        d.setBounds(
                            (px - size / 2).toInt(),
                            (py - size / 2).toInt(),
                            (px + size / 2).toInt(),
                            (py + size / 2).toInt()
                        )
                        d.setTint(poi.color)
                        d.draw(canvas)
                    }
                } ?: run {
                    poiPaint.color = poi.color
                    canvas.drawCircle(px, py, 10f / scaleFactor, poiPaint)
                }
                
                // Draw Label
                poiPaint.color = Color.WHITE
                poiPaint.textSize = 18f / scaleFactor
                poiPaint.setShadowLayer(2f, 0f, 0f, Color.BLACK)
                canvas.drawText(poi.name, px, py + (35f / scaleFactor), poiPaint)
                poiPaint.clearShadowLayer()
            }
        }

        // Draw Path
        if (travelerHistory.size > 1) {
            val path = Path()
            path.moveTo(travelerHistory[0].x, travelerHistory[0].y)
            for (i in 1 until travelerHistory.size) {
                path.lineTo(travelerHistory[i].x, travelerHistory[i].y)
            }
            canvas.drawPath(path, pathPaint)
        }

        // Draw Destination
        destinationPos?.let {
            canvas.drawCircle(it.x, it.y, 15f / scaleFactor, destinationPaint)
        }

        // Draw Traveler
        travelerPos?.let {
            canvas.drawCircle(it.x, it.y, 20f / scaleFactor, travelerPaint)
        }

        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = max(0.5f, min(scaleFactor, 5.0f))
            matrix.postScale(detector.scaleFactor, detector.scaleFactor, detector.focusX, detector.focusY)
            invalidate()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            matrix.postTranslate(-distanceX, -distanceY)
            invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            matrix.invert(inverseMatrix)
            val pts = floatArrayOf(e.x, e.y)
            inverseMatrix.mapPoints(pts)
            onMapClickListener?.invoke(PointF(pts[0], pts[1]))
            return true
        }
    }
}
