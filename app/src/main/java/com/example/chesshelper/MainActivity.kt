package com.example.chesshelper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** settings screen: api key, side, difficulty, start / stop */
class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var keyBox: EditText
    private lateinit var modelBox: EditText
    private lateinit var white: RadioButton
    private lateinit var black: RadioButton
    private lateinit var myTurn: CheckBox
    private lateinit var eloBar: SeekBar
    private lateinit var eloLabel: TextView
    private lateinit var engineStockfish: RadioButton
    private lateinit var engineMaia: RadioButton
    private lateinit var maiaHostBox: EditText
    private lateinit var maiaModelBox: EditText
    private lateinit var maiaEloBox: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        fun addText(t: String, size: Float = 16f): TextView {
            val v = TextView(this)
            v.text = t
            v.textSize = size
            v.setPadding(0, pad / 2, 0, 0)
            root.addView(v)
            return v
        }

        fun addInput(value: String, hintText: String, secret: Boolean = false): EditText {
            val e = EditText(this)
            e.hint = hintText
            e.setText(value)
            e.inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
            root.addView(e)
            return e
        }

        fun addButton(t: String, onClick: () -> Unit) {
            val b = Button(this)
            b.text = t
            b.setOnClickListener { onClick() }
            root.addView(b)
        }

        addText("chess helper", 26f)

        addText("claude api key")
        keyBox = addInput(prefs.getString("api_key", "") ?: "", "sk-ant-...", secret = true)

        addText("model")
        modelBox = addInput(prefs.getString("model", "claude-haiku-5-5") ?: "claude-haiku-5-5", "claude-haiku-5-5")

        addText("i play")
        val group = RadioGroup(this)
        group.orientation = RadioGroup.HORIZONTAL
        white = RadioButton(this)
        white.text = "white"
        white.id = View.generateViewId()
        black = RadioButton(this)
        black.text = "black"
        black.id = View.generateViewId()
        group.addView(white)
        group.addView(black)
        root.addView(group)
        if (prefs.getString("color", "white") == "black") black.isChecked = true else white.isChecked = true

        myTurn = CheckBox(this)
        myTurn.text = "it's my turn when i press start"
        myTurn.isChecked = prefs.getBoolean("my_turn", true)
        root.addView(myTurn)

        eloLabel = addText("")
        eloBar = SeekBar(this)
        eloBar.max = UciEngine.MAX_ELO - UciEngine.MIN_ELO
        eloBar.progress = (prefs.getInt("elo2", UciEngine.MAX_ELO) - UciEngine.MIN_ELO).coerceIn(0, eloBar.max)
        eloBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                updateEloLabel()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(eloBar)
        updateEloLabel()

        addText("engine")
        val engineGroup = RadioGroup(this)
        engineGroup.orientation = RadioGroup.HORIZONTAL
        engineStockfish = RadioButton(this)
        engineStockfish.text = "stockfish (on the phone)"
        engineStockfish.id = View.generateViewId()
        engineMaia = RadioButton(this)
        engineMaia.text = "maia3 (on my pc)"
        engineMaia.id = View.generateViewId()
        engineGroup.addView(engineStockfish)
        engineGroup.addView(engineMaia)
        root.addView(engineGroup)
        if (prefs.getString("engine", "stockfish") == "maia3") engineMaia.isChecked = true else engineStockfish.isChecked = true

        addText("maia3 settings (only used when maia3 is picked). run tools/maia3_server.py on your pc, it prints the address", 12f)
        addText("pc address")
        maiaHostBox = addInput(prefs.getString("maia_host", "") ?: "", "192.168.1.20:8765")
        addText("maia3 model (5m, 23m or 79m)")
        maiaModelBox = addInput(prefs.getString("maia_model", "23m") ?: "23m", "23m")
        addText("maia3 rating (the human strength it plays like, 600 to 2600)")
        maiaEloBox = addInput(prefs.getInt("maia_elo", 1500).toString(), "1500")
        maiaEloBox.inputType = InputType.TYPE_CLASS_NUMBER

        addText("")
        addButton("1. allow draw over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        addButton("2. start") { start() }
        addButton("stop") {
            stopService(Intent(this, ChessService::class.java))
            toast("stopped")
        }

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun updateEloLabel() {
        val elo = UciEngine.MIN_ELO + eloBar.progress
        eloLabel.text = if (elo >= UciEngine.MAX_ELO) "difficulty: full power (slide left to make it easier)" else "difficulty: stockfish elo $elo"
    }

    private fun save() {
        prefs.edit()
            .putString("api_key", keyBox.text.toString().trim())
            .putString("model", modelBox.text.toString().trim().ifEmpty { "claude-haiku-5-5" })
            .putString("color", if (black.isChecked) "black" else "white")
            .putBoolean("my_turn", myTurn.isChecked)
            .putInt("elo2", UciEngine.MIN_ELO + eloBar.progress)
            .putString("engine", if (engineMaia.isChecked) "maia3" else "stockfish")
            .putString("maia_host", maiaHostBox.text.toString().trim().removePrefix("http://"))
            .putString("maia_model", maiaModelBox.text.toString().trim().ifEmpty { "23m" })
            .putInt("maia_elo", (maiaEloBox.text.toString().trim().toIntOrNull() ?: 1500).coerceIn(600, 2600))
            .apply()
    }

    private fun start() {
        save()
        if (keyBox.text.isBlank()) {
            toast("paste your api key first")
            return
        }
        if (engineMaia.isChecked && maiaHostBox.text.isBlank()) {
            toast("type the maia3 pc address first (run tools/maia3_server.py on your pc)")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("overlay check says off, trying anyway")
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 100)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ChessService::class.java)
            i.putExtra("code", resultCode)
            i.putExtra("data", data)
            startForegroundService(i)
            toast("running. open your chess app now")
            moveTaskToBack(true)
        } else if (requestCode == 100) {
            toast("screen capture was not allowed")
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
