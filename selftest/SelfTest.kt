import com.example.chesshelper.*
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import javax.imageio.ImageIO

/**
 * Runs the board-reading and game-following code on a computer, with no phone:
 *  1. on a real screenshot,
 *  2. on drawn boards of simulated games (both colors, flipped and not, castling, en passant, promotion, ...),
 *  3. with a real Stockfish for the suggestions.
 * usage: java SelfTestKt <screenshot.jpg> <stockfish-binary>
 */
var failures = 0
fun check(ok: Boolean, what: String) {
    if (!ok) { failures++; println("  FAIL: $what") }
}

// ---------------------------------------------------------------- drawing a fake chess app screen

class Look(val light: Color, val dark: Color, val highlight: Color, val pieceW: Color, val pieceB: Color)

val looks = listOf(
    Look(Color(235, 218, 188), Color(173, 138, 108), Color(213, 196, 80), Color(250, 250, 250), Color(70, 70, 70)),   // like the real app
    Look(Color(238, 238, 210), Color(118, 150, 86), Color(246, 246, 105), Color(255, 255, 255), Color(30, 30, 30)),   // green board
    Look(Color(222, 227, 230), Color(140, 162, 173), Color(155, 199, 0), Color(245, 245, 245), Color(60, 60, 64))     // blue board
)

class Scene(
    val look: Look, val w: Int, val h: Int, val left: Int, val top: Int, val size: Int,
    val whiteBottom: Boolean, val rng: Random
)

