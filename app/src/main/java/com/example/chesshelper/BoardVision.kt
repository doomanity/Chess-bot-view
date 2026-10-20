package com.example.chesshelper

import kotlin.math.abs

/** where the 8x8 squares are on the screen, in pixels */
class BoardGeom(val left: Int, val top: Int, val size: Int) {
    val sq: Float get() = size / 8f
    override fun toString() = "board(left=$left, top=$top, size=$size)"
}

/** what the camera sees on one square: is something on it, and how bright is that something */
class SquareRead(val occupied: Boolean, val tone: Int, val fill: Float)

/**
 * Finds the chessboard in a screenshot and tells which squares have a piece and what color it is.
 * No android classes in here on purpose, so it can be tested on a normal computer.
 * pixels are ARGB ints like android bitmaps: (p shr 16) and 255 = red.
 */
object BoardVision {
    const val EMPTY = 0
    const val WHITE = 1
    const val BLACK = 2

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF
    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b
    private fun dist(a: Int, b: Int) = abs(red(a) - red(b)) + abs(green(a) - green(b)) + abs(blue(a) - blue(b))
    private fun luma(p: Int) = (red(p) * 299 + green(p) * 587 + blue(p) * 114) / 1000

    // ------------------------------------------------------------------ finding the board

    /** looks for the board. if hint is still a good board (same place as last time) it is returned as is */
    fun findBoard(px: IntArray, w: Int, h: Int, hint: BoardGeom? = null): BoardGeom? {
        if (hint != null && scoreGeom(px, w, h, hint) >= 0.85) return hint

        // the most common colors on screen: the two square colors are always among them
        val buckets = HashMap<Int, IntArray>()
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = px[y * w + x]
                val k = (((p shr 19) and 31) shl 10) or (((p shr 11) and 31) shl 5) or ((p shr 3) and 31)
                val e = buckets.getOrPut(k) { IntArray(4) }
                e[0]++
                e[1] += red(p)
                e[2] += green(p)
                e[3] += blue(p)
                x += 3
            }
            y += 3
        }
        val merged = ArrayList<IntArray>() // [count, r, g, b] sums, bins of the same color merged
        for (e in buckets.values.sortedByDescending { it[0] }) {
            val c = rgb(e[1] / e[0], e[2] / e[0], e[3] / e[0])
            val into = merged.firstOrNull { dist(rgb(it[1] / it[0], it[2] / it[0], it[3] / it[0]), c) <= 24 }
            if (into != null) {
                for (i in 0..3) into[i] += e[i]
            } else if (merged.size < 40) {
                merged.add(e.copyOf())
            }
        }
        val top = merged.sortedByDescending { it[0] }.take(8)
            .map { rgb(it[1] / it[0], it[2] / it[0], it[3] / it[0]) }

        var best: BoardGeom? = null
        var bestScore = 0.0
        for (i in top.indices) {
            for (j in i + 1 until top.size) {
                if (dist(top[i], top[j]) < 30) continue
                val g = candidate(px, w, h, top[i], top[j]) ?: continue
                val s = scoreGeom(px, w, h, g)
                if (s >= 0.85 && s * g.size > bestScore) {
                    bestScore = s * g.size
                    best = g
                }
            }
        }
        return best
    }

    /** the biggest square-ish area made only of these two colors */
    fun candidate(px: IntArray, w: Int, h: Int, c1: Int, c2: Int): BoardGeom? {
        val tol = 40
        val rowOk = BooleanArray(h)
        var y = 0
        while (y < h) {
            var cnt = 0
            var x = 0
            while (x < w) {
                val p = px[y * w + x]
                if (dist(p, c1) <= tol || dist(p, c2) <= tol) cnt++
                x += 2
            }
            val ok = cnt * 2 >= w * 0.10
            rowOk[y] = ok
            if (y + 1 < h) rowOk[y + 1] = ok
            y += 2
        }
        val rows = longestRun(rowOk) ?: return null
        val y0 = rows.first
        val y1 = rows.second
        val colOk = BooleanArray(w)
        for (x in 0 until w) {
            var cnt = 0
            var yy = y0
            while (yy <= y1) {
                val p = px[yy * w + x]
                if (dist(p, c1) <= tol || dist(p, c2) <= tol) cnt++
                yy += 2
            }
            colOk[x] = cnt * 2 >= (y1 - y0 + 1) * 0.10
        }
        val cols = longestRun(colOk) ?: return null
        val bw = cols.second - cols.first + 1
        val bh = y1 - y0 + 1
        if (minOf(bw, bh) < minOf(w, h) * 0.3) return null
        if (abs(bw - bh) > 0.04 * maxOf(bw, bh)) return null
        return BoardGeom(cols.first, y0, (bw + bh) / 2)
    }

    /** longest stretch of true values, ignoring gaps of up to 3 (the blurry line between two squares) */
    private fun longestRun(ok: BooleanArray): Pair<Int, Int>? {
        var bestStart = -1
        var bestEnd = -1
        var start = -1
        var lastOn = -1
        for (i in 0..ok.size) {
            val on = i < ok.size && ok[i]
            if (on) {
                if (start < 0) start = i
                lastOn = i
            } else if (start >= 0 && (i >= ok.size || i - lastOn > 3)) {
                if (lastOn - start > bestEnd - bestStart) {
                    bestStart = start
                    bestEnd = lastOn
                }
                start = -1
            }
        }
        return if (bestStart >= 0) Pair(bestStart, bestEnd) else null
    }

    /** color of the empty part of a square, taken from points near the middle of its four edges */
    private fun edgeColor(px: IntArray, w: Int, g: BoardGeom, r: Int, c: Int): Int {
        val s = g.size / 8f
        val x0 = g.left + c * s
        val y0 = g.top + r * s
        val pts = arrayOf(
            floatArrayOf(0.5f, 0.07f), floatArrayOf(0.5f, 0.93f),
            floatArrayOf(0.07f, 0.5f), floatArrayOf(0.93f, 0.5f)
        )
        val cols = IntArray(4) { i ->
            val x = (x0 + pts[i][0] * s).toInt()
            val y = (y0 + pts[i][1] * s).toInt()
            px[y * w + x]
        }
        var bestIdx = 0
        var bestVotes = -1
        for (i in 0..3) {
            var votes = 0
            for (j in 0..3) if (dist(cols[i], cols[j]) <= 30) votes++
            if (votes > bestVotes) {
                bestVotes = votes
                bestIdx = i
            }
        }
        return cols[bestIdx]
    }

    /** 0..1: how much this looks like a real chessboard (squares alternate between two colors) */
    fun scoreGeom(px: IntArray, w: Int, h: Int, g: BoardGeom): Double {
        if (g.left < 0 || g.top < 0 || g.left + g.size > w || g.top + g.size > h || g.size < 160) return 0.0
        val cols = IntArray(64)
        for (r in 0..7) for (c in 0..7) cols[r * 8 + c] = edgeColor(px, w, g, r, c)
        // the two most common square colors
        val reps = ArrayList<IntArray>() // [color, count]
        for (c in cols) {
            val e = reps.firstOrNull { dist(it[0], c) <= 30 }
            if (e != null) e[1]++ else reps.add(intArrayOf(c, 1))
        }
        reps.sortByDescending { it[1] }
        if (reps.size < 2 || dist(reps[0][0], reps[1][0]) < 24) return 0.0
        val a = reps[0][0]
        val b = reps[1][0]
        val cls = IntArray(64) { i ->
            when {
                dist(cols[i], a) <= 36 -> 0
                dist(cols[i], b) <= 36 -> 1
                else -> -1
            }
        }
        var m0 = 0
        var m1 = 0
        for (r in 0..7) for (c in 0..7) {
            val k = cls[r * 8 + c]
            if (k == (r + c) % 2) m0++
            if (k == (r + c + 1) % 2) m1++
        }
        return maxOf(m0, m1) / 64.0
    }

    // ------------------------------------------------------------------ reading the squares

    /** one SquareRead per square, index = screen row * 8 + screen column (row 0 is the top of the screen) */
    fun readSquares(px: IntArray, w: Int, g: BoardGeom): Array<SquareRead> {
        val s = g.size / 8f
        val out = Array(64) { SquareRead(false, 0, 0f) }
        for (r in 0..7) for (c in 0..7) {
            val x0 = g.left + c * s
            val y0 = g.top + r * s

            // background of this square: most common color on a ring just inside its border
            val ring = ArrayList<Int>(48)
            for (i in 0 until 12) {
                val t = 0.1f + 0.8f * i / 11f
                ring.add(px[(y0 + 0.06f * s).toInt() * w + (x0 + t * s).toInt()])
                ring.add(px[(y0 + 0.94f * s).toInt() * w + (x0 + t * s).toInt()])
                ring.add(px[(y0 + t * s).toInt() * w + (x0 + 0.06f * s).toInt()])
                ring.add(px[(y0 + t * s).toInt() * w + (x0 + 0.94f * s).toInt()])
            }
            var bg = ring[0]
            var bestVotes = -1
            for (i in ring.indices step 2) {
                var votes = 0
                for (j in ring.indices) if (dist(ring[i], ring[j]) <= 30) votes++
                if (votes > bestVotes) {
                    bestVotes = votes
                    bg = ring[i]
                }
            }

            // middle of the square: how many pixels are clearly not background, and how big is the blob
            val n = 24
            var total = 0
            var diff = 0
            var minX = n
            var maxX = -1
            var minY = n
            var maxY = -1
            val tones = IntArray(n * n)
            var tn = 0
            for (iy in 0 until n) {
                for (ix in 0 until n) {
                    val fx = 0.18f + 0.64f * (ix + 0.5f) / n
                    val fy = 0.18f + 0.64f * (iy + 0.5f) / n
                    val p = px[(y0 + fy * s).toInt() * w + (x0 + fx * s).toInt()]
                    total++
                    if (dist(p, bg) > 30) {
                        diff++
                        tones[tn++] = luma(p)
                        if (ix < minX) minX = ix
                        if (ix > maxX) maxX = ix
                        if (iy < minY) minY = iy
                        if (iy > maxY) maxY = iy
                    }
                }
            }
            val fill = diff.toFloat() / total
            // a piece is a big blob; the little dots that apps draw for possible moves are not
            val blobW = if (maxX >= minX) (maxX - minX + 1) * 0.64f / n else 0f
            val blobH = if (maxY >= minY) (maxY - minY + 1) * 0.64f / n else 0f
            val occupied = fill >= 0.15f && blobW >= 0.36f && blobH >= 0.36f

            // brightness of the piece: median over the pixels that are not background
            var tone = 0
            if (occupied && tn > 0) {
                java.util.Arrays.sort(tones, 0, tn)
                tone = tones[tn / 2]
            }
            out[r * 8 + c] = SquareRead(occupied, tone, fill)
        }
        return out
    }

    /**
     * splits the pieces into white and black by brightness (2-means on the tone of occupied squares).
     * returns null if it can't tell (for example no pieces found, or all the same brightness).
     */
    fun classify(reads: Array<SquareRead>): IntArray? {
        val tones = reads.filter { it.occupied }.map { it.tone.toFloat() }
        if (tones.size < 2) return null
        var lo = tones.min()
        var hi = tones.max()
        if (hi - lo < 50f) return null
        repeat(12) {
            val mid = (lo + hi) / 2f
            val a = tones.filter { it <= mid }
            val b = tones.filter { it > mid }
            if (a.isEmpty() || b.isEmpty()) return null
            lo = a.average().toFloat()
            hi = b.average().toFloat()
        }
        if (hi - lo < 50f) return null
        val mid = (lo + hi) / 2f
        return IntArray(64) { i ->
            val r = reads[i]
            if (!r.occupied) EMPTY else if (r.tone > mid) WHITE else BLACK
        }
    }

    /** true if white pieces are mostly at the bottom of the screen, false if at the top, null if can't tell */
    fun whiteBottomOf(screen: IntArray): Boolean? {
        var top = 0
        var bottom = 0
        for (i in 0 until 64) {
            if (screen[i] != WHITE) continue
            if (i / 8 >= 4) bottom++ else top++
        }
        return if (bottom == top) null else bottom > top
    }

    /** tiny grayscale copy of an area, used to notice that something on the screen changed */
    fun thumb(px: IntArray, w: Int, h: Int, g: BoardGeom?): IntArray {
        val n = 80
        val x0 = g?.left ?: 0
        val y0 = g?.top ?: 0
        val sw = g?.size ?: w
        val sh = g?.size ?: h
        return IntArray(n * n) { i ->
            val x = (x0 + ((i % n) + 0.5f) * sw / n).toInt().coerceIn(0, w - 1)
            val y = (y0 + ((i / n) + 0.5f) * sh / n).toInt().coerceIn(0, h - 1)
            luma(px[y * w + x])
        }
    }

    fun changed(a: IntArray, b: IntArray): Boolean {
        var n = 0
        for (i in a.indices) if (abs(a[i] - b[i]) > 24) n++
        return n >= 10
    }
}
