package com.example.chesshelper

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

    /** returns the best move like "e2e4", or null if there is none */
    fun bestMove(fen: String, ms: Int): String? {
        send("position fen $fen")
        send("go movetime $ms")
        val line = waitFor("bestmove")
        val move = line.split(" ").getOrNull(1)
        return if (move == null || move == "(none)") null else move
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
