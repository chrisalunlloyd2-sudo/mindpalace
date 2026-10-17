#!/usr/bin/env python3
"""
perf_harness.py — PERF-A3 (t_993a201f) unattended performance-test harness
for MindPalace, per docs/AUTONOMOUS_PERF_TEST.md (D5 load injection,
safety section, A3 acceptance criteria).

One command runs everything:
  build/jar check -> hermetic `--perf` launch -> wait on readiness marker
  (first [PERF-FRAME] line on stdout — not a sleep) -> fire ONE Ollama
  request when the target frame marker passes -> collect CSV + snapshots +
  Ollama timings -> per-window stats (baseline vs generation) -> verdict ->
  own-process cleanup, verified by a post-run process sweep.

The game already ships everything else (PERF-A2, commit 7f6aecc):
frame lock, input track, PerfCsvLogger (`perf_frames.csv`), snapshot writer
(`perf_frame_%06d.png`), `[PERF-STARTUP]`/`[PERF-FRAME]`/`[PERF-POSE]`
stdout markers, `PERF_RESULT:` verdict line, exit codes 0/1/2
(GameEngine.setPerf / loop / finishPerfRun).

Knobs (flags or env; flags win):
  --model            PERF_MODEL          (llama3.2:1b)  LOCAL Ollama model
  --num-predict      PERF_NUM_PREDICT    (64)
  --num-ctx          PERF_NUM_CTX        (8192)
  --frames           PERF_FRAMES         (1200)  perf.frames cap in game
  --framelock        PERF_FRAMELOCK      (8.3)   ms/frame locked sim dt
  --load-frame       PERF_LOAD_FRAME     (180)   fire Ollama at/after this
  --ollama-url       PERF_OLLAMA_URL     (http://127.0.0.1:11434) loopback ONLY
  --track            PERF_TRACK          (classpath default track)
  --out              PERF_OUT            (target/perf-runs/<ts>)
  --skip-build       PERF_SKIP_BUILD=1   use existing jar
  --no-suspend-agent PERF_SUSPEND_AGENT=0  A/B arm: agent cycle ticks (NOT hermetic)
  --prompt-file      PERF_PROMPT_FILE    (default: varied text written into OUT)
  --timeout          PERF_TIMEOUT_S      (900)   whole-run wall cap

Safety (doc "Safety", binds A3):
  1. Ollama is client-only: POSTs to the loopback URL; never touches server
     config. LOCAL MODELS ONLY — the model must exist on the local daemon
     (verified via /api/tags before launch).
  2. The harness stops nothing it does not own: it kills ONLY the PID it
     spawned, with single-slash `taskkill /F /PID` (the //F form is a silent
     no-op in this Git Bash — AGENTS.md landmine). The live game is never a
     target; pre-existing java PIDs are recorded untouched for the post-run
     sweep comparison.
  3. No Ollama process-priority change at the default arm — nothing to
     restore (the doc permits an A4 arm; A3 leaves priority untouched).
  4. Bounded run, twice over: perf.frames in the game AND --timeout+kill here.
  5. GL_RENDERER ends up in result.json (game prints it on PERF_RESULT).
"""

import argparse
import csv
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
JAVA_HOME = "C:/Program Files/Java/jdk-17"
MVN = "C:\\ProgramData\\chocolatey\\lib\\maven\\apache-maven-3.9.16\\bin\\mvn.cmd"

PERF_FRAME_RE = re.compile(r"\[PERF-FRAME\] (\d+)")
PERF_RESULT_RE = re.compile(r"PERF_RESULT: (OK|WARN|FAIL)")
# Same regex GameEngine.finishPerfRun uses to soften FAIL→WARN on software GL.
SOFTWARE_GPU_SUB = re.compile(r"llvmpipe|softpipe|swrast|software|microsoft basic render", re.I)


