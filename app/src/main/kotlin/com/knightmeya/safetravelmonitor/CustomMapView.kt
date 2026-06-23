package com.knightmeya.safetravelmonitor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class CustomMapView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var mapBitmap: Bitmap? = null
    private val matrix = Matrix()
    private val inverseMatrix = Matrix()
    
    private var scaleFactor = 1.0f
    private val scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    private var travelerPos: PointF? = null
    private var destinationPos: PointF? = null
    private val travelerHistory = mutableListOf<PointF>()
    
    private var followMode = false
    
    private val travelerPaint = Paint().apply {
        color = Color.parseColor("#3B82F6") // Blue-500
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val pathPaint = Paint().apply {
        color = Color.parseColor("#60A5FA") // Blue-400
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    
    private val destinationPaint = Paint().apply {
        color = Color.parseColor("#EF4444") // Red-500
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    var onMapClickListener: ((PointF) -> Unit)? = null

    init {
        val resId = context.resources.getIdentifier("custom_map", "drawable", context.packageName)
        if (resId != 0) {
            mapBitmap = BitmapFactory.decodeResource(resources, resId)
        }
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
        canvas.save()
        canvas.concat(matrix)

        mapBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }

        // Draw Path (History)
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
