package com.example.chesshelper

import java.io.IOException

/**
 * Picks the moves to show and grades every one of them with the phone's stockfish at full power:
 * the best moves of the engine you chose (stockfish at your difficulty, or the likely moves of maia3),
 * each marked best / decent / acceptable / blunder by how much worse it is than the best move.
 */
class Advisor(private val sf: UciEngine, private val lister: MoveLister?) {
    /** set when the maia3 pc could not be reached for the last call (we used stockfish instead) */
    var listerError: String? = null
        private set

    fun advise(fen: String, n: Int, ms: Int = 700): List<Cand> {
        listerError = null
        val lines = sf.analyse(fen, ms, maxOf(8, n))
        if (lines.isEmpty()) return emptyList()

        val picks = ArrayList<String>()
        if (lister != null) {
            try {
                picks.addAll(lister.topMoves(fen, n))
            } catch (e: IOException) {
                listerError = e.message ?: "not reachable"
            }
        }
        if (picks.isEmpty() && sf.isLimited) {
            // the difficulty setting makes stockfish play worse on purpose: its own pick goes first
            sf.playedMove(fen, ms)?.let { picks.add(it) }
        }
        for (l in lines) picks.add(l.move) // fill up with the real best moves
        val chosen = picks.distinct().take(n)

        val best = lines[0].score
        val out = ArrayList<Cand>()
        for ((i, mv) in chosen.withIndex()) {
            val line = lines.firstOrNull { it.move == mv } ?: sf.evalMove(fen, mv, 250) ?: continue
            val loss = maxOf(0, best - line.score)
            out.add(Cand(mv, line.score, line.mate, Grade.of(loss), i + 1))
        }
        return out
    }
}
