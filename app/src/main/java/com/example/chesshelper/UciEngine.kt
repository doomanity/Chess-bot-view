package com.example.chesshelper

/** a move for the side to play, and how good stockfish thinks it is for that side (like +0.3) */
class Suggestion(val move: String, val eval: String)

/** talks to the stockfish binary over the UCI text protocol */
class UciEngine(path: String) {
    private val proc: Process = ProcessBuilder(path).redirectErrorStream(true).start()
    private val writer = proc.outputStream.bufferedWriter()
    private val reader = proc.inputStream.bufferedReader()

    init {
        send("uci")
        waitFor("uciok")
    }

    private fun send(cmd: String) {
        writer.write(cmd)
        writer.newLine()
        writer.flush()
    }

    private fun waitFor(prefix: String): String {
        while (true) {
            val line = reader.readLine() ?: throw IllegalStateException("engine stopped")
            if (line.startsWith(prefix)) return line
        }
    }

    fun configure(elo: Int) {
        send("setoption name Threads value 2")
        send("setoption name Hash value 32")
        if (elo >= MAX_ELO) {
            send("setoption name UCI_LimitStrength value false")
        } else {
            send("setoption name UCI_LimitStrength value true")
            send("setoption name UCI_Elo value $elo")
        }
        send("isready")
        waitFor("readyok")
    }

    /** returns the best move like "e2e4" plus a short evaluation, or null if there is no move */
    fun bestMove(fen: String, ms: Int): Suggestion? {
        send("position fen $fen")
        send("go movetime $ms")
        var eval = ""
        while (true) {
            val line = reader.readLine() ?: throw IllegalStateException("engine stopped")
            if (line.startsWith("info ") && " score " in line && "bound" !in line) {
                val t = line.split(" ")
                val i = t.indexOf("score")
                if (i >= 0 && i + 2 < t.size) {
                    val v = t[i + 2].toIntOrNull()
                    if (v != null) {
                        eval = if (t[i + 1] == "cp") String.format(java.util.Locale.US, "%+.1f", v / 100.0)
                        else if (t[i + 1] == "mate") (if (v > 0) "mate in $v" else "mated in ${-v}") else eval
                    }
                }
            }
            if (line.startsWith("bestmove")) {
                val move = line.split(" ").getOrNull(1)
                return if (move == null || move == "(none)") null else Suggestion(move, eval)
            }
        }
    }

    fun close() {
        try { send("quit") } catch (_: Exception) {}
        proc.destroy()
    }

    companion object {
        const val MIN_ELO = 1320
        const val MAX_ELO = 3190
    }
}
