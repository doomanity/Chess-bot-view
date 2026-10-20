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
import com.github.bhlangonijr.chesslib.Side
import java.io.File

/**
 * the brain of the app. runs in the background:
 * watch the screen -> find the board and see which squares have white / black pieces (locally, no ai)
 * -> follow the game with the rules of chess -> stockfish picks a move -> draw it on the overlay.
 * haiku is only asked when the game doesn't start from the normal position, when we lose track, or after a promotion.
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
    @Volatile private var lastThumb: IntArray? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var screenW = 0
    private var screenH = 0


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
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        wm.addView(ov, lp)
        overlay = ov
    }

    // ---------------------------------------------------------------- main loop

    private class Frame(val px: IntArray, val geom: BoardGeom)

    private var geomHint: BoardGeom? = null

    /** grabs the screen and finds the board on it. null if there is no board on the screen right now */
    private fun look(): Frame? {
        val bmp = grab() ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val g = BoardVision.findBoard(px, bmp.width, bmp.height, geomHint) ?: return null
        geomHint = g
        return Frame(px, g)
    }

    private fun screenOf(f: Frame): IntArray? =
        BoardVision.classify(BoardVision.readSquares(f.px, screenW, f.geom))

    private fun loop() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val apiKey = (p.getString("api_key", "") ?: "").trim()
        val model = (p.getString("model", "claude-haiku-5-5") ?: "claude-haiku-5-5").trim()
        val iAmWhite = p.getString("color", "white") == "white"
        val myTurnAtStart = p.getBoolean("my_turn", true)
        val elo = p.getInt("elo2", UciEngine.MAX_ELO)

        try {
            val e = UciEngine(File(applicationInfo.nativeLibraryDir, "libstockfish.so").absolutePath)
            e.configure(elo)
            engine = e
        } catch (e: Exception) {
            toast("stockfish failed to start: ${e.message}")
            stopSelf()
            return
        }

        val tracker = Tracker(iAmWhite, myTurnAtStart)
        var lastFrom: RectF? = null
        var lastTo: RectF? = null
        var lastText: String? = null
        say("open your chess app, reading in 5 seconds")
        try {
            Thread.sleep(5000)
            while (running) {
                try {
                    val f = look()
                    if (f == null) {
                        say("looking for the chess board...")
                        lastThumb = null
                        Thread.sleep(700)
                        continue
                    }
                    val now = BoardVision.thumb(f.px, screenW, screenH, f.geom)
                    val prev = lastThumb
                    if (prev != null && !BoardVision.changed(prev, now)) {
                        Thread.sleep(350)
                        continue
                    }

                    // something changed: hide our highlights and wait until the board stops moving
                    hideOverlay()
                    Thread.sleep(500)
                    var shot = look() ?: continue
                    for (i in 0 until 8) {
                        Thread.sleep(250)
                        val again = look() ?: break
                        val same = !BoardVision.changed(
                            BoardVision.thumb(shot.px, screenW, screenH, shot.geom),
                            BoardVision.thumb(again.px, screenW, screenH, again.geom)
                        )
                        shot = again
                        if (same) break
                    }
                    lastThumb = BoardVision.thumb(shot.px, screenW, screenH, shot.geom)

                    val screen = screenOf(shot)
                    if (screen == null) {
                        say("can't tell the pieces apart on this screen")
                        continue
                    }

                    var step = tracker.update(screen)
                    if (step == Tracker.Step.RESYNC) {
                        say("lost track of the game, reading it again")
                        tracker.forget()
                        step = tracker.update(screen)
                    }
                    if (step == Tracker.Step.NEED_AI || (tracker.needsVerify && step != Tracker.Step.UNREADABLE)) {
                        val starting = step == Tracker.Step.NEED_AI
                        say(if (starting) "reading the board with haiku..." else "checking the promoted piece...")
                        var read: AiRead? = null
                        for (i in 1..3) {
                            read = Haiku.read(apiKey, model, shot.px, screenW, screenH, shot.geom)
                            if (read != null || Haiku.lastError.isNotEmpty()) break
                        }
                        if (read == null) {
                            val msg = if (Haiku.lastError.isNotEmpty()) Haiku.lastError
                            else "couldn't read the board. " + HaikuParse.lastDebug
                            say(msg)
                            toast(msg)
                            lastThumb = null
                            Thread.sleep(2000)
                            continue
                        }
                        val ok = if (starting) tracker.supplyGrid(read.grid, read.bottomWhite, screen)
                        else tracker.verifyWith(read.grid, screen)
                        if (!ok) {
                            if (starting) {
                                say("haiku's board didn't match the screen, trying again")
                                lastThumb = null
                                Thread.sleep(1500)
                                continue
                            }
                        }
                    } else if (step == Tracker.Step.UNREADABLE) {
                        say("couldn't follow that, waiting for the next move")
                        continue
                    }

                    val b = tracker.board ?: continue
                    val wb = tracker.whiteBottom ?: iAmWhite
                    val myTurn = (b.sideToMove == Side.WHITE) == iAmWhite
                    if (!myTurn) {
                        say("waiting for the opponent...")
                        lastText = null
                        continue
                    }

                    if (step == Tracker.Step.NO_CHANGE && lastText != null) {
                        // nothing moved (maybe you just tapped a piece): put our highlight back
                        showMove(lastFrom, lastTo, lastText!!)
                        rearm()
                        continue
                    }

                    val best = engine?.bestMove(b.fen, 1000)
                    if (best == null || best.move.length < 4) {
                        say("no move found, game over?")
                        continue
                    }
                    val fromSq = best.move.substring(0, 2)
                    val toSq = best.move.substring(2, 4)
                    val text = "$fromSq -> $toSq  (${best.eval})"
                    say("best move: $text")
                    lastFrom = squareRect(shot.geom, wb, fromSq)
                    lastTo = squareRect(shot.geom, wb, toSq)
                    lastText = text
                    showMove(lastFrom, lastTo, text)
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

    /** after we draw, wait for the overlay to show up and ignore it in the next change check */
    private fun rearm() {
        Thread.sleep(500)
        val f = look() ?: return
        // the next loop compares against a fresh thumbnail taken with the highlight on screen
        lastThumb = BoardVision.thumb(f.px, screenW, screenH, f.geom)
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

    // ---------------------------------------------------------------- overlay

    private fun hideOverlay() {
        main.post { overlay?.visibility = View.INVISIBLE }
    }

    private fun showMove(f: RectF?, t: RectF?, text: String) {
        main.post { overlay?.showMove(f, t, text) }
    }

    /** screen rectangle of a square like "e2", from the board we found on the screen */
    private fun squareRect(g: BoardGeom, whiteBottom: Boolean, sq: String): RectF? {
        val file = sq[0] - 'a'
        val rank = sq[1] - '1'
        if (file !in 0..7 || rank !in 0..7) return null
        val col = if (whiteBottom) file else 7 - file
        val row = if (whiteBottom) 7 - rank else rank
        val s = g.size / 8f
        return RectF(g.left + col * s, g.top + row * s, g.left + (col + 1) * s, g.top + (row + 1) * s)
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
