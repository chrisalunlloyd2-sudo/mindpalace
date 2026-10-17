#!/usr/bin/env python3
r"""PERF-A4 (t_24d503fb) — interleaved A/B matrix runner on top of scripts/perf_harness.py.

Arm grid (docs/AUTONOMOUS_PERF_TEST.md, PERF-A4):
  num_predict (32, 64, 128) x num_thread (1, 2, 3) = 9 arms, N runs each.

Interleaving: arm order walks round-robin over the grid (a1 b1 c1 a2 b2 c2
...) so every arm's i-th run lands at a different wall-clock position —
daemon churn / thermal drift hit all arms equally instead of favouring the
arm that ran first. Per-run prompt text varies (defeats prefill caching;
same discipline A3 shipped for its single arm).

Each run reuses PerfRun from perf_harness (hermetic --perf launch, stdout
marker handshake, own-PID-only cleanup, perf_frames.csv) with --skip-build,
keep_alive=0 for every run (A3 discipline: equal daemon state at run start).

Note on arms this driver does NOT run: an in-game off-main-thread agent-call
arm is not exercisable here — perf mode runs with suspendAgent=true (the
agent cycle is parked), so the Ollama load always comes from the harness's
side thread. Whether the GAME's own agent path must be moved off the render
thread is an outlier-attribution outcome (todo 6), not a matrix arm.

Usage:
  python scripts/perf_matrix.py --runs-per-arm 10
  python scripts/perf_matrix.py --smoke        # tiny wiring check
  Python 3.6+ stdlib only. LOCAL MODELS ONLY (harness ollama_check gates it).
"""
import argparse
import csv
import json
import subprocess
import sys
import time
import traceback
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import perf_harness as ph

OUTLIER_MS = 800.0  # A4 outlier threshold for stage/thread attribution


def read_csv_full(csv_path):
    rows = []
    with open(csv_path, newline="", encoding="utf-8", errors="replace") as f:
        for row in csv.DictReader(f):
            try:
                rows.append({
                    "frame": int(row["frame"]),
                    "frame_ms": float(row["frame_ms"]),
                    "update_ms": float(row["update_ms"]) if row.get("update_ms") else None,
                    "stage_slowest_ms": (float(row["stage_slowest_ms"])
                                         if row.get("stage_slowest_ms") else None),
                    "stage_name": row.get("stage_name", ""),
                    "snap": row.get("snap") == "1",
                    "hitch": row.get("hitch") == "1",
                })
            except (KeyError, ValueError):
                continue
    return rows


def pooled_stats(frame_lists, load_frame):
    """Pool baseline/generation frame_ms across an arm's runs -> window stats."""
    def agg(sub, label):
        vals = sorted(r["frame_ms"] for r in sub if r["frame_ms"] is not None)
        if not vals:
            return {"window": label, "frames": 0}
        def pct(p):
            idx = max(0, min(len(vals) - 1, int(p / 100.0 * len(vals)) - 1))
            return round(vals[idx], 2)
        return {"window": label, "frames": len(vals),
                "over80_ms": sum(1 for v in vals if v > 80.0),
                "over800_ms": sum(1 for v in vals if v > OUTLIER_MS),
                "worst_ms": round(vals[-1], 2),
                "p50_ms": pct(50), "p95_ms": pct(95), "p99_ms": pct(99)}
    base = [r for rows in frame_lists for r in rows if r["frame"] < load_frame]
    gen = [r for rows in frame_lists for r in rows if r["frame"] >= load_frame]
    return {"baseline": agg(base, "baseline"), "generation": agg(gen, "generation")}


def outlier_rows(frame_lists, load_frame):
    """Per-frame 800+ms generation-window outliers for attribution."""
    out = []
    for rows in frame_lists:
        for r in rows:
            if (r["frame"] >= load_frame and r["frame_ms"] > OUTLIER_MS
                    and not r["snap"]):
                out.append({"frame": r["frame"], "frame_ms": r["frame_ms"],
                            "update_ms": r["update_ms"],
                            "stage_slowest_ms": r["stage_slowest_ms"],
                            "stage_name": r["stage_name"]})
    return sorted(out, key=lambda x: -x["frame_ms"])


def prompt_variant(seq, num_predict):
    return (f"Perf matrix probe seq{seq:03d} "
            f"{datetime.now(timezone.utc).isoformat()}. "
            f"In {num_predict} tokens: describe how a corridor maze of "
            f"repositories behaves when one distant request stalls a frame. "
            f"Use the words palindrome, telemetry, frame lock.")