def log(msg):
    print(f"[perf-harness {datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


def env_flag(name, default):
    v = os.environ.get(name)
    return default if v is None or v == "" else v


def resolve_jar():
    jars = sorted(p for p in (REPO / "target").glob("mindpalace-*.jar")
                  if "original-" not in p.name and "-shaded" not in p.name)
    return jars[0] if jars else None


def build(skip):
    if skip:
        jar = resolve_jar()
        if jar:
            log(f"BUILD skipped, using {jar.name}")
        else:
            log("BUILD skipped but no target/mindpalace-*.jar found")
        return jar
    log("BUILD: mvn.cmd -DskipTests package ...")
    try:
        r = subprocess.run(["cmd.exe", "/c", MVN, "-DskipTests", "package"],
                           cwd=str(REPO), capture_output=True, text=True,
                           errors="replace", timeout=1500)
    except Exception as e:
        log(f"BUILD FAILED: {e}")
        return None
    tail = "\n".join((r.stdout or "").splitlines()[-5:])
    if r.returncode != 0 or "BUILD FAILURE" in tail:
        log(f"BUILD FAILED (rc={r.returncode}):\n{tail}")
        return None
    jar = resolve_jar()
    log(f"BUILD OK -> {jar}" if jar else "BUILD success but jar glob empty")
    return jar


def ollama_check(url, model):
    """Safety 1: local daemon reachable + model installed. Client-only.
    15s timeout + 1 retry — the daemon is often mid-generation for the live
    agent loop and answers slowly; a transient timeout must not abort a run."""
    last = None
    for attempt in range(2):
        try:
            with urllib.request.urlopen(url.rstrip("/") + "/api/tags", timeout=15) as r:
                tags = json.loads(r.read().decode())
            names = [m.get("name", "") for m in tags.get("models", [])]
            ok = any(n == model or n.startswith(model + ":") for n in names)
            log(f"OLLAMA CHECK {'OK' if ok else 'MISSING MODEL'}: '{model}' on {url}")
            return ok
        except Exception as e:
            last = e
            log(f"OLLAMA CHECK attempt {attempt + 1} failed: {e}")
            time.sleep(3)
    log(f"OLLAMA CHECK FAILED after retry: {last}")
    return False


def fire_ollama(url, model, prompt, num_predict, num_ctx):
    """D5: ONE POST /api/generate; record durations + tok/s."""
    body = json.dumps({"model": model, "prompt": prompt, "stream": False,
                       "options": {"num_ctx": num_ctx, "num_predict": num_predict}}).encode()
    req = urllib.request.Request(url.rstrip("/") + "/api/generate", data=body,
                                 headers={"Content-Type": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            reply = json.loads(r.read().decode())
    except Exception as e:
        log(f"OLLAMA FIRE FAILED: {e}")
        return {"error": str(e), "wall_s": round(time.time() - t0, 3)}
    wall_s = time.time() - t0
    eval_count = reply.get("eval_count", 0)
    eval_dur = reply.get("eval_duration", 0)
    toks = round(eval_count / (eval_dur / 1e9), 2) if eval_dur and eval_count else None
    rec = {"model": model, "wall_s": round(wall_s, 3),
           "load_duration_ns": reply.get("load_duration"),
           "prompt_eval_duration_ns": reply.get("prompt_eval_duration"),
           "eval_duration_ns": reply.get("eval_duration"),
           "eval_count": eval_count,
           "prompt_eval_count": reply.get("prompt_eval_count"),
           "tokens_per_s": toks}
    log(f"OLLAMA REPLY: wall={wall_s:.1f}s tok/s={toks} eval_count={eval_count}")
    return rec


def read_csv_rows(csv_path):
    rows = []
    with open(csv_path, newline="", encoding="utf-8", errors="replace") as f:
        for row in csv.DictReader(f):
            try:
                rows.append({"frame": int(row["frame"]),
                             "frame_ms": float(row["frame_ms"]),
                             "snap": row["snap"] == "1",
                             "hitch": row["hitch"] == "1"})
            except (KeyError, ValueError):
                continue
    return rows


def window_stats(rows, load_frame):
    """A3 deliverable: baseline vs generation window stats. Snapshot frames
    are counted separately (doc D3: only the raw read lands in the frame)."""
    def agg(sub, label):
        vals = sorted(r["frame_ms"] for r in sub)
        if not vals:
            return {"window": label, "frames": 0}

        def pct(p):
            idx = max(0, min(len(vals) - 1, int(p / 100.0 * len(vals)) - 1))
            return round(vals[idx], 2)
        return {"window": label,
                "frames": len(vals),
                "excluded_snap": sum(1 for r in sub if r["snap"]),
                "over80ms": sum(1 for v in vals if v > 80.0),
                "hitches": sum(1 for r in sub if r["hitch"]),
                "hitch_sum_over80_ms": round(sum(v - 80.0 for v in vals if v > 80.0), 1),
                "worst_ms": round(vals[-1], 2),
                "p50_ms": pct(50), "p95_ms": pct(95), "p99_ms": pct(99)}
    return {"baseline": agg([r for r in rows if r["frame"] < load_frame], "baseline (pre-load)"),
            "generation": agg([r for r in rows if r["frame"] >= load_frame], "generation (during decode)")}


def java_pids():
    try:
        out = subprocess.run(["tasklist"], capture_output=True, text=True,
                             errors="replace", timeout=30).stdout or ""
        return sorted(l.split()[1] for l in out.splitlines()
                      if re.match(r"(?i)^java\w*\.exe\s+\d+", l))
    except Exception:
        return []


def kill_own_pid(pid):
    """Safety 2: single-slash flags — //F silently no-ops in this Git Bash."""
    r = subprocess.run(["taskkill", "/F", "/PID", str(pid)],
                       capture_output=True, text=True, errors="replace")
    log(f"CLEANUP: taskkill /F /PID {pid} -> " +
        ("terminated" if r.returncode == 0 else (r.stderr or "").strip()))
    return r.returncode == 0


class PerfRun:
    """Hermetic --perf child + stdout tail + load firing."""

    def __init__(self, args):
        self.args = args
        self.proc = None
        self.load_rec = None
        self.load_rec_event = threading.Event()

    def launch(self, jar, out_dir):
        cmd = [f"{JAVA_HOME}/bin/java",
               "-Dprism.order=sw", "-Dprism.vsync=false",
               "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200",
               "-Xms256m", "-Xmx768m",
               f"-Dmindpalace.frameLock={self.args.framelock}",
               f"-Dmindpalace.perf.frames={self.args.frames}",
               f"-Dmindpalace.perf.suspendAgent={str(self.args.suspend_agent).lower()}"]
        if self.args.track:
            cmd.append(f"-Dmindpalace.inputTrack={self.args.track}")
        cmd += ["-jar", str(jar), "--perf", str(out_dir), "--time", "12"]
        log("LAUNCH: java -Dmindpalace.frameLock=" + self.args.framelock +
            f" -Dmindpalace.perf.frames={self.args.frames} "
            f"-Dmindpalace.perf.suspendAgent={str(self.args.suspend_agent).lower()} "
            f"{Path(jar).name} --perf {out_dir} --time 12")
        self.proc = subprocess.Popen(cmd, cwd=str(REPO),
                                     stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT,
                                     text=True, encoding="utf-8", errors="replace")
        log(f"LAUNCHED pid={self.proc.pid}")

    def run(self):
        """Tail stdout until EOF / PERF_RESULT / timeout. Fires the Ollama
        request on a side thread as soon as a frame marker >= --load-frame
        is seen (readiness+fire both ride stdout markers — no sleeps)."""
        result_line = None
        load_fired_at = None
        fired = False
        out_log = Path(self.args._out_dir) / "game_stdout.log"
        t_start = time.time()
        with open(out_log, "w", encoding="utf-8") as lf:
            for line in self.proc.stdout:
                lf.write(line)
                lf.flush()
                line = line.rstrip("\r\n")
                if line.startswith("[PERF-FRAME]") or line.startswith("PERF_RESULT:") \
                        or line.startswith("[PERF]") or line.startswith("[PERF-STARTUP]") \
                        or line.startswith("[PERF_WARN]") or line.startswith("[PERF-POSE]") \
                        or "Exception" in line:
                    print(f"  [game] {line}", flush=True)
                if not fired:
                    m = PERF_FRAME_RE.search(line)
                    if m and int(m.group(1)) >= self.args.load_frame:
                        fired = True
                        load_fired_at = int(m.group(1))
                        log(f"frame {load_fired_at} marker seen — firing one "
                            f"{self.args.model} request on {self.args.ollama_url}")
                        threading.Thread(target=self._fire, daemon=True).start()
                if "PERF_RESULT:" in line:
                    result_line = line.strip()
                if time.time() - t_start > self.args.timeout:
                    log(f"TIMEOUT {self.args.timeout}s — killing own pid")
                    kill_own_pid(self.proc.pid)
                    break
        try:
            self.proc.wait(timeout=60)
        except Exception:
            kill_own_pid(self.proc.pid)
        # bounded join: decode of num_predict tokens must fit in these limits
        self.load_rec_event.wait(timeout=620)
        return result_line, load_fired_at, self.load_rec

    def _fire(self):
        try:
            rec = fire_ollama(self.args.ollama_url, self.args.model,
                              self.args._prompt, self.args.num_predict,
                              self.args.num_ctx)
        finally:
            self.load_rec_event.set()
        self.load_rec = rec


def main():
    ap = argparse.ArgumentParser(description="Unattended mindpalace perf harness (PERF-A3)")
    ap.add_argument("--model", default=env_flag("PERF_MODEL", "llama3.2:1b"))
    ap.add_argument("--num-predict", type=int, default=int(env_flag("PERF_NUM_PREDICT", "64")))
    ap.add_argument("--num-ctx", type=int, default=int(env_flag("PERF_NUM_CTX", "8192")))
    ap.add_argument("--frames", type=int, default=int(env_flag("PERF_FRAMES", "1200")))
    ap.add_argument("--framelock", default=env_flag("PERF_FRAMELOCK", "8.3"))
    ap.add_argument("--load-frame", type=int, default=int(env_flag("PERF_LOAD_FRAME", "180")))
    ap.add_argument("--ollama-url", default=env_flag("PERF_OLLAMA_URL", "http://127.0.0.1:11434"))
    ap.add_argument("--track", default=env_flag("PERF_TRACK", "") or None)
    ap.add_argument("--out", default=env_flag("PERF_OUT", "") or None)
    ap.add_argument("--skip-build", action="store_true",
                    default=str(env_flag("PERF_SKIP_BUILD", "0")) == "1")
    ap.add_argument("--no-suspend-agent", dest="suspend_agent", action="store_false",
                    default=env_flag("PERF_SUSPEND_AGENT", "1") == "1")
    ap.add_argument("--prompt-file", default=env_flag("PERF_PROMPT_FILE", "") or None)
    ap.add_argument("--timeout", type=int, default=int(env_flag("PERF_TIMEOUT_S", "900")))
    args = ap.parse_args()

    out_dir = Path(args.out).resolve() if args.out else None
    if out_dir is None:
        out_dir = (REPO / "target" / "perf-runs" /
                   datetime.now().strftime("%Y%m%d-%H%M%S")).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    args._out_dir = out_dir

    log(f"PERF HARNESS (PERF-A3) — out={out_dir}")
    pre_pids = java_pids()
    if pre_pids:
        log(f"pre-existing java pids (NOT ours, untouched): {pre_pids}")

    if not ollama_check(args.ollama_url, args.model):
        log("ABORT (safety 1: local models only, model check failed)")
        return 1

    jar = build(args.skip_build)
    if not jar:
        return 1

    # A4 finding: same-prompt runs were confounded by prefill caching — the
    # default prompt embeds a unique per-run timestamp.
    prompt_path = args.prompt_file
    if not prompt_path:
        prompt_path = str(out_dir / "load_prompt.txt")
        Path(prompt_path).write_text(
            f"Perf harness probe {datetime.now(timezone.utc).isoformat()}. "
            f"In {args.num_predict} tokens: describe how a corridor maze of "
            f"repositories behaves when one distant request stalls a frame. "
            f"Use the words palindrome, telemetry, frame lock.",
            encoding="utf-8")
    args._prompt = Path(prompt_path).read_text(encoding="utf-8")

    run = PerfRun(args)
    result_line = load_fired_at = load_rec = None
    try:
        run.launch(jar, out_dir)
        result_line, load_fired_at, load_rec = run.run()
    finally:
        if run.proc and run.proc.poll() is None:
            kill_own_pid(run.proc.pid)
        if run.proc:
            try:
                run.proc.wait(timeout=30)
            except Exception:
                pass

    post_pids = java_pids()
    our_pid_gone = run.proc is None or str(run.proc.pid) not in post_pids
    if not our_pid_gone:
        kill_own_pid(run.proc.pid)
        our_pid_gone = str(run.proc.pid) not in java_pids()

    csv_path = out_dir / "perf_frames.csv"
    rows = read_csv_rows(csv_path) if csv_path.exists() else []
    snaps = sorted(p.name for p in out_dir.glob("perf_frame_*.png"))
    gpu_line = ""
    if result_line:
        mm = re.search(r"gpu=([^ ]+)", result_line)
        gpu_line = mm.group(1) if mm else ""
    software_gl = bool(SOFTWARE_GPU_SUB.search(gpu_line)) if gpu_line else None

    verdict = (PERF_RESULT_RE.search(result_line).group(1)
               if result_line else None)
    game_rc = run.proc.returncode if run.proc else None
    # Game exit codes (finishPerfRun): 0 pass/WARN, 2 perf FAIL, 1 setup fail.
    passed = verdict in ("OK", "WARN") and game_rc == 0 and len(rows) > 0

    summary = {
        "task": "PERF-A3 (t_993a201f)",
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "git_sha": subprocess.run(["git", "rev-parse", "HEAD"], cwd=str(REPO),
                                  capture_output=True, text=True).stdout.strip(),
        "jar": Path(jar).name,
        "run_dir": str(out_dir),
        "knobs": {"model": args.model, "num_predict": args.num_predict,
                  "num_ctx": args.num_ctx, "frames": args.frames,
                  "framelock_ms": args.framelock, "load_frame": args.load_frame,
                  "suspend_agent": args.suspend_agent,
                  "track": args.track or "classpath:tracks/perf_track_09_agents.json"},
        "game_exit_code": game_rc,
        "perf_result_line": result_line,
        "verdict": verdict,
        "harness_pass": passed,
        "software_gl": software_gl,
        "frames_logged": len(rows),
        "snapshots": snaps,
        "load_fired_at_frame": load_fired_at,
        "ollama": load_rec,
        "own_pid_terminated": our_pid_gone,
        "pre_existing_java_pids": pre_pids,
        "post_run_java_pids": post_pids,
        "windows": window_stats(rows, args.load_frame) if rows else None,
    }
    (out_dir / "result.json").write_text(json.dumps(summary, indent=2),
                                         encoding="utf-8")
    wins = summary["windows"] or {}
    base, gen = wins.get("baseline", {}), wins.get("generation", {})
    log(f"RESULT verdict={verdict} pass={passed} frames={len(rows)} "
        f"snapshots={len(snaps)} fired@{load_fired_at} "
        f"tok/s={(load_rec or {}).get('tokens_per_s')}")
    log(f"WINDowS baseline: {json.dumps(base)}")
    log(f"WINDOWS generation: {json.dumps(gen)}")
    log(f"CERTAINTY: cleanup own_pid_gone={our_pid_gone} "
        f"swarm={post_pids or 'no java processes'}")
    log(f"artifacts: {out_dir}\\result.json perf_frames.csv snapshots")
    return 0 if passed else 2


if __name__ == "__main__":
    sys.exit(main())