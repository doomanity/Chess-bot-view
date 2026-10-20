package com.example.chesshelper

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

/** chess bookkeeping: build positions, and follow the game by matching which squares have pieces */
object Game {

    /** checks that a piece placement string looks like a real position */
    fun isValidPlacement(p: String): Boolean {
        val ranks = p.split("/")
        if (ranks.size != 8) return false
        var wk = 0
        var bk = 0
        var wp = 0
        var bp = 0
        var white = 0
        var black = 0
        for ((i, r) in ranks.withIndex()) {
            var n = 0
            for (c in r) {
                if (c in '1'..'8') {
                    n += c - '0'
                } else if ("prnbqkPRNBQK".indexOf(c) >= 0) {
                    n += 1
                    if (c.isUpperCase()) white++ else black++
                    if (c == 'K') wk++
                    if (c == 'k') bk++
                    if (c == 'P') { wp++; if (i == 0 || i == 7) return false }
                    if (c == 'p') { bp++; if (i == 0 || i == 7) return false }
                } else {
                    return false
                }
            }
            if (n != 8) return false
        }
        return wk == 1 && bk == 1 && wp <= 8 && bp <= 8 && white <= 16 && black <= 16
    }

    private fun grid(p: String): List<String> = p.split("/").map { r ->
        buildString {
            for (c in r) {
                if (c in '1'..'8') repeat(c - '0') { append('.') } else append(c)
            }
        }
    }

    /** a screenshot can't show castling rights, so guess: king and rook still on their home squares */
    fun guessCastling(p: String): String {
        val g = grid(p)
        var s = ""
        if (g[7][4] == 'K') {
            if (g[7][7] == 'R') s += "K"
            if (g[7][0] == 'R') s += "Q"
        }
        if (g[0][4] == 'k') {
            if (g[0][7] == 'r') s += "k"
            if (g[0][0] == 'r') s += "q"
        }
        return if (s.isEmpty()) "-" else s
    }

    fun newBoard(placement: String, whiteToMove: Boolean): Board {
        val b = Board()
        val side = if (whiteToMove) "w" else "b"
        b.loadFromFen("$placement $side ${guessCastling(placement)} - 0 1")
        return b
    }

    fun placement(b: Board): String = b.fen.substringBefore(' ')

    // ------------------------------------------------------------------ occupancy (which squares have a white / black piece)

    /** 64 numbers, index = rank * 8 + file (a1 = 0, h1 = 7, a2 = 8 ...): 0 empty, 1 white piece, 2 black piece */
    fun occOf(b: Board): IntArray {
        val o = IntArray(64)
        for (i in 0 until 64) {
            val p = b.getPiece(Square.squareAt(i))
            o[i] = when {
                p == Piece.NONE -> BoardVision.EMPTY
                p.pieceSide == Side.WHITE -> BoardVision.WHITE
                else -> BoardVision.BLACK
            }
        }
        return o
    }

    fun mismatch(a: IntArray, b: IntArray): Int {
        var n = 0
        for (i in 0 until 64) if (a[i] != b[i]) n++
        return n
    }

    /** turns what we see on the screen (row 0 = top) into the same layout as occOf */
    fun screenToChess(screen: IntArray, whiteBottom: Boolean): IntArray {
        val o = IntArray(64)
        for (r in 0..7) for (c in 0..7) {
            val file = if (whiteBottom) c else 7 - c
            val rank = if (whiteBottom) 7 - r else r
            o[rank * 8 + file] = screen[r * 8 + c]
        }
        return o
    }

    fun startOcc(): IntArray = occOf(Board())

    /** occupancy (screen layout) of 8 rows of text like "rnbqkbnr", top of the screen first */
    fun gridOcc(grid: List<String>): IntArray {
        val o = IntArray(64)
        for (r in 0..7) for (c in 0..7) {
            val ch = grid[r][c]
            o[r * 8 + c] = when {
                ch == '.' -> BoardVision.EMPTY
                ch.isUpperCase() -> BoardVision.WHITE
                else -> BoardVision.BLACK
            }
        }
        return o
    }

    private fun compress(row: String): String {
        val sb = StringBuilder()
        var n = 0
        for (c in row) {
            if (c == '.') {
                n++
            } else {
                if (n > 0) { sb.append(n); n = 0 }
                sb.append(c)
            }
        }
        if (n > 0) sb.append(n)
        return sb.toString()
    }

    /** piece placement for a FEN from 8 screen rows. a board seen from Black's side is turned around */
    fun placementFromGrid(grid: List<String>, whiteBottom: Boolean): String {
        val ordered = if (whiteBottom) grid else grid.reversed().map { it.reversed() }
        return ordered.joinToString("/") { compress(it) }
    }

    // ------------------------------------------------------------------ following the game

    /** one way to get from a position to what the screen shows: the moves, and how many squares still differ (0 or 1) */
    class Hit(val path: List<Move>, val mis: Int)

    private fun collect(b: Board, occ: IntArray, depth: Int, path: ArrayList<Move>, out: ArrayList<Hit>) {
        if (depth == 0) {
            val m = mismatch(occOf(b), occ)
            if (m <= 1) out.add(Hit(ArrayList(path), m))
            return
        }
        // with the wrong side to move a "legal" move could take a king, which breaks the move generator
        val moves = try { b.legalMoves() } catch (e: Exception) { return }
        for (mv in moves) {
            if (b.getPiece(mv.to).pieceType == PieceType.KING) continue
            b.doMove(mv)
            path.add(mv)
            collect(b, occ, depth - 1, path, out)
            path.removeAt(path.size - 1)
            b.undoMove()
        }
    }

    /** all move sequences of exactly this many moves that end (almost) at what the screen shows. b is left unchanged */
    fun hitsAt(b: Board, occ: IntArray, depth: Int): List<Hit> {
        val hits = ArrayList<Hit>()
        collect(b, occ, depth, ArrayList(), hits)
        return hits
    }

    fun hasPromotion(h: Hit): Boolean = h.path.any { it.promotion != Piece.NONE }

    private fun underpromotions(h: Hit): Int =
        h.path.count { it.promotion != Piece.NONE && it.promotion.pieceType != PieceType.QUEEN }

    /** several hits can look the same on screen (a pawn promoting to any piece): take the queen */
    fun pick(hits: List<Hit>): Hit = hits.minByOrNull { underpromotions(it) }!!

    fun play(b: Board, h: Hit) {
        for (m in h.path) b.doMove(m)
    }
}
