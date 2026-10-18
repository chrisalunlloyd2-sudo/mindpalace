#!/usr/bin/env python3
"""MP-045 evidence checker — refuse `complete` without real proof.

AGENTS.md's cascade (`cascade_dev.sh`) always produces four pieces of
evidence for a shipped step: a commit, a build, a `--selftest` result, and
an `--e2e` waypoint tour. A TASK_*.json card marked `complete`/`done` with
no (or fake) evidence for one of those is worse than a card still open —
the work looks finished, nobody re-queues it, and it has to be rediscovered
later (this routine's own doc-drift issues are full of exactly that: docs
claiming something shipped that never actually built/ran).

This script does not replace the cascade. It is a cheap, local, no-network
gate Hermes (or any cron wrapper) can call before accepting a card as
`complete`, and a `--spot-check` mode for MP-045's "spot-check one random
completed card per day."

Expected card schema (additive — existing fields from card_hygiene.py's
`status`/`state` keep working): a `complete`/`done` card should carry an
`evidence` object:

    {
      "status": "complete",
      "evidence": {
        "commit": "<git sha, 7-40 hex chars>",
        "build": "OK",
        "selftest": "54/0",              // must show zero failures
        "e2e": "target/e2e-shots"        // dir, or a recognizable PASS marker
      }
    }

Usage:
    python scripts/evidence_checker.py --card path/to/TASK_0123.json
    python scripts/evidence_checker.py --task-dir /path/to/todo_files/mindpalace
    python scripts/evidence_checker.py --task-dir DIR --spot-check
"""

import argparse
import json
import random
import re
import subprocess
import sys
from pathlib import Path

COMPLETE_STATUSES = {"complete", "completed", "done", "shipped"}
EVIDENCE_KEYS = ("commit", "build", "selftest", "e2e")
COMMIT_RE = re.compile(r"^[0-9a-f]{7,40}$", re.IGNORECASE)
# "0 failed", "54/0", "0/0 failed", "54/0 failed" — all read as zero
# failures; a bare "PASS"/"green"/"OK" with no count is NOT accepted, it's
# exactly the kind of unverifiable claim this checker exists to catch.
# The "/0" alternative must also consume an optional trailing "failed" —
# otherwise "54/0 failed" matches only "/0", leaving "failed" behind for
# the remainder scan below to (wrongly) treat as a real failure marker
# (PR #281 review).
SELFTEST_ZERO_FAILED_RE = re.compile(r"(\b0\s*failed\b|/\s*0\b(?:\s*failed\b)?)", re.IGNORECASE)
# A `build`/`e2e` value that says it didn't actually succeed — "FAILED",
# "error", "skipped", "unknown" and friends — must never be accepted just
# because the field is non-empty (PR #281 review: a card with
# evidence.build="FAILED" was passing before this).
FAILURE_MARKER_RE = re.compile(r"\b(fail(ed)?|error(ed)?|skip(ped)?|unknown|timed[ _-]?out)\b", re.IGNORECASE)


def _field(card, *names):
    return next((name for name in names if name in card), None)


def _status(card):
    key = _field(card, "status", "state")
    return str(card.get(key, "")).strip().lower() if key else ""


def commit_exists_locally(commit, repo_dir):
    """Best-effort: only meaningful when run inside the git checkout."""
    try:
        result = subprocess.run(
            ["git", "-C", str(repo_dir), "cat-file", "-e", commit + "^{commit}"],
            capture_output=True, timeout=10,
        )
        return result.returncode == 0
    except Exception:
        return None  # unknown — not a failure, just unverifiable here


