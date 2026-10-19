# chess helper

android app: reads a chess board on your screen with claude haiku, asks stockfish for the best move, and highlights it on top of your chess app.

## get the apk (github builds it for you)

in termux:

    pkg install git unzip
    unzip chess-helper-app.zip
    cd chess-helper-app
    git init -b main
    git add .
    git commit -m "chess helper app"
    git remote add origin https://github.com/YOUR_USERNAME/chess-helper.git
    git push -u origin main

(make the empty repo on github first, no readme. when git asks for a password, paste a personal access token with "repo" scope, not your real password.)

then open the repo on github, go to the Actions tab and wait for the build (about 5 to 10 minutes).
the apk shows up under Releases as "latest build". download it and install it (allow "install unknown apps" for your browser).

## use it

1. open the app, paste your claude api key, pick your side and difficulty
2. tap "1. allow draw over other apps" and turn it on
3. tap "2. start" and allow screen capture
4. open your chess app. after a few seconds the best move is highlighted
5. stop it from the app or from the notification

## if the build fails

open the failed run in the Actions tab, copy the red error text and send it to claude.
