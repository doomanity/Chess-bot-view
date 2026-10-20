#!/usr/bin/env python3
"""
maia3 server for the chess helper app. run it on your pc (phone and pc on the same wifi).

setup on the pc (once):
  pip install torch
  pip install git+https://github.com/CSSLab/maia3.git
  maia3-cache --model 23m

run:
  python maia3_server.py                 (default model 23m, cpu)
  python maia3_server.py --device cuda   (if you have an nvidia gpu)

it prints the address to type into the app (like 192.168.1.20:8765).
the app sends the position and the rating you picked, this script answers with maia3's move.
"""
import argparse
import json
import shlex
import socket
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Uci:
    """one running uci engine process"""

    def __init__(self, cmd):
        self.cmd = cmd
        self.proc = subprocess.Popen(
            cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1
        )
        self.send("uci")
        self.wait("uciok")
        self.send("isready")
        self.wait("readyok")

    def send(self, line):
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()

    def wait(self, prefix):
        while True:
            line = self.proc.stdout.readline()
            if not line:
                raise RuntimeError("engine stopped")
            if line.startswith(prefix):
                return line.strip()

    def option(self, name, value):
        self.send(f"setoption name {name} value {value}")

    def best(self, fen):
        self.send("position fen " + fen)
        self.send("go nodes 1")  # maia3 answers with one forward pass
        return self.wait("bestmove").split()[1]

    def close(self):
        try:
            self.send("quit")
            self.proc.terminate()
        except Exception:
            pass


class Pool:
    """keeps one engine for the current model and rating, restarts it when the app asks for another"""

    def __init__(self, args):
        self.args = args
        self.lock = threading.Lock()
        self.key = None
        self.engine = None

    def command(self, model, elo):
        if self.args.cmd:
            return shlex.split(self.args.cmd.format(model=model, elo=elo))
        cmd = ["maia3-uci", "--model", model, "--elo", str(elo), "--device", self.args.device, "--use-uci-history"]
        if self.args.device == "cpu":
            cmd.append("--no-use-amp")
        return cmd

    def move(self, fen, model, elo):
        with self.lock:
            key = (model, elo)
            if key != self.key:
                if self.engine:
                    self.engine.close()
                print(f"starting engine: model {model}, rating {elo} (the first start loads the model, be patient)")
                self.engine = Uci(self.command(model, elo))
                # maia3 normally picks a human-like move at random (a human blunders now and then).
                # temperature 0 makes it always play its most likely move, which is far stronger
                self.engine.option("Temperature", self.args.temperature)
                self.engine.option("SelfElo", elo)
                self.engine.option("OppoElo", self.args.oppo_elo or elo)
                self.engine.send("isready")
                self.engine.wait("readyok")
                self.key = key
            try:
                return self.engine.best(fen)
            except Exception:
                self.key = None  # the engine died (bad position?), start a fresh one next time
                raise


def local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "your-pc-ip"


def make_handler(pool, default_model, default_elo):
    class Handler(BaseHTTPRequestHandler):
        def reply(self, code, obj):
            body = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            self.reply(200, {"ok": True})

        def do_POST(self):
            try:
                n = int(self.headers.get("content-length", "0"))
                req = json.loads(self.rfile.read(n) or b"{}")
                fen = req["fen"]
                model = str(req.get("model") or default_model)
                elo = int(req.get("elo") or default_elo)
                mv = pool.move(fen, model, elo)
                print(f"{fen}  ->  {mv}")
                self.reply(200, {"move": mv})
            except Exception as e:
                print("error:", e)
                self.reply(500, {"error": str(e)})

        def log_message(self, *a):
            pass

    return Handler


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--model", default="23m", help="5m, 23m or 79m")
    ap.add_argument("--elo", type=int, default=1500)
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--temperature", default="0", help="0 = always the most likely move (strongest). 1 = human-like randomness")
    ap.add_argument("--oppo-elo", type=int, default=0, help="rating of the opponent bot you play, 0 = same as your maia rating")
    ap.add_argument("--cmd", default=None, help="advanced: run another uci engine instead, {model} and {elo} get filled in")
    args = ap.parse_args()
    pool = Pool(args)
    srv = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(pool, args.model, args.elo))
    print(f"maia3 server ready. in the app type this address:  {local_ip()}:{args.port}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
