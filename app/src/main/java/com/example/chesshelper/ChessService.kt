package com.example.chesshelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.SystemClock
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import com.github.bhlangonijr.chesslib.Side
import java.io.File
import java.io.IOException

/**
 * the brain of the app. runs in the background, in real time and without the AI:
 * look at the screen a few times a second -> find the board and see which squares have white / black pieces
 * -> follow the game with the rules of chess (and the piece shapes it learned) -> the engine picks a move
 * -> draw it on the overlay. haiku is only asked when the game doesn't start from the normal position,
 * or when the app is completely lost and can't recognize the pieces itself.
 */
class ChessService : Service() {
    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var overlay: BoardOverlay? = null
    private var advisor: Advisor? = null
    private var stockfish: UciEngine? = null
    private var remote: RemoteEngine? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    private var wide: Bitmap? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var screenW = 0
    private var screenH = 0

    // what we know about the screen
    private var geom: BoardGeom? = null           // the board, in screen pixels
    private var region = IntArray(0)              // pixels of the board area (reused every frame)
    private var prevThumb: IntArray? = null
    private var lastCls: IntArray? = null         // piece colors at the last frame we processed
    private var overlayShown = false
    private var overlayIdx = IntArray(0)          // screen squares under the highlights
    private var shownFen: String? = null
    private var shownMoves: List<DrawMove> = emptyList()
    private var shownBoard: RectF? = null
    private var nMoves = 3
    private var aiCalls = 0
    private var aiBlockedUntil = 0L
    private var engineName = "stockfish"

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

    // ---------------------------------------------------------------- screen capture

