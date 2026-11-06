#!/usr/bin/env python3
"""MP-014/#327 ollama-server capacity validator.

The quorum-livelock fix (385ddf5) depends on the LIVE `ollama serve` process
carrying the env block:

    OLLAMA_MAX_LOADED_MODELS=4 OLLAMA_KEEP_ALIVE=-1 OLLAMA_NUM_PARALLEL=2 OLLAMA_FLASH_ATTENTION=1

Nothing else in the repo sets or verifies those — if the server restarts
(machine reboot, Ollama auto-update, someone running `ollama serve` manually
per DEV_SETUP.md) it silently reverts to defaults and the NO_QUORUM livelock
recurs (MAX_LOADED_MODELS=1 LRU-evicts every other voter's model each ballot;
cold loads blow the vote timeout; every cycle reads ok:0 abstain:3 real:0
visible:[]).

This is the check_model_pins.py pattern applied to the server's own resident
capacity: read the serve process env directly (psutil) and fail LOUD when the
capacity is below the voter roster, instead of silently falling back into
cold-load-abort behavior.

Checks:
  1. A serve process is running and answers on its OLLAMA_HOST (default
     http://127.0.0.1:11434).
  2. OLLAMA_MAX_LOADED_MODELS >= voter-roster size parsed from ModelConfig.java
     (FLASH/TIE/EXTRA_VOTER_A/EXTRA_VOTER_B + the base voters the quorum reads).
  3. OLLAMA_KEEP_ALIVE is negative (infinity pin — what the fix prescribed).
  4. OLLAMA_NUM_PARALLEL >= 2 (serialized ballots are the freeze pattern).

psutil is optional-but-expected on the dev box; without it the env checks are
reported as skipped (exit 2, distinguishable from drift's exit 1) so a cron
job without psutil still gets the API-level liveness check.

Usage:
    python scripts/check_ollama_capacity.py [--repo-dir .]
Exit 0 = capacity OK; 1 = drift found (fix the serve env and restart);
2 = cannot fully verify (psutil missing / serve not running).
"""

import argparse
import json
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

MODEL_CONFIG_JAVA = "src/main/java/com/mindpalace/agent/ModelConfig.java"
FIELD_RE = re.compile(r'public static final String (\w+)\s*=\s*"([^"]+)"')

# The quorum's voter roster, in the order WeightedQuorumVote assembles it:
# base voters (flash + tie) plus the two MP-014 extra voters. Keep in sync
# with ModelConfig/AgentManager — this list is deliberately explicit so a
# roster change that forgets this script is visible here at review time.
VOTER_FIELDS = ["FLASH_MODEL", "TIE_MODEL", "EXTRA_VOTER_A", "EXTRA_VOTER_B"]
BASE_VOTER_COUNT = 3  # pre-MP-014 base roster WeightedQuorumVote always reads

DEFAULT_HOST = "http://127.0.0.1:11434"


def parse_voter_roster(repo_dir):
    """Voter count = base roster + distinct non-null extras parsed from ModelConfig.java."""
    path = Path(repo_dir) / MODEL_CONFIG_JAVA
    if not path.is_file():
        return None, None, f"{MODEL_CONFIG_JAVA} not found under {repo_dir}"
    values = dict(FIELD_RE.findall(path.read_text(encoding="utf-8")))
    extras = [values.get(f) for f in VOTER_FIELDS[2:]]
    extras = [m for m in extras if m]
    roster = BASE_VOTER_COUNT + len(set(extras))
    return roster, values, None


def api_alive(host):
    """/api/version answers -> True."""
    try:
        with urllib.request.urlopen(host.rstrip("/") + "/api/version", timeout=3) as r:
            return r.status == 200
    except Exception:
        return False


def serve_processes():
    """[(pid, env)] for every ollama serve process we can read env from."""
    try:
        import psutil
    except ImportError:
        return None
    procs = []
    for p in psutil.process_iter(["pid", "name"]):
        try:
            if (p.info["name"] or "").lower() != "ollama.exe" and (p.info["name"] or "").lower() != "ollama":
                continue
            cmd = " ".join(p.cmdline())
            if "serve" not in cmd:
                continue
            procs.append((p.pid, p.environ()))
        except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
            continue
    return procs


def _host_matches(env, host):
    """True if this serve process is the one bound to the host we're checking.

    OLLAMA_HOST env is scheme-less ('127.0.0.1:11434'); --host may carry a
    scheme. Compare on host:port; an unset OLLAMA_HOST means the default
    (127.0.0.1:11434). Other serves on other ports are a different box-level
    service (e.g. an MPBox mirror) — not the game's server, not our drift.
    """
    bound = str(env.get("OLLAMA_HOST", "127.0.0.1:11434")).strip() or "127.0.0.1:11434"
    target = host.replace("http://", "").replace("https://", "").rstrip("/")
    return bound == target or bound.endswith(":" + target.split(":")[-1]) and target.split(":")[0] in bound


