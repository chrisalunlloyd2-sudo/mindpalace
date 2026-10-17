#!/usr/bin/env python3
"""Ask the running game to restart politely: it shows a countdown and the player can press Esc to cancel.

Usage: python tools/request_restart.py [--seconds 30] [--reason "new build ready"] [--dir <game working dir>]

Never kills anything. Exit codes: 0 = the game accepted and is restarting, 2 = the player cancelled
(do not ask again for 10 minutes), 3 = no response (game not running or not reading requests).
"""
import argparse
import os
import sys
import time

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=int, default=30)
    ap.add_argument("--reason", default="update ready")
    ap.add_argument("--dir", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    ap.add_argument("--no-wait", action="store_true")
    a = ap.parse_args()
    d = os.path.abspath(a.dir)
    req = os.path.join(d, "restart.request")
    cancelled = os.path.join(d, "restart.cancelled")
    if os.path.exists(cancelled):
        os.remove(cancelled)
    with open(req, "w", encoding="utf-8") as f:
        f.write(f"{a.seconds}\n{a.reason}\n")
    print(f"restart requested: {a.seconds}s, reason: {a.reason}")
    if a.no_wait:
        return 0
    deadline = time.time() + a.seconds + 120
    picked_up = False
    while time.time() < deadline:
        if os.path.exists(cancelled):
            print("the player cancelled the restart; snoozed for 10 minutes")
            return 2
        if not os.path.exists(req):
            picked_up = True
            break
        time.sleep(1)
    if picked_up and not os.path.exists(cancelled):
        print("the game consumed the request and is restarting")
        return 0
    # Nobody answered: remove the request so a game started later does not restart on its own.
    if os.path.exists(req):
        os.remove(req)
    print("no response from the game (not running, or restart is snoozed)")
    return 3

if __name__ == "__main__":
    sys.exit(main())
