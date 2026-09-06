#!/usr/bin/env python3
"""
scout_bot.py — MindPalace quota-free recon bot (H23 + H12 + H24 metrics).

Reads what the LIVE game writes (game_console.log, chat_logs/*.jsonl,
telemetry.db, memory.db) and produces structured intelligence:

  --report          one-shot JSON+human report to stdout (default)
  --watch           tail game_console.log live, print new lines as they land
  --metrics         write mindpalace_memory/metrics/slm_quality_YYYY-MM-DD.json
                    (meta-chatter %, code-rate %, tool-call outcomes)
  --map             ASCII map of rooms/agents from latest telemetry

No LLM calls, no cloud, $0 quota. Safe to cron every 15 min.
"""
import json, os, re, sqlite3, sys, time
from collections import Counter
from datetime import datetime, timezone
from difflib import SequenceMatcher
from pathlib import Path

for _s in (sys.stdout, sys.stderr):
    if hasattr(_s, "reconfigure"):
        _s.reconfigure(encoding="utf-8", errors="replace")

REPO = Path(r"C:/Users/viper/AIGEN_SYS/repos/mindpalace")
CHAT_DIR = REPO / "chat_logs"
MEMDIR = Path(r"C:/Users/viper/AIGEN_SYS/mindpalace_memory")
METRICS_DIR = MEMDIR / "metrics"
CONSOLE = REPO / "game_console.log"

META_KW = ("would you like", "let's continue", "please provide", "understood,",
           "as outlined", "drifting off-topic", "refocus", "absolutely, let's",
           "more detailed explanation", "let's refine our approach")
CODE_PAT = re.compile(r"```|public class |def \w+\(|System\.out|import java\.|function \w+\(|const \w+ =")
# NOTE: the game console writes UTF-8 arrows as literal '?' (codepage), so
# the regexes below match the actual bytes in game_console.log.
TOOL_RE = re.compile(r"\[Tool\] (\w+) \?? (.+)")
ROUTED_RE = re.compile(r"routed (\w+) \?? ([\w.:-]+)")
QUORUM_RE = re.compile(r"Quorum\[#([\w-]+): (.+?)\] (\w+) \??(\d+) \??(\d+) \??(\d+) \(w:([\d.]+)")
NO_ROOM_RE = re.compile(r"Auto-cycle .+ discussing \?")


def read_chat(day=None):
    """All chat messages (ts, text) from per-day logs, newest last."""
    files = sorted(CHAT_DIR.glob("chat-*.jsonl"))
    if day:
        files = [f for f in files if day in f.name]
    msgs = []
    for f in files:
        try:
            for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    d = json.loads(line)
                    msgs.append((d.get("ts", ""), str(d.get("msg", ""))))
                except Exception:
                    pass
        except OSError:
            pass
    return msgs


def today_stats():
    msgs = read_chat(day=datetime.now().strftime("%Y-%m-%d"))
    if not msgs:
        return None
    texts = [t for _, t in msgs]
    meta = sum(1 for t in texts if any(k in t.lower() for k in META_KW))
    code = sum(1 for t in texts if CODE_PAT.search(t))
    # Repetition: dedupe near-identical openers
    openers = Counter(t.strip().split("\n")[0][:60] for t in texts)
    top = openers.most_common(3)
    loop_ratio = sum(c for _, c in top) / max(len(texts), 1)
    return {
        "date": datetime.now().strftime("%Y-%m-%d"),
        "messages": len(texts),
        "meta_chatter_pct": round(100 * meta / len(texts), 1),
        "code_bearing_pct": round(100 * code / len(texts), 1),
        "top_openers": [{"line": l, "count": c} for l, c in top],
        "loop_ratio_pct": round(100 * loop_ratio, 1),
    }


def console_tail(n=60):
    try:
        lines = CONSOLE.read_text(encoding="utf-8", errors="replace").splitlines()
        return [l for l in lines[-n:]]
    except OSError:
        return []


def console_stats():
    """Parse the live console for cycle/routing/quorum/tool activity."""
    try:
        text = CONSOLE.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return {}
    routed = ROUTED_RE.findall(text)
    quorums = QUORUM_RE.findall(text)
    tools = TOOL_RE.findall(text)
    solved = len(re.findall(r"SOLVED ", text))
    raised = len(re.findall(r"raised issue #", text))
    no_room = len(NO_ROOM_RE.findall(text))
    return {
        "cycles": text.count("Auto-cycle"),
        "cycles_without_room": no_room,
        "routed": Counter(m for _, m in routed).most_common(5),
        "quorum_results": Counter(q[2] for q in quorums).most_common(),
        "quorum_votes_yes": sum(int(q[3]) for q in quorums),
        "tool_calls": Counter(name for name, _ in tools).most_common(),
        "tool_fails": sum(1 for name, res in tools if "failed" in res or "missing" in res or "never-twice" in res),
        "issues_solved": solved,
        "issues_raised": raised,
    }


