package com.example.chesshelper

import android.content.Context
import android.content.SharedPreferences

/** all the settings, read from the app's saved preferences (the app screen and the side menu both write them) */
class Cfg(p: SharedPreferences) {
    val apiKey = (p.getString("api_key", "") ?: "").trim()
    val model = (p.getString("model", "claude-haiku-5-5") ?: "claude-haiku-5-5").trim()
    val iAmWhite = p.getString("color", "white") == "white"
    val myTurnAtStart = p.getBoolean("my_turn", true)
    val elo = p.getInt("elo2", UciEngine.MAX_ELO)
    val useMaia = p.getString("engine", "stockfish") == "maia3"
    val maiaHost = (p.getString("maia_host", "") ?: "").trim()
    val maiaModel = (p.getString("maia_model", "23m") ?: "23m").trim().ifEmpty { "23m" }
    val maiaElo = p.getInt("maia_elo", 1500)
    val nMoves = p.getInt("n_moves", 3).coerceIn(1, 4)
    val thinkMs = p.getInt("think_ms", 700)

    // what to draw
    val showEval = p.getBoolean("show_eval", true)
    val showThreat = p.getBoolean("show_threat", true)
    val showLine = p.getBoolean("show_line", true)
    val style = p.getString("arrow_style", "neon") ?: "neon"   // neon, solid, minimal
    val anim = p.getBoolean("anim", true)
    val animSpeed = p.getInt("anim_speed", 100) / 100f          // 0.5 .. 2
    val arrowSize = p.getInt("arrow_size", 100) / 100f          // 0.6 .. 1.6
    val cardSize = p.getInt("card_size", 100) / 100f            // 0.7 .. 1.4
    val opacity = p.getInt("opacity", 95) / 100f                // 0.3 .. 1
    val theme = p.getString("theme", "wallpaper") ?: "wallpaper"

    // the side bar
    val sidebar = p.getBoolean("sidebar", true)
    val handleRight = p.getString("handle_side", "right") != "left"
    val handleY = p.getFloat("handle_y", 0.42f)
    val savesPerGame = p.getInt("saves", 3)                     // -1 = unlimited

    companion object {
        fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        fun load(ctx: Context) = Cfg(prefs(ctx))

        /** keys that only change the look: no need to think again when they change */
        val LOOK_KEYS = setOf("arrow_style", "anim", "anim_speed", "arrow_size", "card_size", "opacity", "theme",
            "handle_side", "handle_y", "sidebar")
    }
}
