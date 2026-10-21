package com.example.chesshelper

import kotlin.math.exp

/**
 * live accuracy for you and the bot, the way lichess does it:
 * every move is scored by how much it dropped the mover's winning chances (0..100),
 * and the game score mixes the plain average with the harmonic average (so blunders hurt more).
 */
class Accuracy {
    private val me = ArrayList<Double>()
    private val bot = ArrayList<Double>()

    fun reset() {
        me.clear()
        bot.clear()
    }

    /** a move was played. before/after = engine score from white's side (centipawns, mates are huge numbers) */
    fun add(isMe: Boolean, moverWhite: Boolean, beforeWhite: Int, afterWhite: Int) {
        (if (isMe) me else bot).add(moveAccuracy(moverWhite, beforeWhite, afterWhite))
    }

    val myScore: Int? get() = score(me)
    val botScore: Int? get() = score(bot)
    val myMoves: Int get() = me.size
    val botMoves: Int get() = bot.size

    /** like "you 87% · bot 72%", or null before the first move */
    fun text(): String? {
        val a = myScore
        val b = botScore
        if (a == null && b == null) return null
        return "you ${a?.let { "$it%" } ?: "-"} · bot ${b?.let { "$it%" } ?: "-"}"
    }

    companion object {
        /** chance to win (0..100) for a score in centipawns from the mover's side */
        fun winPct(cp: Int): Double {
            val c = cp.coerceIn(-1000, 1000)
            return 50.0 + 50.0 * (2.0 / (1.0 + exp(-0.00368208 * c)) - 1.0)
        }

        fun moveAccuracy(moverWhite: Boolean, beforeWhite: Int, afterWhite: Int): Double {
            val sign = if (moverWhite) 1 else -1
            val drop = maxOf(0.0, winPct(beforeWhite * sign) - winPct(afterWhite * sign))
            return (103.1668 * exp(-0.04354 * drop) - 3.1669 + 1.0).coerceIn(0.0, 100.0)
        }

        fun score(list: List<Double>): Int? {
            if (list.isEmpty()) return null
            val mean = list.average()
            val harmonic = list.size / list.sumOf { 1.0 / maxOf(it, 5.0) }
            return ((mean + harmonic) / 2.0).toInt().coerceIn(0, 100)
        }
    }
}
