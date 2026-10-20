package com.example.chesshelper

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side

/**
 * Follows the game on the screen. It only needs to know which squares have a white or black piece:
 * the piece types come from the rules of chess. The AI is only asked to read the board once at the
 * start (and only if the game is not at the standard starting position), or when we lose track.
 */
class Tracker(private val iAmWhite: Boolean, private val myTurnAtStart: Boolean) {
    enum class Step { NEED_AI, STARTED, NO_CHANGE, MOVED, UNREADABLE, RESYNC }

    var board: Board? = null
        private set
    var whiteBottom: Boolean? = null
        private set
    private var misses = 0

    companion object {
        const val MAX_PLY = 3
    }

    /** forget everything, the next update asks for a fresh read */
    fun forget() {
        board = null
        whiteBottom = null
        misses = 0
        needsVerify = false
    }

    /** true after a pawn promoted: the screen can't tell us which piece it became, so the AI should look once */
    var needsVerify = false
        private set

    /** screen = 64 values (0 empty, 1 white, 2 black), row 0 is the top of the screen */
    fun update(screen: IntArray): Step {
        val known = board
        if (known == null || whiteBottom == null) {
            whiteBottom = BoardVision.whiteBottomOf(screen) ?: whiteBottom ?: iAmWhite
        }
        val occ = Game.screenToChess(screen, whiteBottom!!)
        if (known == null) {
            if (Game.mismatch(occ, Game.startOcc()) <= 1) {
                board = Board() // standard starting position, white to move
                misses = 0
                needsVerify = false
                return Step.STARTED
            }
            return Step.NEED_AI
        }

        // every explanation gets a cost: 2 per move, 3 per misread square, 2 extra if we have to
        // assume we had the wrong side to move. the cheapest wins. a one-square near-hit only counts
        // when it is the only one at that length, so a half-drawn frame can't pick a random move
        val other = Game.newBoard(Game.placement(known), known.sideToMove == Side.BLACK)
        val dbg = System.getProperty("tdebug") != null
        if (dbg) println("        [trk] known=${known.fen} mis0=${Game.mismatch(Game.occOf(known), occ)}")
        var bestCost = Int.MAX_VALUE
        var bestHit: Game.Hit? = null
        var bestOther = false
        var bestD = 0
        for (d in 0..MAX_PLY) {
            if (bestCost <= 2 * d) break // nothing deeper can be cheaper
            for (useOther in booleanArrayOf(false, true)) {
                if (useOther && d == 0) continue
                val b = if (useOther) other else known
                val all = Game.hitsAt(b, occ, d)
                val exact = all.filter { it.mis == 0 }
                val near = all.filter { it.mis == 1 }
                val extra = if (useOther) 2 else 0
                if (exact.isNotEmpty() && 2 * d + extra < bestCost) {
                    bestCost = 2 * d + extra; bestHit = Game.pick(exact); bestOther = useOther; bestD = d
                } else if (exact.isEmpty() && near.size == 1 && 2 * d + 3 + extra < bestCost) {
                    bestCost = 2 * d + 3 + extra; bestHit = near[0]; bestOther = useOther; bestD = d
                }
            }
        }
        val hit = bestHit
        if (hit != null) {
            if (dbg) println("        [trk] pick other=$bestOther d=$bestD mis=${hit.mis} cost=$bestCost path=${hit.path}")
            val target = if (bestOther) other else known
            Game.play(target, hit)
            board = target
            return moved(bestD, hit)
        }

        misses++
        return if (misses >= 3) Step.RESYNC else Step.UNREADABLE
    }

    private fun moved(plies: Int, h: Game.Hit): Step {
        misses = 0
        if (Game.hasPromotion(h)) needsVerify = true
        return if (plies == 0) Step.NO_CHANGE else Step.MOVED
    }

    /** the AI looked again after a promotion: take its piece types, keep whose turn it is */
    fun verifyWith(grid: List<String>, screen: IntArray): Boolean {
        val b = board ?: return false
        val gridScreen = Game.gridOcc(grid)
        if (Game.mismatch(gridScreen, screen) > 3) return false
        val wb = whiteBottom ?: return false
        val placement = Game.placementFromGrid(grid, wb)
        if (!Game.isValidPlacement(placement)) return false
        board = Game.newBoard(placement, b.sideToMove == Side.WHITE)
        needsVerify = false
        return true
    }

    /**
     * Starts from what the AI read: 8 screen rows of text. Checks it against the piece colors we see
     * ourselves, so a misread board is thrown away instead of trusted. Returns false if it doesn't fit.
     */
    fun supplyGrid(grid: List<String>, claimWhiteBottom: Boolean?, screen: IntArray): Boolean {
        val gridScreen = Game.gridOcc(grid)
        if (Game.mismatch(gridScreen, screen) > 3) return false
        val wb = BoardVision.whiteBottomOf(gridScreen) ?: claimWhiteBottom ?: iAmWhite
        val placement = Game.placementFromGrid(grid, wb)
        if (!Game.isValidPlacement(placement)) return false
        val whiteToMove = if (myTurnAtStart) iAmWhite else !iAmWhite
        board = Game.newBoard(placement, whiteToMove)
        whiteBottom = wb
        misses = 0
        needsVerify = false
        return true
    }
}
