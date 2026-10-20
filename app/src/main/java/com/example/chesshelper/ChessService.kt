package com.example.chesshelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import java.io.File
import kotlin.math.abs

/**
 * the brain of the app. runs in the background:
 * watch the screen -> when the board changes, ask haiku to read it -> stockfish picks a move -> draw it on the overlay
 */
class ChessService : Service() {
    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: BoardOverlay? = null
    private var engine: UciEngine? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    private var lastBmp: Bitmap? = null
    private var lastThumb: IntArray? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var screenW = 0
    private var screenH = 0

    private var box: FloatArray? = null          // board position found by haiku (fractions of the screen)
    private var boxOverride: FloatArray? = null  // board position typed in by you, wins if set
    private var whiteBottom = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        startForeground(1, buildNotification("starting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            setup(code, data)
        } catch (e: Exception) {
            toast("start failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        worker = Thread { loop() }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun setup(code: Int, data: Intent) {
        wm = getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        screenW = bounds.width()
        screenH = bounds.height()

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)
        projection = p

        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader = r
        vdisplay = p.createVirtualDisplay(
            "chess", screenW, screenH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )

        val ov = BoardOverlay(this)
        ov.visibility = View.INVISIBLE
        val lp = WindowManager.LayoutParams(
            screenW, screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(ov, lp)
        overlay = ov
    }

    // ---------------------------------------------------------------- main loop

    private fun loop() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val apiKey = (p.getString("api_key", "") ?: "").trim()
        val model = (p.getString("model", "claude-haiku-5-5") ?: "claude-haiku-5-5").trim()
        val iAmWhite = p.getString("color", "white") == "white"
        val myTurnAtStart = p.getBoolean("my_turn", true)
        val elo = p.getInt("elo", 1500)
        boxOverride = parseBox(p.getString("box", "") ?: "")

        try {
            val e = UciEngine(File(applicationInfo.nativeLibraryDir, "libstockfish.so").absolutePath)
            e.configure(elo)
            engine = e
        } catch (e: Exception) {
            toast("stockfish failed to start: ${e.message}")
            stopSelf()
            return
        }

        var board: Board? = null
        var first = true
        say("open your chess app, reading in 6 seconds")
        try {
            Thread.sleep(6000)
            while (running) {
                try {
                    val frame = grab()
                    if (frame == null) {
                        Thread.sleep(300)
                        continue
                    }
                    val prev = lastThumb
                    if (!first && prev != null && !changed(prev, thumb(frame))) {
                        Thread.sleep(400)
                        continue
                    }

                    // something changed: hide our highlights, let the move animation finish, take a clean shot
                    hideOverlay()
                    Thread.sleep(if (first) 300L else 1000L)
                    first = false
                    val shot = grab() ?: continue
                    lastThumb = thumb(shot)

                    say("reading the board...")
                    var reading: Reading? = null
                    for (i in 1..3) {
                        reading = Haiku.read(apiKey, model, shot)
                        if (reading != null || Haiku.lastError.isNotEmpty()) break
                    }
                    if (reading == null) {
                        val msg = if (Haiku.lastError.isNotEmpty()) Haiku.lastError
                        else "couldn't read the board. " + Haiku.lastDebug
                        say(msg)
                        toast(msg)
                        continue
                    }
                    val r: Reading = reading
                    whiteBottom = r.whiteBottom
                    if (r.box != null) box = r.box
                    val place = r.placement

                    val cur = board
                    val b: Board = when {
                        cur == null -> Game.newBoard(place, if (myTurnAtStart) iAmWhite else !iAmWhite)
                        place == Game.placement(cur) -> {
                            rearm()
                            continue
                        }
                        Game.track(cur, place) -> cur
                        else -> {
                            say("lost track of the game, resyncing (assuming it's your turn)")
                            Game.newBoard(place, iAmWhite)
                        }
                    }
                    board = b

                    val myTurn = (b.sideToMove == Side.WHITE) == iAmWhite
                    if (!myTurn) {
                        say("waiting for the opponent...")
                        rearm()
                        continue
                    }

                    val best = engine?.bestMove(b.fen, 800)
                    if (best == null || best.length < 4) {
                        say("no move found, game over?")
                        rearm()
                        continue
                    }
                    val fromSq = best.substring(0, 2)
                    val toSq = best.substring(2, 4)
                    val text = "$fromSq -> $toSq"
                    say("best move: $text")
                    showMove(squareRect(fromSq), squareRect(toSq), text)
                    rearm()
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    say("error: ${e.message}")
                    toast("error: ${e.message}")
                    Thread.sleep(1500)
                }
            }
        } catch (e: InterruptedException) {
            // service is stopping
        }
    }

    /** after we draw or wait, remember what the screen looks like so we only react to real changes */
    private fun rearm() {
        Thread.sleep(400)
        grab()?.let { lastThumb = thumb(it) }
    }

    // ---------------------------------------------------------------- screen capture

    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return lastBmp
        try {
            val plane = img.planes[0]
            val rowPad = plane.rowStride - plane.pixelStride * screenW
            val wide = Bitmap.createBitmap(screenW + rowPad / plane.pixelStride, screenH, Bitmap.Config.ARGB_8888)
            wide.copyPixelsFromBuffer(plane.buffer)
            val cut = Bitmap.createBitmap(wide, 0, 0, screenW, screenH)
            if (cut !== wide) wide.recycle()
            lastBmp = cut
        } finally {
            img.close()
        }
        return lastBmp
    }

