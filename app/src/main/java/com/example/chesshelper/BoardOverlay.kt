package com.example.chesshelper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** transparent full-screen layer that highlights the suggested move on top of the chess app */
class BoardOverlay(ctx: Context) : View(ctx) {
    private var from: RectF? = null
    private var to: RectF? = null
    private var label = ""
    private val where = IntArray(2)

    private val fromPaint = Paint().apply { color = Color.argb(120, 255, 235, 59) }
    private val toPaint = Paint().apply { color = Color.argb(130, 76, 175, 80) }
    private val line = Paint().apply {
        color = Color.argb(200, 255, 87, 34)
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val outline = Paint().apply {
        color = Color.rgb(255, 87, 34)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val pill = Paint().apply { color = Color.argb(190, 0, 0, 0); isAntiAlias = true }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 54f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    /** rectangles are in screen pixels (the same pixels as the screenshot) */
    fun showMove(f: RectF?, t: RectF?, text: String) {
        from = f
        to = t
        label = text
        visibility = View.VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // some phones put this window a little lower than the top of the screen (status bar, camera cutout).
        // ask where we really are and move the drawing back so highlights land on the right squares.
        getLocationOnScreen(where)
        canvas.translate(-where[0].toFloat(), -where[1].toFloat())

        val f = from
        val t = to
        if (f != null && t != null) {
            canvas.drawRect(f, fromPaint)
            canvas.drawRect(t, toPaint)
            canvas.drawRect(f, outline)
            canvas.drawRect(t, outline)
            canvas.drawLine(f.centerX(), f.centerY(), t.centerX(), t.centerY(), line)
        }
        if (label.isNotEmpty()) {
            val w = textPaint.measureText(label) + 80f
            val cx = width / 2f
            canvas.drawRoundRect(cx - w / 2, 90f, cx + w / 2, 190f, 40f, 40f, pill)
            canvas.drawText(label, cx, 160f, textPaint)
        }
    }
}
