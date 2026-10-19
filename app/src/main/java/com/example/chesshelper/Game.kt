package com.example.chesshelper

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.Move

/** chess bookkeeping: validate what haiku read, keep the game in sync with the screen */
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

    private fun findMoves(b: Board, target: String, depth: Int): List<Move>? {
        for (mv in b.legalMoves()) {
            b.doMove(mv)
            val path: List<Move>? = if (depth == 1) {
                if (placement(b) == target) listOf(mv) else null
            } else {
                val sub = findMoves(b, target, depth - 1)
                if (sub != null) listOf(mv) + sub else null
            }
            b.undoMove()
            if (path != null) return path
        }
        return null
    }

    /** tries to reach the target placement with 1..maxPly legal moves. on success the moves are played on b */
    fun track(b: Board, target: String, maxPly: Int = 3): Boolean {
        for (d in 1..maxPly) {
            val path = findMoves(b, target, d)
            if (path != null) {
                for (m in path) b.doMove(m)
                return true
            }
        }
        return false
    }
}
