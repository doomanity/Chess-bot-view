package com.example.chesshelper

/** what the AI told us about the screenshot: 8 rows of pieces as seen on screen, which side is at the bottom, where the board is */
class AiRead(val grid: List<String>, val bottomWhite: Boolean?, val box: FloatArray?)

/** turns the AI's text reply into an AiRead. kept free of android classes so it can be tested on a computer */
object HaikuParse {
    @Volatile var lastDebug: String = ""

    fun parse(reply: String): AiRead? {
        lastDebug = ""
        val rowRegex = Regex("^[prnbqkPRNBQK.]{8}$")
        val rows = reply.lines().map { it.trim().trim('`').trim() }.filter { rowRegex.matches(it) }
        if (rows.size < 8) {
            lastDebug = "no 8-row board in the reply: " + reply.trim().takeLast(140)
            return null
        }
        val grid = rows.takeLast(8)

        val bottom = Regex("BOTTOM:[\\s*`]*(white|black)", RegexOption.IGNORE_CASE).find(reply)
            ?.groupValues?.get(1)?.lowercase()
        val bottomWhite: Boolean? = when (bottom) {
            "white" -> true
            "black" -> false
            else -> null
        }

        val m = Regex("BOX:[\\s*`\\[]*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)").find(reply)
        var box: FloatArray? = null
        if (m != null) {
            val nums = m.groupValues.drop(1).mapNotNull { it.toFloatOrNull() }
            if (nums.size == 4 && nums[2] > nums[0] && nums[3] > nums[1] && nums.all { it in 0f..1.02f }) {
                box = nums.toFloatArray()
            }
        }
        return AiRead(grid, bottomWhite, box)
    }
}
