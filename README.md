# chess helper

android app: watches the chess board on your screen, asks stockfish for the best move, and highlights it on top of your chess app.

the board is read on the phone itself (finds the board, sees which squares have white or black pieces, follows the game with the rules of chess). claude haiku is only used when the game doesn't start from the normal position, when the app loses track, or to check a promoted piece, so it costs almost nothing.

## get the apk

github builds it for you. open the Actions tab and wait for the build (about 5 to 10 minutes), then download the apk from Releases ("latest build") and install it (allow "install unknown apps" for your browser).

## use it

1. open the app, paste your claude api key, pick your side and difficulty
2. tap "1. allow draw over other apps" and turn it on
3. tap "2. start" and allow screen capture
4. open your chess app. after a few seconds the best move is highlighted (green = where to, yellow = which piece, label shows the eval)
5. stop it from the app or from the notification

## if the build fails

open the failed run in the Actions tab, copy the red error text and send it to claude.

## tests

selftest/SelfTest.kt checks the board reading and game following on a real screenshot and on simulated games (both sides, flipped board, castling, en passant, promotion, mid-game start). it runs on a computer, not in the app.
