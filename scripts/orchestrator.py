#!/usr/bin/env python3
"""scripts/orchestrator.py — #41 quota-free team orchestrator scaffold.

Pure stdlib, ZERO LLM calls (#41: every heartbeat must spend tokens on
judgment, never rediscovery). One command runs the full verify cycle
offline and prints the next action for the squad:

    python scripts/orchestrator.py            # verify + emit NEXT_STEPS.md
    python scripts/orchestrator.py --comment  # also post evidence to #9

What it does (per the issue spec):
 1. Reads issue #9 (step log) as the single source of truth via `gh` CLI
    (no repo re-scan). If `gh` is not authed, falls back to the local
    HYPOTHESES_50.md / NEXT_100_STEPS.md trail.
 2. Runs the selftest gate detached-ish via scripts/dev.bat selftest,
    parses the FINAL RESULT line (log is UTF-16 LE BOM-aware on Windows
    consoles; handles UTF-8 too).
 3. Classifies fail vs flake: one rerun on fail; persistent = real fail.
 4. Emits NEXT_STEPS.md at repo root: state ledger + top-3 actions.
 5. Optional --comment posts the evidence block to issue #9 via gh CLI.
 6. Persists .alice_state.json (gitignored) so the next agent skips
    rediscovery.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
STATE_FILE = REPO / ".alice_state.json"
NEXT_STEPS = REPO / "NEXT_STEPS.md"
ISSUE = 9

RESULT_RE = re.compile(r"RESULT:\s*(\d+)\s*passed,\s*(\d+)\s*failed")


def _run(cmd: list[str], timeout: int | None = None) -> tuple[int, str]:
    """Run a subprocess, capture output, decode BOM-aware (UTF-16 LE quirk)."""
    try:
        p = subprocess.run(
            cmd, cwd=str(REPO), capture_output=True, timeout=timeout
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        return 1, f"[orchestrator] subprocess error: {e}"
    raw = p.stdout or p.stderr or b""
    # Windows console logs may be UTF-16 LE with BOM; sniff the BOM first —
    # BOM-less UTF-16 decoding of plain UTF-8 bytes "succeeds" into mojibake
    # without ever raising, so BOM presence must drive the choice.
    if raw.startswith(b"\xff\xfe") or raw.startswith(b"\xfe\xff"):
        text = raw.decode("utf-16", errors="replace").lstrip("\ufeff")
    else:
        for enc in ("utf-8", "cp1252"):
            try:
                text = raw.decode(enc)
                break
            except (UnicodeDecodeError, ValueError):
                continue
        else:
            text = raw.decode("utf-8", errors="replace")
    # Strip stray carriage returns from cmd.exe console output.
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    return p.returncode, text


def read_issue9() -> str:
    """Step-log single source of truth via gh; fallback to local trail."""
    rc, out = _run(["gh", "issue", "view", str(ISSUE), "--json", "state,title"])
    if rc == 0 and out.strip():
        try:
            meta = json.loads(out)
            head = f"issue #{ISSUE}: {meta.get('title','?')} [{meta.get('state','?')}]"
        except json.JSONDecodeError:
            head = f"issue #{ISSUE} (unparsed)"
        return head
    # gh not available/authed — fall back to the local docs trail.
    for name in ("HYPOTHESES_50.md", "NEXT_100_STEPS.md"):
        f = REPO / name
        if f.exists():
            return f"(gh unavailable) local trail: {name} ({f.stat().st_size} B)"
    return "(gh unavailable, no local trail found)"


def last_result(text: str) -> tuple[int, int] | None:
    """Parse the FINAL RESULT line — take the LAST regex element (#41 spec)."""
    hits = RESULT_RE.findall(text)
    if not hits:
        return None
    p, f = hits[-1]
    return int(p), int(f)


def run_gate() -> tuple[int, dict]:
    """Run the selftest gate via scripts/dev.bat selftest (Windows) or
    scripts/dev.sh selftest elsewhere. Returns (rc, evidence dict)."""
    if os.name == "nt":
        cmd = ["cmd", "/c", "scripts\\dev.bat", "selftest"]
    else:
        cmd = ["bash", "scripts/dev.sh", "selftest"]
    rc, out = _run(cmd, timeout=900)
    res = last_result(out)
    ev = {
        "rc": rc,
        "result": list(res) if res else None,
        "log_tail": "\n".join(out.splitlines()[-6:])[-600:],
    }
    return rc, ev


def main() -> int:
    """Main (function)."""
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    issue_state = read_issue9()

    state: dict = {}
    if STATE_FILE.exists():
        try:
            state = json.loads(STATE_FILE.read_text(encoding="utf-8",
                                                    errors="replace"))
        except (json.JSONDecodeError, OSError):
            state = {}

    print(f"[orchestrator] {now}")
    print(f"[orchestrator] step-log: {issue_state}")
    print("[orchestrator] running selftest gate (scripts/dev.bat selftest)...")
    rc, ev = run_gate()
    res = ev["result"]

    verdict = "unknown"
    rerun_note = "-"
    if res is None:
        verdict = "no-result-line (gate aborted? see log_tail)"
    elif res[1] == 0 and rc == 0:
        verdict = f"GREEN {res[0]}/{res[0]}"
    else:
        # Fail vs flake (#41 step 3): exactly one rerun.
        print("[orchestrator] non-green — rerunning once to classify flake...")
        rc2, ev2 = run_gate()
        res2 = ev2["result"]
        if res2 is not None and res2[1] == 0 and rc2 == 0:
            verdict = f"FLAKE (passed on rerun: {res2[0]}/{res2[0]})"
            rerun_note = "rerun GREEN -> transient"
            ev = ev2  # keep the passing evidence
        else:
            verdict = "FAIL (persistent across rerun)"
            rerun_note = "rerun also non-green"

    state.update({
        "last_run": now,
        "verdict": verdict,
        "rc": rc,
        "result": ev["result"],
        "issue9": issue_state,
    })
    STATE_FILE.write_text(json.dumps(state, indent=2), encoding="utf-8")

    # NEXT_STEPS.md — state ledger + top actions (#41 step 4).
    if verdict.startswith("GREEN"):
        actions = [
            "Pick the next open low issue and land it KISS (lows sweep in progress).",
            "Re-run `python scripts/orchestrator.py` after the next land.",
            "Refresh CHANGELOG.md if user-facing behavior shipped.",
        ]
    elif verdict.startswith("FAIL"):
        actions = [
            "Read the failing checks in the selftest log (NOT tracked here).",
            "Fix the persistent failure first — do not land new work on red.",
            "Re-run the gate to confirm green before resuming the sweep.",
        ]
    elif verdict.startswith("FLAKE"):
        actions = [
            "Retry once via the same command — flake passed on its rerun.",
            "If it flakes repeatedly, treat as fail and read the log.",
            "Land nothing on this run; verify stability first.",
        ]
    else:
        actions = [
            "Gate produced no RESULT line — inspect scripts/dev.bat output.",
            "Build first (mvn package) if classes are stale.",
            "Re-run `python scripts/orchestrator.py`.",
        ]

    lines = [
        "# NEXT_STEPS",
        "",
        f"_Generated by scripts/orchestrator.py (#41) — {now}. No LLM calls._",
        "",
        "## State ledger",
        f"- gate: **{verdict}** (rc={rc}, RESULT={ev['result']})",
        f"- rerun: {rerun_note}",
        f"- step-log: {issue_state}",
        (f"- prior state: {state.get('verdict','(none)')} @ {state.get('last_run','?')}"
         if state.get("last_run") else "- prior state: (fresh)"),
        "",
        "## Top actions",
    ]
    lines += [f"{i}. {a}" for i, a in enumerate(actions, 1)]
    NEXT_STEPS.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"[orchestrator] verdict: {verdict}")
    print(f"[orchestrator] wrote {next(iter([NEXT_STEPS.name]), 'NEXT_STEPS.md')}")
    print(f"[orchestrator] next: {actions[0]}")

    # Optional evidence post (#41 step 5). Only when green.
    if "--comment" in sys.argv and verdict.startswith("GREEN") and res:
        body = (
            f"**Orchestrator verification ({now})**\n\n"
            f"- gate: `{verdict}` (RESULT {res[0]} passed, 0 failed, rc={rc})\n"
            f"- step-log: {issue_state}\n"
            f"- zero LLM calls; state in `.alice_state.json` (gitignored)\n"
            f"- next action: {actions[0]}\n"
        )
        prc, pout = _run(["gh", "issue", "comment", str(ISSUE), "--body", body])
        print(f"[orchestrator] comment posted rc={prc}")

    return 0 if verdict.startswith(("GREEN", "FLAKE")) else 1


if __name__ == "__main__":
    sys.exit(main())