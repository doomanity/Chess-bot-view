package com.example.chesshelper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** one suggested move to draw: where it goes (screen pixel rectangles), how good it is, and what the list says */
class DrawMove(val from: RectF, val to: RectF, val grade: Int, val rank: Int, val label: String, val eval: String)

/** transparent full-screen layer that draws the suggested moves on top of the chess app */
class BoardOverlay(ctx: Context) : View(ctx) {
    private var moves: List<DrawMove> = emptyList()
    private var board = RectF()
    private var debug = ""
    private val where = IntArray(2)
    private val d = ctx.resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 18, 28, 44)
        setShadowLayer(14f * d, 0f, 3f * d, Color.argb(120, 0, 0, 0))
    }
    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = 12f * d
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private fun colorOf(grade: Int, alpha: Int = 255): Int {
        val c = when (grade) {
            Grade.BEST -> Color.rgb(27, 138, 58)       // dark green
            Grade.DECENT -> Color.rgb(139, 212, 80)    // light green
            Grade.OK -> Color.rgb(242, 197, 17)        // yellow
            else -> Color.rgb(229, 57, 53)             // red
        }
        return Color.argb(alpha, Color.red(c), Color.green(c), Color.blue(c))
    }

    /** rectangles are in screen pixels (the same pixels as the screenshot). boardRect is the whole board */
    fun setMoves(list: List<DrawMove>, boardRect: RectF) {
        moves = list
        board = boardRect
        invalidate()
    }

    fun clearMove() {
        if (moves.isEmpty()) return
        moves = emptyList()
        invalidate()
    }

    /** a small status line at the bottom of the screen, so a screenshot tells what the app thinks */
    fun setDebug(t: String) {
        if (t == debug) return
        debug = t
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // some phones put this window a little lower than the top of the screen (status bar, camera cutout).
        // ask where we really are and move the drawing back so highlights land on the right squares.
        getLocationOnScreen(where)
        canvas.translate(-where[0].toFloat(), -where[1].toFloat())
        val screenW = (width + where[0]).toFloat()

        // the worst first, so the best move is drawn on top
        for (m in moves.sortedByDescending { it.rank }) drawMove(canvas, m)
        if (moves.isNotEmpty()) drawCard(canvas, screenW)
        if (debug.isNotEmpty()) canvas.drawText(debug, screenW / 2f, (height + where[1]) - 40f * d, debugPaint)
    }

    private fun drawMove(c: Canvas, m: DrawMove) {
        val sq = m.to.width()
        val col = colorOf(m.grade)

        // the piece to pick up: a frame (a light fill too for the first choice)
        if (m.rank == 1) {
            fill.color = colorOf(m.grade, 60)
            c.drawRoundRect(m.from, sq * 0.1f, sq * 0.1f, fill)
        }
        stroke.color = col
        stroke.strokeWidth = sq * 0.055f
        c.drawRoundRect(RectF(m.from).apply { inset(sq * 0.03f, sq * 0.03f) }, sq * 0.1f, sq * 0.1f, stroke)

        // where it goes
        fill.color = colorOf(m.grade, if (m.rank == 1) 105 else 80)
        c.drawRoundRect(m.to, sq * 0.1f, sq * 0.1f, fill)
        stroke.strokeWidth = sq * 0.07f
        c.drawRoundRect(RectF(m.to).apply { inset(sq * 0.035f, sq * 0.035f) }, sq * 0.1f, sq * 0.1f, stroke)

        arrow(c, m, col, sq)

        // rank badge (top right) and skull for a blunder (bottom right)
        val r = sq * 0.17f
        badge(c, m.to.right - r * 1.1f, m.to.top + r * 1.1f, r, col)
        text.textSize = r * 1.35f
        text.textAlign = Paint.Align.CENTER
        text.color = if (m.grade == Grade.DECENT || m.grade == Grade.OK) Color.BLACK else Color.WHITE
        c.drawText(m.rank.toString(), m.to.right - r * 1.1f, m.to.top + r * 1.1f + r * 0.47f, text)
        text.color = Color.WHITE
        if (m.grade == Grade.BLUNDER) skull(c, m.to.right - r * 1.1f, m.to.bottom - r * 1.1f, r * 1.15f)
    }

    private fun arrow(c: Canvas, m: DrawMove, col: Int, sq: Float) {
        val x0 = m.from.centerX()
        val y0 = m.from.centerY()
        val x1 = m.to.centerX()
        val y1 = m.to.centerY()
        val len = hypot(x1 - x0, y1 - y0)
        if (len < sq * 0.4f) return
        val ux = (x1 - x0) / len
        val uy = (y1 - y0) / len
        val width = sq * if (m.rank == 1) 0.17f else 0.11f
        val head = sq * 0.36f
        val sx = x0 + ux * sq * 0.22f
        val sy = y0 + uy * sq * 0.22f
        val ex = x1 - ux * sq * 0.08f
        val ey = y1 - uy * sq * 0.08f
        val bx = ex - ux * head
        val by = ey - uy * head
        stroke.color = Color.argb(235, Color.red(col), Color.green(col), Color.blue(col))
        stroke.strokeWidth = width
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawLine(sx, sy, bx, by, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        val a = atan2(uy, ux)
        val half = head * 0.62f
        val p = Path()
        p.moveTo(ex, ey)
        p.lineTo(bx + (-sin(a)) * half, by + cos(a) * half)
        p.lineTo(bx - (-sin(a)) * half, by - cos(a) * half)
        p.close()
        fill.color = Color.argb(235, Color.red(col), Color.green(col), Color.blue(col))
        c.drawPath(p, fill)
    }

    private fun badge(c: Canvas, cx: Float, cy: Float, r: Float, col: Int) {
        fill.color = Color.argb(230, 255, 255, 255)
        c.drawCircle(cx, cy, r * 1.12f, fill)
        fill.color = col
        c.drawCircle(cx, cy, r, fill)
    }

    /** a little skull on a red disc */
    private fun skull(c: Canvas, cx: Float, cy: Float, r: Float) {
        fill.color = Color.WHITE
        c.drawCircle(cx, cy, r * 1.08f, fill)
        fill.color = Color.rgb(229, 57, 53)
        c.drawCircle(cx, cy, r, fill)
        fill.color = Color.WHITE
        c.drawCircle(cx, cy - r * 0.12f, r * 0.58f, fill)
        c.drawRoundRect(RectF(cx - r * 0.32f, cy + r * 0.2f, cx + r * 0.32f, cy + r * 0.72f), r * 0.1f, r * 0.1f, fill)
        fill.color = Color.rgb(229, 57, 53)
        c.drawCircle(cx - r * 0.23f, cy - r * 0.1f, r * 0.17f, fill)
        c.drawCircle(cx + r * 0.23f, cy - r * 0.1f, r * 0.17f, fill)
        val nose = Path()
        nose.moveTo(cx, cy + r * 0.04f)
        nose.lineTo(cx - r * 0.09f, cy + r * 0.24f)
        nose.lineTo(cx + r * 0.09f, cy + r * 0.24f)
        nose.close()
        c.drawPath(nose, fill)
        stroke.color = Color.rgb(229, 57, 53)
        stroke.strokeWidth = r * 0.07f
        c.drawLine(cx - r * 0.1f, cy + r * 0.45f, cx - r * 0.1f, cy + r * 0.72f, stroke)
        c.drawLine(cx + r * 0.1f, cy + r * 0.45f, cx + r * 0.1f, cy + r * 0.72f, stroke)
    }

    /** the list of moves: rank, move, eval, skull for a blunder. above the board if there is room, else below it */
    private fun drawCard(c: Canvas, screenW: Float) {
        val rowH = 38f * d
        val pad = 10f * d
        val w = minOf(board.width(), screenW - 24f * d).coerceAtLeast(240f * d)
        val h = moves.size * rowH + pad * 2
        val left = (board.left + (board.width() - w) / 2f).coerceIn(8f * d, screenW - w - 8f * d)
        var top = board.top - h - 14f * d
        if (top < 56f * d) top = board.bottom + 14f * d
        c.drawRoundRect(RectF(left, top, left + w, top + h), 18f * d, 18f * d, card)

        for ((i, m) in moves.sortedBy { it.rank }.withIndex()) {
            val cy = top + pad + rowH * i + rowH / 2f
            val col = colorOf(m.grade)
            fill.color = col
            c.drawCircle(left + pad + rowH * 0.36f, cy, rowH * 0.31f, fill)
            text.textSize = rowH * 0.42f
            text.textAlign = Paint.Align.CENTER
            text.color = if (m.grade == Grade.DECENT || m.grade == Grade.OK) Color.BLACK else Color.WHITE
            c.drawText(m.rank.toString(), left + pad + rowH * 0.36f, cy + rowH * 0.15f, text)
            text.color = Color.WHITE
            text.textSize = rowH * 0.52f
            text.textAlign = Paint.Align.LEFT
            c.drawText(m.label, left + pad + rowH * 0.95f, cy + rowH * 0.18f, text)
            text.textAlign = Paint.Align.RIGHT
            text.color = Color.argb(230, 220, 228, 240)
            val ex = left + w - pad - (if (m.grade == Grade.BLUNDER) rowH * 0.9f else 0f)
            c.drawText(m.eval, ex, cy + rowH * 0.18f, text)
            text.color = Color.WHITE
            if (m.grade == Grade.BLUNDER) skull(c, left + w - pad - rowH * 0.34f, cy, rowH * 0.34f)
        }
    }
}
