package com.titan.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var hasRect = false

    private val rectPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val dimPaint = Paint().apply {
        color = Color.parseColor("#88000000")
        style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                endX = event.x
                endY = event.y
                hasRect = true
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x
                endY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                endX = event.x
                endY = event.y
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!hasRect) return

        val left = minOf(startX, endX)
        val top = minOf(startY, endY)
        val right = maxOf(startX, endX)
        val bottom = maxOf(startY, endY)

        canvas.drawRect(0f, 0f, width.toFloat(), top, dimPaint)
        canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, top, left, bottom, dimPaint)
        canvas.drawRect(right, top, width.toFloat(), bottom, dimPaint)

        canvas.drawRect(left, top, right, bottom, rectPaint)
    }

    fun getSelectedRectNormalized(): FloatArray? {
        if (!hasRect || width == 0 || height == 0) return null
        val left = minOf(startX, endX) / width
        val top = minOf(startY, endY) / height
        val right = maxOf(startX, endX) / width
        val bottom = maxOf(startY, endY) / height
        if (right - left < 0.02f || bottom - top < 0.02f) return null
        return floatArrayOf(left, top, right, bottom)
    }

    fun reset() {
        hasRect = false
        invalidate()
    }
}
