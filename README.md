# chess helper

android app: watches the chess board on your screen, asks stockfish for the best move, and highlights it on top of your chess app.

the board is tracked on the phone itself, a few times a second: it finds the board, sees which squares have white or black pieces, learns what each piece looks like from the starting position, and follows the game with the rules of chess. hint dots, highlights and check frames are ignored. claude haiku is only used once when you start from a position that isn't the normal start (or if the app gets completely lost), so it costs almost nothing.

two engines: stockfish (on the phone, adjustable difficulty) or maia3 (plays like a human of the rating you pick, runs on your pc, see below).

## get the apk

github builds it for you. open the Actions tab and wait for the build (about 5 to 10 minutes), then download the apk from Releases ("latest build") and install it (allow "install unknown apps" for your browser).

## use it

1. open the app, paste your claude api key, pick your side and difficulty
2. tap "1. allow draw over other apps" and turn it on
3. tap "2. start" and allow screen capture
4. open your chess app. after a few seconds it shows 1 to 4 moves (you pick how many in the app) as arrows with a small list: dark green = best, light green = decent, yellow = acceptable, red with a skull = blunder. the numbers are stockfish's evaluation at full power, for both engines
5. stop it from the app or from the notification

## maia3 (optional, runs on your pc)

on your pc (phone and pc on the same wifi):

    pip install torch
    pip install git+https://github.com/CSSLab/maia3.git
    maia3-cache --model 23m
    python tools/maia3_server.py

it prints an address like 192.168.1.20:8765. in the app pick "maia3 (on my pc)" and type that address, the model and the rating. if the pc can't be reached the app falls back to stockfish for that move.

## if the build fails

open the failed run in the Actions tab, copy the red error text and send it to claude.

## tests

selftest/SelfTest.kt checks the board reading and game following on a real screenshot and on simulated games (both sides, flipped board, castling, en passant, promotion, mid-game start). it runs on a computer, not in the app.
