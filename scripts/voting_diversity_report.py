#!/usr/bin/env python3
"""Voting-diversity analysis report generator (TASK_0128 redo).

Derived from the quorum ledger rather than
maintaining a second writer: every finished quorum cycle is appended to
AIGEN_SYS/quorum_ledger.jsonl (APPROVED via QuorumTaskSpawner.ledgerAppend
with task_spawned_id, other outcomes via ledgerAppendAny — TASK_0128 redo),
and the cycle's proposal_type is the rotated type chosen by AgentManager's
ProposalRotator (commit f1331a9).

Reads the ledger (Windows-compatible paths), groups cycles by proposal
type, computes approval / divergence / weight / pulse statistics per
type, and writes AIGEN_SYS/voting_diversity_analysis.md.

Usage:
    python scripts/voting_diversity_report.py               # default paths
    python scripts/voting_diversity_report.py --ledger PATH --out PATH
Exit codes: 0 = report written; 2 = ledger missing/empty/unparseable.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

ROTATION_ORDER = [
    "system_health_check",
    "feature_iteration",
    "performance_optimization",
    "code_quality",
    "agent_training",
]


def default_aigen_home() -> Path:
    """Default aigen home (function)."""
    home = os.environ.get("AIGEN_HOME")
    if home:
        return Path(home)
    return Path.home() / "AIGEN_SYS"


def load_ledger(path: Path) -> list[dict]:
    """Load ledger.

    Args: path.
    """
    records: list[dict] = []
    with path.open("r", encoding="utf-8", errors="replace") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError as exc:
                print(f"WARN: skipping unparseable ledger line {lineno}: {exc}", file=sys.stderr)
                continue
            if isinstance(rec, dict):
                records.append(rec)
    return records


def classify_divergence(votes: dict) -> str:
    """Shape-aware divergence classifier.

    Legacy ledger shape:  votes = {approve: N, reject: N, blind: N} (counts).
    Post-0128redo shape: votes = {<model>: "APPROVE"|"REJECT"|"BLIND"}.
    """
    if not votes:
        return "no_votes"
    keys = {str(k).strip().lower() for k in votes}
    if keys <= {"approve", "reject", "blind"} and all(
            not isinstance(v, str) for v in votes.values()):
        # Count-shaped: split only when both sides actually showed up.
        approve = float(votes.get("approve") or 0)
        reject = float(votes.get("reject") or 0)
        if approve + reject + float(votes.get("blind") or 0) <= 0:
            return "no_votes"
        return "split" if (approve > 0 and reject > 0) else "unanimous"
    # Per-agent shape: string vote values.
    vals = {str(v).strip().upper() for v in votes.values()
            if isinstance(v, str) and v.strip()}
    return "unanimous" if len(vals) <= 1 else "split"


def per_agent_votes(cycles: list[dict]) -> dict[str, dict[str, int]]:
    """Per-agent tally of APPROVE/REJECT/BLIND across cycles.

    Only per-agent-shaped vote maps count here; count-shaped legacy maps
    (approve/reject/blind numeric keys) have no agent identity.
    """
    tally: dict[str, dict[str, int]] = defaultdict(
        lambda: {"APPROVE": 0, "REJECT": 0, "BLIND": 0})
    for c in cycles:
        for agent, vote in (c.get("votes") or {}).items():
            if not isinstance(vote, str) or str(agent).lower() in (
                    "approve", "reject", "blind"):
                continue
            key = vote.strip().upper()
            if key in ("APPROVE", "REJECT", "BLIND"):
                tally[agent][key] += 1
    return tally


def fmt_row(t: str, cycles: list[dict]) -> str:
    """Fmt row.

    Args: t, cycles.
    """
    n = len(cycles)
    approved = sum(1 for c in cycles if c.get("outcome") == "APPROVED")
    rejected = sum(1 for c in cycles if c.get("outcome") == "REJECTED")
    other = n - approved - rejected
    split = sum(1 for c in cycles if classify_divergence(c.get("votes") or {}) == "split")
    unanimous = sum(1 for c in cycles if classify_divergence(c.get("votes") or {}) == "unanimous")
    weights = [float(c["weight"]) for c in cycles if isinstance(c.get("weight"), (int, float))]
    pulses = [float(c["pulse"]) for c in cycles if isinstance(c.get("pulse"), (int, float))]
    avg_w = sum(weights) / len(weights) if weights else 0.0
    avg_p = sum(pulses) / len(pulses) if pulses else 0.0
    app_rate = approved / n * 100 if n else 0.0
    div_rate = split / n * 100 if n else 0.0
    return (
        f"| {t} | {n} | {app_rate:.1f}% | {rejected} | {other} "
        f"| {div_rate:.1f}% | {unanimous} | {split} | {avg_w:.2f} | {avg_p:.2f} |"
    )


def main() -> int:
    """Main (function)."""
    ap = argparse.ArgumentParser(description="Voting-diversity report (TASK_0128 redo)")
    ap.add_argument("--ledger", type=Path,
                    default=default_aigen_home() / "quorum_ledger.jsonl")
    ap.add_argument("--out", type=Path,
                    default=default_aigen_home() / "voting_diversity_analysis.md")
    ap.add_argument("--min-cycles", type=int, default=1,
                    help="minimum ledger cycles before a report is emitted (default 1)")
    args = ap.parse_args()

    if not args.ledger.exists():
        print(f"ERROR: ledger not found: {args.ledger}", file=sys.stderr)
        return 2
    records = load_ledger(args.ledger)
    if not records:
        print(f"ERROR: ledger holds 0 parseable cycles: {args.ledger}", file=sys.stderr)
        return 2
    if len(records) < args.min_cycles:
        print(f"ERROR: ledger has {len(records)} cycles, need {args.min_cycles}",
              file=sys.stderr)
        return 2

    # Ledger records every finished cycle (TASK_0128 redo: ledgerAppendAny
    # for non-APPROVED outcomes, ledgerAppend with task_spawned_id for
    # APPROVED ones). Approval rate per type is thus measurable.
    approved_cycles = [r for r in records if r.get("outcome") == "APPROVED"]
    by_type: dict[str, list[dict]] = defaultdict(list)
    for r in records:
        by_type.setdefault(str(r.get("proposal_type") or "unknown"), []).append(r)

    generated = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    rotation_seen = [t for t in ROTATION_ORDER if t in by_type]

    # Rotated types first in spec order, then any legacy/unknown tail.
    ordered_types = [t for t in ROTATION_ORDER if t in by_type]
    ordered_types += [t for t in sorted(by_type) if t not in ROTATION_ORDER]

    out: list[str] = []
    w = out.append
    w("# Voting Diversity Analysis Report")
    w("")
    w(f"Generated: {generated}")
    w(f"Ledger: `{args.ledger}` ({len(records)} cycles, {len(approved_cycles)} APPROVED)")
    w("")
    w("Derived from the quorum ledger (`QuorumTaskSpawner.ledgerAppend`); proposal")
    w("types come from the deterministic 5-type ProposalRotator wired into")
    w("`AgentManager.runQuorumVote` (TASK_0128 redo, commit f1331a9).")
    w("")
    w("---")
    w("")
    w("## Summary by Proposal Type")
    w("")
    w("| Type | Cycles | Approval Rate | Rejected | Other | Divergence Rate |"
      " Unanimous | Split | Avg Weight | Avg Pulse |")
    w("|------|--------|---------------|----------|-------|-----------------|"
      "-----------|-------|------------|-----------|")
    for t in ordered_types:
        cyc = by_type[t]
        n = len(cyc)
        approved = sum(1 for c in cyc if c.get("outcome") == "APPROVED")
        rejected = sum(1 for c in cyc if c.get("outcome") == "REJECTED")
        other = n - approved - rejected
        divs = [classify_divergence(c.get("votes") or {}) for c in cyc]
        split = sum(1 for d in divs if d == "split")
        unanimous = sum(1 for d in divs if d == "unanimous")
        weights = [float(c["weight"]) for c in cyc
                   if isinstance(c.get("weight"), (int, float))]
        pulses = [float(c["pulse"]) for c in cyc
                  if isinstance(c.get("pulse"), (int, float))]
        avg_w = sum(weights) / len(weights) if weights else 0.0
        avg_p = sum(pulses) / len(pulses) if pulses else 0.0
        app_rate = approved / n * 100 if n else 0.0
        div_rate = split / n * 100 if n else 0.0
        w(f"| {t} | {n} | {app_rate:.1f}% | {rejected} | {other} "
          f"| {div_rate:.1f}% | {unanimous} | {split} | {avg_w:.2f} | {avg_p:.2f} |")
    w("")
    w("Pattern legend: divergence_rate > 50% = high dissent; 20-50% = moderate; below 20% = strong consensus.")
    w("")
    for t in ordered_types:
        cyc = by_type[t]
        n = len(cyc)
        divs = [classify_divergence(c.get("votes") or {}) for c in cyc]
        split = sum(1 for d in divs if d == "split")
        div = split / n * 100 if n else 0.0
        if div > 50:
            pattern = "High dissent. Agents disagree on this proposal type."
        elif div > 20:
            pattern = "Moderate dissent. Mixed opinions."
        else:
            pattern = "Strong consensus. Agents agree on this proposal type."
        w(f"### {t}")
        w("")
        w(f"**Pattern**: {pattern}")
        w("")
    w("---")
    w("")
    w("## Rotation Health")
    w("")
    w(f"- Rotation types seen: {len(rotation_seen)}/5"
      + (f" ({', '.join(rotation_seen)})" if rotation_seen else ""))
    missing = [t for t in ROTATION_ORDER if t not in by_type]
    w(f"- Missing from ledger: {', '.join(missing) if missing else 'none'}")
    w("- Distribution (target: roughly equal): "
      + ", ".join(f"{t}={len(by_type[t])}" for t in ordered_types))
    w("")
    w("---")
    w("")
    w("## Agent-Specific Patterns")
    w("")
    for agent, tally in sorted(per_agent_votes(records).items()):
        n = sum(tally.values())
        if n == 0:
            continue
        app = tally.get("APPROVE", 0)
        rej = tally.get("REJECT", 0)
        bli = tally.get("BLIND", 0)
        w(f"### {agent}")
        w("")
        w(f"- APPROVE: {app}/{n} ({app / n * 100:.0f}%)  "
          f"REJECT: {rej}/{n} ({rej / n * 100:.0f}%)  "
          f"BLIND: {bli}/{n} ({bli / n * 100:.0f}%)")
        for t in ordered_types:
            tot = sum(1 for c in by_type[t]
                      for ag, v in (c.get("votes") or {}).items()
                      if ag == agent and isinstance(v, str)
                      and v.strip().upper() in ("APPROVE", "REJECT", "BLIND"))
            yes = sum(1 for c in by_type[t]
                      for ag, v in (c.get("votes") or {}).items()
                      if ag == agent and isinstance(v, str)
                      and v.strip().upper() == "APPROVE")
            if tot:
                w(f"  - {t}: {yes}/{tot} APPROVE")
        w("")
    w("---")
    w("")
    w("## Key Findings")
    w("")
    measurable = sum(1 for t in ordered_types if len(by_type[t]) >= 2)
    w(f"1. {measurable} proposal types have enough cycles (2+) for a measurable divergence/approval pattern.")
    w("2. Divergence is computed from the ledger's vote map; weight/pulse are the quorum engine's resonance stats.")
    w("3. Recommendation: read patterns per type above before predicting future votes by domain.")
    w("")
    w("---")
    w("")
    w("Report generated by scripts/voting_diversity_report.py (TASK_0128 redo).")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"OK: report written to {args.out}")
    print(f"Total cycles analyzed: {len(records)}")
    print(f"Proposal types: {', '.join(ordered_types)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())