def validate_evidence(card, repo_dir=None, strict=False):
    """Return (ok: bool, reasons: list[str])."""
    reasons = []
    status = _status(card)
    if status not in COMPLETE_STATUSES:
        return True, []  # nothing to enforce on a card that isn't claiming done

    evidence = card.get("evidence")
    if not isinstance(evidence, dict):
        return False, [f"status='{status}' but no 'evidence' object present"]

    for key in EVIDENCE_KEYS:
        value = evidence.get(key)
        if not value or not str(value).strip():
            reasons.append(f"evidence.{key} missing or empty")

    commit = str(evidence.get("commit", "")).strip()
    if commit and not COMMIT_RE.match(commit):
        reasons.append(f"evidence.commit '{commit}' doesn't look like a git sha")
    elif commit and repo_dir is not None:
        exists = commit_exists_locally(commit, repo_dir)
        if exists is False:
            reasons.append(f"evidence.commit '{commit}' not found in this checkout")
        elif exists is None and strict:
            reasons.append(f"evidence.commit '{commit}' could not be verified (not a git checkout?)")

    selftest = str(evidence.get("selftest", "")).strip()
    if selftest:
        # The legitimate zero-failures phrase ("0 failed"/"54/0") must not
        # make FAILURE_MARKER_RE blind to a DIFFERENT failure sitting
        # alongside it in the same string ("54/0; 1 failed", "skipped (0
        # failed)", "0 failed; ERROR: timed out" must all still reject —
        # PR #281 review: a blanket short-circuit let all three through).
        # Strip only the zero-failures substring out, then scan what's left.
        remainder = SELFTEST_ZERO_FAILED_RE.sub("", selftest)
        if FAILURE_MARKER_RE.search(remainder):
            reasons.append(f"evidence.selftest '{selftest}' reports a failure, not zero failures")
        elif not SELFTEST_ZERO_FAILED_RE.search(selftest):
            reasons.append(f"evidence.selftest '{selftest}' doesn't show zero failures")

    build = str(evidence.get("build", "")).strip()
    if build and FAILURE_MARKER_RE.search(build):
        reasons.append(f"evidence.build '{build}' reports a failure")

    e2e = str(evidence.get("e2e", "")).strip()
    if e2e:
        if FAILURE_MARKER_RE.search(e2e):
            reasons.append(f"evidence.e2e '{e2e}' reports a failure")
        elif e2e_looks_like_path(e2e):
            # Resolve relative paths against repo_dir (not cwd) so a card
            # checked from outside the checkout still finds its own
            # artifacts (PR #281 review).
            e2e_path = Path(e2e)
            if not e2e_path.is_absolute() and repo_dir is not None:
                e2e_path = Path(repo_dir) / e2e_path
            if not e2e_path.exists():
                reasons.append(f"evidence.e2e path '{e2e}' does not exist")
            elif e2e_path.is_dir() and not any(e2e_path.iterdir()):
                reasons.append(f"evidence.e2e dir '{e2e}' is empty")
        # else: a bare success marker string ("green"/"19 waypoints") —
        # accepted, we can't verify it without the real shots dir.

    return (len(reasons) == 0), reasons


def e2e_looks_like_path(value):
    return "/" in value or "\\" in value or Path(value).is_absolute()


def check_card(path, repo_dir=None, strict=False):
    try:
        card = json.loads(Path(path).read_text(encoding="utf-8"))
    except Exception as e:
        return False, [f"could not parse card: {e}"]
    return validate_evidence(card, repo_dir=repo_dir, strict=strict)


def _card_status_only(path):
    """Cheap read — just the status field, no evidence validation (no commit subprocess)."""
    try:
        card = json.loads(Path(path).read_text(encoding="utf-8"))
    except Exception:
        return None
    return _status(card)


def list_task_paths(task_dir):
    task_dir = Path(task_dir)
    if not task_dir.is_dir():
        # A typo'd or unmounted queue path must never read as "zero cards,
        # all clean" — that would silently bypass the whole gate (PR #281
        # review). Missing is the caller's problem to report, not ours to hide.
        raise NotADirectoryError(f"--task-dir '{task_dir}' does not exist or is not a directory")
    return sorted(task_dir.glob("TASK_*.json"))


def scan_task_dir(task_dir, repo_dir=None, strict=False):
    results = []
    for path in list_task_paths(task_dir):
        ok, reasons = check_card(path, repo_dir=repo_dir, strict=strict)
        results.append((path, ok, reasons))
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--card", help="validate a single TASK_*.json card")
    parser.add_argument("--task-dir", help="scan every TASK_*.json card in this directory")
    parser.add_argument("--spot-check", action="store_true",
                         help="with --task-dir: validate exactly one random completed card, strictly")
    parser.add_argument("--repo-dir", default=".", help="git checkout to verify commits against (default: cwd)")
    args = parser.parse_args()

    if not args.card and not args.task_dir:
        parser.error("one of --card or --task-dir is required")

    repo_dir = Path(args.repo_dir)
    failed = False

    if args.card:
        ok, reasons = check_card(args.card, repo_dir=repo_dir)
        if ok:
            print(f"PASS  {args.card}")
        else:
            failed = True
            print(f"FAIL  {args.card}")
            for r in reasons:
                print(f"      - {r}")

    if args.task_dir:
        try:
            task_paths = list_task_paths(args.task_dir)
        except NotADirectoryError as e:
            print(f"FAIL  {e}")
            sys.exit(1)
        if args.spot_check:
            # Only read status (cheap) for every card; only the ONE chosen
            # card gets the expensive full validation (commit subprocess
            # etc.) — spot-checking "one card a day" must not cost as much
            # as validating the whole queue (PR #281 review).
            completed_paths = [p for p in task_paths if _card_status_only(p) in COMPLETE_STATUSES]
            if not completed_paths:
                print(f"spot-check: no completed cards found in {args.task_dir}")
            else:
                path = random.choice(completed_paths)
                ok, reasons = check_card(path, repo_dir=repo_dir, strict=True)
                if ok:
                    print(f"SPOT-CHECK PASS  {path}")
                else:
                    failed = True
                    print(f"SPOT-CHECK FAIL  {path}")
                    for r in reasons:
                        print(f"      - {r}")
        else:
            results = scan_task_dir(args.task_dir, repo_dir=repo_dir)
            for path, ok, reasons in results:
                if ok:
                    print(f"PASS  {path}")
                else:
                    failed = True
                    print(f"FAIL  {path}")
                    for r in reasons:
                        print(f"      - {r}")

    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
