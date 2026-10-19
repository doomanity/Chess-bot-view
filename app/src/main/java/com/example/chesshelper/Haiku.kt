package com.example.chesshelper

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class Reading(val placement: String, val whiteBottom: Boolean, val box: FloatArray?)

/** asks claude haiku to read the chessboard from a screenshot */
object Haiku {
    @Volatile var lastError: String = ""

    private const val PROMPT = """This is a screenshot of a chess app. Find the chessboard and read where every piece is.

Go rank by rank, from rank 8 down to rank 1, as seen from White's side.
If the board is flipped on screen (Black pieces at the bottom), still describe it from White's side.
Write one short line per rank. Each rank must add up to 8 squares and there must be exactly one king per side.

Then finish with exactly these three lines and nothing after them:
ORIENT: white_bottom   (write black_bottom instead if Black's pieces are at the bottom of the screen)
BOX: left,top,right,bottom   (the outer edge of the 8x8 squares only, without coordinates or borders, as numbers from 0 to 1: fractions of the image width and height)
FEN: the piece placement part of a FEN only. Uppercase = white, lowercase = black, digits = empty squares. Example: rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR

If you cannot see a full chessboard, write FEN: NONE"""

    fun read(apiKey: String, model: String, shot: Bitmap): Reading? {
        lastError = ""
        try {
            val scale = minOf(1f, 1568f / maxOf(shot.width, shot.height))
            val img = if (scale < 1f) {
                Bitmap.createScaledBitmap(shot, (shot.width * scale).toInt(), (shot.height * scale).toInt(), true)
            } else shot
            val bos = ByteArrayOutputStream()
            img.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

            val imageBlock = JSONObject()
                .put("type", "image")
                .put("source", JSONObject()
                    .put("type", "base64")
                    .put("media_type", "image/jpeg")
                    .put("data", b64))
            val textBlock = JSONObject().put("type", "text").put("text", PROMPT)
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 700)
                .put("messages", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray().put(imageBlock).put(textBlock))))

            val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                lastError = "api error $code: ${text.take(160)}"
                return null
            }

            val content = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            return parse(sb.toString())
        } catch (e: java.io.IOException) {
            lastError = "network error: ${e.message}"
            return null
        }
    }

    fun parse(reply: String): Reading? {
        val fen = Regex("FEN:[\\s*`]*([prnbqkPRNBQK1-8/]+)").findAll(reply).lastOrNull()?.groupValues?.get(1)
            ?: return null
        if (!Game.isValidPlacement(fen)) return null
        val whiteBottom = !Regex("ORIENT:[\\s*`]*black", RegexOption.IGNORE_CASE).containsMatchIn(reply)
        val m = Regex("BOX:[\\s*`\\[]*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)").find(reply)
        var box: FloatArray? = null
        if (m != null) {
            val nums = m.groupValues.drop(1).mapNotNull { it.toFloatOrNull() }
            if (nums.size == 4 && nums[2] > nums[0] && nums[3] > nums[1] && nums.all { it in 0f..1.02f }) {
                box = nums.toFloatArray()
            }
        }
        return Reading(fen, whiteBottom, box)
    }
}
