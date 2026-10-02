package com.example.moodsync

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class FaceBoxOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#C992FF")
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val glowPaint = Paint().apply {
        color = Color.parseColor("#7C2CFF")
        style = Paint.Style.STROKE
        strokeWidth = 18f
        alpha = 90
        isAntiAlias = true
    }

    private var faceRect: RectF? = null

    fun setFaceRect(rect: RectF?) {
        faceRect = rect
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val rect = faceRect ?: return

        canvas.drawRoundRect(rect, 24f, 24f, glowPaint)
        canvas.drawRoundRect(rect, 24f, 24f, boxPaint)
    }
}