def main():
    ap = argparse.ArgumentParser(description="PERF-A4 interleaved matrix runner")
    ap.add_argument("--runs-per-arm", type=int,
                    default=int(ph.env_flag("PERF_MATRIX_RUNS", "10")))
    ap.add_argument("--model", default=ph.env_flag("PERF_MODEL", "llama3.2:1b"))
    ap.add_argument("--num-ctx", type=int,
                    default=int(ph.env_flag("PERF_NUM_CTX", "8192")))
    ap.add_argument("--frames", type=int,
                    default=int(ph.env_flag("PERF_FRAMES", "1200")))
    ap.add_argument("--framelock", default=ph.env_flag("PERF_FRAMELOCK", "8.3"))
    ap.add_argument("--load-frame", type=int,
                    default=int(ph.env_flag("PERF_LOAD_FRAME", "180")))
    ap.add_argument("--ollama-url",
                    default=ph.env_flag("PERF_OLLAMA_URL", "http://127.0.0.1:11434"))
    ap.add_argument("--num-predicts",
                    default=ph.env_flag("PERF_NUM_PREDICTS", "32,64,128"))
    ap.add_argument("--num-threads",
                    default=ph.env_flag("PERF_NUM_THREADS", "1,2,3"))
    ap.add_argument("--frame-prof", action="store_true", default=True)
    ap.add_argument("--no-frame-prof", dest="frame_prof", action="store_false")
    ap.add_argument("--out", default=None,
                    help="root output dir (default target/perf-matrix/<ts>)")
    ap.add_argument("--smoke", action="store_true",
                    help="tiny wiring check: 1 run/arm-ish, 160 frames, 16 tok")
    ap.add_argument("--timeout", type=int,
                    default=int(ph.env_flag("PERF_TIMEOUT_S", "900")),
                    help="per-run game+fire wall cap s")
    args = ap.parse_args()

    preds = [int(x) for x in args.num_predicts.split(",") if x.strip()]
    threads = [int(x) for x in args.num_threads.split(",") if x.strip()]
    arms = [(p, t) for p in sorted(preds) for t in sorted(threads)]
    n_runs = max(1, args.runs_per_arm)
    if args.smoke:
        args.frames, args.load_frame = 240, 80
        preds, threads = [16], [1, 3]
        arms = [(p, t) for p in preds for t in threads]
        n_runs = 1
    # Interleaved execution order: round-robin over arms.
    order = [arm for _ in range(n_runs) for arm in range(len(arms))]

    root = (Path(args.out).resolve() if args.out else
            (ph.REPO / "target" / "perf-matrix" /
             datetime.now().strftime("%Y%m%d-%H%M%S")).resolve())
    root.mkdir(parents=True, exist_ok=True)
    print(f"[perf-matrix] root={root} arms={arms} runs/arm={n_runs} "
          f"frames={args.frames} smoke={args.smoke}", flush=True)

    pre = ph.java_pids()
    if pre:
        print(f"[perf-matrix] pre-existing java pids left untouched: {pre}", flush=True)
    if not ph.ollama_check(args.ollama_url, args.model):
        print("[perf-matrix] ABORT: ollama/model check failed", flush=True)
        return 1
    jar = ph.resolve_jar()
    if not jar:
        print("[perf-matrix] NO JAR — run mvn package first", flush=True)
        return 1

    results = []
    t0_matrix = time.time()
    for seq, arm_idx in enumerate(order):
        num_predict, num_thread = arms[arm_idx]
        out_dir = root / f"p{num_predict}_t{num_thread}" / f"run{seq:03d}"
        out_dir.mkdir(parents=True, exist_ok=True)
        prompt = prompt_variant(seq, num_predict)
        a = argparse.Namespace(
            model=args.model, num_predict=num_predict, num_ctx=args.num_ctx,
            num_thread=num_thread, frames=args.frames,
            framelock=args.framelock, load_frame=args.load_frame,
            ollama_url=args.ollama_url, track=None, out=str(out_dir),
            skip_build=True, suspend_agent=True, prompt_file=None,
            timeout=args.timeout, frame_prof=args.frame_prof,
            keep_alive="0", _out_dir=out_dir, _prompt=prompt)
        print(f"[perf-matrix] === run {seq + 1}/{len(order)}: "
              f"arm p{num_predict}/t{num_thread} -> {out_dir}", flush=True)
        rec = {"arm_predict": num_predict, "arm_thread": num_thread,
               "seq": seq, "run_dir": str(out_dir)}
        t0 = time.time()
        try:
            run = ph.PerfRun(a)
            try:
                run.launch(jar, out_dir)
                result_line, fired_at, load_rec = run.run()
            finally:
                if run.proc and run.proc.poll() is None:
                    ph.kill_own_pid(run.proc.pid)
                if run.proc:
                    try:
                        run.proc.wait(timeout=30)
                    except Exception:
                        pass
            csv_path = out_dir / "perf_frames.csv"
            rows = read_csv_full(csv_path) if csv_path.exists() else []
            verdict = (ph.PERF_RESULT_RE.search(result_line).group(1)
                       if result_line else None)
            rec.update({
                "verdict": verdict,
                "game_exit_code": run.proc.returncode if run.proc else None,
                "load_fired_at_frame": fired_at,
                "frames_logged": len(rows),
                "ollama": load_rec,
                "windows": ph.window_stats(rows, args.load_frame) if rows else None,
                "wall_s": round(time.time() - t0, 1),
            })
            print(f"[perf-matrix] done verdict={verdict} frames={len(rows)} "
                  f"fired@{fired_at} tok/s={(load_rec or {}).get('tokens_per_s')} "
                  f"({rec['wall_s']} s)", flush=True)
        except Exception as e:
            rec.update({"error": str(e), "trace": traceback.format_exc(),
                        "wall_s": round(time.time() - t0, 1)})
            print(f"[perf-matrix] RUN FAILED: {e}", flush=True)
        (out_dir / "result.json").write_text(json.dumps(rec, indent=2),
                                             encoding="utf-8")
        results.append(rec)
        # Incremental summary write so a killed run still leaves data behind.
        (root / "matrix_summary.json").write_text(
            json.dumps({"task": "PERF-A4 (t_24d503fb)",
                        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
                        "smoke": args.smoke, "runs_per_arm": n_runs,
                        "arms": [{"num_predict": p, "num_thread": t}
                                 for p, t in arms],
                        "per_run": results}, indent=2), encoding="utf-8")

    # ---- Final aggregation: pool each arm's per-frame rows from disk ----
    arm_blocks = []
    for p, t in arms:
        frame_lists, fired, oll = [], [], []
        for rec in results:
            if rec["arm_predict"] == p and rec["arm_thread"] == t:
                csv_path = Path(rec["run_dir"]) / "perf_frames.csv"
                if csv_path.exists():
                    frame_lists.append(read_csv_full(csv_path))
                if rec.get("ollama") and rec["ollama"].get("tokens_per_s"):
                    oll.append(rec["ollama"]["tokens_per_s"])
                if rec.get("load_fired_at_frame") is not None:
                    fired.append(rec)
        stats = pooled_stats(frame_lists, args.load_frame) if frame_lists else None
        outliers = outlier_rows(frame_lists, args.load_frame) if frame_lists else []
        block = {"num_predict": p, "num_thread": t,
                 "runs_ok": sum(1 for r in results
                                if r["arm_predict"] == p and r["arm_thread"] == t
                                and r.get("windows")),
                 "windows": stats,
                 "outliers_over_800ms": outliers[:8],
                 "outlier_count": len(outliers),
                 "token_s_mean": (round(sum(oll) / len(oll), 1) if oll else None)}
        arm_blocks.append(block)

    git_sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=str(ph.REPO),
                             capture_output=True, text=True).stdout.strip()
    summary = {"task": "PERF-A4 (t_24d503fb)",
               "timestamp_utc": datetime.now(timezone.utc).isoformat(),
               "git_sha": git_sha, "smoke": args.smoke,
               "runs_per_arm": n_runs, "frames": args.frames,
               "load_frame": args.load_frame, "num_ctx": args.num_ctx,
               "arms": [{"num_predict": p, "num_thread": t} for p, t in arms],
               "per_run": results, "aggregated": arm_blocks}
    (root / "matrix_summary.json").write_text(json.dumps(summary, indent=2),
                                              encoding="utf-8")

    # Compact human table next to the JSON.
    lines = ["| predict | thread | ok | gen_p50 | gen_p95 | gen_worst | >80ms | >800ms | tok/s |",
             "|---|---|---|---|---|---|---|---|---|"]
    for b in arm_blocks:
        g = (b["windows"] or {}).get("generation") or {}
        lines.append(
            f"| {b['num_predict']} | {b['num_thread']} | {b['runs_ok']}/{n_runs} "
            f"| {g.get('p50_ms', '-')} | {g.get('p95_ms', '-')} "
            f"| {g.get('worst_ms', '-')} | {g.get('over80_ms', '-')} "
            f"| {b['outlier_count']} | {b['token_s_mean']} |")
    table_path = root / "matrix_table.md"
    table_path.write_text(
        "# PERF-A4 matrix (interleaved)\n\n" + "\n".join(lines) + "\n",
        encoding="utf-8")

    ok = sum(1 for r in results if r.get("windows"))
    print(f"[perf-matrix] DONE runs ok={ok}/{len(results)} "
          f"total={round(time.time() - t0_matrix, 1)} s", flush=True)
    print(f"[perf-matrix] table:\n" + "\n".join(lines), flush=True)
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())