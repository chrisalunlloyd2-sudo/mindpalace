#!/usr/bin/env python3
"""Local task-card diagnostics and one-time infrastructure unblock.

Run ``python scripts/card_hygiene.py diag`` once daily against the task queue.
Cards are TASK_*.json objects with a ``status``/``state`` field; optional
``blocked_reason``/``reason`` and retry-count fields drive repair/reporting.
Set MINDPALACE_CARD_HYGIENE_TIER=repair (or pass ``--quality-tier repair``)
to enable the deterministic, one-time infrastructure requeue. No model or
network calls are made.
"""

import argparse
import json
import os
import re
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path


ACTIVE_STATUSES = {"claimed", "in_progress", "running", "started"}
TERMINAL_STATUSES = {"complete", "completed", "done", "closed", "cancelled"}
INFRA_REASONS = {"infra", "infrastructure", "infrastructure_failure"}


def _field(card, *names):
    return next((name for name in names if name in card), None)


def _retry_counts(card):
    retry_key = _field(card, "retry_count", "retries", "attempts")
    maximum_key = _field(card, "max_retries", "maxRetries", "retry_limit")
    try:
        retries = int(card[retry_key]) if retry_key else 0
        maximum = int(card[maximum_key]) if maximum_key else None
    except (TypeError, ValueError):
        return None, None
    if retries < 0 or (maximum is not None and maximum < 0):
        return None, None
    return retries, maximum


def _is_infrastructure_failure(reason):
    normalized = re.sub(r"[\s-]+", "_", str(reason or "").strip().lower())
    return normalized in INFRA_REASONS


def _atomic_write(path, card):
    original_mode = path.stat().st_mode
    fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(card, output, indent=2, ensure_ascii=False)
            output.write("\n")
        os.chmod(temporary_name, original_mode)
        os.replace(temporary_name, path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def scan_cards(task_dir, stale_hours=24, quality_tier="observe", now=None):
    """Return a deterministic report; repair tier only requeues infra blocks once."""
    now = now or datetime.now(timezone.utc)
    cutoff = now - timedelta(hours=stale_hours)
    report = {
        "date": now.date().isoformat(),
        "quality_tier": quality_tier,
        "scanned": 0,
        "stranded": [],
        "infra_unblocked": [],
        "max_retry_blocks": [],
        "errors": [],
    }

    if not task_dir.is_dir():
        report["errors"].append({"card": str(task_dir), "error": "task directory does not exist"})
        return report

    for path in sorted(task_dir.glob("TASK_*.json")):
        try:
            card = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(card, dict):
                raise ValueError("card JSON must be an object")
            report["scanned"] += 1
            status_key = _field(card, "status", "state")
            status = str(card.get(status_key, "") if status_key else "").strip().lower()
            retries, maximum = _retry_counts(card)
            if retries is None:
                raise ValueError("retry counts must be non-negative integers")

            maxed_out = maximum is not None and retries >= maximum
            if status == "blocked" and maxed_out:
                report["max_retry_blocks"].append({
                    "card": path.name,
                    "retry_count": retries,
                    "max_retries": maximum,
                })

            reason_key = _field(card, "blocked_reason", "reason")
            reason = card.get(reason_key, "") if reason_key else ""
            unblock_count = int(card.get("infra_unblock_count", 0) or 0)
            already_unblocked = card.get("infra_unblock_attempted") is True or unblock_count > 0
            if (
                quality_tier == "repair"
                and status == "blocked"
                and _is_infrastructure_failure(reason)
                and not already_unblocked
                and not maxed_out
            ):
                status_key = status_key or "status"
                card[status_key] = "queued"
                card["infra_unblock_attempted"] = True
                card["infra_unblock_count"] = unblock_count + 1
                card["infra_unblocked_at"] = now.isoformat()
                retry_key = _field(card, "retry_count", "retries", "attempts") or "retry_count"
                card[retry_key] = retries + 1
                _atomic_write(path, card)
                report["infra_unblocked"].append(path.name)
                continue

            if status in ACTIVE_STATUSES and datetime.fromtimestamp(
                path.stat().st_mtime, timezone.utc
            ) <= cutoff:
                report["stranded"].append({
                    "card": path.name,
                    "status": status,
                    "modified_at": datetime.fromtimestamp(
                        path.stat().st_mtime, timezone.utc
                    ).isoformat(),
                })
        except (OSError, ValueError, json.JSONDecodeError, TypeError) as exc:
            report["errors"].append({"card": path.name, "error": str(exc)})

    return report


def _default_home():
    return Path(os.environ.get("AIGEN_HOME", Path.home() / "AIGEN_SYS"))


def main(argv=None):
    parser = argparse.ArgumentParser(description="Run local task-card hygiene diagnostics.")
    parser.add_argument("command", choices=("diag",))
    parser.add_argument(
        "--task-dir",
        type=Path,
        default=_default_home() / "todo_management" / "todo_files" / "mindpalace",
    )
    parser.add_argument(
        "--report-dir",
        type=Path,
        default=_default_home() / "todo_management" / "reports",
    )
    parser.add_argument("--stale-hours", type=float, default=24)
    parser.add_argument(
        "--quality-tier",
        choices=("observe", "repair"),
        default=os.environ.get("MINDPALACE_CARD_HYGIENE_TIER", "observe"),
        help="observe reports only; repair also requeues eligible infra blocks once",
    )
    args = parser.parse_args(argv)
    if args.stale_hours < 0:
        parser.error("--stale-hours must be non-negative")
    report = scan_cards(args.task_dir, args.stale_hours, args.quality_tier)
    args.report_dir.mkdir(parents=True, exist_ok=True)
    report_path = args.report_dir / f"card-hygiene-{report['date']}.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"report: {report_path}")
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