    /** the newest picture of the screen (the same bitmap object every time, so don't keep it) */
    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage()
        if (img != null) {
            try {
                val plane = img.planes[0]
                val rowPad = plane.rowStride - plane.pixelStride * screenW
                val bw = screenW + rowPad / plane.pixelStride
                var b = wide
                if (b == null || b.width != bw || b.height != screenH) {
                    b?.recycle()
                    b = Bitmap.createBitmap(bw, screenH, Bitmap.Config.ARGB_8888)
                    wide = b
                }
                plane.buffer.rewind()
                b!!.copyPixelsFromBuffer(plane.buffer)
            } finally {
                img.close()
            }
        }
        return wide
    }

    /** the board area of the screen: pixels plus where the board is inside them. g is in screen pixels */
    private class Frame(val px: IntArray, val w: Int, val h: Int, val local: BoardGeom, val abs: BoardGeom)

    private fun regionFrame(bmp: Bitmap, g: BoardGeom): Frame? {
        if (g.left < 0 || g.top < 0 || g.left + g.size > screenW || g.top + g.size > screenH) return null
        val m = (g.size * 0.06f).toInt()
        val rx = maxOf(0, g.left - m)
        val ry = maxOf(0, g.top - m)
        val rw = minOf(screenW, g.left + g.size + m) - rx
        val rh = minOf(screenH, g.top + g.size + m) - ry
        if (rw <= 0 || rh <= 0) return null
        if (region.size != rw * rh) region = IntArray(rw * rh)
        bmp.getPixels(region, 0, rw, rx, ry, rw, rh)
        val local = BoardGeom(g.left - rx, g.top - ry, g.size)
        if (BoardVision.scoreGeom(region, rw, rh, local) < 0.7) return null
        return Frame(region, rw, rh, local, g)
    }

    /** grabs the screen and finds the board on it. null if there is no board on the screen right now */
    private fun look(): Frame? {
        val bmp = grab() ?: return null
        geom?.let { g -> regionFrame(bmp, g)?.let { return it } }
        // the board is not where it was: search the whole screen
        val px = IntArray(screenW * screenH)
        bmp.getPixels(px, 0, screenW, 0, 0, screenW, screenH)
        val g = BoardVision.findBoard(px, screenW, screenH, null)
        geom = g
        return if (g == null) null else regionFrame(bmp, g)
    }

    private fun thumbOf(f: Frame) = BoardVision.thumb(f.px, f.w, f.h, f.local)

    // ---------------------------------------------------------------- main loop

    private fun loop() {
        val p = getSharedPreferences("cfg", MODE_PRIVATE)
        val apiKey = (p.getString("api_key", "") ?: "").trim()
        val model = (p.getString("model", "claude-haiku-5-5") ?: "claude-haiku-5-5").trim()
        val iAmWhite = p.getString("color", "white") == "white"
        val myTurnAtStart = p.getBoolean("my_turn", true)
        val elo = p.getInt("elo2", UciEngine.MAX_ELO)
        val useMaia = p.getString("engine", "stockfish") == "maia3"
        val maiaHost = (p.getString("maia_host", "") ?: "").trim()
        val maiaModel = (p.getString("maia_model", "23m") ?: "23m").trim().ifEmpty { "23m" }
        val maiaElo = p.getInt("maia_elo", 1500)
        nMoves = p.getInt("n_moves", 3).coerceIn(1, 4)

        try {
            val sf = UciEngine(File(applicationInfo.nativeLibraryDir, "libstockfish.so").absolutePath)
            sf.configure(elo)
            stockfish = sf
        } catch (e: Exception) {
            toast("stockfish failed to start: ${e.message}")
            stopSelf()
            return
        }
        if (useMaia && maiaHost.isNotEmpty()) {
            remote = RemoteEngine(maiaHost, maiaModel, maiaElo)
            engineName = "maia3"
        } else {
            engineName = "stockfish"
        }
        advisor = Advisor(stockfish!!, remote)

        val tracker = Tracker(iAmWhite, myTurnAtStart)
        var moving = false
        var lastRead = 0L
        var lastStep = Tracker.Step.UNREADABLE
        var lostBoardSince = 0L
        say("open your chess app")
        try {
            Thread.sleep(2500)
            while (running) {
                try {
                    val now = SystemClock.elapsedRealtime()
                    val f = look()
                    if (f == null) {
                        if (lostBoardSince == 0L) lostBoardSince = now
                        if (now - lostBoardSince > 1200) hideMove()
                        say("looking for the chess board...")
                        prevThumb = null
                        geom = null
                        Thread.sleep(500)
                        continue
                    }
                    lostBoardSince = 0L
                    val thumb = thumbOf(f)
                    val prev = prevThumb
                    prevThumb = thumb

                    if (prev != null && BoardVision.changed(prev, thumb)) {
                        if (!moving) {
                            // the first sign of a change. hint dots or a selected piece don't change the position
                            if (justCosmetic(f)) {
                                Thread.sleep(POLL)
                                continue
                            }
                            moving = true
                            hideMove()
                        }
                        Thread.sleep(POLL)
                        continue
                    }

                    // the picture is calm. read it when something moved, or again while we can't make sense of it
                    val due = moving || prev == null || (lastStep == Tracker.Step.UNREADABLE && now - lastRead >= 700)
                    if (!due) {
                        Thread.sleep(POLL)
                        continue
                    }
                    moving = false
                    lastRead = now
                    lastStep = process(f, tracker, now, apiKey, model, iAmWhite)
                    Thread.sleep(POLL)
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

    /** true if the only changes are the ones that don't matter: hint dots, a selected piece, our own highlight */
    private fun justCosmetic(f: Frame): Boolean {
        val last = lastCls ?: return false
        val cls = BoardVision.classify(BoardVision.readSquares(f.px, f.w, f.local)) ?: return false
        for (i in 0 until 64) {
            if (i in overlayIdx) {
                // under our highlight the color can look different, but a piece appearing or leaving is real
                if ((cls[i] != BoardVision.EMPTY) != (last[i] != BoardVision.EMPTY)) return false
            } else if (cls[i] != last[i]) {
                return false
            }
        }
        return true
    }

    /** reads the board in this calm frame, follows the game, and shows the best move if it is our turn */
    private fun process(first: Frame, tracker: Tracker, now: Long, apiKey: String, model: String, iAmWhite: Boolean): Tracker.Step {
        var f = first
        if (overlayShown) {
            // our own highlight would be part of the picture
            hideMove()
            Thread.sleep(250)
            f = look() ?: return Tracker.Step.UNREADABLE
        }
        val reads = BoardVision.readSquares(f.px, f.w, f.local)
        val cls = BoardVision.classify(reads)
        if (cls == null) {
            say("can't tell the pieces apart on this screen")
            return Tracker.Step.UNREADABLE
        }
        lastCls = cls
        var step = tracker.update(cls, reads, now)

        if (step == Tracker.Step.NEED_AI || step == Tracker.Step.RESYNC || (tracker.needsVerify && step != Tracker.Step.UNREADABLE)) {
            val starting = step == Tracker.Step.NEED_AI || step == Tracker.Step.RESYNC
            if (now < aiBlockedUntil) {
                say("waiting a bit before asking haiku again")
                return Tracker.Step.UNREADABLE
            }
            say(if (starting) "reading the board with haiku..." else "checking the promoted piece...")
            val shot = Bitmap.createBitmap(f.px, 0, f.w, f.w, f.h, Bitmap.Config.ARGB_8888)
            var read: AiRead? = null
            aiCalls++
            for (i in 1..2) {
                read = Haiku.read(apiKey, model, shot)
                if (read != null || Haiku.lastError.isNotEmpty()) break
            }
            shot.recycle()
            if (read == null) {
                val msg = if (Haiku.lastError.isNotEmpty()) Haiku.lastError else "couldn't read the board. " + HaikuParse.lastDebug
                say(msg)
                toast(msg)
                aiBlockedUntil = SystemClock.elapsedRealtime() + 8000
                return Tracker.Step.UNREADABLE
            }
            if (starting) {
                if (step == Tracker.Step.RESYNC) tracker.forget()
                if (!tracker.supplyGrid(read.grid, read.bottomWhite, cls, reads)) {
                    say("haiku's board didn't match the screen, trying again")
                    aiBlockedUntil = SystemClock.elapsedRealtime() + 5000
                    return Tracker.Step.UNREADABLE
                }
                step = Tracker.Step.STARTED
            } else {
                tracker.verifyWith(read.grid, cls, reads)
            }
        } else if (step == Tracker.Step.UNREADABLE) {
            say("can't follow what's on the board, looking again...")
            debugLine(tracker, step)
            return step
        }

        debugLine(tracker, step)
        val b = tracker.board ?: return step
        val wb = tracker.whiteBottom ?: iAmWhite
        val myTurn = (b.sideToMove == Side.WHITE) == iAmWhite
        if (!myTurn) {
            say("waiting for the opponent...")
            shownFen = null
            return step
        }

        val fen = b.fen
        val g = f.abs
        if (shownFen == fen && shownMoves.isNotEmpty()) {
            showMoves(shownMoves, shownBoard ?: RectF(g.left.toFloat(), g.top.toFloat(), (g.left + g.size).toFloat(), (g.top + g.size).toFloat()))
            return step
        }
        say("thinking...")
        val adv = advisor ?: return step
        val cands = adv.advise(fen, nMoves)
        adv.listerError?.let { toast("maia3 pc not reachable ($it), using stockfish") }
        if (cands.isEmpty()) {
            say("no move found, game over?")
            return step
        }
        val moves = ArrayList<DrawMove>()
        for (c in cands) {
            val fromRect = squareRect(g, wb, c.move.substring(0, 2)) ?: continue
            val toRect = squareRect(g, wb, c.move.substring(2, 4)) ?: continue
            moves.add(DrawMove(fromRect, toRect, c.grade, c.rank, Game.san(fen, c.move), c.text))
        }
        val boardRect = RectF(g.left.toFloat(), g.top.toFloat(), (g.left + g.size).toFloat(), (g.top + g.size).toFloat())
        say("best: " + cands.joinToString("  ") { "${Game.san(fen, it.move)} ${it.text}" })
        shownFen = fen
        shownMoves = moves
        shownBoard = boardRect
        showMoves(moves, boardRect)
        return step
    }

    private fun debugLine(tracker: Tracker, step: Tracker.Step) {
        val text = "$step ${tracker.lastPlies}p | shapes ${tracker.templates.known}/12 | off ${tracker.lastTypeMismatch} | ai $aiCalls | $engineName"
        main.post { overlay?.setDebug(text) }
    }

    // ---------------------------------------------------------------- overlay

    private fun hideMove() {
        if (!overlayShown) return
        overlayShown = false
        overlayIdx = IntArray(0)
        main.post { overlay?.clearMove() }
    }

    private fun showMoves(moves: List<DrawMove>, boardRect: RectF) {
        overlayShown = true
        val g = BoardGeom(boardRect.left.toInt(), boardRect.top.toInt(), boardRect.width().toInt())
        overlayIdx = moves.flatMap { listOf(screenIndexOf(it.from, g), screenIndexOf(it.to, g)) }.distinct().toIntArray()
        main.post { overlay?.setMoves(moves, boardRect) }
        // wait until our highlight is in the picture, so it isn't mistaken for a change on the board
        Thread.sleep(350)
        look()?.let { prevThumb = thumbOf(it) }
    }

    /** which screen square (row * 8 + column) a highlight rectangle covers */
    private fun screenIndexOf(r: RectF, g: BoardGeom): Int {
        val s = g.size / 8f
        val col = ((r.centerX() - g.left) / s).toInt().coerceIn(0, 7)
        val row = ((r.centerY() - g.top) / s).toInt().coerceIn(0, 7)
        return row * 8 + col
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

    private var lastSaid = ""

    private fun say(text: String) {
        if (text == lastSaid) return
        lastSaid = text
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))
    }

    private fun toast(text: String) {
        main.post { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val POLL = 120L // ms between looks at the screen
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        try { overlay?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { vdisplay?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        stockfish?.close()
        remote?.close()
        super.onDestroy()
    }
}