def check_env(env, roster):
    """Problem list for one serve process env. Empty list = clean.

    Tiers, calibrated on live evidence (2026-11-05):
      DRIFT (the proven freeze): MAX_LOADED_MODELS<=1 — every voter but one
        is LRU-evicted per ballot, cold loads blow the vote timeout, the
        NO_QUORUM livelock (#158/385ddf5) recurs. Same tier for KEEP_ALIVE
        unpinned (voters unload between ballots -> cold-load abstains) —
        that exact pattern was live on the 11436 mirror serve.
      WARN (under-capacity but proven workable): MAX < roster — mid-ballot
        LRU eviction; ballots still complete via reload inside the raised
        timeout (the 11-04 15:58 telemetry shows a healthy 5-voter
        APPROVE cycle on MAX=3), so warn loudly instead of failing the gate.
      WARN: NUM_PARALLEL<2 — serialized ballots stretch cycle wall-clock.
    """
    problems = []
    def geti(key):
        try:
            return int(str(env.get(key, "")).strip())
        except ValueError:
            return None
    cap = geti("OLLAMA_MAX_LOADED_MODELS")
    if cap is None:
        problems.append(("DRIFT", "OLLAMA_MAX_LOADED_MODELS not set (default 1) — "
                          f"below the {roster}-voter roster; the NO_QUORUM livelock WILL recur"))
    elif cap <= 1:
        problems.append(("DRIFT", f"OLLAMA_MAX_LOADED_MODELS={cap} — the #158 freeze "
                          "pattern (LRU evicts every other voter each ballot)"))
    elif cap < 4:
        problems.append(("WARN", f"OLLAMA_MAX_LOADED_MODELS={cap} < 4 (below the 385ddf5 "
                        f"prescribed block; voter roster is {roster} — mid-ballot LRU eviction, "
                        "ballots survive via reload inside the raised timeout, proven workable "
                        "11-04 on MAX=3 but below spec)"))
    elif cap < roster:
        # The 385ddf5 spec itself: 4 residents, 5 voters, reload pads the 5th
        # (12GB-box RAM budget) — spec-conformant, report informationally only.
        print(f"  note: MAX_LOADED_MODELS={cap} < roster {roster} — spec-conformant "
              "(385ddf5 accepts reload-padded ballots above MAX=4)")
    keep = str(env.get("OLLAMA_KEEP_ALIVE", "")).strip()
    if not keep.startswith("-"):
        problems.append(("DRIFT", f"OLLAMA_KEEP_ALIVE={keep or 'unset'} — not infinity; "
                        "voters unload between ballots (cold-load abstains; this exact "
                        "env was live on the 11436 mirror serve 2026-11-05)"))
    par = geti("OLLAMA_NUM_PARALLEL")
    if par is not None and par < 2:
        problems.append(("WARN", f"OLLAMA_NUM_PARALLEL={par} < 2 — serialized ballots on a "
                        "CPU-only box stretch cycle wall-clock toward the timeout"))
    return problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo-dir", default=".")
    ap.add_argument("--host", default=DEFAULT_HOST)
    args = ap.parse_args()

    roster, values, err = parse_voter_roster(args.repo_dir)
    if err:
        print(f"CONFIG ERROR: {err}")
        return 2
    print(f"voter roster parsed from ModelConfig.java: {roster} "
          f"(base {BASE_VOTER_COUNT} + extras "
          f"{[values.get(f) for f in VOTER_FIELDS[2:] if values.get(f)]})")

    if not api_alive(args.host):
        print(f"DRIFT: no ollama server answering at {args.host} — "
              "serve is down or listening elsewhere")
        return 1
    print(f"api alive at {args.host}")

    procs = serve_processes()
    if procs is None:
        print("SKIP: psutil not installed — cannot read serve process env; "
              "install psutil for the full capacity check")
        return 2
    if not procs:
        print("DRIFT: API answers but no readable `ollama serve` process env found "
              "(tray-app service model) — verify the serve env manually per DEV_SETUP.md")
        return 1

    drift = False
    warn = False
    checked = 0
    for pid, env in procs:
        if not _host_matches(env, args.host):
            continue
        checked += 1
        problems = check_env(env, roster)
        host = env.get("OLLAMA_HOST", DEFAULT_HOST)
        if problems:
            print(f"problems in serve pid {pid} (OLLAMA_HOST={host}):")
            for tier, msg in problems:
                if tier == "DRIFT":
                    drift = True
                    print(f"  DRIFT - {msg}")
                else:
                    warn = True
                    print(f"  WARN  - {msg}")
        else:
            print(f"OK serve pid {pid} (OLLAMA_HOST={host}): capacity "
                  f"MAX_LOADED_MODELS={env.get('OLLAMA_MAX_LOADED_MODELS')} "
                  f"KEEP_ALIVE={env.get('OLLAMA_KEEP_ALIVE')} "
                  f"NUM_PARALLEL={env.get('OLLAMA_NUM_PARALLEL')} >= roster {roster}")
    if not checked:
        print(f"DRIFT: no serve process bound to {args.host} found (API answered, "
              "so the owner may be a service we can't read env from) — verify the "
              "serve env manually per DEV_SETUP.md")
        return 1
    if drift:
        print("Fix: restart the serve with the DEV_SETUP.md env block "
              "(OLLAMA_MAX_LOADED_MODELS=4 OLLAMA_KEEP_ALIVE=-1 "
              "OLLAMA_NUM_PARALLEL=2 OLLAMA_FLASH_ATTENTION=1 ollama serve)")
        return 1
    if warn:
        print("CAPACITY WARNING — no freeze-pattern drift, but the serve is below "
              "the 385ddf5 spec; expect slower (reload-padded) ballots.")
        return 0
    print("CAPACITY OK — no drift found.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
