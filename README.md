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
5. stop or pause it from the app, the quick menu or the notification

## extras

- **guess the bot's move**: while the bot thinks, a faint dashed arrow with a "?" shows its likely move (maia3 at the bot's rating if you use maia3, else stockfish). your answer to that move is worked out early, so if the guess is right your moves show up at once ("ready early" on the card)
- **live accuracy**: "you 87% · bot 72%" on the card, lichess style. each move is scored by how much it dropped the mover's winning chances. moves the app missed (two moves at once) are skipped
- **bot rating**: set the rating of the bot you play against. used for the guess and told to maia3 as the opponent rating
- **pause**: hides everything and stops reading the screen until you resume

## side bar (quick menu while playing)

touch the right (or left) edge of the screen: a thin glowing bar shows up. slide it towards the middle and the quick menu opens: your side, engine, strength, moves shown, eval bar / threat / best line, arrow style, effects, theme, rethink, max power (stockfish at full power for one move, a few saves per game) and new game. drag the bar up or down to move it. it hides itself when you don't touch it.

## maia3 (optional, runs on your pc)

on your pc (phone and pc on the same wifi):

    pip install torch
    pip install git+https://github.com/CSSLab/maia3.git
    maia3-cache --model 23m
    python tools/maia3_server.py

it prints an address like 192.168.1.20:8765. in the app pick "maia3 (on my pc)" and type that address, the model and the rating. if the pc can't be reached the app falls back to stockfish for that move.

**human-ness** (0 to 100%): 0 = always maia's most likely move (strongest). higher = it picks moves the way a real human of that rating would, randomness and mistakes included (maia3's temperature, 100% = 1.0).

**away from home**: install tailscale on the pc and the phone with the same account, then use the pc's 100.x.x.x tailscale address instead. don't open the port on your router, the server has no password.

## if the build fails

open the failed run in the Actions tab, copy the red error text and send it to claude.

## tests

selftest/SelfTest.kt checks the board reading and game following on a real screenshot and on simulated games (both sides, flipped board, castling, en passant, promotion, mid-game start). it runs on a computer, not in the app.