def telemetry_counts():
    db = MEMDIR / "telemetry.db"
    if not db.exists():
        return None
    out = {}
    try:
        c = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
        cur = c.cursor()
        for cat in ("quorum", "agent", "depin", "system", "code", "issue"):
            try:
                cur.execute("SELECT COUNT(*) FROM events WHERE category=?", (cat,))
                out[cat] = cur.fetchone()[0]
            except sqlite3.OperationalError:
                pass
        # last hour of events
        try:
            cur.execute(
                "SELECT COUNT(*) FROM events WHERE ts > ?",
                (int(time.time() * 1000) - 3600_000,))
            out["last_hour"] = cur.fetchone()[0]
        except sqlite3.OperationalError:
            pass
        c.close()
    except sqlite3.Error:
        return None
    return out


def game_alive():
    try:
        import subprocess
        out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq java.exe"],
                              capture_output=True, text=True, timeout=15).stdout
        return "java.exe" in out.lower()
    except Exception:
        return None


def ascii_map():
    rooms = []
    try:
        text = CONSOLE.read_text(encoding="utf-8", errors="replace")
        m = re.search(r"World built: (\d+) hallways?, (\d+) rooms", text)
        if m:
            rooms.append(f"MANSION: {m.group(2)} rooms / {m.group(1)} hallways")
        for pat, label in ((r"\[TuringTape\] anchored at (.+)", "TURING TAPE"),
                            (r"\[RotorRoom\] (.+)", "ROTOR RINGS"),
                            (r"\[Banburismus\] (.+)", "BANBURISMUS"),
                            (r"\[NashFountain\] (.+)", "NASH FOUNTAIN"),
                            (r"\[Plugboard\] (.+)", "PLUGBOARD")):
            mm = re.findall(pat, text)
            if mm:
                rooms.append(f"{label}: {mm[-1]}")
    except OSError:
        pass
    return rooms


def report():
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    alive = game_alive()
    ts = telemetry_counts()
    cs = console_stats()
    tod = today_stats() or {}
    print(f"=== MINDPALACE SCOUT REPORT — {now} ===")
    print(f"game process: {'ALIVE' if alive else 'DOWN' if alive is False else '?'}")
    if ts:
        print(f"telemetry events: {ts}")
    if cs:
        print(f"console: cycles={cs.get('cycles', 0)} solved={cs.get('issues_solved',0)} "
              f"raised={cs.get('issues_raised',0)} tool_fails={cs.get('tool_fails',0)}")
        print(f"  routing: {cs.get('routed')}")
        print(f"  quorum: {cs.get('quorum_results')} yes-votes={cs.get('quorum_votes_yes',0)}")
        print(f"  tool calls: {cs.get('tool_calls')}")
    if tod:
        print(f"today's chat ({tod.get('messages', 0)} msgs): "
              f"meta-chatter {tod.get('meta_chatter_pct')}% | code-bearing "
              f"{tod.get('code_bearing_pct')}% | loop-ratio {tod.get('loop_ratio_pct')}%")
        for o in tod.get("top_openers", [])[:2]:
            print(f"  top opener x{o['count']}: {o['line'][:56]}")
    for r in ascii_map():
        print("  " + r)
    print("=== END SCOUT REPORT ===")


def metrics():
    tod = today_stats()
    if not tod:
        print("no chat messages today yet")
        return
    METRICS_DIR.mkdir(parents=True, exist_ok=True)
    out = METRICS_DIR / f"slm_quality_{tod['date']}.json"
    data = dict(tod)
    data["telemetry"] = telemetry_counts()
    data["game_alive"] = game_alive()
    # merge with earlier same-day runs (keep latest per field)
    if out.exists():
        try:
            old = json.loads(out.read_text(encoding="utf-8"))
            data["history"] = old.get("history", []) + [{
                "t": datetime.now().strftime("%H:%M"), "msgs": tod["messages"],
                "meta": tod["meta_chatter_pct"], "code": tod["code_bearing_pct"]}]
        except Exception:
            data["history"] = []
    else:
        data["history"] = []
    out.write_text(json.dumps(data, indent=2), encoding="utf-8")
    print(f"wrote {out}")
    print(json.dumps(data, indent=2)[:800])


def watch():
    print(f"scout_bot WATCH — tailing {CONSOLE} (Ctrl+C to stop)")
    try:
        with open(CONSOLE, "r", encoding="utf-8", errors="replace") as f:
            f.seek(0, os.SEEK_END)
            while True:
                line = f.readline()
                if line:
                    line = line.strip()
                    if line:
                        print(f"[{datetime.now().strftime('%H:%M:%S')}] {line}")
                else:
                    time.sleep(1)
    except KeyboardInterrupt:
        print("watch stopped")
    except OSError as e:
        print(f"cannot open console log: {e}")


if __name__ == "__main__":
    args = sys.argv[1:]
    if "--watch" in args:
        watch()
    elif "--metrics" in args:
        metrics()
    elif "--map" in args:
        for r in ascii_map():
            print(r)
    else:
        report()