fun render(sc: Scene, b: Board, lastFrom: Int?, lastTo: Int?, lifted: Int? = null, dots: List<Int> = emptyList(), overlay: Boolean = false): IntArray {
    val img = BufferedImage(sc.w, sc.h, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(22, 32, 48); g.fillRect(0, 0, sc.w, sc.h)
    g.color = Color(38, 74, 117); g.fillRoundRect(20, 200, sc.w - 40, 90, 40, 40)          // a bar above the board
    g.color = Color(38, 74, 117); g.fillRoundRect(20, sc.top + sc.size + 20, sc.w - 40, 130, 40, 40)
    val s = sc.size / 8f
    for (r in 0..7) for (c in 0..7) {
        val file = if (sc.whiteBottom) c else 7 - c
        val rank = if (sc.whiteBottom) 7 - r else r
        val idx = rank * 8 + file
        val x = sc.left + c * s; val y = sc.top + r * s
        val lightSq = (file + rank) % 2 == 1
        g.color = if (idx == lastFrom || idx == lastTo) sc.look.highlight else if (lightSq) sc.look.light else sc.look.dark
        g.fillRect(Math.round(x), Math.round(y), Math.round(s) + 1, Math.round(s) + 1)
        if (c == 0) { g.color = Color(120, 100, 80); g.font = Font("SansSerif", Font.PLAIN, 22); g.drawString("${rank + 1}", x + 5, y + 24) }
        if (r == 7) { g.color = Color(120, 100, 80); g.drawString("${'a' + file}", x + s - 22, y + s - 8) }
        val p = b.getPiece(com.github.bhlangonijr.chesslib.Square.squareAt(idx))
        if (p != com.github.bhlangonijr.chesslib.Piece.NONE && idx != lifted) {
            val white = p.pieceSide == Side.WHITE
            val big = when (p.pieceType.name) { "PAWN" -> 0.27f; "KING", "QUEEN" -> 0.40f; else -> 0.34f }
            val cx = x + s / 2; val cy = y + s / 2 + s * 0.04f
            val rad = s * big
            g.color = if (white) sc.look.pieceW else sc.look.pieceB
            g.fill(java.awt.geom.Ellipse2D.Float(cx - rad, cy - rad * 1.1f, rad * 2, rad * 2.2f))
            g.color = if (white) Color(30, 30, 30) else Color(200, 200, 200)
            g.stroke = java.awt.BasicStroke(5f)
            g.draw(java.awt.geom.Ellipse2D.Float(cx - rad, cy - rad * 1.1f, rad * 2, rad * 2.2f))
        }
        if (idx in dots) { g.color = Color(0, 0, 0, 40); val d = s * 0.16f; g.fill(java.awt.geom.Ellipse2D.Float(x + s / 2 - d, y + s / 2 - d, d * 2, d * 2)) }
    }
    if (overlay) { g.color = Color(255, 87, 34, 200); g.stroke = java.awt.BasicStroke(14f); g.drawLine(sc.left + 200, sc.top + 200, sc.left + 600, sc.top + 700) }
    g.dispose()
    val px = IntArray(sc.w * sc.h)
    img.getRGB(0, 0, sc.w, sc.h, px, 0, sc.w)
    // a little camera noise, like a lossy copy
    for (i in px.indices step 7) {
        val n = sc.rng.nextInt(7) - 3
        val p = px[i]
        val r = (((p shr 16) and 255) + n).coerceIn(0, 255); val gg = (((p shr 8) and 255) + n).coerceIn(0, 255); val bb = ((p and 255) + n).coerceIn(0, 255)
        px[i] = (255 shl 24) or (r shl 16) or (gg shl 8) or bb
    }
    return px
}

fun sqIdx(name: String) = (name[1] - '1') * 8 + (name[0] - 'a')

// ---------------------------------------------------------------- what the phone app does per frame

/** what the AI would answer when asked to read the screen: the 8 rows as drawn */
fun screenRows(truth: Board, whiteBottom: Boolean): List<String> = (0..7).map { r -> (0..7).map { c ->
    val file = if (whiteBottom) c else 7 - c; val rank = if (whiteBottom) 7 - r else r
    val p = truth.getPiece(com.github.bhlangonijr.chesslib.Square.squareAt(rank * 8 + file))
    if (p == com.github.bhlangonijr.chesslib.Piece.NONE) '.' else p.fenSymbol[0]
}.joinToString("") }

class Phone(val tracker: Tracker, val sc: Scene) {
    var geom: BoardGeom? = null
    var truth: Board? = null      // lets the test play the part of the AI when the tracker asks for a check after a promotion
    var aiChecks = 0
    /** one look at the screen, returns the tracker's verdict */
    fun look(px: IntArray): Tracker.Step? {
        val g = BoardVision.findBoard(px, sc.w, sc.h, geom)
        if (g == null) { println("    [phone] board not found"); return null }
        geom = g
        val reads = BoardVision.readSquares(px, sc.w, g)
        val cls = BoardVision.classify(reads)
        if (cls == null) { println("    [phone] pieces not classified (tones ${reads.filter { it.occupied }.map { it.tone }.distinct().sorted()})"); return null }
        val st = tracker.update(cls)
        if (System.getProperty("verbose") != null) println("      [look] step=$st tracker=${tracker.board?.fen}")
        val t = truth
        if (tracker.needsVerify && t != null && (st == Tracker.Step.MOVED)) {
            aiChecks++
            check(tracker.verifyWith(screenRows(t, sc.whiteBottom), cls), "AI check after promotion was rejected")
        }
        return st
    }
}

fun samePosition(tracker: Tracker, truth: Board): Boolean {
    val b = tracker.board ?: return false
    return Game.placement(b) == Game.placement(truth) && b.sideToMove == truth.sideToMove
}

// ---------------------------------------------------------------- tests

fun testRealScreenshot(path: String) {
    println("== real screenshot")
    val img = ImageIO.read(File(path))
    val w = img.width; val h = img.height
    val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
    val g = BoardVision.findBoard(px, w, h)
    check(g != null, "board not found")
    if (g == null) return
    println("  found $g")
    check(Math.abs(g.left - 78) <= 3 && Math.abs(g.top - 918) <= 3 && Math.abs(g.size - 1032) <= 4, "board position differs from the one measured by hand (78, 918, 1032)")
    val cls = BoardVision.classify(BoardVision.readSquares(px, w, g))
    check(cls != null, "pieces not classified")
    if (cls == null) return
    // the board in the screenshot: Black at the bottom, white pawn moved d2-d4
    val expect = "WWWWWWWW" + "WWWW.WWW" + "........" + "....W..." + "........" + "........" + "BBBBBBBB" + "BBBBBBBB"
    val got = (0 until 64).map { ".WB"[cls[it]] }.joinToString("")
    check(got == expect, "piece map\n    got      $got\n    expected $expect")
    check(BoardVision.whiteBottomOf(cls) == false, "orientation should be flipped (white at the top)")
    // follow it: the app starts from the standard position, so d2-d4 must be found as one move
    val tr = Tracker(false, true)
    val step = tr.update(cls)
    println("  tracker step on this screenshot: $step")
    val start = Tracker(false, true)
    // fresh game with the same screen: not the starting position (d4 was played), so the AI would be asked
    check(step == Tracker.Step.NEED_AI, "expected NEED_AI for a position after 1.d4")
    val ok = tr.supplyGrid(listOf("RNBKQBNR", "PPPP.PPP", "........", "....P...", "........", "........", "pppppppp", "rnbkqbnr"), false, cls)
    check(ok, "AI grid for this screenshot was rejected")
    println("  position after AI read: ${tr.board?.fen}")
    check(tr.board != null && Game.placement(tr.board!!) == "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR", "placement from the AI grid is wrong")
    check(tr.board!!.sideToMove == Side.BLACK, "side to move should be black (user plays black and checked 'my turn')")
}

fun playMove(truth: Board, uci: String): Move {
    val mv = truth.legalMoves().first { it.toString() == uci }
    truth.doMove(mv)
    return mv
}

fun simulate(name: String, iAmWhite: Boolean, look: Look, sf: UciEngine?, plies: Int, seed: Long, size: Int = 1032, w: Int = 1189, h: Int = 2576, left: Int = 78, top: Int = 918) {
    val rng = Random(seed)
    val sc = Scene(look, w, h, left, top, size, whiteBottom = iAmWhite, rng = rng)
    val truth = Board()
    val tracker = Tracker(iAmWhite, true)
    val phone = Phone(tracker, sc)
    phone.truth = truth
    var step = phone.look(render(sc, truth, null, null))
    check(step == Tracker.Step.STARTED, "$name: start position not recognised ($step)")
    var suggestionsOk = 0; var suggestionsBad = 0
    var ply = 0
    var multi = 0; var anim = 0
    while (ply < plies && !truth.legalMoves().isEmpty() && !truth.isDraw) {
        // choose a move for whoever is to play
        val mineToPlay = (truth.sideToMove == Side.WHITE) == iAmWhite
        var mv: Move
        if (mineToPlay && sf != null && tracker.board != null && samePosition(tracker, truth)) {
            val s = sf.bestMove(tracker.board!!.fen, 60)
            val legal = s != null && truth.legalMoves().any { it.toString() == s.move }
            if (legal) suggestionsOk++ else suggestionsBad++
            mv = if (legal && rng.nextInt(10) < 8) truth.legalMoves().first { it.toString() == s!!.move } else truth.legalMoves()[rng.nextInt(truth.legalMoves().size)]
        } else {
            val l = truth.legalMoves(); mv = l[rng.nextInt(l.size)]
        }
        // prefer queen promotion like apps do by default
        val before = Board(); before.loadFromFen(truth.fen)
        truth.doMove(mv)
        ply++
        val from = mv.from.ordinal; val to = mv.to.ordinal
        // an animation frame: piece lifted off its square, nothing on the target yet
        if (rng.nextInt(4) == 0) {
            val frame = render(sc, before, null, null, lifted = from)
            phone.look(frame); anim++
        }
        // the legal move dots the app shows while a piece is selected (should not change anything)
        if (rng.nextInt(4) == 0) phone.look(render(sc, before, null, null, dots = listOf(to, (to + 8) % 64)))
        var frame = render(sc, truth, from, to)
        // sometimes the opponent's reply is already on screen when we look (2 moves at once)
        var multiMove = false
        if (!truth.legalMoves().isEmpty() && rng.nextInt(3) == 0) {
            val l = truth.legalMoves(); val mv2 = l[rng.nextInt(l.size)]
            truth.doMove(mv2); ply++; multiMove = true; multi++
            frame = render(sc, truth, mv2.from.ordinal, mv2.to.ordinal)
        }
        step = phone.look(frame)
        if (step == Tracker.Step.UNREADABLE || step == Tracker.Step.RESYNC) { step = phone.look(frame) } // a second look at the same screen
        val ok = samePosition(tracker, truth)
        if (System.getProperty("verbose") != null) println("    ply $ply ${mv} step=$step multi=$multiMove ok=$ok  tracker=${tracker.board?.fen}")
        if (!ok && System.getProperty("verbose") != null) {
            val cls = BoardVision.classify(BoardVision.readSquares(frame, sc.w, phone.geom!!))!!
            val occ = Game.screenToChess(cls, sc.whiteBottom)
            println("    DEBUG obs vs truth squares differing: " + Game.mismatch(occ, Game.occOf(truth)))
            val k = Board(); k.loadFromFen(before.fen)
            println("    DEBUG board before this ply: ${before.fen}")
            for (d in 0..3) {
                val hk = Game.hitsAt(k, occ, d).filter { it.mis == 0 }
                println("    DEBUG depth $d exact hits from before-board: ${hk.size} ${hk.take(3).map { h -> h.path.joinToString(",") }}")
            }
        }
        check(ok, "$name: ply $ply lost the game (step=$step multi=$multiMove)\n    truth   ${truth.fen}\n    tracker ${tracker.board?.fen}")
        if (!ok) return
    }
    println("  $name: followed $ply plies ok (animations $anim, double moves $multi, suggestions ok $suggestionsOk bad $suggestionsBad, ai checks ${phone.aiChecks})")
    check(suggestionsBad == 0, "$name: stockfish suggested an illegal move")
}

/** hand-made sequences with the special moves */
fun special(sf: UciEngine?) {
    println("== special moves")
    val sc = Scene(looks[0], 1189, 2576, 78, 918, 1032, true, Random(5))
    fun run(label: String, iAmWhite: Boolean, moves: String) {
        val s = sc.let { Scene(it.look, it.w, it.h, it.left, it.top, it.size, iAmWhite, it.rng) }
        val truth = Board(); val tr = Tracker(iAmWhite, true); val ph = Phone(tr, s)
        ph.truth = truth
        ph.look(render(s, truth, null, null))
        for (u in moves.split(" ")) {
            val mv = playMove(truth, u)
            ph.look(render(s, truth, mv.from.ordinal, mv.to.ordinal))
            check(samePosition(tr, truth), "$label: after $u\n    truth   ${truth.fen}\n    tracker ${tr.board?.fen}")
        }
        println("  $label ok (${if (iAmWhite) "white" else "black"} at the bottom)")
    }
    run("castling both sides", true, "e2e4 e7e5 g1f3 b8c6 f1c4 g8f6 e1g1 d7d5 e4d5 f6d5 f1e1 c8e6 b1c3 f8e7 d2d4 e8g8")
    run("castling flipped", false, "e2e4 e7e5 g1f3 b8c6 f1c4 g8f6 e1g1 d7d5 e4d5 f6d5 f1e1 c8e6 b1c3 f8e7 d2d4 e8g8")
    run("en passant", true, "e2e4 a7a6 e4e5 d7d5 e5d6 c7d6")
    run("en passant black", false, "a2a3 e7e5 a3a4 e5e4 d2d4 e4d3 c2d3")
    run("queen promotion", true, "a2a4 b7b5 a4b5 a7a6 b5a6 c8b7 a6b7 b8c6 b7a8q")
    run("queen promotion flipped", false, "a2a4 b7b5 a4b5 a7a6 b5a6 c8b7 a6b7 b8c6 b7a8q")
    run("knight promotion (the AI check fixes the piece)", true, "a2a4 b7b5 a4b5 a7a6 b5a6 c8b7 a6b7 b8c6 b7a8n")
    run("knight promotion flipped", false, "a2a4 b7b5 a4b5 a7a6 b5a6 c8b7 a6b7 b8c6 b7a8n")
}

/** start in the middle of a game (the AI reads the board), including a wrong guess about whose turn it is */
fun midGame() {
    println("== starting in the middle of a game")
    for (iAmWhite in listOf(true, false)) for (myTurnGuess in listOf(true, false)) {
        val sc = Scene(looks[1], 1189, 2576, 60, 700, 1070, iAmWhite, Random(9))
        val truth = Board()
        for (u in "e2e4 e7e5 g1f3 b8c6 f1b5 a7a6 b5a4 g8f6 e1g1".split(" ")) playMove(truth, u) // black to move
        val tr = Tracker(iAmWhite, myTurnGuess)
        val ph = Phone(tr, sc)
        ph.truth = truth
        val frame = render(sc, truth, sqIdx("e1"), sqIdx("g1"))
        val step = ph.look(frame)
        check(step == Tracker.Step.NEED_AI, "mid-game start should ask the AI (was $step)")
        val rows = screenRows(truth, iAmWhite)
        val g = BoardVision.findBoard(frame, sc.w, sc.h)!!
        val cls = BoardVision.classify(BoardVision.readSquares(frame, sc.w, g))!!
        // wrong claim about orientation must not matter
        check(tr.supplyGrid(rows, !iAmWhite, cls), "AI rows rejected")
        // a misread piece colour must be rejected
        val bad = rows.toMutableList(); bad[3] = bad[3].replace('.', 'P').take(8)
        check(!Tracker(iAmWhite, myTurnGuess).supplyGrid(bad, null, cls), "a clearly wrong AI board was accepted")
        // play on: black replies d6, then white c3. even if our guess about the turn was wrong, we recover
        for (u in "d7d6 c2c3".split(" ")) {
            val mv = playMove(truth, u)
            val st = ph.look(render(sc, truth, mv.from.ordinal, mv.to.ordinal))
            check(samePosition(tr, truth), "mid-game (iAmWhite=$iAmWhite turnGuess=$myTurnGuess) after $u: step=$st\n    truth   ${truth.fen}\n    tracker ${tr.board?.fen}")
        }
        println("  iAmWhite=$iAmWhite, guessed my turn=$myTurnGuess: ok")
    }
}

fun engineTest(sf: UciEngine) {
    println("== stockfish")
    val s = sf.bestMove("rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq - 0 1", 300)
    println("  after 1.d4 black suggestion: ${s?.move} eval ${s?.eval}")
    check(s != null && s.move.length >= 4, "no move from stockfish")
    // mate in one: white to play Qh5#  (scholar's mate setup)
    val m = sf.bestMove("r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4", 300)
    println("  scholar's mate position: ${m?.move} eval ${m?.eval}")
    check(m?.move == "h5f7", "stockfish should find Qxf7#")
}

fun main(args: Array<String>) {
    val shot = args[0]
    val sfPath = args[1]
    val sf = UciEngine(sfPath)
    sf.configure(UciEngine.MAX_ELO)
    testRealScreenshot(shot)
    special(sf)
    midGame()
    println("== simulated games")
    var seed = 100L
    for (look in looks.indices) for (white in listOf(true, false)) {
        simulate("look$look ${if (white) "white" else "black"}", white, looks[look], sf, 90, seed++)
    }
    // other screen sizes and board positions
    simulate("small phone", true, looks[0], sf, 60, 7, size = 656, w = 720, h = 1560, left = 32, top = 340)
    simulate("big tablet black", false, looks[2], sf, 60, 8, size = 1600, w = 1800, h = 2560, left = 100, top = 400)
    engineTest(sf)
    sf.close()
    println(if (failures == 0) "\nALL TESTS PASSED" else "\n$failures FAILURE(S)")
    if (failures > 0) System.exit(1)
}
