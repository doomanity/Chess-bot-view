package com.example.chesshelper

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** asks claude haiku to read the chessboard from a screenshot */
object Haiku {
    @Volatile var lastError: String = ""   // api / network problem (stops the retries)

    private const val PROMPT = """This is a screenshot of a chessboard from a chess app. Read the pieces exactly as they appear on the screen.

Write the board as 8 lines, from the top row of the screen to the bottom row. Each line has exactly 8 characters, from the left of the screen to the right, with no spaces and no labels.
Use R N B Q K P for white pieces, r n b q k p for black pieces, and . for an empty square.

After the 8 lines write exactly this line:
BOTTOM: white

BOTTOM says whose pieces start at the bottom of the screen: write white or black. If the rank numbers show 1 at the top, or the files run h to a from left to right, the board is flipped and black is at the bottom.

If you cannot see a full chessboard, write only the word NONE.
Do not write anything else."""

    /** reads the board part of a screenshot (px = whole screen, w x h) */
    fun read(apiKey: String, model: String, px: IntArray, w: Int, h: Int, g: BoardGeom): AiRead? {
        lastError = ""
        val m = (g.size * 0.06f).toInt()
        val x0 = maxOf(0, g.left - m)
        val y0 = maxOf(0, g.top - m)
        val x1 = minOf(w, g.left + g.size + m)
        val y1 = minOf(h, g.top + g.size + m)
        val shot = Bitmap.createBitmap(px, y0 * w + x0, w, x1 - x0, y1 - y0, Bitmap.Config.ARGB_8888)
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
            return HaikuParse.parse(sb.toString())
        } catch (e: java.io.IOException) {
            lastError = "network error: ${e.message}"
            return null
        }
    }
}
