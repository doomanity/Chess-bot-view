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
    @Volatile var lastError: String = ""   // api / network problem (stops the retries)
    @Volatile var lastDebug: String = ""   // why a reply couldn't be used (shown to you)

    private const val PROMPT = """This is a screenshot of a chess app. Find the chessboard and read the pieces exactly as they appear on the screen.

Write the board as 8 lines, from the top row of the screen to the bottom row. Each line has exactly 8 characters, from the left of the screen to the right, with no spaces and no labels.
Use R N B Q K P for white pieces, r n b q k p for black pieces, and . for an empty square.

After the 8 lines write exactly these two lines:
BOTTOM: white
BOX: left,top,right,bottom

BOTTOM says whose pieces start at the bottom of the screen: write white or black. If the rank numbers show 1 at the top, or the files run h to a from left to right, the board is flipped and black is at the bottom.
BOX is the outer edge of the 8x8 squares only (no coordinates, no borders), as numbers from 0 to 1: fractions of the image width and height.

If you cannot see a full chessboard, write only the word NONE.
Do not write anything else."""

    fun read(apiKey: String, model: String, shot: Bitmap): Reading? {
        lastError = ""
        lastDebug = ""
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
                .put("max_tokens", 1000)
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

    /** turns haiku's 8 screen rows into a normal piece placement, flipping it if black is at the bottom */
    fun parse(reply: String): Reading? {
        val rowRegex = Regex("^[prnbqkPRNBQK.]{8}$")
        val rows = reply.lines().map { it.trim().trim('`').trim() }.filter { rowRegex.matches(it) }
        if (rows.size < 8) {
            lastDebug = "no 8-row board in the reply: " + reply.trim().takeLast(140)
            return null
        }
        val grid = rows.takeLast(8)
        val whiteBottom = !Regex("BOTTOM:[\\s*`]*black", RegexOption.IGNORE_CASE).containsMatchIn(reply)
        // a flipped board is turned 180 degrees: reverse the row order and each row
        val ordered = if (whiteBottom) grid else grid.reversed().map { it.reversed() }
        val placement = ordered.joinToString("/") { compress(it) }
        if (!Game.isValidPlacement(placement)) {
            lastDebug = "board didn't look like a real position: $placement"
            return null
        }

        val m = Regex("BOX:[\\s*`\\[]*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)").find(reply)
        var box: FloatArray? = null
        if (m != null) {
            val nums = m.groupValues.drop(1).mapNotNull { it.toFloatOrNull() }
            if (nums.size == 4 && nums[2] > nums[0] && nums[3] > nums[1] && nums.all { it in 0f..1.02f }) {
                box = nums.toFloatArray()
            }
        }
        return Reading(placement, whiteBottom, box)
    }
}
