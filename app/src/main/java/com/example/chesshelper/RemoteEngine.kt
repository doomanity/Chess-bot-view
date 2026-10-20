package com.example.chesshelper

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** maia3 running on your pc: tools/maia3_server.py answers with the move. host looks like 192.168.1.20:8765 */
class RemoteEngine(private val host: String, private val model: String, private val elo: Int) : MoveEngine {
    override fun bestMove(fen: String, ms: Int): Suggestion? {
        val conn = URL("http://$host/move").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 60000 // the first move can take a while, the server loads the model then
            conn.setRequestProperty("content-type", "application/json")
            val body = JSONObject().put("fen", fen).put("model", model).put("elo", elo)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: throw IOException("server error $code"))
            val text = stream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            if (code !in 200..299) throw IOException(json.optString("error", "server error $code"))
            val move = json.optString("move")
            return if (move.isEmpty() || move == "(none)") null else Suggestion(move, "maia3 $elo")
        } finally {
            conn.disconnect()
        }
    }

    override fun close() {}
}