    private fun effectiveBox(): FloatArray? = boxOverride ?: box

    /** tiny grayscale copy of the board area (or the whole screen if we don't know where the board is) */
    private fun thumb(b: Bitmap): IntArray {
        var src = b
        val bx = effectiveBox()
        if (bx != null) {
            val l = (bx[0] * b.width).toInt().coerceIn(0, b.width - 2)
            val t = (bx[1] * b.height).toInt().coerceIn(0, b.height - 2)
            val rr = (bx[2] * b.width).toInt().coerceIn(l + 1, b.width)
            val bb = (bx[3] * b.height).toInt().coerceIn(t + 1, b.height)
            if (rr - l >= 2 && bb - t >= 2) src = Bitmap.createBitmap(b, l, t, rr - l, bb - t)
        }
        val s = Bitmap.createScaledBitmap(src, 64, 64, true)
        val px = IntArray(64 * 64)
        s.getPixels(px, 0, 64, 0, 0, 64, 64)
        if (s !== src) s.recycle()
        if (src !== b) src.recycle()
        return IntArray(px.size) { i ->
            val c = px[i]
            (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
    }

    private fun changed(a: IntArray, b: IntArray): Boolean {
        var n = 0
        for (i in a.indices) if (abs(a[i] - b[i]) > 24) n++
        return n.toFloat() / a.size > 0.003f
    }

    // ---------------------------------------------------------------- overlay

    private fun hideOverlay() {
        main.post { overlay?.visibility = View.INVISIBLE }
    }

    private fun showMove(f: RectF?, t: RectF?, text: String) {
        main.post { overlay?.showMove(f, t, text) }
    }

    /** screen rectangle of a square like "e2", using the board position from haiku (or your override) */
    private fun squareRect(sq: String): RectF? {
        val bx = effectiveBox() ?: return null
        val file = sq[0] - 'a'
        val rank = sq[1] - '1'
        if (file !in 0..7 || rank !in 0..7) return null
        val col = if (whiteBottom) file else 7 - file
        val row = if (whiteBottom) 7 - rank else rank
        val left = bx[0] * screenW
        val top = bx[1] * screenH
        val w = (bx[2] - bx[0]) * screenW / 8f
        val h = (bx[3] - bx[1]) * screenH / 8f
        return RectF(left + col * w, top + row * h, left + (col + 1) * w, top + (row + 1) * h)
    }

    private fun parseBox(s: String): FloatArray? {
        val nums = s.split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (nums.size != 4 || nums[2] <= nums[0] || nums[3] <= nums[1]) return null
        if (!nums.all { it in 0f..1.02f }) return null
        return nums.toFloatArray()
    }

    // ---------------------------------------------------------------- notifications

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("chess", "chess helper", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ChessService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE
        )
        val icon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        return Notification.Builder(this, "chess")
            .setContentTitle("chess helper")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(icon, "stop", stop).build())
            .build()
    }

    private fun say(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }

    private fun toast(text: String) {
        main.post { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        try { overlay?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { vdisplay?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        engine?.close()
        super.onDestroy()
    }
}
